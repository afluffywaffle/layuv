# Léamh / Layuv Android — Handoff (2026-06-24)

Branch: `native-port-drawpath-ink`
Build: `cd android_native && ./gradlew :app:assembleDebug`
Engine tests: `cd android_native && ./gradlew :docx:test` (run after ANY `docx/` change)
Install: `$HOME/Library/Android/sdk/platform-tools/adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`

Devices (full details + adb recovery in memory `reference_supernote_device.md`):
- **Nomad** `SN078C10005528` — used this session over USB.
- **Manta** `SN100C10008955` — active WiFi test device, `192.168.12.185:5555` (DHCP). If `connect`
  times out, re-enable over USB: `adb -s SN100C10008955 tcpip 5555` then `adb connect 192.168.12.185:5555`.
- adb is NOT on PATH → always use `$HOME/Library/Android/sdk/platform-tools/adb`.
- App id `com.afluffywaffle.layuv.dev`. Launch reader:
  `am start -n com.afluffywaffle.layuv.dev/com.afluffywaffle.layuv.reader.ReaderActivity`.
- Sample chapter on-device: `/sdcard/Document/salt_road.docx` (fictional, with planted craft flaws).

**Prior work (done before this session, committed):** the P1–P5 Tracker, the Help nav rework
(reader-consistent edge-nav — now the shared `EdgeNavView`), and the app-icon decision (keep the
serif "L"). Those handoff sections are retired.

---

## This session — landed + committed: the **Ask AI** feature

A full in-app revision loop: annotate a chapter → in-reader chat panel → the model returns a
rewrite that addresses the annotations → save as a clean, **versioned** `.docx` draft → re-annotate.
Provider-aware (Claude native API + Gemini via its OpenAI-compat endpoint). Built, on-device, and
runtime-verified on Gemini's free tier. The original annotated file is **never modified**.

Commits (newest first):
- `bbd8df0` — **ink notes → vision**: each handwritten ink note is sent as an image the model reads
  (Gemini + Claude); Help/disclosure rewrites.
- `654ba5f` — gated AI button (hidden until set up); reader-style single-side **edge-nav** in the
  full-screen reply viewer; large-input + continuation-limit **inline banners**; never-overwrite saves.
- `b042cfe` — expand/collapse icons (`ic_expand`/`ic_collapse`) + side flip strip in the viewer.
- `fe75b58` — the conversation panel: rewrite-as-versioned-draft card, provider-aware labels,
  `===REWRITE===` protocol, screen-flip, "AI"-bubble toolbar icon.

### Architecture
- **Engine (`docx/`, pure JVM):**
  - `ManuscriptSerializer.buildPrompt(plainText, annotations)` → `Prompt(text, inkAnnotationIds)` —
    seed prompt (chapter + structured annotations); ink notes referenced as "attached image N" with
    their ids surfaced for loading.
  - `RewriteProtocol` — `===REWRITE===`/`===END REWRITE===` markers; `parse()` splits a reply into
    `{conversation, rewrite}` (discussion shown inline, rewrite becomes a save card).
  - `DocxFromText.build(sourceDocx, text)` — clones the archive, regenerates the body, **strips all
    Léamh/comment/ink sidecars** → a clean draft.
  - `DocxStore.readAiChat/writeAiChat` (`leamh/aichat.json`), `DocxStore.readInkPng(bytes, ann.id)`.
- **App (`app/.../ai/`):** `AiProvider` iface; `ClaudeProvider` (`/v1/messages`) +
  `OpenAiCompatibleProvider` (`{baseUrl}/chat/completions`, used for Gemini); `AiProviderFactory`
  (provider/model from `leamh` prefs; `displayName`); `SecureKeyStore` (EncryptedSharedPreferences,
  GMS-free Tink). `AiMessage(role, text, images)` — `images` = PNG bytes → multimodal content blocks.
- **Reader (`app/.../reader/`):** `AskAiPanel` (top-half chat panel in `ReaderActivity`: seeds the
  prompt, loads ink PNGs onto the seed turn, renders discussion inline + a "Save & Open as
  `<root>_draft_vN.docx`" card, never dumps the chapter; handedness side flip strip via `ai_flip_side`;
  inline banners). `AiReplyActivity` (read-only full-screen reply viewer, single-side `EdgeNavView`
  rail). AI chat button is **gated** (hidden until `ai_disclosure_accepted` + a stored key).
  `AiSettingsActivity` (key entry, gated behind the 4-ack disclosure). `HelpActivity` ("Ask AI" gate
  page + "Directing the AI" page).

