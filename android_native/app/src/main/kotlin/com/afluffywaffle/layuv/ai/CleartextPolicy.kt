package com.afluffywaffle.layuv.ai

import java.net.URL

/**
 * The single source of truth for when Layuv may speak plain, unencrypted HTTP.
 *
 * ## Why this exists
 * A user can point the "Custom" provider at a model they run themselves — a Mac
 * Mini at home, a MacBook at work, a laptop on a phone hotspot. Those servers
 * usually speak plain HTTP, which Android blocks by default (API 28+). Granting
 * cleartext in `res/xml/network_security_config.xml` is necessarily broad —
 * Android's network-security-config can name exact hosts but CANNOT express an IP
 * range (no CIDR), so "permit only 192.168.x" isn't expressible in the manifest.
 *
 * So the real scoping lives HERE, in code: the manifest grants the capability, and
 * [cleartextError] is the boundary that actually decides. The app will only ever
 * send plain HTTP to a private/trusted address and REFUSES plain HTTP to any public
 * internet host — so a mistyped or malicious URL can't leak the manuscript in the
 * clear. Every cloud provider (Claude, Gemini, OpenAI) is hardcoded `https://` and
 * TLS-verified; the only cleartext that can ever happen is to a host this file OKs.
 *
 * ## The trust model (home / work / hotspot / remote)
 * The whole thing reduces to one question: are both devices on the same network,
 * and do you trust that network?
 *
 *  - **A network you own, both devices on it** (home Wi-Fi, your own phone hotspot)
 *    → plain HTTP is fine; the traffic never leaves that network. No setup.
 *  - **A shared/untrusted network** (work or café Wi-Fi) → technically local, but
 *    others on it could see plain HTTP, and client isolation may block it outright
 *    → use HTTPS or reach the machine over a VPN (Tailscale) instead.
 *  - **The server is somewhere you aren't** (you're remote / on cellular) → the
 *    connection crosses the public internet → plain HTTP is REFUSED here → use
 *    Tailscale, which makes the machine reachable as a trusted address (100.64/10,
 *    `*.ts.net`) AND encrypts the tunnel end-to-end even though the server only
 *    speaks plain HTTP.
 *
 * [isPrivateHost] therefore trusts: RFC1918 LAN (10/8, 172.16/12, 192.168/16),
 * loopback (127/8, ::1), link-local (169.254/16, fe80::/10), IPv6 unique-local
 * (fc00::/7), the Tailscale/CGNAT range (100.64/10), and local hostnames
 * (`localhost`, single-label names, `*.local`, `*.lan`, `*.internal`, `*.ts.net`).
 *
 * ## Porting note (future iOS / iPadOS / macOS clients)
 * The networking *rule* above is platform-agnostic and applies to any director
 * device (Supernote, iPhone, iPad). The *enforcement*, however, is Android-specific
 * (network-security-config + this class). Apple platforms use App Transport
 * Security (ATS) for the manifest half — so the next port must re-implement this
 * same private-host guard in Swift; it does not come for free. Tailscale is a
 * first-class app on Apple platforms, so the remote case is actually easier there.
 */
object CleartextPolicy {

    /**
     * `null` if [url] is safe to send (HTTPS anywhere, or plain HTTP to a trusted
     * private host); otherwise a human-readable error explaining the refusal.
     * A malformed URL returns `null` here — the connection attempt fails later with
     * its own message; this guard only blocks the specific "cleartext to public" case.
     */
    fun cleartextError(url: String): String? = try {
        val u = URL(url)
        if (!u.protocol.equals("http", ignoreCase = true)) null // https (or anything else) is fine
        else if (isPrivateHost(u.host)) null
        else "That's a plain-HTTP address on the public internet, which isn't encrypted. " +
            "Use https://, or reach a server on your own machine over a VPN like Tailscale."
    } catch (e: Exception) {
        null
    }

    /** True if [host] is a private/LAN/loopback/Tailscale address or local name. */
    fun isPrivateHost(host: String?): Boolean {
        val h = host?.trim()?.trim('[', ']')?.lowercase().orEmpty()
        if (h.isEmpty()) return false
        if (h == "localhost") return true
        ipv4(h)?.let { return isPrivateV4(it) }
        if (h.contains(':')) return isPrivateV6(h) // IPv6 literal
        // A hostname (not an IP literal). Public hosts always carry a public TLD, so a
        // single-label name ("mini") or a known-local suffix is treated as LAN.
        return !h.contains('.') ||
            h.endsWith(".local") || h.endsWith(".lan") ||
            h.endsWith(".internal") || h.endsWith(".home.arpa") ||
            h.endsWith(".ts.net") // Tailscale MagicDNS
    }

    private fun ipv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }

    private fun isPrivateV4(o: IntArray): Boolean = when {
        o[0] == 10 -> true                          // 10.0.0.0/8 private
        o[0] == 127 -> true                         // 127.0.0.0/8 loopback
        o[0] == 172 && o[1] in 16..31 -> true       // 172.16.0.0/12 private (incl. iOS hotspot 172.20.10.x)
        o[0] == 192 && o[1] == 168 -> true          // 192.168.0.0/16 private
        o[0] == 169 && o[1] == 254 -> true          // 169.254.0.0/16 link-local
        o[0] == 100 && o[1] in 64..127 -> true      // 100.64.0.0/10 CGNAT — Tailscale's range
        else -> false
    }

    private fun isPrivateV6(h: String): Boolean = when {
        h == "::1" -> true                          // loopback
        h.startsWith("fc") || h.startsWith("fd") -> true // fc00::/7 unique-local
        h.startsWith("fe8") || h.startsWith("fe9") ||
            h.startsWith("fea") || h.startsWith("feb") -> true // fe80::/10 link-local
        else -> false
    }
}
