# CLAUDE.md — Léamh project instructions

Read this file at the start of every session. It contains architecture
decisions, design constraints, and coding standards that must be followed
without being re-explained each time.

---

## What Léamh is

A manuscript annotation tool with two active native codebases:

| Codebase | Platform | Location |
|---|---|---|
| Native Kotlin Android app | Supernote Nomad/Manta (e-ink) | `android_native/` |
| Native Swift macOS app | macOS (App Store target) | `macos_native/` |

- Format: DOCX annotations (`word/comments.xml`) — round-trips with Word, Pages, Google Docs
- Bundle ID: `com.afluffywaffle.layuv`
- Repo: github.com/afluffywaffle/layuv
- GPL v3 licensed

**Flutter app is archived** to `archive/flutter/`. Do not restore or add Flutter code.

---

## macos_native/ — Swift macOS app

Swift/SwiftUI app targeting macOS (App Store). Read the memory file for full
architecture details before touching this tree.

### Structure

- `macos_native/Packages/LeamhDocx/` — pure Swift DOCX engine (no AppKit/UIKit)
  - Mirrors `android_native/docx/` exactly: same golden fixtures, same JSON format
  - Depends on ZIPFoundation (vendored at `macos_native/Packages/ZIPFoundation/`)
- `macos_native/LeamhApp/` — SwiftUI macOS app (Xcode 27 format, objectVersion=90)

### Building and testing

```bash
# Test the Swift engine
cd macos_native/Packages/LeamhDocx
unset GIT_CONFIG_COUNT GIT_CONFIG_KEY_0 GIT_CONFIG_VALUE_0
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/swift test

# Build + install (compile-error check only, no install/launch)
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild \
  -project macos_native/LeamhApp/LeamhApp.xcodeproj \
  -scheme LeamhApp -configuration Debug -sdk macosx build

# To actually RUN the app, use the install script instead of launching from
# DerivedData directly — it always replaces /Applications/Layuv.app so there is
# never more than one bundle registered under com.afluffywaffle.layuv (running
# both a DerivedData copy and an /Applications copy at once causes duplicate
# Dock icons and duplicate file-open instances, since Launch Services can't
# disambiguate two bundles sharing one bundle ID).
macos_native/build_and_install.sh Release   # or Debug
```

### Critical Xcode 27 project format note

- `PBXFileSystemSynchronizedRootGroup` replaces PBXGroup/PBXFileReference — `files`
  arrays in build phases are **empty** (sources auto-discovered from disk)
- ZIPFoundation must be a SEPARATE framework target — `import ZIPFoundation` in
  DocxArchive.swift needs a real module, can't be inlined with LeamhDocx sources
- `GENERATE_INFOPLIST_FILE = YES` — no manual Info.plist
- `xcode-select` points to CommandLineTools; always use `DEVELOPER_DIR=` override

### Engine parity with android_native

`macos_native/Packages/LeamhDocx/` mirrors `android_native/docx/`. When fixing
bugs in one engine, apply the same fix to the other. The two engines share
identical test fixture files (same golden JSON/XML/DOCX) but maintain separate
expected-output goldens — updating one does NOT update the other.

### Key files — macOS app

Always read these before modifying.

| File | Purpose |
|---|---|
| `macos_native/LeamhApp/LeamhApp/ReaderView.swift` | NSTextView reader — AnnotatingTextView subclass, tool popover, per-tool highlight rendering, ReaderCoordinator |
| `macos_native/LeamhApp/LeamhApp/DocumentStore.swift` | @MainActor state store — file open/save, annotation CRUD, security-scoped bookmarks, editingAnnotation |
| `macos_native/LeamhApp/LeamhApp/HomeView.swift` | Root layout — NavigationSplitView recents sidebar, ReaderScreen with toolbar |
| `macos_native/LeamhApp/LeamhApp/AnnotationsPanel.swift` | Annotations list — search field, tag filter chips, tap-to-edit |
| `macos_native/LeamhApp/LeamhApp/AnnotationEditSheet.swift` | Edit sheet — note TextEditor, tag toggles, tool picker |
| `macos_native/LeamhApp/LeamhApp/ToolPickerView.swift` | Floating tool picker popover (6 tools) |
| `macos_native/LeamhApp/LeamhApp/AppTheme.swift` | Typography + colour constants (warm paper, Literata, Source Sans 3) |
| `macos_native/Packages/LeamhDocx/Sources/LeamhDocx/DocxStore.swift` | Swift DOCX engine read/write entry point |