### Key behaviors
- **Chapter-scale, all-or-nothing:** always sends the WHOLE chapter (full context); returns a
  complete new draft, not regional edits. ~5–10 pages returns complete in one call; a manuscript
  (e.g. 120 pages) truncates at the 16k output cap → inline warning + a 3-Continue cap. Disclosed in
  the Privacy ack + the "Ask AI" Help page.
- **Ink notes (Option 1):** clean text + each ink annotation's bare PNG as an image, anchored by the
  text reference ("attached image N"). The vision model OCRs the handwriting (print + cursive). No
  on-device OCR (MyScript is cert-gated; ML Kit is GMS-banned). Multi-turn re-sends the ink on the
  seed turn; cleared on New/resume.
- **API key, not a chat subscription:** consumer plans (Claude Max, ChatGPT Plus, Gemini Advanced)
  do NOT grant API access; Gemini has a free API tier. Stated on the gate page (real user-confusion point).

### Verified on-device (Gemini)
- Full chapter loop: annotate → Send → complete rewrite addressing the marks → Save & Open → **clean
  draft** (zip = only `[Content_Types].xml`, `_rels/.rels`, `word/document.xml`) → **original
  untouched**. Versioning (`salt_road` → `_draft_v2`/`_draft_v3`).
- **Ink-image reading:** a handwritten "rename to Mara" / "cut this paragraph" / "change rain to
  sunshine" was read and acted on — and it asked a clarifying question on the contradictory one.
- 120-page truncation/Continue path; the inline warning + persistent banner.
- Engine `:docx:test` green (incl. `ManuscriptSerializerTest`, `RewriteProtocolTest`,
  `DocxFromTextTest`, `DocxStoreAiChatTest`).

### NOT yet verified
- **Suspend/resume** of the conversation across kill/relaunch (persists in `leamh/aichat.json` —
  should work; not explicitly re-tested).
- **Claude provider** — all testing was on Gemini (no Claude API key; a consumer Max plan can't be used).

---

## ✅ LANDED (2026-06-24) — multi-provider + a user's remote/local LLM (Android)

All three items shipped; `:app:assembleDebug` clean. **App-layer + manifest + `res/xml` only — no
`docx/` change, no engine tests.** Not yet runtime-tested end-to-end (no "brain" server stood up yet;
the cleartext guard's host-classification logic was verified standalone — 16/16 cases, incl. boundary
traps 172.32 and 100.200 correctly refused).

1. **Provider-AGNOSTIC client (refactored from the picker, by user decision).** There is **no provider
   list** — Layuv speaks the OpenAI-compatible wire format to ANY endpoint. The native `ClaudeProvider`
   was **deleted**; `OpenAiCompatibleProvider` is the sole client. `AiProviderFactory` is now just
   `baseUrl`/`model` (prefs `ai_base_url`, `ai_model`) → `OpenAiCompatibleProvider(baseUrl, model,
   requireKey=false)`; `displayName()` derives a label from the host (anthropic→"Claude",
   googleapis→"Gemini", openai→"OpenAI", else "your server"). `OpenAiCompatibleProvider`: (a) optional
   auth — omits `Authorization` when the key is blank (local servers need none); (b) runs every request
   through `CleartextPolicy` first. `AiSettingsActivity` is one agnostic form — **Endpoint (base URL) +
   Model + optional Key** + worked-example URLs (Claude `https://api.anthropic.com/v1`, Gemini, OpenAI,
   local `http://ip:11434/v1`). The AI-button gate (`ReaderActivity.isAiConfigured`) now keys on
   **base-URL set**, NOT a stored key (keyless local server must still show the button).
   - **Claude via its OpenAI-compat endpoint** (`https://api.anthropic.com/v1`, Bearer auth, model e.g.
     `claude-sonnet-4-6`). ⚠️ **Verify ink-note vision on-device** — Anthropic's compat layer is a shim;
     it does map OpenAI `image_url` → images, but this path is untested (native Claude was never tested
     either; Gemini — the tested path — already proves vision through the same `image_url` shape).
2. **Cleartext HTTP — Option 1 (decided with user): permit at NSC, enforce private-only IN CODE.**
   `res/xml/network_security_config.xml` keeps system trust anchors and grants the cleartext capability
   (Android NSC can't CIDR-scope, so true "private-only" isn't expressible in the manifest);
   **`CleartextPolicy.kt` is the real boundary** — refuses `http://` to any non-private host. Trusts
   RFC1918 / loopback / link-local / IPv6 ULA / Tailscale 100.64/10 + `*.ts.net` / local names. Wired
   via `android:networkSecurityConfig`. **This file is the single source of truth — read its KDoc.**
3. **Provider-aware disclosure.** The one-time Help gate (`AI_PRIVACY_TEXT`) is now provider-neutral
   (dropped the Claude-only "doesn't train" claim; explains paid-vs-free-vs-local data policy generally).
   `AiSettingsActivity`'s reminder is one general note (endpoint-you-set / local-stays-local /
   free-tier-may-train) and the endpoint field carries the trusted-vs-shared HTTP warning.
   `AI_ENCRYPTION_TEXT` generalized + sharpened (home/hotspot = fine; work/remote = Tailscale; app
   refuses public cleartext). The Help gate intro now says "any OpenAI-compatible endpoint … or a model
   you run yourself, which needs no key".

