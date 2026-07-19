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

## ✅ REFACTORED (2026-07-19) — working-copy model replaces versioned "Export for AI"

**Why:** the versioned `_draft_vN.docx` export/archive scheme (a new numbered copy on every export,
3-newest-kept archive folder, an `AI-export-folder` preference to point at) turned out to be more
bookkeeping than the manual-Claude-Code workflow needed. It's replaced by a simpler **working-copy
model**: Layuv always annotates a single `<base>_annotated.docx` copy, and hands the external drafting
process only what it needs to locate each annotation — not a duplicated chapter export.

**What landed (`:docx:test` 77/77 green, Swift LeamhDocx 43/43 green, `:app:assembleDebug` clean):**
- **`ReaderActivity.resolveAnnotatedCopy(file)`** — called from `loadFromFile` on every open. If the
  opened file isn't already a `_annotated.docx` copy, it makes one once (same folder, atomic tmp+rename)
  and opens that; if the copy already exists, reopens it as-is so existing ink is never clobbered. The
  original manuscript file is never mutated — it stays pristine for the external drafting process to
  keep owning version numbering.
- **`exportAnnotationsOnly()`** (replaces the old `exportForAi()`/`AiExporter.kt`) — writes
  `<cleanBase>_annotations.md` (annotations + their anchors: quoted passage, prefix/suffix context,
  position fraction — no chapter text) flat into the annotated copy's own folder, plus one PNG per ink
  annotation, via `ManuscriptSerializer.buildAnnotationsOnlyExport`. Not versioned (overwrites each
  pass) since it's a reference artifact, not a draft — the external process reads the manuscript
  `.docx` directly and only needs to know where each annotation applies.
- **`importRewrite()`** — simplified to a plain file browser (`FileBrowserActivity`, optionally
  pre-navigated to a configured import folder). No more auto-detecting a versioned rewrite DOCX across
  candidate paths; picking a file overwrites the working copy in place (tmp + atomic rename) and
  reloads it.
- **`AiExporter.kt` removed.** No `ai_export_folder` preference, no `_draft_vN` naming, no
  keep-3/archive-folder logic, on the Android side.
- Both engines' `DocxArchive` now do a **raw-copy ZIP rewrite** when producing the annotated copy —
  the zip is cloned byte-for-byte apart from the parts actually being changed, rather than
  reconstructed part-by-part.

---

## ✅ FIXED (2026-06-25 + 2026-06-26) — annotation toolbar "Tap outside" toggle

The toggle never gated anything: the reader's `cancelSelection()` (→ `onHidePopup`) dismissed the
toolbar on ANY tap, and the Supernote's window manager never delivers `ACTION_OUTSIDE` to
`PopupWindow`s (verified on-device with a probe — so the popup-side `isOutsideTouchable` was dead).
Fix: gate the reader's tap-dismiss on the pref — `ReaderView.onSingleTapUp` calls `cancelSelection()`
only when `outsideTapDismisses()` (reads `pref_outside_dismiss`, **default ON**).

**2026-06-26 audit + hardening** — two remaining dismissal leaks closed:

1. **Stylus slop** (`handleStylusEvent ACTION_MOVE`): a pen drift > 8dp was enough to start a new
   drag, fire `onHidePopup`, and if the scrub landed on blank space → invalid selection →
   `cancelSelection()`. With pref=off and a committed selection, the activation slop is now **3×**
   (24dp vs 8dp). Accidental wobble doesn't trigger a new drag; real selection drags (> 50dp) still
   work fine.

2. **Stylus tap semantics**: the original fix also gated the stylus tap path (line 878) with
   `outsideTapDismisses()`, which meant the pen couldn't dismiss an accidental selection.
   Corrected — **stylus taps always dismiss** (pen is always intentional on Supernote); only
   *finger/palm* taps are gated.

**Final dismiss behaviour with pref=off:**

| Gesture | Dismisses? |
|---|---|
| Stray palm/finger tap | ✗ blocked |
| Deliberate stylus tap | ✓ always |
| Finger swipe > 60dp | ✓ always |
| ✕ button or tool pick | ✓ always |

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