### Feature status (commit 42920fe, branch native-port-drawpath-ink)

**Implemented:**
- File open (NSOpenPanel) + recents sidebar with security-scoped bookmarks
- Text rendering — TextKit 2 NSTextView, bold/italic format spans, warm-paper background
- Text selection → tool picker popover → annotation creation (6 tools)
- Per-tool annotation rendering: yellow fill (highlight), solid underline, double underline, strikethrough, thick-dash teal (wavy), orange tint (bookmark), thick dotted green + green tint (comment)
- Tap annotated text in reader → opens edit sheet
- Annotations panel with search + multi-select tag filter chips
- Annotation edit sheet — note, tag, tool; auto-opens for new comment annotations
- Save (atomic write via replaceItemAt, reads base bytes fresh from disk)
- App Sandbox entitlements + security-scoped bookmarks for recents

**iOS-parity pass (2026-06-28):**
- AI layer — AI Settings form, Ask AI chat panel (streaming, rewrite card → Save as Draft via
  NSSavePanel), AI menu (Chat / Settings / Export for AI / Import rewrite / set folders). Shared
  `AskAiViewModel` (Shared/) drives both iOS and macOS chat.
- Reader nav modes — `scroll` (single-column, unchanged) + `screenFlip` (horizontal paged, NO curl
  animation; two-column applies only in this paged mode). Edge-margin clicks + toolbar arrows flip
  pages; toolbar page indicator. macOS has NO page-turn/curl mode by design.
- Ink — `InkEditorView` (macOS) with a custom NSView stroke canvas (mouse/trackpad/tablet),
  pen/eraser/width/clear. Saves a `macink` strokes JSON (re-editable on Mac) + transparent PNG
  (cross-platform display). Same degradation model as iPad/Android (PNG always shows; strokes
  re-editable only on the writing platform). Ink tool added to ToolPickerView (now 7 tools).
- Reader theming — `Shared/PaperTheme.swift`, the **écri** palette (parchment/bone/dusk/sage/night),
  drives reader bg + body text + underline/highlight colours + ink canvas. `DocumentStore.paperTheme`
  persisted; picker in Format menu + Typography toolbar menu. `night` = explicit dark paper (chrome
  light-lock untouched). Built on BOTH Swift apps. Android: do NOT port colours (greyscale e-ink) —
  only `écri-font` matters there. See leamh_tracker.md "Reader theming" + "écri front-matter interop".

**Pending (priority order):**
1. App icon (placeholder only)
2. Device/usability pass — screen-flip pagination + ink not yet runtime-tested on-device

### Coding standards — Swift / macos_native

- `DocumentStore` is `@MainActor` — all mutations on main thread; I/O in `Task.detached`
- All DOCX writes go through `DocumentStore.save()` — reads base bytes fresh from disk,
  writes to temp file, then `FileManager.replaceItemAt` atomic rename. Never bypass.
- `editingAnnotation` on `DocumentStore` is the single trigger for the edit sheet —
  set it from anywhere (VC tap, panel row, comment creation) rather than passing bindings.
- No AppKit/UIKit imports in `macos_native/Packages/LeamhDocx/` — engine must stay pure Swift.
- After every engine change: run `swift test` from `macos_native/Packages/LeamhDocx/`.
- After every app-layer change: run `xcodebuild` and confirm BUILD SUCCEEDED.
- No e-ink constraints on macOS — use standard AppKit affordances (fills, solid colours, animations OK).

---

## android_native/ — the active codebase

Read `android_native/README.md` before touching this tree.

### Module structure

- `app/` — Android application (Activities, Views, EPD wrappers)
- `docx/` — Pure-JVM DOCX engine: parse, anchor, read, full write-back.
  **Zero `android.*` imports** — JUnit-testable on desktop JVM.

### Invariants — never break these

1. **ONE canonical plain-text string P** — used for BOTH rendering and
   anchoring. Handles tabs/tables/numeric entities. Never reintroduce a
   second string or the legacy Flutter `<w:t...>` regex (it over-matches
   `<w:tab/>`/`<w:tbl>`/`<w:tr>`/`<w:tc>`).

2. **DocxWriteQueue is the only write path** — ALL DOCX writes must:
   (a) read base bytes FROM DISK (not stale in-memory copy),
   (b) serialize on a single executor (no overlapping writes),
   (c) persist atomically: per-write temp + fsync + atomic-rename.
   Any bypass is a critical corruption risk.

