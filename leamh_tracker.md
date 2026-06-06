# Léamh — Project Tracker

**Repo:** `github.com/afluffywaffle/layuv`  
**Bundle ID:** `com.afluffywaffle.layuv`  
**Licence:** GPL v3 · **Distribution:** F-Droid (Android), direct (macOS), App Store (iOS)

---

## In Progress 🔧

*Nothing active — see Up Next.*

---

## Up Next (in order)

### Ink capture in annotation panel
- Storage: strokes → PNG → `word/media/ink_[id].png` inside DOCX ZIP, referenced as `<w:drawing><wp:inline>` in Word comment — visible in Word/Docs, no HTR dependency
- `Annotation` model needs `hasInk: bool` flag
- Canvas sizing: large canvas for comfort; display size set via EMUs in `<a:ext cx cy/>` (914400 per inch); full res preserved
- [ ] iPad: PencilKit canvas → `UIImage` → PNG → archive entry
- [ ] Supernote: Flutter `Canvas` strokes → `Picture` → `toImage()` PNG → same path
- [ ] macOS: keyboard-only (gate behind `supportsInk`)

### HTR (Handwriting Recognition)
- [ ] iPad: PencilKit / Scribble offline
- [ ] Supernote: ONNX Runtime offline (no Play Services)

### Markup mode
- [ ] Gesture classifier
- [ ] Strokes → annotation types
- [ ] Margin note → HTR → comment pipeline

### Export for AI
- [ ] Clipboard format as a structured Claude prompt
- Needs design discussion before implementation

### App icon
- [x] Icon designed in Affinity and generated via `flutter_launcher_icons` ✅

---

## Completed ✅

- Basic reader — scroll, screen-flip, page-flip modes
- Annotation model — highlight, underline, bookmark, note; position as `%` fraction
- `AnnotationStore` (JSON sidecar, `.leamh.md` format)
- `DocxStore` — reads DOCX body text; stores position in `leamh/position.json` inside ZIP
- `AnnotationStoreInterface` — unified interface for `AnnotationStore` and `DocxStore`
- Annotations panel — tabbed UI (Annotations | Bookmarks)
- Annotations panel — swipe-to-delete (`Dismissible`, confirm dialog)
- Annotations panel — edit mode: checkbox multi-select, "Delete Selected" + "Delete All" bottom bar, confirm dialogs
- Annotations panel — jump-to emphasis: `▶` margin indicator at full opacity for 3s after jump-to, e-ink safe, no animation
- Annotations panel — 💬 icon on tiles toggles full note inline; filled/outline icon reflects state
- Bookmarks tab — renders correctly, tap-to-jump works
- Resume reading position — fraction + offset/page saved on scroll/page change (debounced 1s); restored on open across all three reader modes
- 2-column page flip mode — toggle in ••• menu (pageFlip + non-e-ink only), on by default, persisted via `shared_preferences`
- PageFlip scroll bug — `SelectableText` internal scroll disabled; all three `itemBuilder` return paths wrapped in `SizedBox.expand()`; `_paginate` uses `safeHeight = maxHeight - (lineHeight * 2)`
- PageFlip column width resize race — `_paginatedColWidth` snapshotted inside `_paginate` and used in `itemBuilder`
- PageFlip page counter — correctly shows spreads vs pages using `_actuallyTwoCol`
- PageFlip narrow screen page count — `_actuallyTwoCol` promoted to state variable; consistent across `itemCount`, `_onJumpRequested`, `_onPageChanged`
- Security-scoped bookmarks — macOS: `BookmarkChannel.swift` with `saveBookmark`/`resolveBookmark`/`stopAccessing`; iOS: same channel, `stopAccessing` no-op; `BookmarkService` in Dart with `shared_preferences` persistence
- Word boundary snapping — `snapToWordBoundaries` + `_isWordBoundary` in `annotation_utils.dart`; applied via `onSelectionChanged` debounce in all three readers
- Toolbar auto-popup — per-reader `_selectionDebounce` (350ms); `_anchorForSelection` via `GlobalKey` + `RenderEditable` traversal; `_cancelSelectionNotifier` wired in all three readers (`initState`/`dispose`)
- Toolbar dismiss — `GestureDetector(opaque)` background; `_suppressToolbarUntil` 600ms post-dismiss guard
- DOCX architecture — `leamh/annotations.json` as primary store; `leamh/document_clean.xml` as original snapshot; every write restores from clean then injects formatting fresh; no leftover markup accumulation
- DOCX round-trip Word compatibility — three-fix pass: (1) defer anchor/bookmark positions to a final single pass after all rPr insertions so positions never go stale; (2) strip existing comment range markers before injection to prevent duplication from Word-native originals in clean snapshot; (3) skip rPr injection when property already present in run's existing rPr block (prevents doubling native Word formatting imported as Léamh annotations)
- DOCX native OOXML formatting — write side: annotations saved as run properties (`<w:u>`, `<w:highlight>`, `<w:strike>`, `<w:u w:val="double"/>`, `<w:u w:val="wave"/>`) with comment range anchors for note/tag annotations; all tools mapped to OOXML equivalents
- DOCX format span extractor — `extractFormatSpans` reads from `leamh/document_clean.xml` when available so reader doesn't pick up Léamh-injected marks as native bold/italic
- DOCX native comment import — `_parseComments` detects legacy Léamh `[tool:X]` format vs native Word comments; imported as `tool=comment` annotations with recovered `selectedText`/`position`
- DOCX native formatting import — `_importNativeFormatting` scans `document.xml` for existing `<w:highlight>`, `<w:u>`, `<w:strike>` and imports as Léamh annotations; adjacent same-tool runs merged; called only when `leamh/annotations.json` absent
- `_buildPlainMap` — `_unesc()` on `<w:t>` content; `</w:p>` emits `\n` matching `docxToText` output
- `_locateInPlain` — prefix/suffix context match; position-hint disambiguation; quote-normalised fallback
- Annotations panel delete-all — `onChanged` callback; `reader_screen` passes `_reloadAnnotations`; bookmarks tab gets `_deleteAllBookmarks`; tab controller promoted to state
- Jump-to accuracy — `fractionForText` rewritten to call `docxToText(bytes)` (same extraction as reader)
- Debug print cleanup — `[TOOLBAR]`, `[FRAC]`, `[SAVE]`, `[PANEL]`, `[DOCX]` prefixes removed; `_inspectDocx` removed
- Annotation panel comment display — text wraps, tile expands, 12pt Source Sans 3
- Word / Google Docs compatibility — confirmed working in Word and Google Docs (Jun 2026); Supernote legibility check deferred to device testing phase

