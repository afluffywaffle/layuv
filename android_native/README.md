# Léamh — native Android port (Supernote e-ink)

A native Kotlin Android app for **Supernote Nomad / Manta** (e-ink, Android 11,
no Google Play Services). It exists because Flutter's compositor fights the EPD
partial-refresh path on these devices. The Flutter app (this repo's root) stays
for macOS/iOS; this port is Supernote-only and reads/writes the **same DOCX
files** so annotations round-trip across platforms — and with Word, Pages, and
Google Docs.

Bundle ID `com.afluffywaffle.layuv` · GPL v3.

## The one idea everything hangs on

There is exactly **one canonical plain-text string `P`**, used for *both*
rendering and annotation anchoring. Annotations are anchored by offsets into `P`
(`position = start / P.length`, plus 20-char `prefix`/`suffix`). The reader
hands that same string to `StaticLayout`, so a layout char offset **is** an
index into `P` by construction — selection→annotation and annotation→highlight
round-trip for free.

### `P` is a CLEAN extraction (not a copy of the Flutter regex)

The Flutter app builds `P` with `docx_store._buildPlainMap`, whose regex
`<w:t(?:[^>]*)>` mis-matches `<w:tab/>`, `<w:tbl>`, `<w:tr>`, `<w:tc>` and leaks
raw XML into the string (and it skips numeric entities). This port uses a
**clean, element-name-aware extraction** instead:

```
<w:t>…</w:t>      decoded text (named + numeric entities); one xmlOffset/code unit
<w:tab/>          '\t'   offset = tag start
<w:br/> <w:cr/>   '\n'   offset = tag start
</w:p>            '\n'   offset = tag start   (trailing newline kept)
everything else   ignored
```

Crucially, the clean extraction is **byte-identical to `_buildPlainMap`
(plain + xmlOffsets) on ordinary prose** — it only diverges where the legacy
regex is buggy, producing the correct result there. That is what keeps
round-trip exact on the common case while fixing tabs/tables/entities. The
golden tests enforce both halves of that claim.

> Round-trip does **not** require reproducing the legacy bug: `annotations.json`
> stores self-contained `selectedText`/`prefix`/`suffix`, and every reader
> (Flutter included) re-locates them by a robust context match. The **written**
> DOCX still mirrors `docx_store`'s validated-compatible OOXML (comment XML,
> run-property mapping, content-types, rels, ink `<w:drawing>`) so Word / Pages
> / Google Docs round-trip forward **and** backward.

## Module layout