3. **Compatibility is rule #1** — written DOCX must round-trip forward AND
   backward with Word, Pages, Google Docs. The "byte-identical to Dart
   store" golden constraint is dissolved (Flutter is archived); fix native,
   regenerate goldens from correct native output.

4. **Engine purity** — `docx/` module has ZERO `android.*` imports.
   Never add android.* to docx/.

5. **Reader is `LAYER_TYPE_SOFTWARE` View only** — NOT Jetpack Compose
   (same compositor trap as Flutter). StaticLayout over string P, drawn by
   a software-layer View that owns `onDraw` plus every EPD waveform call.

### Building and testing

```bash
# Run the docx engine test suite (run after every engine change)
cd android_native && ./gradlew :docx:test

# Build the app APK
cd android_native && ./gradlew :app:assembleDebug
```

Golden tests: `android_native/docx/src/test/`. Regenerate via tools in
`android_native/tools/golden_gen/` when engine behavior changes.
Never hand-edit goldens.

### Dependencies

- Onyx: `com.onyx.android.sdk:onyxsdk-device:1.2.28` from
  `https://repo.boox.com/repository/maven-public/`
- Fonts: Literata + Source Sans 3 in `android_native/.../assets/fonts/`
- JDK: pinned to JDK 21
- **No Google Play Services — ever.** No MLKit, Firebase, or any GMS dep.

---

## Architecture — DOCX as native format

Léamh uses DOCX as its working format. Annotations are stored as native
DOCX comments in `word/comments.xml` inside the file.

- Every file opened is a DOCX. Non-DOCX files are converted first.
- The original source file is NEVER modified.
- Annotations are read/written by `DocxStore` in
  `android_native/docx/src/main/kotlin/.../docx/DocxStore.kt`.
- Storage: Android has no sandbox — file system access works directly.

---

## E-ink rules (Supernote Nomad/Manta)

These rules apply to all UI added to `android_native/app/`:

- No animations of any kind
- Show/hide with plain conditionals only
- All UI must be greyscale-safe — no colour-only affordances
- Tap targets: 48dp minimum, 64dp preferred
- Highlights / comments / ink: light grey fill behind black text (`HIGHLIGHT_FILL`,
  ~12% black) — greyscale-safe, applied as a non-metric span (ink additionally
  shows a margin icon). Underline / double underline / strikethrough are solid
  lines; bookmark is a margin icon. A static fill is fine: annotation changes
  already trigger a full EPD clear, so the fill does not force continuous refresh.
- EPD waveform calls must go through the established EPD/EinkClient path;
  call `postRectForPw` for regional partial refresh after ink/annotation
  changes

---

## Design constraints

| Property | Value |
|---|---|
| Background | `#F5F0E8` warm paper |
| Highlight / comment / ink | Light grey fill behind black text (`HIGHLIGHT_FILL`, ~12% black) — greyscale safe |
| Body font | Literata (bundled) |
| UI chrome font | Source Sans 3 (bundled) |
| Position references | Always 0.0–1.0 fraction — never pixel offsets |

Never hardcode pixel positions for annotation anchoring.
Never use colour as the only visual affordance.

---

## Typography rules — native Android

These apply to every new View. Thin/light text is illegible on e-ink.

| Usage | Typeface | Weight |
|---|---|---|
| Reader body text | `ReaderTheme.body()` (Literata) | Regular |
| Italic / quote text | `ReaderTheme.bodyItalic()` | Italic |
| Titles, emphasis | `ReaderTheme.bodyBold()` | Bold |
| **Buttons, action labels, section headers** | **`ReaderTheme.chromeBold()`** | **Bold Source Sans 3** |
| Truly secondary labels (page numbers, counts) | `ReaderTheme.chrome()` | Regular Source Sans 3 |

**Never use `ReaderTheme.chrome()` for tappable elements or primary labels** —
regular Source Sans 3 is too light on e-ink. Default to `chromeBold()` for all
interactive chrome; drop to `chrome()` only for genuinely decorative/dim text.

Preview text (e.g. page-jump overlay) must use **the same size as the reader
body text** (`ReaderTheme.BODY_TEXT_SP`). Pass it as a parameter so it
automatically tracks when a user font-size preference is added.

---

## Key files — handle with care

Always read these in full before modifying. Never restructure without
explicit instruction.