**UX follow-up (next time the Ask AI pane / `AskAiPanel` is touched):** add a **"Settings" button in the
Ask AI pane** that opens `AiSettingsActivity` directly — so a user copy-pasting endpoint/model/key
doesn't have to round-trip through Help & About → Ask AI each time to reach settings (user feedback,
while setting up). Note: the AI chat bubble already appears the moment an endpoint is saved (gate =
base-URL set + disclosures accepted), which is intended/fine; this is purely a faster path to settings.

**UX follow-up (next time `AiReplyActivity` is touched):** let users **reply from the expanded
full-screen reply viewer**, not just the panel. Small-screen (Nomad) design — keep the viewer
read-only/clean by default; add a **"Reply" toggle** that reveals a compact input + Send on demand (so
reading stays full-screen until you want to reply); `adjustPan` so the OSK floats and the text stays in
view; on Send → "Working…" → the viewer refreshes to the new latest reply and the input collapses back
to hidden (so you stay in the comfortable full-screen view for the whole back-and-forth and never have
full-reply-text + input + keyboard all fighting the small screen at once). Minimal fallback if still too
cramped: "Reply" just closes the viewer and focuses the panel's input. (User feedback.)

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

## ✅ BUILT (2026-06-27) — On-device Reference Library

Users pick a folder of `.md`/`.txt`/`.rst`/`.org` files on the Supernote (characters.md, settings.md, style guide, etc.). On every Ask AI send those files are prepended as a `role="system"` message — client-side in `AskAiPanel.buildLibrarySystemMessage()`, before the request leaves the device. Works for any endpoint: cloud API key (Claude, Gemini, OpenAI) or a direct local LLM (Ollama, LM Studio via Tailscale).

**Two new fields in `AiSettingsActivity`:**
- **Context window (tokens) — experimental** (`ai_context_limit` pref, 0 = no cap) — for local LLM users who need to cap injection to fit their model's window; labelled experimental because it can't be verified without a local LLM.
- **Reference library** folder picker (`ai_library_dir` pref) — displays folder name + file count + ~token estimate; warns if the library would exceed the available budget at the configured context limit.

**Budget:** `contextLimit − 16k (response reserve) − 8k (chapter buffer)`; token estimation = `chars÷4`; files injected in sorted-name order until budget exhausted; `Long.MAX_VALUE` when no limit is set (cloud models).

`AiProviderFactory.libraryDir()` / `contextLimit()` are the pref accessors. `OpenAiCompatibleProvider.buildBody()` needed no changes — it already serializes `m.role` as-is, so `"system"` works. System message is prepended on every send including multi-turn replies. Deployed to Manta 2026-06-27; API-key behavioral verify pending (needs a chapter + library folder on-device with a configured key).

Key files: `app/.../reader/AskAiPanel.kt` (`buildLibrarySystemMessage()`), `app/.../reader/AiSettingsActivity.kt` (two new sections + `updateLibraryDisplay()`/`calcLibraryDisplay()`/`onActivityResult` for folder pick), `app/.../ai/AiProviderFactory.kt` (`libraryDir()`, `contextLimit()`).

---

## ✅ BUILT (2026-06-27/28) — Ask AI UX Polish (queued follow-ups #6)

Four items from the queued UX follow-up list (tracked since 2026-06-24), all deployed to Manta:

- **Test connection** — `AiSettingsActivity` already had `testConnection()` wired to a text link; it's now a proper pill/button below the Save row. Flushes the typed endpoint+model to prefs, sends a one-shot ping (`"Reply with exactly: connection ok"`), and shows the result in an inline `statusLabel` ("Connection OK." or the error message).
- **Settings shortcut in Ask AI pane** — "Settings" text button added to `AskAiPanel.buildHeader()` (between "New" and "Hide"); opens `AiSettingsActivity` directly without closing the panel, so endpoint/model/key changes don't require a round-trip through Help & About.
- **Reply from expanded viewer** — `AiReplyActivity` is no longer read-only. A reply input + "Reply" button row sits below the body. On tap: `setResult(RESULT_OK, Intent().putExtra(EXTRA_REPLY, text))` + `finish()`. `AskAiPanel.expandLatest()` now uses `@Suppress("DEPRECATION") activity.startActivityForResult(intent, AiReplyActivity.REQUEST_CODE)`. `ReaderActivity.onActivityResult` case `REQ_AI_EXPAND` routes the text to `aiPanel.onExpandResult(text)`, which calls `appendUser()` + `callProvider()` — the conversation continues without the user ever collapsing back to the panel.
- **Text-only endpoint error/fallback** — landed earlier: `OpenAiCompatibleProvider.mapHttpError()` returns `AiResult.NeedsTextOnlyRetry` when a request carrying images gets a 400/404/415/422; `AskAiPanel.callProvider()` automatically re-sends once without ink images (`omitInk=true`) + appends `INK_OMITTED_NOTE` to the seed so the model knows images were dropped. On-device verify pending (needs a text-only endpoint + a chapter with an ink note).

Key files: `app/.../reader/AskAiPanel.kt` (Settings button, `startActivityForResult`, `onExpandResult()`), `app/.../reader/AiReplyActivity.kt` (`EXTRA_REPLY`, `REQUEST_CODE`, reply bar), `app/.../reader/ReaderActivity.kt` (`REQ_AI_EXPAND`, `onActivityResult` case), `app/.../ai/OpenAiCompatibleProvider.kt` (`NeedsTextOnlyRetry`, `TEXT_ONLY_REJECT_CODES`).

---

## Key files

| File | Purpose |
|---|---|
| `docx/.../ManuscriptSerializer.kt` | seed prompt → `Prompt(text, inkAnnotationIds)` |
| `docx/.../RewriteProtocol.kt` | `===REWRITE===` markers + `parse()` |
| `docx/.../DocxFromText.kt` | rewrite text → clean draft `.docx` |
| `docx/.../DocxStore.kt` | `readAiChat`/`writeAiChat`, `readInkPng` |
| `app/.../ai/*` | `AiProvider`, `OpenAiCompatibleProvider` (**sole** client; `ClaudeProvider` deleted), `AiProviderFactory` (agnostic: baseUrl/model), `AiMessage`, `SecureKeyStore`, **`CleartextPolicy`** + `res/xml/network_security_config.xml` |
| `app/.../reader/AskAiPanel.kt` | chat panel — seed, ink load, render, save |
| `app/.../reader/AiReplyActivity.kt` | full-screen reply viewer (`EdgeNavView` rail) |
| `app/.../reader/AiSettingsActivity.kt` | **agnostic endpoint form** — base URL + model + optional key, Paste on every field, partial-save (gated) |
| `app/.../reader/HelpActivity.kt` | "Ask AI" gate + "Directing the AI" pages |
| `app/.../reader/EdgeNavView.kt` | shared edge-nav (now has a `side` option: both/left/right) |

Logcat (AI): `adb -s <serial> logcat -s AI,DocxWriteQueue` (the `AI` tag logs `request sent; response <code>`).
Note: `am start` can't deep-link `HelpActivity` (blocked) — navigate on-device to check Help.

---

## ✅ BUILT (2026-06-24) — the ③ "brain" Phase 1 reference proxy (`brain/`)

**Built and locally verified end-to-end (against a mock upstream — no Gemini key needed for that leg).**
Lives in **`brain/`** (new top-level dir): `leamh_brain.py` (~250 lines, **Python stdlib only — zero pip
deps**, chosen for auditability/privacy), `brain.env.example`, `.gitignore` (ignores `brain.env` +
`references/`), `README.md`. Reference library is **external + never committed**: `~/layuv-brain/references/`
(default; override `BRAIN_REFERENCES_DIR`) — seeded with a sample `story-bible.md` carrying canary facts.

**What it does:** serves `POST /v1/chat/completions` (and bare `/chat/completions`); reads ALL `.md`/`.txt`
reference files **fresh per request** (evolving library, no restart) and injects them **whole** as ONE
`system` message (NO RAG); swaps in the Mac-held upstream key + model; **streams the upstream SSE back
byte-for-byte** in the exact shape Layuv parses (`choices[0].delta.content` / `finish_reason` / `[DONE]`).
**The Mac owns the model** (`BRAIN_UPSTREAM_MODEL`) — the device's model field is ignored (decouples the
director). Keyless-LAN by default; optional `BRAIN_TOKEN` (device sends it as its key) for shared/remote
nets. Privacy-safe logging (counts only — never the manuscript or key). Banner prints the exact Layuv
settings + LAN IP on startup.

