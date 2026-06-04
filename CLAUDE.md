# CLAUDE.md — Léamh project instructions

Read this file at the start of every session. It contains architecture
decisions, design constraints, and coding standards that must be followed
without being re-explained each time.

---

## What Léamh is

A cross-platform manuscript annotation tool built in Flutter. Targets:
- macOS (primary desktop)
- iOS / iPad Pro (PencilKit ink, Scribble HTR)
- Android / Supernote Nomad + Manta (e-ink, Android 11, no Google Play
  Services)

F-Droid distribution target. GPL v3 licensed.
Bundle ID: com.afluffywaffle.layuv
Repo: github.com/afluffywaffle/layuv

---

## Architecture — DOCX as native format

Léamh uses DOCX as its working format. Annotations are stored as native
DOCX comments in `word/comments.xml` inside the file. Key rules:

- Every file opened in Léamh is a DOCX. Non-DOCX files (txt, md, rtf)
  are converted to a DOCX copy via a save dialog before opening.
- The original source file is NEVER modified.
- Annotations are read/written by `DocxStore` in `lib/models/docx_store.dart`.
- `AnnotationStore` (`lib/models/annotation_store.dart`) is the legacy
  JSON-based store. It is kept in place but no longer used by the app.
  Do not delete it without explicit instruction.
- Both stores implement `AnnotationStoreInterface`
  (`lib/models/annotation_store_interface.dart`).
- All widgets that take a store parameter must type it as
  `AnnotationStoreInterface`, never as `AnnotationStore` or `DocxStore`
  directly.

---

## Storage — sandbox rules

- macOS is sandboxed. Writing files next to the source is NOT permitted
  without security-scoped bookmarks.
- `DocxStore._writeArchive` writes directly to `filePath`. The sandbox
  grants write access to the file the user opened, so a direct write is
  permitted and correct — do not change it back to a temp-file-then-copy
  pattern (copying targets the directory, which the sandbox rejects).
- The save dialog (`FilePicker.platform.saveFile`) grants sandbox
  permission for the chosen path including iCloud Drive.
- Security-scoped bookmarks are implemented via a Swift method channel
  (`com.afluffywaffle.layuv/bookmarks`, `macos/Runner/BookmarkChannel.swift`).
  `main.dart` saves a bookmark when a DOCX is opened; `DocxStore`
  resolves it before each read/write so iCloud/arbitrary paths persist
  across launches.
- Android/Supernote: no sandbox, file system access works directly.

---

## E-ink gate

`isEink` is a top-level getter in `lib/utils/platform_utils.dart`.

Rules when `isEink` is true:
- No animations of any kind
- No `Dismissible` widgets (swipe is unreliable on e-ink)
- No `AnimatedSwitcher`, no `AnimatedContainer`
- Show/hide with plain conditionals only
- Annotations panel opens as full-screen push route, not a sheet
- All UI must be greyscale-safe — no colour-only affordances
- Tap targets must be large enough for pen/finger

Always check `isEink` before adding any animation or swipe interaction.

---

## Platform gates

```dart
// In platform_utils.dart
bool get isEink  // Supernote Nomad/Manta — Android e-ink, no Play Services
bool get supportsInk  // iPad (PencilKit) — macOS keyboard-only for now
```

- `supportsInk` gate is ready but macOS ink is not yet implemented.
  Do not add ink features to macOS without explicit instruction.
- Supernote has NO Google Play Services — never add MLKit, Firebase,
  or any Play Services dependency.

---

## Design constraints

| Property | Value |
|---|---|
| Background | `#F5F0E8` warm paper — use `const Color(0xFFF5F0E8)` |
| Highlight opacity | `Colors.black.withValues(alpha: 0.15)` — greyscale safe |
| Annotation highlight | `Colors.black.withValues(alpha: 0.15)` |
| Body font | Literata (bundled in `fonts/`) |
| UI chrome font | Source Sans 3 (bundled in `fonts/`) |
| Position references | Always 0.0–1.0 fraction — never pixel offsets |
| Dialog background | Always `const Color(0xFFF5F0E8)` |
| Dialog font | Always `fontFamily: 'Literata'` |

Never hardcode pixel positions for annotation anchoring.
Never use colour as the only visual affordance.

---

## Key files — handle with care

These files are central to the app. Always read them in full before
modifying. Never restructure them without explicit instruction.

| File | Purpose |
|---|---|
| `lib/reader_screen.dart` | Main reader — all three modes, annotation flow |
| `lib/models/docx_store.dart` | DOCX annotation backend |
| `lib/models/annotation_store_interface.dart` | Shared store interface |
| `lib/reader/annotations_panel.dart` | Annotations list panel |
| `lib/reader/annotation_panel.dart` | Single annotation edit sheet |
| `lib/utils/platform_utils.dart` | `isEink`, `supportsInk` gates |
| `lib/main.dart` | File picker, DOCX conversion flow |
| `macos/Runner/DebugProfile.entitlements` | macOS sandbox entitlements |
| `macos/Runner/Release.entitlements` | macOS sandbox entitlements |

---

## Coding standards

- All store operations go through `_serialized()` mutex — never call
  `_write` or `_writeArchive` outside the lock chain.
- All public store methods catch errors, `debugPrint`, and return
  empty/null gracefully. Never throw to caller.
- Use `debugPrint` not `print` everywhere.
- All `async` methods that touch widgets must check `if (!mounted) return`
  after every `await`.
- `position` on `Annotation` is always a 0.0–1.0 fraction.
- `fraction` on `ReadingPosition` is persisted in `toJson`/`fromJson`.
- IDs are generated with `newId()` from `lib/models/annotation.dart`.
- Sealed classes use exhaustive `switch` pattern matching, not `is` checks.

---

## Reading modes

Three modes, all in `lib/reader/`:
- `ScrollReader` — continuous scroll
- `ScreenFlipReader` — page-at-a-time, screen flip navigation
- `PageFlipReader` — animated page flip

All three:
- Accept `savedPosition` and restore on construction
- Call `onPositionChanged` as user reads
- Accept `jumpNotifier` (`ValueNotifier<double?>`) for jump-to-position
- Accept `annotations` list for inline highlight rendering

---

## Workflow — how tasks are assigned

Tasks come from a human reviewer in a separate claude.ai conversation.
Prompts are precise and complete. Follow them exactly.

After every task:
1. Run `flutter analyze`
2. If clean: report the analyze output only, do not paste full files
   unless there are errors or the reviewer explicitly asks
3. If errors: fix them, re-analyze, then report

Do not refactor, rename, or restructure code that is not part of the
assigned task. Do not add features that were not requested.