### 🌐 Trust model — the reusable reference (model the iOS/iPadOS/macOS ports on this)

The rule is platform-agnostic: **are both devices on the same network, and do you trust it?**

| Setup | Same net? | Trust? | User does | Wire |
|---|---|---|---|---|
| Mac Mini at home, client on home Wi-Fi | ✅ | ✅ yours | nothing — plain HTTP | stays on LAN |
| MacBook at work, client on same work Wi-Fi | ✅ | ⚠️ shared | **Tailscale** (others/IT could sniff; client-isolation may block) | tunnel |
| MacBook at work, client at home/cellular | ❌ | — | **Tailscale** (required) | tunnel |
| iPhone hotspot, both client + brain joined | ✅ | ✅ yours | nothing — plain HTTP (no cellular used) | stays on hotspot |
| iPhone hotspot for client, brain remote | ❌ | — | **Tailscale** | tunnel |

**Owned network + both devices on it → plain HTTP, zero setup. Anything else → Tailscale** (makes the
brain a trusted address *and* encrypts). The app enforces it: plain HTTP only to private/trusted hosts,
**refused** to the public internet. **Porting caveat:** the *rule* carries to iPhone/iPad/Mac, but the
*enforcement* is Android-specific (NSC + `CleartextPolicy.kt`) — Apple uses **App Transport Security**,
so each port must re-implement the private-host guard in Swift. Tailscale is easier on Apple (first-class
app) than on the Play-Services-free Supernote. See memory `project_ai_networking.md`.

### ✅ Pipe verified on-device (2026-06-24) + remaining checks
**Verified:** Nomad → MLX (`mlx_lm.server`, Qwen3-1.7B-4bit) on the MacBook over LAN
(`http://192.168.153.59:8080/v1`, **no key**) → Layuv **Test connection = OK**. Confirms the agnostic
form, the keyless-local path, the Paste-on-every-field + partial-save UX, and the cleartext guard
correctly *allowing* `http://` to a private LAN host. (Quality is junk — 1.7B — but the wire is proven.)
Full text-only Send loop also verified (annotate typed note → Send → 200, 433-token prompt; reply renders,
junk quality as expected for 1.7B — confirms the **model** is the only bottleneck, not the pipe).

**UX follow-up (next time AI settings is touched):** make **Test connection** a prominent button/pill —
it's a text link today and easy to miss (user feedback).

**Known limitation — text-only endpoints reject ink notes (HOLD these fixes for when it recurs):** ink
notes are sent as `image_url` images, so a text-only endpoint 404s the whole Send (confirmed: MLX/Qwen
plain-text → 200, with-image → 404). Layuv then shows a misleading *"check the model name."* Recommended:
(1) clearer message (*"model may not accept images — use a vision model or remove the ink note"*); (2) a
text-only fallback that omits ink images + substitutes a placeholder so the rest still rewrites; (3) maybe
a per-endpoint "supports vision" toggle. Full detail in memory `ai_text_only_endpoint_images.md`.

Remaining test paths (no code left to write; on-device verification):
- **Fastest, quality, zero-cost — Gemini (cloud, already-proven path):** free AIza key at
  aistudio.google.com → endpoint `https://generativelanguage.googleapis.com/v1beta/openai`, model
  `gemini-2.5-flash`. Tests the full loop with a good model, no Mac, no cost.
- **Local/LAN plumbing — MLX on the user's MacBook Pro M3 Pro (18GB):** `pipx install mlx-lm` then
  `mlx_lm.server --model mlx-community/Qwen3-1.7B-4bit --host 0.0.0.0 --port 8080` → Supernote on the
  same Wi-Fi points at `http://<mac-LAN-ip>:8080/v1`, **no key**. Proves keyless-local + plain-HTTP-LAN +
  the cleartext guard. ⚠️ Quality is poor — per the user's own testing (`~/Downloads/Gofal-Session-2026-06-18.md`)
  18GB only stably hosts a 1.7B @ 64K (~8.9GB; 4B OOM-crashes). MLX serves an OpenAI-compatible `/v1`, so
  it's a drop-in. Real quality wants the Mac-Mini tier (14B+) or the Anthropic-key proxy.
