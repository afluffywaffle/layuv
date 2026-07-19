import Foundation

/// Errors surfaced by the AI provider. All cases carry a user-readable description.
enum AiError: LocalizedError {
    case notConfigured
    case cleartextBlocked(String)
    case http(Int, String)
    case networkError(Error)

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "No AI endpoint configured. Set one in AI Settings."
        case .cleartextBlocked(let host):
            return "Plain HTTP is not allowed for \"\(host)\". Use HTTPS or a private/local address."
        case .http(let code, let msg):
            return "Server error \(code): \(msg)"
        case .networkError(let e):
            return e.localizedDescription
        }
    }
}

/// Streams completions from an OpenAI-compatible SSE endpoint (`POST /chat/completions`).
/// Supports multimodal requests (inline base64 PNG images as `image_url` content items).
enum OpenAiCompatibleProvider {

    /// Sentinel yielded as the last stream element when `finish_reason == "length"`.
    static let truncatedMarker = "__TRUNCATED__"

    /// Returns an `AsyncThrowingStream` of partial token strings.
    /// The stream ends normally on `[DONE]`. If truncated it first yields `truncatedMarker`.
    /// Call-site should cancel the stream on user dismissal; `onTermination` cancels the task.
    static func stream(
        turns: [AiTurn],
        images: [Data] = [],
        settings: AiProviderSettings = .shared
    ) throws -> AsyncThrowingStream<String, Error> {
        guard settings.isConfigured else { throw AiError.notConfigured }
        let url = try buildURL(settings)
        guard CleartextPolicy.isAllowed(url) else {
            throw AiError.cleartextBlocked(url.host ?? url.absoluteString)
        }
        let body = try buildBody(turns: turns, images: images, settings: settings)

        var request = URLRequest(url: url)
        request.httpMethod  = "POST"
        request.setValue("application/json",  forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        if let key = SecureKeyStore.read(key: SecureKeyStore.apiKeyName), !key.isEmpty {
            request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = body

        return AsyncThrowingStream { continuation in
            let marker = truncatedMarker
            let task = Task {
                do {
                    let (bytes, response) = try await URLSession.shared.bytes(for: request)
                    if let http = response as? HTTPURLResponse,
                       !(200..<300).contains(http.statusCode) {
                        // Read the error body and surface the server's error.message
                        // when present (parity with the Kotlin twin's mapHttpError),
                        // falling back to the generic per-code message.
                        var data = Data()
                        for try await b in bytes { data.append(b) }
                        let serverMsg = Self.serverErrorMessage(data)
                        throw AiError.http(http.statusCode, serverMsg ?? httpMessage(http.statusCode))
                    }
                    var truncated = false
                    for try await line in bytes.lines {
                        guard line.hasPrefix("data: ") else { continue }
                        let payload = String(line.dropFirst(6))
                        if payload == "[DONE]" { break }
                        if let token = parseToken(payload, truncated: &truncated) {
                            continuation.yield(token)
                        }
                        if truncated { break }
                    }
                    if truncated { continuation.yield(marker) }
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: - Private helpers

    private static func buildURL(_ settings: AiProviderSettings) throws -> URL {
        var base = settings.baseUrl.trimmingCharacters(in: .whitespaces)
        while base.hasSuffix("/") { base = String(base.dropLast()) }
        let path = base + "/chat/completions"
        guard let url = URL(string: path) else { throw AiError.networkError(URLError(.badURL)) }
        return url
    }

    private static func buildBody(
        turns: [AiTurn],
        images: [Data],
        settings: AiProviderSettings
    ) throws -> Data {
        var messages: [[String: Any]] = []

        for (i, turn) in turns.enumerated() {
            // Images are attached to the FIRST user message as content items.
            if turn.role == AiTurn.roleUser && i == 0 && !images.isEmpty {
                var content: [[String: Any]] = [["type": "text", "text": turn.text]]
                for img in images {
                    content.append([
                        "type": "image_url",
                        "image_url": ["url": "data:image/png;base64,\(img.base64EncodedString())"],
                    ])
                }
                messages.append(["role": turn.role, "content": content])
            } else {
                messages.append(["role": turn.role, "content": turn.text])
            }
        }

        let payload: [String: Any] = [
            "model":      settings.model,
            "messages":   messages,
            "max_tokens": 16000,
            "stream":     true,
        ]
        return try JSONSerialization.data(withJSONObject: payload)
    }

    /// Extracts the content delta from a single SSE `data:` payload.
    /// Sets `truncated = true` if `finish_reason == "length"`.
    private static func parseToken(_ json: String, truncated: inout Bool) -> String? {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = obj["choices"] as? [[String: Any]],
              let first = choices.first else { return nil }
        if let reason = first["finish_reason"] as? String, reason == "length" {
            truncated = true
        }
        guard let delta  = first["delta"] as? [String: Any],
              let text   = delta["content"] as? String,
              !text.isEmpty else { return nil }
        return text
    }

    /// Extracts `{"error":{"message":...}}` from an error-response body, if present.
    private static func serverErrorMessage(_ data: Data) -> String? {
        guard !data.isEmpty,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let error = obj["error"] as? [String: Any],
              let message = error["message"] as? String,
              !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        return message
    }

    private static func httpMessage(_ code: Int) -> String {
        switch code {
        case 401: return "Unauthorized — check your API key in AI Settings."
        case 403: return "Forbidden."
        case 404: return "Endpoint not found — check the base URL in AI Settings."
        case 429: return "Rate limited — wait a moment and try again."
        case 500...599: return "The server returned an error."
        default: return HTTPURLResponse.localizedString(forStatusCode: code)
        }
    }
}
