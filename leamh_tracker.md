# Léamh — Project Tracker

**Repo:** `github.com/afluffywaffle/layuv`  
**Bundle ID:** `com.afluffywaffle.layuv`  
**Licence:** GPL v3 · **Distribution:** Direct (macOS), App Store (iOS)

---

## Platform Strategy

| Platform | Delivery | Status |
|---|---|---|
| macOS | Standalone app | Active — Flutter now, Swift/SwiftUI rewrite planned |
| iPad / iPhone | Standalone app | Active — Flutter now, Swift/SwiftUI rewrite planned |
| Supernote Nomad/Manta | Native Kotlin APK (sideload) | Active — native rewrite in `android_native/`; Flutter APK retired for Supernote |

**Native Android port (`android_native/`):** A ground-up native Kotlin app for Supernote Nomad/Manta replaces the Flutter APK there — Flutter's compositor fought e-ink partial refresh. Same DOCX files round-trip with the Flutter app (macOS/iOS) and Word/Pages/GDocs. The pure-JVM `docx/` engine (parse, anchor, read incl. native-format + comment import fallback, full write-back) is **complete and golden-tested byte-for-byte against the real Dart `DocxStore`** (`cd android_native && ./gradlew :docx:test`). Reader uses one clean canonical plain-text string for both render and anchor, drawn by `StaticLayout` over a `LAYER_TYPE_SOFTWARE` `View` owning the Onyx `EpdController` waveforms (not Compose). See `android_native/README.md` and the CLAUDE.md native-port section.

**Sync layer:** DOCX file on iCloud Drive (or any file sync). No proprietary sync needed — annotations are native Word comments and run formatting, readable on any platform. Open the same file in Léamh on macOS, Léamh on iPad, or Léamh on Supernote and all annotations are present.

**Swift/SwiftUI rewrite (macOS + iOS):** Flutter was chosen for cross-platform coverage including Supernote. Flutter stays for Supernote; rewrite targets Apple platforms only. SwiftUI covers those better (PencilKit, TextKit 2, native sandboxing, iCloud). Dart code serves as line-by-line reference for the Swift port.

**Supernote delivery — Flutter APK (sideload):** The DOC app plugin model was evaluated but does not support two-column reading or a controlled reading surface — the plugin only overlays Ratta's closed renderer. The standalone Flutter APK preserves Léamh's full reading experience (two-column, Literata, annotation panel, warm paper theme) and cross-platform annotation compatibility. Ink is the one open question — see ink section below.

**Supernote plugin SDK (for reference):** SDK (`sn-plugin-lib`) is live at `docs.supernote.com`, preview firmware available (Chauvet3.28.42 Beta for Nomad/Manta). Plugin architecture noted for future reference: React Native 0.79.2 in PluginHost, communicates with DOC via AIDL, `getSelectedText()` + `getCurrentFilePath()` are the primary DOC hooks, `PluginFileAPI` is `.note`-only. Not used by Léamh currently — plugin cannot provide a controlled reading surface or two-column layout, and cannot write to DOCX archives.

---

## In Progress 🔧