- **Claude (cloud):** `https://api.anthropic.com/v1` + a console `sk-ant-` key → verify ink-note vision
  through the compat layer (see item 1 caveat).
- In all: confirm `http://` LAN allowed, `http://` public refused with the guard message, suspend/resume.
- Still unverified from the prior session: conversation **suspend/resume**, and the **Claude** provider.

**Bigger vision (plan file, not started):** a Mac-Mini "memory layer" — a small server holding the
project's reference files + the Anthropic key, exposing an OpenAI-compatible endpoint, so the model
writes with persistent continuity; Apple devices call it live, the Supernote syncs via **Supernote
Private Cloud**; Tailscale/Headscale for remote. See `~/.claude/plans/rewrite-if-this-was-rustling-comet.md`
→ "NEXT ARCHITECTURE". The macOS app has **zero AI** today (net-new Swift work); the `docx/` engine is
pure-JVM and reusable on the Mini.

**Other tracked follow-ups:** import non-DOCX (.txt/.rtf/.doc/.epub → flatten to .docx); macOS engine
parity (mirror `ManuscriptSerializer`/`RewriteProtocol`/`DocxFromText`/aichat/ink-images into `LeamhDocx`).

---

## Key files

| File | Purpose |
|---|---|
| `docx/.../ManuscriptSerializer.kt` | seed prompt → `Prompt(text, inkAnnotationIds)` |
| `docx/.../RewriteProtocol.kt` | `===REWRITE===` markers + `parse()` |
| `docx/.../DocxFromText.kt` | rewrite text → clean draft `.docx` |
| `docx/.../DocxStore.kt` | `readAiChat`/`writeAiChat`, `readInkPng` |
| `app/.../ai/*` | `AiProvider`, `ClaudeProvider`, `OpenAiCompatibleProvider`, `AiProviderFactory`, `AiMessage`, `SecureKeyStore` |
| `app/.../reader/AskAiPanel.kt` | chat panel — seed, ink load, render, save |
| `app/.../reader/AiReplyActivity.kt` | full-screen reply viewer (`EdgeNavView` rail) |
| `app/.../reader/AiSettingsActivity.kt` | key entry (gated) |
| `app/.../reader/HelpActivity.kt` | "Ask AI" gate + "Directing the AI" pages |
| `app/.../reader/EdgeNavView.kt` | shared edge-nav (now has a `side` option: both/left/right) |

Logcat (AI): `adb -s <serial> logcat -s AI,DocxWriteQueue` (the `AI` tag logs `request sent; response <code>`).
Note: `am start` can't deep-link `HelpActivity` (blocked) — navigate on-device to check Help.

---

## Handoff prompt for a new conversation

> I'm working on the Léamh/Layuv Android app (`android_native/` at `/Users/jayromacorda/Develop/layuv`),
> branch `native-port-drawpath-ink`. Read `CLAUDE.md` and `HANDOFF_next.md` in full first, and skim
> `~/.claude/plans/rewrite-if-this-was-rustling-comet.md` for the AI architecture.
>
> The **Ask AI feature is built, committed, and verified on-device** (Gemini): annotate a chapter →
> in-reader chat panel → the model returns a complete rewrite addressing the marks (including
> **handwritten ink notes**, read as images) → "Save & Open" as a clean versioned `.docx` →
> re-annotate. See HANDOFF "This session — landed."
>
> **Pick up the next item: multi-provider + a user's remote/local LLM.** `OpenAiCompatibleProvider`
> already speaks the wire format; what's left is (1) a provider picker + `ai_base_url`/token in
> `AiSettingsActivity` (route via `AiProviderFactory.current()`, new `ai_provider="custom"`);
> (2) a **scoped** cleartext-HTTP `network-security-config` for LAN endpoints (private hosts only,
> never global) + an HTTPS warning; (3) **provider-aware disclosure copy** — the Privacy ack still
> hard-codes "Anthropic's commercial API does not train…", which is wrong for Gemini's free tier;
> generalize it as part of this. Confirm scope with me before building.
>
> Build: `cd android_native && ./gradlew :app:assembleDebug`. Engine tests after any `docx/` change:
> `./gradlew :docx:test`. Devices/adb + IP recovery: memory `reference_supernote_device.md`
> (Manta `SN100C10008955` @ `192.168.12.185:5555`; Nomad `SN078C10005528`). adb path:
> `$HOME/Library/Android/sdk/platform-tools/adb`. App id `com.afluffywaffle.layuv.dev`. I can't
> deep-link Help via `am` — to check Help, navigate on-device (reader → ⋯ → Help & About → swipe).
> Sample chapter at `/sdcard/Document/salt_road.docx`. Still unverified: conversation
> **suspend/resume**, and the **Claude provider** (only Gemini tested).
