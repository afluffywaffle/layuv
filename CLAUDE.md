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
DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer \
  /Applications/Xcode-beta.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/swift test

# Build the app
DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer xcodebuild \
  -project macos_native/LeamhApp/LeamhApp.xcodeproj \
  -scheme LeamhApp -configuration Debug -sdk macosx build
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
- No swipe gestures (unreliable on e-ink)
- Show/hide with plain conditionals only
- All UI must be greyscale-safe — no colour-only affordances
- Tap targets: 48dp minimum, 64dp preferred
- Highlights: dotted underline only — no fill (fill forces full refresh)
- EPD waveform calls must go through the established EPD/EinkClient path;
  call `postRectForPw` for regional partial refresh after ink/annotation
  changes

---

## Design constraints

| Property | Value |
|---|---|
| Background | `#F5F0E8` warm paper |
| Highlight | Dotted underline, black at ~15% opacity — greyscale safe |
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
| `android_native/app/.../reader/HighlightPainter.kt` | Dotted-underline annotation rendering |
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