**Verified locally this session:** `/health` OK; whole 1,179-char bible injected; **canary facts
(`Call`/`siol`/`Mara Theune`) confirmed present in the forwarded system message**; SSE relayed in Layuv's
parse shape; model override (`brain`→`test-model`); keyless path (no `Authorization` when key blank); clean
process teardown. Run: `cd brain && python3 leamh_brain.py` (after `cp brain.env.example brain.env` + paste
the Gemini key into `BRAIN_UPSTREAM_KEY`).

**Honesty caveat (stated in README):** with a CLOUD upstream (Gemini/Claude) the references + chapter DO go
to that provider — inherent to any cloud model. The brain protects the **key** (stays on the Mac) and the
**architecture** (swap upstream to a local model later = one `brain.env` line, zero device rebuild).

### Remaining — on-device Gemini verify (needs the key ON THE MAC)
The only unproven leg is the real Gemini call: the Gemini key currently lives only in Layuv's encrypted
store on the device, NOT on the Mac. Steps: (1) paste the AIza key into `brain/brain.env`
`BRAIN_UPSTREAM_KEY`; (2) `python3 leamh_brain.py`; (3) in Layuv set Endpoint = `http://<mac-ip>:8077/v1`,
Model = anything, Key = blank → **Test connection**; (4) send a chapter → the reply must reflect a
bible-only fact (proves injection) → Save & Open draft still works → device prefs hold only the Mac URL
(key never left the Mac). Quick keyless smoke without the device: the `curl` block in `brain/README.md`.

---

## Superseded — original NEXT TASK spec for the brain (kept for reference)

**The Android side is DONE.** Layuv is a provider-agnostic OpenAI-compatible client, verified end-to-end
on-device with Gemini this session (annotate → Send → it asked a clarifying question → full rewrite →
Save & Open as a clean versioned draft). **No Android changes are needed for what's next.**

The next direction is the **③ brain** (plan file → "NEXT ARCHITECTURE", Phase 1): a small server on the
user's Mac that gives Layuv **continuity** — it injects the project's reference library into every
request so the model writes with memory instead of cold-starting each time. (Recall the ①/②/③ framing
from this session: ① device→cloud direct, ② device→a model the Mac runs itself, **③ device→Mac proxy that
holds the key + library and forwards to a real model** — ③ is the goal.)

**Phase 1 spec — reference proxy (net-new SERVER code; zero Android changes):**
- A small (~150-line) server on the user's **MacBook Pro M3 Pro** (later a Mac Mini) exposing an
  **OpenAI-compatible `/chat/completions` SSE** endpoint.
