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

## ⏭️ OUTSTANDING / next — multi-provider + a user's remote/local LLM

The user's stated next direction; several deferred copy items hinge on it.

1. **Provider picker + custom endpoint in `AiSettingsActivity`.** `OpenAiCompatibleProvider` already
   speaks the wire format (Bearer + `{model,messages,stream}` SSE, multimodal-ready). Left to do:
   a provider option (`ai_provider="custom"`), `ai_base_url` + token prefs, and the settings UI;
   route via `AiProviderFactory.current()`. Reaches OpenAI and a user's remote/local LLM
   (Ollama/LM Studio/llama.cpp/vLLM `/v1`).
2. **Scoped cleartext-HTTP allowance for a LAN endpoint.** A local model is usually plain HTTP →
   a `network-security-config` limited to private/LAN hosts (NEVER global). Warn when an endpoint
   isn't HTTPS; recommend Tailscale for remote.
3. **Provider-aware disclosure copy (deferred this session, by decision).** The Privacy ack still
   says *"Anthropic's commercial API does not train on data you submit"* — Claude-specific and wrong
   for Gemini's free tier (which may train). Generalize it WITH the multi-provider change so the copy
   flexes per provider.

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
