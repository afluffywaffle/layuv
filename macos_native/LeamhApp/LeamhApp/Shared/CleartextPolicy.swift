import Foundation

/// Guards plain-HTTP connections to the AI provider endpoint.
///
/// NSAllowsArbitraryLoads in the iOS Info.plist lets the OS make the connection;
/// this enum is the real security layer — only RFC-1918 private networks, loopback,
/// link-local, CGNAT/Tailscale, and known-local hostnames are permitted over HTTP.
/// Everything else (public internet) must use HTTPS.
enum CleartextPolicy {

    /// True if the URL may be fetched over plain HTTP. HTTPS URLs always pass.
    static func isAllowed(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "http" else { return true }
        guard let host = url.host?.lowercased() else { return false }
        return isPrivateHost(host)
    }

    /// Mirrors the Kotlin twin (`CleartextPolicy.isPrivateHost`) EXACTLY, including
    /// the order of the checks. The ordering is load-bearing: the IPv6 branch MUST
    /// run before the single-label fallback, because every IPv6 literal is dotless
    /// and would otherwise be waved through as a "single-label name" — which would
    /// allow cleartext to arbitrary public IPv6 hosts (e.g. 2001:4860:4860::8888).
    /// Any change here must be made in BOTH files; see
    /// docs/security/cleartext_policy_test_vectors.md.
    static func isPrivateHost(_ host: String?) -> Bool {
        // Normalise the way Kotlin does: trim, strip IPv6 URL brackets, lowercase.
        let h = (host ?? "")
            .trimmingCharacters(in: .whitespaces)
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            .lowercased()
        if h.isEmpty { return false }
        if h == "localhost" { return true }
        // IPv4 literal
        if let a = IPv4Bytes(h) { return isPrivateV4(a) }
        // IPv6 literal — MUST precede the dotless-hostname fallback below.
        if h.contains(":") { return isPrivateV6(h) }
        // A hostname (not an IP literal): single-label name or known-local suffix.
        if !h.contains(".") { return true }
        for suffix in [".local", ".lan", ".internal", ".home.arpa", ".ts.net"] {
            if h.hasSuffix(suffix) { return true }
        }
        return false
    }
}

private func isPrivateV4(_ o: [Int]) -> Bool {
    if o[0] == 10 { return true }                                // 10/8 private
    if o[0] == 127 { return true }                               // 127/8 loopback
    if o[0] == 172 && o[1] >= 16 && o[1] <= 31 { return true }   // 172.16/12 (incl. hotspot)
    if o[0] == 192 && o[1] == 168 { return true }                // 192.168/16 private
    if o[0] == 169 && o[1] == 254 { return true }                // 169.254/16 link-local
    if o[0] == 100 && o[1] >= 64 && o[1] <= 127 { return true }  // 100.64/10 CGNAT / Tailscale
    return false
}

private func isPrivateV6(_ h: String) -> Bool {
    if h == "::1" { return true }                                        // loopback
    if h.hasPrefix("fc") || h.hasPrefix("fd") { return true }            // fc00::/7 unique-local
    if h.hasPrefix("fe8") || h.hasPrefix("fe9")
        || h.hasPrefix("fea") || h.hasPrefix("feb") { return true }      // fe80::/10 link-local
    return false
}

// Minimal IPv4 dotted-decimal parser — avoids pulling in Network.framework.
// No maxSplits: "10.0.0.1.evil.com" must fail the 4-part check by design,
// not incidentally because the last component won't parse as a number.
private func IPv4Bytes(_ s: String) -> [Int]? {
    let parts = s.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count == 4 else { return nil }
    var out = [Int]()
    for p in parts {
        guard let n = Int(p), n >= 0, n <= 255 else { return nil }
        out.append(n)
    }
    return out
}