- **Holds the API key** (start with the user's **Gemini free** key; Claude later for quality) — the key
  **NEVER** touches the device.
- Reads **ALL** project reference files from a folder and **injects them whole** into each request
  (**NO RAG** — whole-context injection; per the plan a personal-novel bible fits the window).
- Forwards to the upstream model and **streams the reply back unchanged** (SSE passthrough).
- **LAN-first:** bind to the Mac's LAN interface, **plain HTTP** — Layuv's `CleartextPolicy` already
  allows private/LAN hosts and refuses public cleartext (proven this session). Tailscale for remote = a
  later phase.
- **Layuv just re-points** its endpoint pref at `http://<mac-ip>:<port>/v1` — no app code, no rebuild.
- **Verify (the whole point):** from Layuv, send a chapter → the reply must reflect a fact that exists
  **only** in the reference files (proves injection) → the rewrite card + Save & Open still work → the
  key never leaves the Mac (device prefs hold only the Mac URL).
- **Confirm scope first:** where the server lives (new `brain/` dir in the repo?), Python vs Node (user
  has python3/pipx/mlx), reference-folder convention, Gemini-first upstream.

---

## ✅ REFINED (2026-06-26, superseded 2026-07-19) — AI submenu UX

All changes on branch `native-port-drawpath-ink`. Build clean; installed on Nomad.

> **Superseded:** the "Export for AI — version continuity" and "Import rewrite" behavior described
> below (versioned `_draft_vN.docx` copies, keep-3 archive, `AI-export-folder`/import-folder
> auto-detect across candidate paths) was replaced 2026-07-19 by the working-copy model — see the
> "REFACTORED" entry near the top of this file. The AI submenu / popup UX and `FileBrowserActivity`
> folder-creation notes below are still accurate to current behavior (menu rows now read "Export
> annotations…" and "Import rewrite…" over the new, simpler backing logic).

### What landed

**Export annotations — now non-versioned:**
- `exportAnnotationsOnly()` writes `<cleanBase>_annotations.md` (annotations + anchors only, no
  chapter text) flat into the annotated copy's own folder, plus one PNG per ink annotation. Overwrites
  each pass — it's a reference artifact, not a draft, so there is no version counter and no archive
  folder to manage.
- The app always works on a single `<base>_annotated.docx` copy (see `resolveAnnotatedCopy()`); there
  is no longer a new versioned copy created per export.

**Import rewrite:**
- Simplified to a plain file browser (`FileBrowserActivity`), optionally pre-navigated to a configured
  import folder. No more auto-detection across multiple candidate paths (import folder + AI export
  folder) — the user just picks the file. Picking a file overwrites the working copy in place
  (tmp + atomic rename) and reloads it.
- "Set import folder…" still remembers the chosen folder and shows a `parent/folder` subtitle in the
  AI menu.

**AI submenu (pill button → submenu):**
- Removed AI Chat bubble from the toolbar pill; replaced with `aiMenuButton` (`AiChatButton`) whose tap opens `showAiMenu()` instead of toggling the chat panel.
- Main overflow `...` menu is clean again — no AI items.
- `showAiMenu()` popup: AI Chat (tappable only when `isAiConfigured()`; otherwise dimmed with "Configure in Help & About" subtitle), Export annotations…, Import rewrite…, Set import folder…
- "Set import folder…" shows its configured `parent/folder` as a subtitle.
- Popup uses `R.drawable.popup_bg` (same border/background as main overflow), `elevation = ReaderTheme.dp(ctx, 6f)`.

**FileBrowserActivity — folder creation:**
- Removed the "create subfolder per export" checkbox entirely (was confusing in both export and import pickers).
- Added `+ Folder` button in the bottom bar (all modes): opens a custom `PopupWindow` (no system `AlertDialog` — no drop shadow, `elevation = 0f`, `GradientDrawable` black border, ReaderTheme fonts). Typing a name and tapping "Create" creates the folder and navigates into it.

**Toasts — removed:**
- All AI-workflow toasts removed: export/import progress, folder-set confirmations, "Exporting…", "Imported — reloading.", etc.
- Pre-existing read-only/error/guard toasts (`File is read-only`, `Open a document first`, etc.) are untouched.

### Key files touched this session

| File | What changed |
|---|---|
| `app/.../reader/ReaderActivity.kt` | `aiMenuButton` (pill), `showAiMenu()`, `overflowActionRowWithSubtitle()`, export/import toast removal, `REQ_SET_IMPORT_FOLDER`, import redirect logic, subfolder always-on |
| `app/.../reader/FileBrowserActivity.kt` | Removed subfolder checkbox + `buildSubfolderRow()` + `EXTRA_HAS_INK`/`EXTRA_CREATE_SUBFOLDER`; added `showNewFolderDialog()` (custom PopupWindow), `+ Folder` button |
| `app/.../ai/AiExporter.kt` | Single `version: Int` param (was split export/nextDraft) — **file since removed**, see the 2026-07-19 REFACTORED entry |

### Open follow-ups (→ tracker)
1. ~~Import rewrite with no folder configured opens browser at storage root~~ — moot now that import is a plain file browser; confirm behavior is still reasonable.
2. `ai_create_subfolder` SharedPreferences orphan — harmless but should be cleared in a future "Reset" action.
3. `aiMenuButton` always visible (previously gated) — confirm this is intended.

---

## Handoff prompt for a new conversation (current as of 2026-07-19)

> I'm working on the Léamh/Layuv project (`/Users/jayromacorda/Develop/layuv`), branch
> `native-port-drawpath-ink`. Read `CLAUDE.md` and this `HANDOFF_next.md` in full first, plus the memory
> index — especially `native_android_port.md`, `project_ai_workflow_and_export.md`, `project_brain_proxy.md`,
> `project_ai_networking.md`, and `ios_ipad_port.md`.
>
> **State: Android is FEATURE-COMPLETE, AI export workflow refactored to a working-copy model
> (2026-07-19).** Reader + annotations + ink + a full **AI layer**: provider-agnostic **Ask AI** (one
> OpenAI-compatible client → Gemini / Claude / OpenAI / local / Mac brain; verified on-device),
> **export annotations** (`exportAnnotationsOnly()` writes `<base>_annotations.md` + ink PNGs, flat
> into the annotated copy's own folder — not versioned, since the app always works on a single
> `<base>_annotated.docx` working copy via `resolveAnnotatedCopy()`), **import rewrite** (now a plain
> file browser — no more auto-detect/versioning), **AI submenu** (pill button opens popup with AI Chat
> / Export annotations… / Import rewrite… / Set import folder…; folder path shown as subtitle),
> **on-device reference library** (system-message injection of user's `.md`/`.txt` folder with optional
> context-limit cap), and **Ask AI UX polish** (Test connection button, Settings shortcut in panel,
> reply from expanded viewer, text-only-endpoint auto-fallback). Code-enforced cleartext guard + GMS-free
> `SecureKeyStore`. Both engines' `DocxArchive` do a raw-copy ZIP rewrite for the annotated copy. Mac-side
> `brain/` Phase-1 proxy built + locally verified (on-device Gemini run pending). Engine `:docx:test`
> 77/77 green; Swift `LeamhDocx` 43/43 green; `:app:assembleDebug` clean.
>
> **Primary AI workflow = manual Claude Code on my novel-project folder** (free via Max sub; reads
> CLAUDE.md + references natively). Layuv's role: annotate the working copy + export the annotations
> (the clean `.md` + ink PNGs that folder consumes) — the manuscript `.docx` itself is read directly by
> the drafting process, not re-exported. Brain is the optional automated path on an API key.
>
> **Current direction = the iPad Swift port** (M1–M4d all DONE; AI layer complete as of commit `7c9fa9f`;
> milestones in memory `ios_ipad_port.md`). Android is in maintenance — the open follow-ups are in
> `leamh_tracker.md` under "Android AI menu — next-up polish".
>
> **Open Android follow-ups (see tracker for detail):**
> (1) Confirm the plain-file-browser import flow (no configured folder) still lands somewhere
> reasonable now that auto-detect is gone.
> (2) `ai_create_subfolder` pref orphan in SharedPreferences — include in any future "Reset AI settings" action.
> (3) `aiMenuButton` always visible in pill (previously gated) — confirm intended.
> (4) **fully gate "Tap outside: off"** — stray non-tap still reaches `cancelSelection()` after ~3 touches; audit every call site in `ReaderView.kt`.
> (5) On-device verifies still pending: Export-for-AI tap-through; brain's real Gemini call (`brain/brain.env` needs the AIza key on the Mac); reference library behavioral verify (needs a chapter + library folder + configured key); text-only endpoint fallback (needs a text-only endpoint + ink annotation).
>
> Build: `cd android_native && ./gradlew :app:assembleDebug` (run `:docx:test` after any `docx/` change).
> Devices: **Nomad** `SN078C10005528` (USB) / **Manta** `SN100C10008955` (WiFi, DHCP — most recently
> `192.168.12.183:5555`); adb at `$HOME/Library/Android/sdk/platform-tools/adb`;
> app id `com.afluffywaffle.layuv.dev`. **Heed `native_android_sequencedcollection_trap.md`**
> (no `List.removeLast()`/`removeFirst()`/`getFirst()` etc. — JDK21 binds to API-35
> `SequencedCollection` → runtime crash on the Supernote). Commit/push only when asked.
