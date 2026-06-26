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

    static func isPrivateHost(_ host: String) -> Bool {
        // Loopback literals
        if host == "localhost" || host == "127.0.0.1" || host == "::1" { return true }
        // Single-label names: "mini", "mymac", etc.
        if !host.contains(".") { return true }
        // Known-local TLDs
        for suffix in [".local", ".lan", ".internal", ".home.arpa", ".ts.net"] {
            if host.hasSuffix(suffix) { return true }
        }
        // IPv4 private + special ranges
        if let a = IPv4Bytes(host) {
            if a[0] == 10 { return true }                                   // 10/8
            if a[0] == 172 && a[1] >= 16 && a[1] <= 31 { return true }     // 172.16/12 (incl. hotspot)
            if a[0] == 192 && a[1] == 168 { return true }                   // 192.168/16
            if a[0] == 127 { return true }                                   // 127/8 full loopback block
            if a[0] == 169 && a[1] == 254 { return true }                   // 169.254/16 link-local
            if a[0] == 100 && a[1] >= 64 && a[1] <= 127 { return true }    // 100.64/10 CGNAT / Tailscale
        }
        // IPv6: link-local fe80::/10, ULA fc00::/7 (fc.../fd...)
        if host.hasPrefix("fe80:") || host.hasPrefix("fc") || host.hasPrefix("fd") { return true }
        return false
    }
}

// Minimal IPv4 dotted-decimal parser — avoids pulling in Network.framework.
private func IPv4Bytes(_ s: String) -> [UInt8]? {
    let parts = s.split(separator: ".", maxSplits: 3, omittingEmptySubsequences: false)
    guard parts.count == 4 else { return nil }
    var bytes = [UInt8]()
    for p in parts {
        guard let n = UInt8(p) else { return nil }
        bytes.append(n)
    }
    return bytes
}