---

## Device Testing

### Running on macOS
```bash
flutter run -d macos
```

### Running on iPad / iPhone
```bash
flutter devices
flutter run -d <id>
```

### Running on Supernote Nomad/Manta (sideload)

**Step 1** — Enable sideloading: Settings → Privacy/Security → "Allow Installation from Unknown Sources"

**Step 2** — Build debug APK:
```bash
cd ~/Develop/layuv
flutter build apk --debug
# Output: build/app/outputs/flutter-apk/app-debug.apk
```

**Step 3** — Transfer via USB (Finder → Supernote → Document folder) or email/cloud

**Step 4** — Tap APK in Supernote file browser → Install

**Step 5** — Logs via WiFi ADB (if firmware supports it):
```bash
adb connect 192.168.x.x:5555
adb logcat | grep flutter
```

**Supernote constraints:** `viewPadding` all zeros; greyscale only; no Play Services; 2-col gated to `!isEink`

---

## Design Constraints (always apply)

- **E-ink safe:** no colour, no animation on Android/Supernote
- **Offline-first:** no network calls
- **No Google Play Services** on Supernote → no MLKit, no Firebase
- **Fonts:** Literata (body) + Source Sans 3 (UI), bundled locally, OFL licences in `fonts/licenses/`
- **Background:** warm paper `#F5F0E8`
- **Annotation highlight:** 15% black opacity (greyscale safe)
- **Position references:** always percentage (0.0–1.0)
- **macOS:** keyboard-only ink for now; `supportsInk` gate is ready

---

## Product Philosophy

Léamh is a reading and annotation accessory, not a word processor. Annotations must be 1-to-1 compatible with Word, Pages, and Google Docs — indistinguishable from native formatting. `w:author="Léamh"` on tracked changes is the only fingerprint.
