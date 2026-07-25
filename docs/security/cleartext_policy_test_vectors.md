# CleartextPolicy test vectors

Shared behavioural contract for the two independent implementations of the
plain-HTTP allow/deny guard:

- Kotlin: `android_native/app/src/main/kotlin/com/afluffywaffle/layuv/ai/CleartextPolicy.kt`
- Swift:  `macos_native/LeamhApp/LeamhApp/Shared/CleartextPolicy.swift`

Both must treat every row identically. If a change to either file makes a row
diverge, that is a regression — update BOTH implementations together (or, if
the divergence is deliberate, update this file to say so and get it reviewed
as a security decision, not a silent drift).

This table reflects `CleartextPolicy.swift` as rewritten 2026-07-24 (normalise →
localhost → IPv4 → IPv6 → hostname, matching Kotlin's order exactly) — not the
prior version, which allow-listed all IPv6 including public hosts.

Verification status:
- **Kotlin**: **NOT** currently exercised by any automated test either. The
  `app/` module has no unit-test infrastructure at all — no `src/test/` and no
  `testImplementation` dependencies in `app/build.gradle.kts` (only the `docx/`
  module has JUnit). Adding those is a build change left for a deliberate
  decision. Verify by hand against this table after any change to the Kotlin file.
- **Swift**: **NOT** currently exercised by any automated test. There is no
  app-level Xcode test target for `LeamhApp` (only the `LeamhDocx` Swift
  package has one), and creating one / moving `CleartextPolicy.swift` into
  the package is out of scope for this audit follow-up. Verify by hand
  against this table after any change to the Swift file.

Columns: `host` is what's passed to `isPrivateHost` (Kotlin) / the URL host
seen by `isAllowed` (Swift). "allow" means plain HTTP is permitted to that
host (`isPrivateHost` / `isPrivateHost` → true). This table intentionally
does NOT cover the HTTPS-always-passes short-circuit as a per-host case —
see the dedicated row at the bottom instead.

| host | expect | rationale |
|---|---|---|
| `localhost` | allow | explicit loopback name |
| `127.0.0.1` | allow | loopback IPv4 literal (127.0.0.0/8) |
| `127.255.255.255` | allow | still within 127/8 |
| `::1` | allow | loopback IPv6 literal |
| `mini` | allow | single-label hostname (no dot) treated as LAN |
| `mymac` | allow | single-label hostname |
| `foo.local` | allow | known-local suffix (mDNS) |
| `foo.lan` | allow | known-local suffix |
| `foo.internal` | allow | known-local suffix |
| `foo.home.arpa` | allow | known-local suffix (RFC 8375) |
| `mini.ts.net` | allow | Tailscale MagicDNS suffix |
| `10.0.0.1` | allow | RFC1918 10.0.0.0/8 |
| `10.255.255.255` | allow | still within 10/8 |
| `172.16.0.1` | allow | RFC1918 172.16.0.0/12, low edge |
| `172.31.255.255` | allow | RFC1918 172.16.0.0/12, high edge |
| `172.20.10.5` | allow | iOS Personal Hotspot range (within 172.16/12) |
| `172.15.0.1` | deny | just below 172.16/12 — must NOT be treated as private |
| `172.32.0.1` | deny | just above 172.16/12 — must NOT be treated as private |
| `192.168.1.1` | allow | RFC1918 192.168.0.0/16 |
| `169.254.1.1` | allow | link-local IPv4 (169.254.0.0/16) |
| `100.64.0.1` | allow | CGNAT / Tailscale range, low edge (100.64.0.0/10) |
| `100.127.255.255` | allow | CGNAT / Tailscale range, high edge |
| `100.63.255.255` | deny | just below 100.64/10 |
| `100.128.0.1` | deny | just above 100.64/10 |
| `fe80::1` | allow | IPv6 link-local (fe80::/10) |
| `fe90::1` | allow | both sides match the `fe9` prefix — within fe80::/10 by CIDR math |
| `fec0::1` | deny | matches none of `fc`/`fd`/`fe8`/`fe9`/`fea`/`feb` on either side — deprecated IPv6 site-local space, correctly excluded |
| `fc00::1` | allow | IPv6 unique-local (ULA), fc00::/7 |
| `fd12:3456::1` | allow | IPv6 ULA, fd-prefixed half of fc00::/7 |
| `2001:4860:4860::8888` | **deny** | public IPv6 (Google DNS) — **regression-guard row**: Swift's old single-label check (`!host.contains(".")`) ran before the IPv6 branch, so every dotless IPv6 literal — including public ones — was waved through as "allow". Real cleartext-leak defect, fixed 2026-07-24 when Swift was rewritten to check IPv6 before the dotless fallback. |
| `2606:4700::1111` | **deny** | public IPv6 (Cloudflare DNS) — second regression-guard row for the same fixed defect |
| `example.com` | deny | ordinary public hostname |
| `google.com` | deny | ordinary public hostname |
| `fdxyz.com` | deny | **adversarial**: public hostname that starts with "fd" but has no `:` — must NOT be misread as an IPv6 ULA literal |
| `fce.example.com` | deny | **adversarial**: public hostname starting "fc" but no `:` |
| `fe80.example.com` | deny | **adversarial**: public hostname starting "fe80" but no `:` — must NOT be misread as IPv6 link-local |
| `10.0.0.1.evil.com` | deny | **adversarial**: not a valid IPv4 literal (6 dot-separated labels); must not partial-match "10." prefix and must not be treated as single-label. Both sides reject it via the parser's **4-part count check** (`parts.size != 4` in Kotlin, `parts.count == 4` guard in Swift) — Swift's IPv4 parser no longer uses `maxSplits:`, so this now fails deterministically on part-count rather than incidentally on `Int("1.evil.com")` returning nil. |
| `192.168.1.1.attacker.net` | deny | **adversarial**: same shape, using the most common private prefix as bait |
| `999.1.1.1` | deny | **adversarial**: malformed IPv4 (octet out of range) — must fail closed, not crash or default-allow |
| `` (empty host) | deny | empty/missing host must fail closed. Verified against the current Swift: `isPrivateHost("")` returns `false` (the `if h.isEmpty { return false }` guard runs first, before any of the private-range checks) — matches Kotlin's `if (h.isEmpty()) return false`. |
| `MINI` | allow | uppercase single-label — host matching must be case-insensitive |
| `FOO.LOCAL` | allow | uppercase known-local suffix — case-insensitive. Verified against the current Swift: `isPrivateHost` itself lowercases (`.lowercased()` in the normalisation step at the top of the function), so this holds even if a caller passes the raw, un-lowercased host directly to `isPrivateHost` rather than through `isAllowed` (which also lowercases before calling it). |
| `EXAMPLE.COM` | deny | uppercase public hostname — still denied |

## Whole-URL cases (exercises the scheme short-circuit, not just isPrivateHost)

| URL | expect | rationale |
|---|---|---|
| `https://example.com/` | allow (sent) | HTTPS always passes regardless of host — the guard only restricts the `http://` scheme |
| `https://10.0.0.1/` | allow (sent) | same — HTTPS to a private IP is unrestricted too, it was never the concern |
| `http://example.com/` | deny (refused) | plain HTTP to a public host — the case this guard exists to block |
| `http://10.0.0.1/` | allow (sent) | plain HTTP to a private host — the case this guard exists to permit |

## IPv6 behaviour — verified in agreement (2026-07-24)

As of the 2026-07-24 rewrite of `CleartextPolicy.swift`, both implementations
use the identical `isPrivateV6` logic: host-string prefixes `"fc"`, `"fd"`
(fc00::/7 ULA) and `"fe8"`, `"fe9"`, `"fea"`, `"feb"` (fe80::/10 link-local,
prefix-matched in four pieces since a plain string comparison can't do CIDR
math on a 16-bit group). `fe8`/`fe9`/`fea`/`feb` together cover exactly
`fe80`–`febf`, i.e. fe80::/10 exactly — `fec0` matches none of them, so
neither side treats the deprecated IPv6 site-local range (`fec0::`–`feff::`)
as private. An earlier draft of this document claimed Kotlin "over-includes"
`fec0::`–`feff::`; that claim was wrong and has been removed. There is no
longer any known IPv6 divergence between the two implementations — the real
defect was the ordering bug in Swift (see the public-IPv6 regression-guard
rows above), not a prefix-width mismatch.

## Other minor difference noted (not test-vector-driven, informational only)

`cleartextError` (Kotlin) catches URL-parse exceptions and returns `null`
(meaning "safe to send") for a malformed URL string, deferring the failure
to the later connection attempt. Swift's `isAllowed` takes an already-parsed
`URL`, so a string that fails to parse into a `URL` never reaches the guard
at all (call sites presumably handle that separately). Not exercised by the
per-host vectors above since it depends on caller-side URL construction, but
worth knowing if the two call sites ever get compared directly.