```
android_native/
  docx/   — PURE Kotlin/JVM, zero android.* imports → JUnit-testable on desktop
    PlainTextMapper.kt   ✅ clean P + xmlOffsets
    XmlEntities.kt       ✅ named + numeric entity decode
    Anchoring.kt         ✅ _locateInPlain 3-tier + findClosest + word-boundary snapping
    model/…              ✅ Annotation / ReadingPosition / enums (JSON parity, camelCase names)
    DocxArchive.kt       ✅ ZipInputStream read (name→bytes, ordered)
    Json.kt              ✅ org.json bridge (compileOnly; Android platform at runtime)
    NativeImport.kt      ✅ import existing <w:highlight>/<w:u>/<w:strike> (merge adjacent)
    LegacyComments.kt    ✅ legacy Léamh + native Word comments.xml → annotations
    DocxStore.kt         ✅ READ path: annotations.json (PNG→hasInk) OR import fallback,
                            position, re-anchor every annotation
                         ✅ WRITE path: clean-snapshot, inject, comments, rels, content-types
    DocxArchive write    ✅ ZipOutputStream full-rewrite
    RunPropertyInjector  ✅ rPr injection + run-splitting + comment/bookmark anchors
    CommentWriter        ✅ comments.xml, rels, content-types ensure
    InkDrawing.kt        ✅ <w:drawing> EMU markup + rel-id
    ContentTypes / JsonWriter  ✅
  app/    — Android reader module (depends on :docx + onyxsdk-device)
    reader/ReaderActivity.kt         ✅ SAF open, position save/restore, column toggle, settings, annotation flow
    reader/ReaderView.kt             ✅ software-layer View, per-column draw, edge-strip nav, selection + handles
    reader/Paginator.kt              ✅ whole-book StaticLayout sliced into column pages
    reader/HighlightPainter.kt       ✅ dotted highlight, solid underline/double/strike, margin icons
    reader/AnnotationPopup.kt        ✅ floating tool picker, locked-tool mode, undo pill
    reader/AnnotationsPanelActivity.kt ✅ filter chips, paginated list, edit mode, ink thumbnails, tap-to-edit
    reader/NoteActivity.kt           ✅ annotation editor — tool, quote, ink button, note, tags
    reader/InkNoteActivity.kt        ✅ drawPath ink overlay, 3 tools (THIN/THICK/ERASER), PNG+JSON save
    reader/InkCanvasView.kt          ✅ canvas for ink input, real-time per-MOVE invalidate
    reader/SearchActivity.kt         ✅ full-text search with page-jump
    reader/FileBrowserActivity.kt    ✅ split-view browser (recents + folder tree), breadcrumb popup
    reader/PageJumpOverlay.kt        ✅ scrub track with preview text + bookmark markers
    reader/DrawPathClient.kt         ✅ binder wrapper for drawPath service (penType, disable areas, clearScreen)
    reader/SettingsPopup.kt          ✅ columns, page-turn side, font size, line spacing, font family
    reader/Epd.kt                    ⚠ wraps Onyx EpdController — still present, should be replaced by RattaEink
    reader/RattaEink.kt              ✅ EinkManager reflection wrapper (sendOneFullFrame, screenRefresh)
    reader/BookLoader.kt             ✅ bytes → DocxStore.load (off-main)
    reader/ReaderTheme.kt            ✅ typography + colour constants
  tools/golden_gen/                  — Dart reference generators (archived; use Kotlin generator instead)
```

## Running

Regenerate golden fixtures (Kotlin — no Dart/Flutter toolchain needed):

```bash
cd android_native && ./gradlew :docx:generateGoldens
```

The Dart tools in `android_native/tools/golden_gen/` are archived reference only.
The Flutter toolchain is no longer required for any part of the build or test pipeline.

Run the JVM engine tests (run after every `docx/` change):

```bash
cd android_native && ./gradlew :docx:test
```

Build the app APK:

