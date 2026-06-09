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
    reader/ReaderActivity.kt   ✅ SAF open, position save/restore, column toggle
    reader/ReaderView.kt       ✅ software-layer View, per-column draw, tap nav
    reader/Paginator.kt        ✅ whole-book StaticLayout sliced into column pages
    reader/HighlightPainter.kt ✅ dotted-underline spans (DashPathEffect + drawPath)
    reader/Epd.kt              ✅ Onyx EpdController waveforms (GU/GC/DU policy)
    reader/BookLoader.kt       ✅ bytes → DocxStore.load (off-main, spike logging)
    reader/SelectionController ⬜ long-press + handles → snap → DocxStore.write
  ink/    — reuse InkCanvasView/InkActivity (transparent-bg fix)          ⬜
  saf/    — txt/md/rtf→DOCX convert (open existing DOCX already works)    ⬜
  tools/golden_gen/gen_goldens.dart — generates the golden fixtures
```

`:app` (the Android reader, depends on `:docx` + `onyxsdk-device:1.2.28`) is
added once the engine lands. Engine-first: the entire compatibility contract is
proven on the JVM before any device work.

## Running

Regenerate the golden fixtures from the Dart reference (run from the repo root):

```bash
dart run android_native/tools/golden_gen/gen_goldens.dart         # plain-text (P + offsets)
dart run android_native/tools/golden_gen/gen_engine_goldens.dart  # anchoring + model + sample.docx
dart run android_native/tools/golden_gen/gen_import_goldens.dart   # native-format + comment import
# write-back goldens come from the REAL Dart store, so they need flutter:
flutter test android_native/tools/golden_gen/writeback_golden_test.dart
```

Run the JVM engine tests:

```bash
cd android_native && ./gradlew :docx:test
```

The goldens are generated by a Dart reference (`buildCleanMap`) and asserted
against the Kotlin `PlainTextMapper`, so the test is a cross-language check:
identical plain text + xmlOffsets, including surrogate-pair (emoji) and entity
handling, and `clean == legacy` on every prose fixture.

## Next phase — the `:app` reader module

The `docx/` engine is done. The `:app` reader exists and builds a sideloadable
APK (`com.afluffywaffle.layuv.dev`, installs alongside the Flutter APK for
comparison). Status of the priority order:

1. ✅ **`:app` scaffold** — Android application module depending on `:docx`; Onyx
   `onyxsdk-device:1.2.28`; `minSdk 30`; `assets/fonts/` bundled; `ReaderActivity`
   launcher.
2. ✅ **Open + render** — SAF `ACTION_OPEN_DOCUMENT` → `DocxStore.load` → one
   `StaticLayout` over `plainMap.plain` (`BREAK_STRATEGY_SIMPLE`,
   `HYPHENATION_FREQUENCY_NONE`, Literata) → line-slice into column pages → draw on
   the `LAYER_TYPE_SOFTWARE` `ReaderView` (`translate` + clip + `layout.draw`).
3. ✅ **Navigation + position** — left/right tap-zones (no swipe), `EpdController`
   page-turn waveform (GU, GC every 6th turn), restore/save via `ReadingPosition`
   ↔ char offset ↔ page and `DocxStore.writePosition` (on `onPause`).
4. ✅ **Two-column** — newspaper flow at half `colWidth`, default-on by
   `smallestScreenWidthDp >= 600` (tunable) with a persisted toggle.
5. 🟡 **Highlights render** (display half done) — resolved spans drawn as dotted
   underlines. ⬜ **Selection → create** (long-press + handles →
   `Anchoring.snapToWordBoundaries` → `selectedText`/`prefix(20)`/`suffix(20)`/
   `position` → `DocxStore.write`) is the next thing to build — best done with
   live device feedback for the drag-handle UX and partial-refresh tuning.
6. ⬜ **Ink (LAST — needs a stylus)** — reuse `InkCanvasView`/`InkActivity` with the
   transparent-background fix; PNG → `word/media/ink_<id>.png` + `hasInk`.

**Build environment:** AGP 8.13.2 on the Gradle 9.1 wrapper. The machine's
default JDK is 26, which AGP rejects — `gradle.properties` pins
`org.gradle.java.home` to Android Studio's bundled JDK 21. `:docx` also needed
`testRuntimeOnly(junit-platform-launcher)` (Gradle 9 no longer auto-adds it) and
a Java-target alignment to 17 to match Kotlin.

**Device spikes to settle early (no stylus needed):** which `EpdController`
waveforms exist on Nomad/Manta firmware and page-turn ghosting cadence; whole-book
`StaticLayout` build time + memory on both devices; dotted-underline crispness on
EPD; selection-drag partial refresh.

### Running on the Supernote (sideload)

```bash
cd android_native && ./gradlew :app:assembleDebug
# adb install -r app/build/outputs/apk/debug/app-debug.apk      (USB)
# or copy the APK to the device and tap it in the file browser
```
Enable Settings → Security → "Install from unknown sources" first. Logs over
WiFi ADB: `adb connect <device-ip>:5555 && adb logcat`.
