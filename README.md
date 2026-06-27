# Léamh

**Léamh** (LAY-uv) — a focused reading and annotation tool for long-form
manuscripts, built natively for Supernote e-ink devices, iOS, and macOS.

Annotations are stored as native DOCX comments (`word/comments.xml`), so a
marked-up document round-trips cleanly with Word, Pages, and Google Docs — your
notes travel with the file, in an open format, with no lock-in.

## Why

Reading and annotating a book-length manuscript should feel like reading, not
like using a word processor. Léamh gives each platform a purpose-built reader:

- **Supernote Nomad/Manta** — a software-rendered e-ink reader with low-latency
  stylus ink, regional EPD refresh, and a greyscale-safe, animation-free UI.
- **iOS / iPadOS** — a size-class-adaptive, reader-first SwiftUI app.
- **macOS** — a TextKit 2 reader with selection-driven annotation tools,
  targeting the App Store.

Highlights, underlines, strikethroughs, bookmarks, comments, and freehand ink
all map to standard DOCX constructs.

## Project structure

```
android_native/   — Supernote e-ink app (Kotlin, Android 11, no Google Play Services)
  app/            — reader Activities, software-layer Views, EPD/ink wrappers
  docx/           — pure-JVM DOCX engine (parse, anchor, read, full write-back)
  tools/          — golden-test generators

macos_native/     — Swift app for macOS + iOS/iPadOS (SwiftUI)
  LeamhApp/       — shared / macOS / iOS UI layers
  Packages/       — LeamhDocx, a pure-Swift port of the DOCX engine

brain/            — optional self-hosted AI proxy (Python stdlib, no RAG)

archive/flutter/  — archived Flutter prototype (reference only)
```

The DOCX engine exists twice — pure-JVM (`android_native/docx/`) and pure-Swift
(`macos_native/Packages/LeamhDocx/`) — kept in lockstep against shared golden
fixtures so every platform reads and writes byte-compatible files.

## AI (optional)

Léamh can package a chapter plus its annotations as Markdown for an AI workflow
("Export for AI"), or query a provider directly ("Ask AI"). It speaks the
OpenAI-compatible API and works with hosted providers or a local model. The
`brain/` proxy lets you keep your upstream key on your own machine and inject a
reference library without sending it to a third party.

## Build

**Supernote / Android** (requires JDK 21, no Google Play Services):

```bash
cd android_native
./gradlew :docx:test         # engine tests
./gradlew :app:assembleDebug # debug APK
```

**macOS / iOS** (requires Xcode):

```bash
cd macos_native/Packages/LeamhDocx
swift test                   # engine tests

xcodebuild -project macos_native/LeamhApp/LeamhApp.xcodeproj \
  -scheme LeamhApp -configuration Debug -sdk macosx build
```

## License

GPL v3 — see [`LICENSE`](LICENSE).