```bash
cd android_native && ./gradlew :app:assembleDebug
# Install: adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The goldens are generated by the Kotlin engine itself and asserted in JUnit, giving
a self-contained cross-check: plain text + xmlOffsets including surrogate-pair (emoji)
and entity handling, `clean == legacy` on all prose fixtures.

## App status — shipped features

The `:app` module builds a sideloadable APK (`com.afluffywaffle.layuv.dev`). All
core reader and annotation features are complete as of 2026-06-21:

- **Open + render:** SAF `ACTION_OPEN_DOCUMENT` → `DocxStore.load` → one `StaticLayout`
  over `plainMap.plain` (Literata, `BREAK_STRATEGY_SIMPLE`) → line-slice into column pages
  → drawn on `LAYER_TYPE_SOFTWARE` `ReaderView`.
- **Navigation + position:** 80dp edge-strip nav (left/right/both/none, configurable),
  page-turn waveform via EPD, position persisted to DOCX `position.json` + SharedPrefs
  fallback on `onPause` + `onSaveInstanceState`.
- **Two-column:** newspaper flow at half `colWidth`, default-on for wide screens, persisted toggle.
- **Body formatting:** direct `<w:b>`/`<w:i>` runs → `FormatSpan`s → `Spannable` (Literata-Italic
  + synthesized bold). Style-based formatting (rStyle/headings) not yet resolved.
- **Text selection → annotation:** long-press/drag (finger) or direct drag (stylus) + word-snap
  via `Anchoring.snapToWordBoundaries`; draggable handles; `AnnotationPopup` (8 tools);
  partial `invalidate(Rect)` for fast regional e-ink refresh during drag. Verified end-to-end.
- **Annotation rendering:** `HighlightPainter` — dotted underline (highlight), solid
  underline/double/strikethrough; margin `ToolIconRenderer` icons per annotation.
- **AnnotationPopup:** floating tool picker, locked-tool mode (`LockSlotView`), undo pill.
- **AnnotationsPanelActivity:** filter chips, sectioned + paginated list, edit mode
  (checkboxes, delete, select-all), ink PNG thumbnails, tap-to-edit (ink rows return
  to ink editor; text rows return to `NoteActivity`).
- **NoteActivity:** full annotation editor — tool selector, quote box, ink button, note
  field, tag chips, save/update.
- **InkNoteActivity + InkCanvasView:** drawPath hardware overlay, 3 tools (THIN/THICK/ERASER
  where ERASER = lasso-circle-erase), rule lines, real-time per-MOVE invalidate,
  PNG + stroke JSON save, clears drawPath on exit.
- **DrawPathClient:** binder wrapper for `com.ratta.drawpath` service — penType 4 (lasso)
  for selection feedback, disable chrome band (flag=0), clearScreen (code 6).
- **Circle gesture:** draw a circle over text → scrub accumulator captures min/max char
  range → annotation popup appears. No special gesture classifier needed.
- **SearchActivity:** full-text search with page-jump.
- **FileBrowserActivity:** split-view (recents top 1/3, folder browser bottom 2/3),
  folder icon, breadcrumb multi-column popup.
- **PageJumpOverlay:** scrub track with preview text + bookmark markers.
- **LeamhDialog:** confirm dialog with "don't ask again" support.
- **Overflow menu:** font size, line spacing, columns, nav side, RTL, font family, flatten ink.
- **App icon:** mipmap PNGs from `layuv.icon` SVG.
- **RattaEink:** `EinkManager` reflection wrapper (`sendOneFullFrame`, `screenRefresh`).

## Remaining work

- **De-Onyx `Epd.kt` (TODO):** `reader/Epd.kt` wraps the Boox `EpdController` — wrong SDK
  for Ratta Supernote, likely a no-op. Ink is already done via `InkNoteActivity` +
  `DrawPathClient`. The remaining de-Onyx work: (a) replace `Epd.kt` with `RattaEink`
  (`android.os.EinkManager` — `sendOneFullFrame` / `screenRefresh` / `setScreenMode`) in
  `ReaderView` (`RattaEink.kt` exists, just not wired); (b) remove `onyxsdk-device:1.2.28`
  and `repo.boox.com` maven entry from `build.gradle.kts` / `settings.gradle.kts`.
- **Pen drag handle → lasso dashes:** `DrawPathClient` penType 4 fires during any stylus drag,
  including handle drags. Switch pen mode during handle drag, restore on drag end.
- **Text selection colour in panel:** system selection is near-black on e-ink; override to
  a legible dotted underline or low-opacity tint.
- **Ink visibility in Pages/Google Docs:** ink PNG lives in `word/comments.xml` — Pages/GDocs
  don't render images in comment bodies. Three options in the tracker (floating anchor,
  inline block, appendix section) — choose and implement one.
- **Style-based formatting:** headings/rStyle → bold/italic not yet resolved.
- **RTL nav direction setting:** reverse nav strips for RTL texts (tracker item).

**Status (2026-06-21): this native port IS the product — the active, primary codebase.**
The Flutter app is archived to `archive/flutter/` as reference only. A future macOS/iOS
app is a Swift port (this Kotlin engine as reference), not a Flutter revival.

**Build environment:** AGP 8.13.2 on the Gradle 9.1 wrapper. The machine's default JDK is
26, which AGP rejects — `gradle.properties` pins `org.gradle.java.home` to Android Studio's
bundled JDK 21. `:docx` also needed `testRuntimeOnly(junit-platform-launcher)` (Gradle 9
no longer auto-adds it) and a Java-target alignment to 17 to match Kotlin.

### Running on the Supernote (sideload)

```bash
cd android_native && ./gradlew :app:assembleDebug
# adb install -r app/build/outputs/apk/debug/app-debug.apk      (USB)
# or copy the APK to the device and tap it in the file browser
```
Enable Settings → Security → "Install from unknown sources" first. Logs over
WiFi ADB: `adb connect <device-ip>:5555 && adb logcat`.
