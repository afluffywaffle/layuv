# Léamh

A manuscript annotation tool for Supernote Nomad/Manta e-ink devices.

Annotations are stored as native DOCX comments (`word/comments.xml`),
round-tripping with Word, Pages, and Google Docs.

## Project structure

```
android_native/   — active product (Kotlin, Android 11, no GMS)
  app/            — reader Activities, Views, EPD/ink wrappers
  docx/           — pure-JVM DOCX engine (parse, anchor, read, write-back)
  tools/          — golden-test generators

archive/flutter/  — archived Flutter cross-platform app (reference only)
leamh_tracker.md  — task tracker
```

## Build

```bash
cd android_native

# Run engine tests
./gradlew :docx:test

# Build debug APK
./gradlew :app:assembleDebug
```

Requires JDK 21. No Google Play Services.

## License

GPL v3 — see `LICENSE`.