| File | Purpose |
|---|---|
| `android_native/app/.../reader/ReaderActivity.kt` | Main reader — page nav, EPD, annotation flow |
| `android_native/app/.../reader/ReaderView.kt` | Software-layer View — onDraw, StaticLayout |
| `android_native/app/.../reader/HighlightPainter.kt` | Annotation line decorations (underline/double/strikethrough) + dotted selection underline |
| `android_native/app/.../reader/AnnotationsPanelActivity.kt` | Annotations list panel Activity |
| `android_native/app/.../reader/NoteActivity.kt` | Single annotation edit Activity |
| `android_native/app/.../reader/InkNoteActivity.kt` | Ink annotation Activity |
| `android_native/app/.../reader/ReaderTheme.kt` | Typography + colour constants |
| `android_native/docx/src/main/kotlin/.../docx/DocxStore.kt` | DOCX read/write entry point |
| `android_native/docx/src/main/kotlin/.../docx/CommentWriter.kt` | Writes comments.xml + rels |
| `android_native/docx/src/main/kotlin/.../docx/RunPropertyInjector.kt` | Injects bookmarks for anchoring |
| `android_native/docx/src/main/kotlin/.../docx/XmlEntities.kt` | XML entity decode |
| `android_native/docx/src/main/kotlin/.../docx/ContentTypes.kt` | [Content_Types].xml management |
| `android_native/docx/src/main/kotlin/.../docx/InkDrawing.kt` | DrawingML for ink annotations |
| `android_native/docx/src/main/kotlin/.../docx/model/Annotation.kt` | Annotation data model + fromMap |

---

## Coding standards — Kotlin / android_native

- All DOCX writes go through `DocxWriteQueue` — never call write methods
  directly outside the queue chain.
- All public store methods catch exceptions, log with `Log.e`, and return
  empty/null gracefully. Never throw to caller.
- `position` on `Annotation` is always a 0.0–1.0 fraction.
- IDs are generated with `newId()` (UUID-based).
- No `android.*` imports in `docx/` module.
- `fromMap()` / JSON deserialization: use safe casts (`as? String ?: ""`),
  per-record try/catch — one bad record must never drop the entire list.
- Activity lifecycle: shut down `ioExecutor` in `onDestroy`; save position
  in both `onPause` AND `onSaveInstanceState`.
- `setResult()` must be called BEFORE `finish()`.
- Never use static `@Volatile` for cross-Activity data handoff — use
  Intent extras or a proper shared store instead.
- Every Activity MUST call `ReaderTheme.seedBodyFont(this)` in `onCreate`
  **before building its UI** (`setContentView`/`buildUi`). `ReaderTheme.bodyFont`
  is a process-wide static; on whichever Activity Android recreates first after
  process death it would otherwise stay at the `"literata"` default and render the
  wrong typeface. Literata / Source Sans 3 must apply on every text surface, not
  just the reader.

---

## Workflow — how tasks are assigned

Tasks come from a human reviewer in a separate claude.ai conversation.
Prompts are precise and complete. Follow them exactly.

After every task touching `android_native/docx/`:
1. Run `cd android_native && ./gradlew :docx:test`
2. If clean: report the test output summary
3. If failures: fix them, re-run, then report

After app-layer changes: build with `./gradlew :app:assembleDebug` and
confirm the build succeeds.

Do not refactor, rename, or restructure code that is not part of the
assigned task. Do not add features that were not requested.

## Working with Claude — efficiency

- **Build/test is the checker.** Tight loop = edit → `xcodebuild -scheme "LeamhApp" -destination 'platform=macOS' build` (macOS app) or `./gradlew :app:assembleDebug` (Android app) → read errors → fix. This compile-fix loop is mechanical and can run on a **Sonnet subagent**; reserve the main (Opus) session for design/architecture decisions.
- **Target, don't sweep.** Grep/Glob to the relevant file or symbol; don't read the whole project tree. Point Claude at the file when you already know where the work is.
- **Route by task type:** boilerplate / mechanical refactor → **Haiku** subagent; compile-fix loops / tests / straightforward features → **Sonnet** subagent; architecture / concurrency / hard debugging → **Opus** (main session).

## Local-LLM hardware-sizing log

At the end of every real-work session, append one JSON line to the GLOBAL log
`~/.claude/llm_local_feasibility_log.jsonl` (schema + full instructions in that
file's first line and in `~/.claude/CLAUDE.md`). It's a cross-project dataset for
a hardware-purchase decision; set `project` to `layuv`.