### Native Android port (`android_native/`) — reader UI
- [x] Pure-JVM `docx/` engine — plain-text mapper, anchoring, model, read path, native/comment import fallback, full write-back; all golden + JVM-verified ✅
- [ ] `:app` Android module (depends on `:docx`; Onyx SDK; minSdk for Nomad/Manta)
- [ ] DOCX reader — `StaticLayout` over the clean canonical string, line-sliced pagination, newspaper two-column (default by screen size + toggle), Literata body
- [ ] Software-layer `ReaderView` + `EpdController` page-turn waveforms (no animation)
- [ ] Large-tap-zone navigation (prev/next), reading-position persistence (`DocxStore.writePosition`)
- [ ] Text selection → annotation (word-snap, dotted-underline highlight render)
- [ ] **Device spikes (Supernote, no stylus needed):** EPD waveform availability + page-turn ghosting cadence; whole-book `StaticLayout` timing/memory on Nomad & Manta; dotted-underline on EPD; selection-drag partial refresh
- [ ] **Ink — investigation COMPLETE, implementation pending.** The correct Supernote ink path is the native **drawPath** low-latency service (NOT the Onyx `InkCanvasView`/`EpdController` POC — that's Boox and likely no-ops on Ratta). Confirmed working live on the Nomad. Full protocol, architecture, and exact next steps in **[Supernote drawPath low-latency ink](#supernote-drawpath-low-latency-ink--investigation-complete-implementation-pending)** below.

---

## Supernote drawPath low-latency ink — investigation COMPLETE, implementation pending

> **Status (2026-06-09):** Reverse-engineering + on-device proof DONE on a real Nomad. Implementation paused. Everything needed to resume is below. This supersedes the old "Flutter canvas vs native SurfaceView" spike plan for Supernote — the native port owns ink now, via drawPath.

### Where the test lives — IMPORTANT
The ink test is **NOT a separate app**. It is built **into the existing native port `:app`** (`applicationId = com.afluffywaffle.layuv.dev`) as additive, clearly-marked SPIKE files — it reuses that module's build/signing and installs as part of the same dev APK, but is **hidden** (no launcher icon) and launched directly. It does **not** touch any reader code.
- `android_native/app/src/main/kotlin/com/afluffywaffle/layuv/spike/DrawPathClient.kt` — the binder client (full API + transaction codes)
- `android_native/app/src/main/kotlin/com/afluffywaffle/layuv/spike/DrawPathSpikeActivity.kt` — interactive harness (Full init / Reset / Pen / Disable f0 / Disable f1 / App render / Clear code 6)
- `android_native/app/src/main/kotlin/com/afluffywaffle/layuv/spike/InkProbeView.kt` — captures stylus MotionEvents, optionally app-renders strokes
- `AndroidManifest.xml` — one `<activity>` entry, `exported=true`, not in launcher
- These are throwaway/spike. When building the real layer, promote the client and delete the harness activity + manifest entry.

Launch:
```bash
~/Library/Android/sdk/platform-tools/adb shell am start -n com.afluffywaffle.layuv.dev/com.afluffywaffle.layuv.spike.DrawPathSpikeActivity
```

### What is PROVEN on-device (Nomad)
- drawPath (`com.ratta.drawpath`, system service, always running) renders real stylus strokes at low latency for our app once configured. Our pen config is adopted (drawPath's `drawAPP` logcat echoes `penType 10, penWidth 200`).
- **Our app ALSO receives the full stylus `MotionEvent` stream** while drawPath renders (probe captured DOWN + ~4466 history points + UP, tool type `STYLUS`, with pressure). drawPath does **not** consume input — it only decides where to *paint*. ⇒ **we capture stroke geometry ourselves; we never need drawPath to hand strokes back.**
- `screencap`/SurfaceFlinger does **not** capture the ink (drawPath writes straight to the EPD controller). Verify visually on-device or via `adb logcat -s drawAPP`.
- Clearing = a **full-screen EPD refresh** (confirmed: the system swipe-down gesture cleared the bleed). drawPath retains its own stroke buffer and re-renders it (`recognition redraw`) until a full refresh flushes it.

### The protocol (reverse-engineered from `librecgnition.so`, verified live)
- **Service:** `service_myservice` via `ServiceManager.getService(...)` (reflection — hidden API).
- **Interface token:** `android.demo.IMyService`.
- **Native C++ libbinder, NOT Java AIDL** → do **NOT** call `Parcel.readException()` on the reply (it misreads the native reply's first int — a constant `19` — as a fake exception). Success = `transact()==true` with no `RemoteException`.
- **SELinux:** `service_myservice` is labeled `myservice_service`; the `shell` domain lacks `find`, so `adb shell service call ...` fails "does not exist". Only an **app** domain can reach it — must test from an app, never shell.
- Every call's parcel starts: `writeInterfaceToken(token)`, `writeString(appName = our package)`, then the payload.

**Full transaction-code map (from disassembling `BpMyService` with radare2):**

| Code | Method | Payload after token+appName | Use |
|---|---|---|---|
| 1 | `setWritableAndNonWritableArea(app, FlagRect[])` | `int count`, then per rect `{left, top, width, height, flag}` | **disable areas** (toolbar protection). **flag CONFIRMED on Nomad 2026-06-09:** `flag 0` = **disable/non-writable** (ink suppressed in the rect, rest of screen stays writable — blacklist) → **use this for chrome**. `flag 1` = **writable whitelist** (that rect becomes the *only* drawable area, everything else blocked) → do NOT use for chrome. Old `disablse size 0` was an ordering artifact — with `reset → pen → disable` the rect installs (`disablse size`/`intersectDisable` go to `1` for strokes crossing it; canvas strokes stay `0`). Coordinate note: drawPath maps the rect into an internal image space (`flagRect 960×2160 → image 1024×10496`), but empirically the header rect still catches header strokes and not canvas strokes, so the mapping is good enough. |
| 2 | `setPenInfo(app, type, width, color)` | `int type, int width, int color` | pen. type 10 = technical pen (no pressure); width = px×100 (2px→200); color black 0 / white 254 / lightgray −102 / darkgray −101. ✅ confirmed |
| 4 | `sayHello()` / `askTrailData(app, …)` | — | retrieve strokes from drawPath (not needed — we capture via MotionEvent) |
| 6 | `clearScreen(app)` | `int 255` (constant) | **programmatic full-screen clear** (== the swipe gesture). ✅ CONFIRMED on Nomad 2026-06-09: actually flushes drawPath's retained buffer — strokes vanish from the EPD. Replaces the no-op `invalidate()` clear. |
| 9 | `setWalcomEmrInfo(app, int)` | `int` | Wacom EMR digitizer config |
| 16 | `askDeletedlData(…)` | — | eraser / deleted strokes |
| 99999 | `setDebugMode(app, int)` | `int` | debug |

- **Post-resume order (per Ratta PDF):** first call must be the reset = `setWritableAndNonWritableArea` with one full-screen rect `(0,0,18888,18888, flag)` (acts as a reset sentinel — drawing still works after it), *then* pen, *then* the real disable areas.
- **Regional refresh:** drawPath does regional EPD updates **internally** via the hteink HAL (`win_update_2_area_display`, `display_rect_after_set(hteink_area_display)`, ioctl `CLEAR_PW_RECT` = clear partial-window rect) but **exposes only the FULL `clearScreen` over binder**. The Ratta **`android.os.EinkManager`** system service (see below) is now mapped but its public methods are also full-screen only — true regional refresh would need `EinkManager.sendHwcCmd(cmd, int[])` with an undocumented cmd id + region array (the `CLEAR_PW_RECT` path), still an RE task.

### Ratta e-ink control API — `android.os.EinkManager` (found 2026-06-09)
Discovered via **plateaukao/AssistiveTouch** (release `sn1.0`) + dumping `/system/framework/framework.jar`. Reached with `getSystemService("eink")` → `android.os.EinkManager` (reflection; **no** raw ServiceManager binder needed). This is the **correct refresh/waveform API for Ratta** — and it's what the native-port **reader** should use instead of the Onyx `EpdController` in `Epd.kt` (Boox; wrong for Ratta).

| Method | Signature | Use |
|---|---|---|
| `sendOneFullFrame()` | `()V` | full-screen refresh (what the swipe / AssistiveTouch long-press do) — clean programmatic full refresh |
| `screenRefresh(force, mode)` | `(ZI)V` | full refresh with flag + mode (no rect → not regional) |
| `setScreenMode(mode, …)` | `(IZ)V` | waveform mode: `EINK_SCREEN_MODE_CLEAR / DEFAULT / SMOOTH / SPEED` (**untested** — try for page turns vs Onyx GU/GC) |
| `sendHwcCmd(cmd, int[])` | `(I[I)V` | low-level command channel; only plausible **regional** route (RE the cmd ids) |
| `setScreenRotation`, `enter/exitSplitScreenMode`, gamma, `standby`/`quitStandby` | — | misc |

The AssistiveTouch repo itself is just a floating overlay button (Accessibility back/home) — **no drawPath knowledge**; its only value was the `EinkManager.sendOneFullFrame()` reflection snippet.

### Implementation architecture (the plan when we resume)
1. **Capture** stroke geometry in our canvas `View.onTouchEvent` (proven; pressure available if we ever want variable width).
2. **Live preview** = drawPath. On resume: reset → `setPenInfo` → `setWritableAndNonWritableArea` with **flag 0** rects over the toolbar/nav strips (flag 0 = disable/blacklist; flag 1 would whitelist-block the reading area).
3. **Commit** on pen-up → persist as ink. The DOCX engine already has `android_native/docx/.../InkDrawing.kt` and the Flutter side has the storage layer (`saveInkPng`, `word/media/ink_[id].png`, `<w:drawing><wp:inline>`), so ink round-trips with Word/Pages/GDocs.
4. **Own redraw** — app renders its committed strokes itself; on reopening a doc the app draws the persisted ink (drawPath not involved). Keep raster-only/transparent-PNG model per the Up-Next ink section.
5. **Clear/erase** = `clearScreen` (code 6) full refresh for now; regional later via hteink if needed.
6. Gate everything behind `isEink` / `supportsInk`.

### Open items / exact next steps
- [x] **CONFIRMED 2026-06-09** — disable **FlagRect flag**: `flag 0` = disable/non-writable (blacklist, rest writable) → use for chrome; `flag 1` = writable whitelist (blocks everything else). Harness `fullInit` updated to `flag 0`.
- [x] **CONFIRMED 2026-06-09** — `clearScreen` (code 6, `app + int 255`) flushes drawPath's buffer; strokes vanish from the EPD. Replaces the no-op `invalidate()` clear.
- [ ] **Hidden-API:** find a non-reflection route to the binder, OR accept the policy workaround. The real app **cannot** rely on `adb shell settings put global hidden_api_policy 0` (test-only). (It was set to `0` on the device for testing — restore with `settings put global hidden_api_policy null`.)
- [ ] **Reader-wide refresh concern (now has a fix):** the native port's `app/.../reader/Epd.kt` uses the **Onyx** `EpdController` (Boox) — likely a no-op on Ratta, so page-turn / selection refreshes in the READER may be broken on Supernote. **Fix = `android.os.EinkManager`** via `getSystemService("eink")` (`sendOneFullFrame()` / `screenRefresh()` / `setScreenMode(CLEAR/SMOOTH/SPEED)`) — see the EinkManager table above. Rework `Epd.kt` onto this. Affects more than ink.
- [ ] **Try `EinkManager.setScreenMode`** waveform modes (`CLEAR/DEFAULT/SMOOTH/SPEED`) for page turns — not yet tested.
- [ ] Build the real ink layer (capture → preview → commit → persist via `InkDrawing.kt` → redraw); promote `DrawPathClient`, delete the spike harness.
- [ ] Optional: regional refresh via `hteink`/`eink` for flash-free erase.

### Device / RE setup notes
- Nomad on WiFi ADB at `192.168.12.185:5555` (reconnect each session: `adb connect 192.168.12.185:5555`; it sleeps/drops off).
- `adb` is at `~/Library/Android/sdk/platform-tools/adb` (not on PATH).
- Native-port build uses the Android Studio JBR (pinned via `org.gradle.java.home` in `android_native/gradle.properties`); build with `./gradlew :app:assembleDebug`.
- RE: pulled `/system_ext/app/drawPath/drawPath.apk`; native lib `lib/arm64-v8a/librecgnition.so`; disassembled with `radare2` (brew); transient working dir `/tmp/drawpath_re` (not committed).
- Ratta's official doc: `~/Documents/Using Supernote Low-Latency Handwriting Service (drawPath).pdf` (4 pages; covers only codes 1 & 2 — the table above is the fuller picture).

---

## Up Next (in order)

### E-ink nav — RTL direction support
- [ ] Add a setting to reverse nav direction (right=prev, left=next) for RTL texts (Japanese light novels, etc.)
- Currently: left strip = previous, right strip = next (LTR convention)
- Setting would live in E-ink settings screen alongside the existing side preference

### Ink capture — iPad (PencilKit) + Supernote (Flutter canvas)

**Architecture — raster-only, transparent PNG:**
- No vector/stroke object model. Canvas draws directly to a pixel buffer; pen-up saves as transparent PNG. Create and edit are the same code path.
- PNG stored with full alpha channel — ink floats over any theme/background. No baked-in background colour.
- Edit flow: tap existing ink annotation → load PNG bytes from DOCX into canvas as background layer → user draws on top or erases (erase = paint alpha=0) → flatten → save back to same `word/media/ink_[id].png`. Delete-and-redo also supported.
- Storage layer complete: `hasInk: bool` on `Annotation`, `saveInkPng()` on `DocxStore`, PNG at `word/media/ink_[id].png`, `<w:drawing><wp:inline>` in comment body (4"×2" EMU), `word/_rels/comments.xml.rels` wired up
- UI placeholder shown on `supportsInk` platforms

**iPad:**
- [ ] `PKCanvasView` via method channel → `PKDrawing.imageFromCurrentPage()` → PNG bytes → `store.saveInkPng()`
- [ ] Edit: load existing PNG as `PKDrawing` background → save on dismiss

**Supernote — RESOLVED (native port, not Flutter):** The Flutter-canvas-vs-SurfaceView question is moot for Supernote — the native Kotlin port owns the reader there, and its ink path is the **drawPath** low-latency service, proven working on the Nomad. Spike done. Full protocol + implementation plan: **[Supernote drawPath low-latency ink](#supernote-drawpath-low-latency-ink--investigation-complete-implementation-pending)**. (This Flutter ink section still applies to iPad/PencilKit.)

### HTR (Handwriting Recognition)
- [ ] iPad: PencilKit / Scribble offline
- *Supernote: deferred — assess after ink spike*

### Markup mode
- [ ] Gesture classifier
- [ ] Strokes → annotation types
- [ ] Margin note → HTR → comment pipeline

### Export for AI
- [ ] Clipboard format as a structured Claude prompt
- Needs design discussion before implementation

### Swift/SwiftUI rewrite (macOS + iOS)
- Replace Flutter with native Swift — better PencilKit, TextKit 2, sandboxing, iCloud
- Dart `DocxStore` is the reference implementation for the Swift DOCX engine (`ZipFoundation` + `XMLDocument`)
- `BookmarkChannel.swift` expands into full app; method channel removed
- Three reader modes → `ScrollView`, `TabView(.page)`, custom page-flip with `UIPageViewController`
- [ ] Project scaffold — SwiftUI app target, shared Package for DOCX engine
- [ ] DOCX engine port (Swift) — `ZipFoundation`, `XMLDocument`, annotation round-trip
- [ ] Reader views (scroll, page)
- [ ] Annotation panel + PencilKit canvas (replaces Flutter placeholder)
- [ ] Annotations panel list view
- [ ] iCloud Drive / security-scoped bookmark handling (native, no method channel)

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
- Ink storage layer — `Annotation.hasInk`, `DocxStore.saveInkPng()`, PNG at `word/media/ink_[id].png`, `<w:drawing><wp:inline>` in comment body (4"×2" EMU), `word/_rels/comments.xml.rels`; `hasInk` detected from archive on load (PNG is source of truth); `_inkCaptured` state in annotation panel; UI placeholder shown on `supportsInk`

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
