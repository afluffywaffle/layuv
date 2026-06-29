import Foundation

/// Drives the Ask-AI conversation: loads/saves history on the open DOCX, sends the manuscript
/// seed + follow-up turns, streams the assistant reply, and surfaces a pending rewrite for
/// "Save as Draft". Pure Foundation — shared verbatim by the iOS and macOS chat views.
@MainActor
final class AskAiViewModel: ObservableObject {
    @Published var turns: [AiTurn] = []
    @Published var partialText  = ""
    @Published var isStreaming  = false
    @Published var errorMessage: String?
    @Published var pendingRewrite: String?
    @Published var lastTruncated = false
    @Published var draftURL: URL?

    private var activeTask: Task<Void, Never>?

    // MARK: - Public actions

    func loadHistory(from store: DocumentStore) async {
        turns = await store.loadAiChat()
        refreshRewriteState()
    }

    /// Sends the manuscript seed prompt (chapter + annotations) as the first user turn.
    func sendSeed(store: DocumentStore) async {
        guard let doc = store.document else { return }
        let annotations = store.annotations.map(\.annotation)
        let seed = ManuscriptSerializer.buildPrompt(plainText: doc.plainText,
                                                     annotations: annotations)
        var images: [Data] = []
        for id in seed.inkAnnotationIds {
            if let png = await store.loadInkPng(id) { images.append(png) }
        }
        turns.append(AiTurn(role: AiTurn.roleUser, text: seed.text))
        await runStream(store: store, images: images)
    }

    func sendMessage(_ text: String, store: DocumentStore) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        turns.append(AiTurn(role: AiTurn.roleUser, text: trimmed))
        await runStream(store: store, images: [])
    }

    func continueTruncated(store: DocumentStore) async {
        turns.append(AiTurn(role: AiTurn.roleUser,
                            text: "Please continue from where you left off."))
        await runStream(store: store, images: [])
    }

    func saveDraft(store: DocumentStore) async {
        guard let rewrite = pendingRewrite else { return }
        do {
            draftURL = try await store.saveAiDraft(rewrite: rewrite)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func clearConversation(store: DocumentStore) async {
        turns = []
        pendingRewrite = nil
        lastTruncated = false
        await store.saveAiChat([])
    }

    func cancelStream() {
        activeTask?.cancel()
    }

    // MARK: - Streaming

    private func runStream(store: DocumentStore, images: [Data]) async {
        isStreaming   = true
        partialText   = ""
        pendingRewrite = nil
        lastTruncated = false
        errorMessage  = nil

        let task: Task<Void, Never> = Task { @MainActor in
            do {
                let sseStream = try OpenAiCompatibleProvider.stream(turns: self.turns,
                                                                     images: images)
                var full      = ""
                var truncated = false
                for try await token in sseStream {
                    if token == OpenAiCompatibleProvider.truncatedMarker {
                        truncated = true
                        break
                    }
                    full += token
                    self.partialText = full
                }
                self.turns.append(AiTurn(role: AiTurn.roleAssistant,
                                         text: full, truncated: truncated))
                self.partialText  = ""
                self.lastTruncated = truncated
                self.refreshRewriteState()
                await store.saveAiChat(self.turns)
            } catch is CancellationError {
                // User cancelled — discard partial text without an error banner.
                self.partialText = ""
            } catch {
                self.errorMessage = error.localizedDescription
            }
            self.isStreaming = false
        }
        activeTask = task
        await task.value
    }

    private func refreshRewriteState() {
        pendingRewrite = turns.last.flatMap { t -> String? in
            guard t.role == AiTurn.roleAssistant else { return nil }
            return RewriteProtocol.parse(t.text).rewrite
        }
    }
}
