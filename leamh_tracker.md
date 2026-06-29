# Léamh — Project Tracker

**Repo:** `github.com/afluffywaffle/layuv`  
**Bundle ID:** `com.afluffywaffle.layuv`  
**Licence:** GPL v3 · **Distribution:** Direct (macOS), App Store (iOS)

---

## Platform Strategy

| Platform | Delivery | Status |
|---|---|---|
| macOS | Standalone app | Active — Swift/SwiftUI (`macos_native/`); Flutter archived |
| iPad / iPhone | Standalone app | Planned — Swift port of macOS app; Flutter archived |
| Supernote Nomad/Manta | Native Kotlin APK (sideload) | Active — `android_native/`; Flutter retired |

**Native Android port (`android_native/`) — THE active product:** Native Kotlin app for Supernote Nomad/Manta. Flutter codebase archived to `archive/flutter/` 2026-06-17 — Flutter's compositor fought e-ink partial refresh. The pure-JVM `docx/` engine (parse, anchor, read incl. native-format + comment import fallback, full write-back) is complete and golden-tested (`cd android_native && ./gradlew :docx:test`). Engine is the authority — goldens are generated from correct native output, not Dart. Reader uses one clean canonical plain-text string for both render and anchor, drawn by `StaticLayout` over a `LAYER_TYPE_SOFTWARE` `View` owning the EPD waveforms (not Compose). See `android_native/README.md` and CLAUDE.md.

**Sync layer:** DOCX file on iCloud Drive (or any file sync). No proprietary sync needed — annotations are native Word comments and run formatting, readable on any platform. Open the same file in Léamh on macOS, Léamh on iPad, or Léamh on Supernote and all annotations are present.

**Swift/SwiftUI rewrite (macOS + iOS):** Flutter was chosen for cross-platform coverage including Supernote. Flutter stays for Supernote; rewrite targets Apple platforms only. SwiftUI covers those better (PencilKit, TextKit 2, native sandboxing, iCloud). Dart code serves as line-by-line reference for the Swift port.

**Supernote delivery — Flutter APK (sideload):** The DOC app plugin model was evaluated but does not support two-column reading or a controlled reading surface — the plugin only overlays Ratta's closed renderer. The standalone Flutter APK preserves Léamh's full reading experience (two-column, Literata, annotation panel, warm paper theme) and cross-platform annotation compatibility. Ink is the one open question — see ink section below.

**Supernote plugin SDK (for reference):** SDK (`sn-plugin-lib`) is live at `docs.supernote.com`, preview firmware available (Chauvet3.28.42 Beta for Nomad/Manta). Plugin architecture noted for future reference: React Native 0.79.2 in PluginHost, communicates with DOC via AIDL, `getSelectedText()` + `getCurrentFilePath()` are the primary DOC hooks, `PluginFileAPI` is `.note`-only. Not used by Léamh currently — plugin cannot provide a controlled reading surface or two-column layout, and cannot write to DOCX archives.

---

## In Progress 🔧

### Native Android port (`android_native/`) — reader UI ✅ COMPLETE (2026-06-21)
- [x] Pure-JVM `docx/` engine — plain-text mapper, anchoring, model, read path, native/comment import fallback, full write-back; all golden + JVM-verified ✅
- [x] `:app` Android module (depends on `:docx`; Onyx SDK; minSdk for Nomad/Manta) ✅
- [x] DOCX reader — `StaticLayout` over the clean canonical string, line-sliced pagination, newspaper two-column (default by screen size + toggle), Literata body ✅
- [x] Software-layer `ReaderView` + EPD waveforms (no animation) ✅
- [x] Large-tap-zone navigation (prev/next), reading-position persistence (`DocxStore.writePosition`) ✅
- [x] Text selection → annotation (word-snap, dotted-underline highlight render) ✅
- [x] **Device spikes (Supernote):** confirmed on Nomad — page-turn ghosting, StaticLayout memory, dotted-underline, selection-drag partial refresh all resolved ✅
- [x] **Ink — COMPLETE.** `InkNoteActivity` + `InkCanvasView` using drawPath hardware overlay; 3 tools (THIN/THICK/ERASER with lasso-circle-erase); real-time per-MOVE invalidate; PNG + stroke JSON save; panel thumbnails + tap-to-edit. Full protocol notes retained below. ✅

---

## Supernote drawPath low-latency ink — COMPLETE

> **Status (2026-06-21):** Reverse-engineering + on-device proof DONE on a real Nomad. Implementation COMPLETE — `InkNoteActivity` + `InkCanvasView` built with drawPath hardware overlay integration, 3 tools (THIN/THICK/ERASER), real-time per-MOVE invalidate, PNG + stroke JSON save, panel thumbnails + tap-to-edit. Protocol notes below remain useful for future maintenance and de-Onyx work.

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
- [x] **Build the real ink layer** (capture → preview → commit → persist via `InkDrawing.kt` → redraw); `DrawPathClient` promoted; `InkNoteActivity`/`InkCanvasView` built. ✅ Done 2026-06-21.
- [ ] **Hidden-API:** find a non-reflection route to the binder, OR accept the policy workaround. The real app **cannot** rely on `adb shell settings put global hidden_api_policy 0` (test-only). (It was set to `0` on the device for testing — restore with `settings put global hidden_api_policy null`.)
- [ ] **Reader-wide refresh / de-Onyx:** `app/.../reader/Epd.kt` still uses the **Onyx** `EpdController` (Boox) — likely a no-op on Ratta. **Fix = `android.os.EinkManager`** via `getSystemService("eink")` (`sendOneFullFrame()` / `screenRefresh()` / `setScreenMode(CLEAR/SMOOTH/SPEED)`) — see the EinkManager table above. Rework `Epd.kt` onto this (`RattaEink` wrapper exists, just not wired to reader yet).
- [ ] **Try `EinkManager.setScreenMode`** waveform modes (`CLEAR/DEFAULT/SMOOTH/SPEED`) for page turns — not yet tested.
- [ ] Optional: regional refresh via `hteink`/`eink` for flash-free erase.

### Device / RE setup notes
- Nomad on WiFi ADB at `192.168.12.185:5555` (reconnect each session: `adb connect 192.168.12.185:5555`; it sleeps/drops off).
- `adb` is at `~/Library/Android/sdk/platform-tools/adb` (not on PATH).
- Native-port build uses the Android Studio JBR (pinned via `org.gradle.java.home` in `android_native/gradle.properties`); build with `./gradlew :app:assembleDebug`.
- RE: pulled `/system_ext/app/drawPath/drawPath.apk`; native lib `lib/arm64-v8a/librecgnition.so`; disassembled with `radare2` (brew); transient working dir `/tmp/drawpath_re` (not committed).
- Ratta's official doc: `~/Documents/Using Supernote Low-Latency Handwriting Service (drawPath).pdf` (4 pages; covers only codes 1 & 2 — the table above is the fuller picture).

---

## DECISION UNDER REVIEW (2026-06-09): Flutter consolidation vs native port

**Why revisiting:** the native Kotlin reader renders well, but its UI is harder to build and looks worse than the existing Flutter app, and the founding premise of the native port — *"Flutter's compositor fights e-ink partial refresh"* — is challenged by what we learned reverse-engineering the Supernote.

**What changed the calculus:** on Supernote, the two hard things live **below the app's rendering layer**, so they're reachable from Flutter via a platform channel (the `BookmarkChannel` pattern the app already uses):
- **Ink** = `drawPath` paints strokes straight to the EPD (bypasses SurfaceFlinger); the app just configures it + captures geometry. Our `DrawPathClient.kt` is plain Binder code → ports to a Flutter plugin unchanged.
- **Refresh** = `android.os.EinkManager` via `getSystemService("eink")` (see the EinkManager table in the drawPath section) — a one-line platform-channel call. This is also the *correct* refresh API for the reader, replacing the Onyx `EpdController`.

So the original "Flutter can't do e-ink" reasoning was largely the **Onyx** model (auto-refresh hooks the View tree, which Flutter's single surface bypasses). Ratta's architecture (drawPath + EinkManager, both app-agnostic) is more Flutter-friendly.

### Gating spike — MUST pass on the Nomad before committing to Flutter
1. **Refresh cleanliness:** ✅ **PASSED 2026-06-09 on the Manta.** Flutter (Impeller/Vulkan) + `EinkManager.sendOneFullFrame()` via `getSystemService("eink")` reflection: content appears cleanly, **no ghosting** on page-flips OR the black/checker torture test (user-verified); the device's auto-EPD even handles in-app changes (auto-refresh off ≈ same). `setScreenMode` resolves (`CLEAR=0,SMOOTH=1,SPEED=2,DEFAULT=0`) but is a subtle global setting. **Key gotcha:** app-switch / first frame does NOT auto-refresh — must call `sendOneFullFrame()` once after the first frame (and on resume), or the panel stays stuck on the previous app's screen. Spike: `lib/spike_eink.dart` + `android/app/.../RattaEinkSpike.kt` + channel `com.afluffywaffle.layuv/eink_spike`.
2. **drawPath ink over a Flutter window:** ✅ **PASSED 2026-06-09 on the Manta.** From the Flutter app: `service_myservice` reachable, `reset`/`pen`/`disable`/`clearScreen` all `ok=true`; stylus ink **"feels native"** (drawPath paints the EPD directly); Flutter independently receives the pointer stream with **stylus tool type + pressure** (geometry capture for persistence); toolbar **disable rect (flag 0) confirmed** (`drawAPP intersectDisable=1` on the strip, ink suppressed; `0` on canvas). Expected rough edges: strokes vanish/artifact on UI refresh (Ratta's documented "app must own + redraw its ink") → solved by the ink-ownership architecture, not a blocker. Spike: `lib/spike_ink.dart` + `android/app/.../DrawPathClient.kt` + channel `com.afluffywaffle.layuv/drawpath`. Gotcha: this device gives **degenerate Flutter metrics** (MediaQuery dpr=1.0/size=0, canvas box height 0 at first layout) — compute physical pixels on the **Android side** from `resources.displayMetrics`, not from Flutter's MediaQuery.
3. **Large-doc memory:** ✅ **PASSED 2026-06-10 on the Manta.** 77k-word DOCX (639 paragraphs, 20 chapters), page-flip 2-col mode. Java heap flat (8.7 MB baseline → 8.8 MB after open → 8.7 MB after flipping — no growth). Native heap +62 MB one-time cost (Flutter/Skia TextPainter page-map), then completely flat under page flips and jump-to-end. No OOM, no ANR after fix. **Blocker found and fixed:** original `_paginate()` was O(n²) — laid out the entire remaining document on each page iteration (100 × TextPainter on 77k chars → ANR). Fixed in `lib/reader/page_flip_reader.dart`: samples 2000 chars to estimate chars-per-line, then only lays out a `2× estimated page` window per iteration → O(n). Fix committed on branch `native-port-drawpath-ink`.

**ALL THREE GATES PASSED. Decision: consolidate on Flutter.**

If any fail and can't be controlled → the native port's premise holds, keep native. If they pass → consolidate on Flutter (better UI + dev velocity + **one codebase across macOS/iOS/Supernote**).

### If the spike passes — Flutter custom-`Paragraph` reader core (the real work item)
The current Flutter reader uses per-column `SelectableText` slices, which **can't** do cross-column selection (native can — confirmed). To reach parity, port the native reader's single-layout design into Dart using `dart:ui` `Paragraph` (the same engine `SelectableText` uses under the hood). Mapping is ~1:1 and the **native files are the reference**:

| Native (reference) | Flutter port |
|---|---|
| `StaticLayout` over one canonical string | `dart:ui` `Paragraph` (`ParagraphBuilder` → `layout(width: colWidth)`) |
| custom `View.onDraw` + `Canvas.drawText` | `CustomPainter`/`RenderBox.paint` + `Canvas.drawParagraph` |
| 2-col = draw line-ranges `[a,b)`/`[b,c)` of one layout | clip+translate the same `Paragraph` per column |
| `getLineForOffset`/`getOffsetForHorizontal`/`getLineBaseline` | `getPositionForOffset`/`getLineMetrics`/`getBoxesForRange`/`getLineBoundary` |
| `HighlightPainter` decorations | draw over `getBoxesForRange` rects |
| `Paginator` | line-range pagination over `Paragraph` |
| `Epd.kt` (Onyx) | `EinkManager` platform channel |
| `DrawPathClient.kt` | drawPath Flutter plugin (same Binder code) |

Reference files: `android_native/app/.../reader/{ReaderView,Paginator,HighlightPainter,ReaderTheme}.kt`, `Epd.kt`, `spike/DrawPathClient.kt`. The docx/anchoring/annotation model is **shared and offset-based**, so it carries over directly.

- **Gain:** native-quality multi-column selection (contiguous offsets in one string), single canonical string for render+anchor, precise e-ink control — while keeping Flutter for all surrounding UI (chrome/panels/toolbars).
- **Cost / give up:** a real reader-core rewrite (biggest single item); lose `SelectableText`'s built-in selection handles/magnifier/menu/accessibility — but the native reader already forgoes these; reimplement only the subset used (drag-select + `snapToWordBoundaries`, already present).
- **Large-doc discipline:** build `Paragraph`s per-page/chunk (not one giant), cache the page map keyed by font/column/geometry.
- **Platform nuance:** custom core is ideal for e-ink Supernote; on **iPad** you may prefer native `SelectableText`/`SelectionArea` (magnifier, lookup, Scribble), so consider platform-gating the reader core (custom on e-ink, widget-based on Apple) or one custom core with platform-specific affordances.
- **Lighter alternative** (cross-column selection only, no full rewrite): wrap the two columns in `SelectionArea` + stitch the cross-widget selection back to document offsets.

### Battery / RAM summary
Native is **modestly** leaner (no Dart VM, lower baseline RAM, leaner idle). But for a non-animated reader the dominant energy cost — **EPD refreshes + radio** — is architecture-independent (same # refreshes for same UX). Flutter's real risk on the low-RAM Nomad is **baseline memory pressure** (Dart VM + Skia/Impeller ~tens of MB, a fixed cost that does NOT grow with document size), not active-reading battery.

### Capability deltas found (native vs current Flutter)
- **Cross-column selection:** native ✅ (one `StaticLayout`); Flutter ❌ (per-column `SelectableText`) — needs the custom core or `SelectionArea`+stitch.
- **Whole-doc highlight landmine:** `RunPropertyInjector` (shared engine, mirrored in Dart `docx_store`) rebuilds the full XML string per covered run → O(runs×docSize) ≈ minutes-long freeze for a 100+ page highlight. Not reachable via normal per-page selection today; fix = single-pass `StringBuilder` insert + pair run opens/closes. Architecture-neutral.

---

## Up Next (in order)

### Annotation toolbar — corner hint + undo button ✅ (shipped 2026-06-13)
- [x] **Corner hint (Photoshop-style triangle):** `_CornerHintPainter` triangle bottom-right of each lockable `ToolButton`, e-ink only. ✅
- [x] **Undo:** evolved past the original "7th button" spec into TWO affordances — (a) a transient icon-only **undo pill** (`UndoToolbar`) that auto-pops over a just-applied annotation and dismisses on the next selection/navigation (works for both tap-a-tool and locked-tool flows); (b) a **persistent app-bar undo** (`AppBarPill`, dimmed when nothing to undo). Both call `_undoLastAnnotation` (tracks `_lastAnnotationId`, set in `_saveImmediate`). ✅
- [x] **Tap-an-annotation actions toolbar:** tapping an existing annotation (pen OR finger now — ReaderView `_handleTap` no longer gates stylus out) opens `AnnotationActionToolbar` (Comment → panel; Delete → confirm dialog with per-document "don't ask again", key `delete_confirm_skip:<path>`). ✅

### Reader UI polish ✅ (2026-06-14)
Small Flutter-app reader chrome tweaks (all in `lib/reader_screen.dart` / `lib/reader/appbar_pill.dart`; e-ink + desktop):
- [x] **Grey out the annotations button when empty:** `hasAnnotations: _annotations.isNotEmpty` passed from reader_screen; `color: hasAnnotations ? Colors.black87 : Colors.black26` in AppBarPill. ✅
- [x] **Auto-close + grey the panel when the last item is deleted:** `_reloadAnnotations` sets `_showAnnotationsPanel = false` (closes overlay on non-e-ink); added `Navigator.of(context).maybePop()` for the e-ink full-screen route case. ✅
- [x] **Move title/filename to the LEFT of the app bar:** `bottomLeading: _buildTitleText(displayTitle)` already wired in ReaderView + PageFlipReader; `left: 16` in `_buildBottomBar`. ✅
- [x] **Lift the bottom bar up:** `EdgeInsets.only(bottom: 12)` already in all three bottom bar implementations. ✅

### Save performance ✅ (overhauled 2026-06-13) — further optimization optional
DOCX writes re-encode the whole zip and the annotation injection is O(N×M); on a 99-annotation device doc one save took **26s** and froze the UI. Fixed this session (all byte-identical, golden-verified):
- [x] **Debounced/coalesced saves** — reader holds `_annotations` as source of truth; `_scheduleSave` (1.5s) → `DocxStore.saveAll(list)` writes the full set once per burst. `_saveDirty` flag; flush on lifecycle `paused`, before the edit panel, on close (with a `_SavingDialog` "Saving…" indicator), best-effort in `dispose`.
- [x] **Off-thread encode (Phase 2)** — `_writeAllAnnotations` runs decode→inject→encode in `Isolate.run` (`_buildAnnotatedDocxBytes`, static/isolate-safe; annotations cross as JSON). UI never freezes during a save regardless of size.
- [x] **Injection ~1.6× faster** — `_buildPlainMap` was rebuilt per-annotation (64% of time); now plain is built once + a lightweight per-`<w:t>` segment index (`_xmlOffsetSegments`/`_byteForChar`) replaces the per-character offset list. Byte-identical.
- [ ] **OPTIONAL further perf (riskier):** remaining cost is per-iteration regex re-scans (run boundaries + post-split rebuilds). Eliminating them needs incremental run-position maintenance across `_splitRunAt`'s canonical reconstruction — diverges on real Word markup (tables/tracked-changes/native-import) that the synthetic tests don't cover. Only worth it for habitual 100+-annotation chapters. Benchmark harness: `test/inject_perf_test.dart` (520 anns) + correctness `test/inject_characterization_test.dart` (overlaps + all tools); both diff against `/tmp/inject_char_baseline`. Could also defer injection to close/export (json-only intermediate saves) — keeps active annotation fast without touching the injection algorithm.

### Native review — DOCX engine + write/corruption path (Run 1, 2026-06-17)
Hybrid review (Opus finders, Sonnet verify w/ high-sev → Opus) of `android_native/docx/` + `app/reader/DocxWriteQueue`/BookLoader + ReaderActivity write sites. **17 confirmed bugs, 3 refuted, 3 cleanups; none critical.** KEY: now that Flutter/Dart is archived, the "byte-identical to the Dart store" golden constraint **dissolves** — fix on the native side and regenerate goldens from corrected native output (real constraint = round-trips with Word/Pages/GDocs). Two confirmed "compat" findings (numeric-entity superset; clean-P vs legacy `_buildPlainMap`) were cross-app-drift-vs-Flutter only → **MOOT post-archive** (native behavior is the correct one).

**10 of 17 bugs fixed 2026-06-17 (commit 692764f); golden tests green (41/41):**
- [x] ✅ Duplicate DrawingML `id="1"` with ≥2 ink annotations — `InkDrawing.build` now takes `drawingId` from caller
- [x] ✅ Bookmark `w:id` self-collision — `100000+bkIdx` replaces `10000+runIndex`
- [x] ✅ ContentTypes self-closed `<Types/>` no-op — expand before replaceFirst
- [x] ✅ `ensureRelsEntry` self-closed `<Relationships/>` no-op — expand before replaceFirst
- [x] ✅ `buildCommentsRels` overwrites entire comments.xml.rels — now merges, preserving non-leamh rels
- [x] ✅ `XmlEntities.decode` throws on large/malformed numeric refs — `toLongOrNull()` + range check
- [x] ✅ `Annotation.fromMap` hard-cast drops entire list — safe casts + per-record try/catch in `loadAnnotations`
- [x] ✅ `hasInk` set true on 0-byte/corrupt PNG — `bytes().isNotEmpty()` check
- [x] ✅ `ioExecutor` never shut down in ReaderActivity — `onDestroy()` calls `shutdown()`
- [x] ✅ `deleteSelected` setResult-after-finish race — setResult called before async write begins

**Still open (6 remaining):**
- [x] ✅ `splitRunAt` drops non-`<w:t>` children (tab/br/field) on split — fixed 2026-06-18; now splits runContent around the `<w:t>` match, preserving all siblings (`<w:tab/>`, `<w:br/>`, `<w:rPrChange>`, etc.) naturally; removed dead `RPR_BLOCK`/`RPR_CHANGE` regexes; 6 new unit tests in `RunPropertyInjectorTest.kt`
- [ ] `RunPropertyInjector` O(annotations×docSize) — per-annotation `PlainTextMapper.build` + full `findAll` re-scans (medium perf, `RunPropertyInjector.kt:86-195`)
- [x] ✅ onPause position write can be lost on process death — fixed 2026-06-19; `savePosition()` now does a synchronous `prefs.edit().putFloat("pos:<path>", fraction).commit()` before the async DocxWriteQueue submit; `onBookLoaded` falls back to the SharedPreferences fraction when the DOCX has no saved position
- [ ] Reads bypass write queue → stale display on concurrent load/write (low)
- [ ] `savingPosition` flag threading — not reset on error in all paths (low)
- [ ] Static `@Volatile` cross-Activity handoff (`NoteActivity`/`InkNoteActivity` `pendingResult`/`pendingLaunch`) — unsynchronized global; fix = FileProvider URI or temp file (low)
- [ ] `DocxArchive.write` re-deflates all entries (drops ZipEntry compression metadata) — low perf impact
- **Run 2 (pending):** app reader UI in depth — ReaderView, Paginator, HighlightPainter, selection, AnnotationsPanel/Search/popups, EPD/Epd/EinkClient.

### Kotlin golden generator rewrite — DONE ✅ (2026-06-17)
**Status:** complete. `./gradlew :docx:generateGoldens` (from `android_native/`) regenerates all four golden suites using the real engine — no Dart/Flutter toolchain needed. 41/41 tests green.

The four Dart tools in `android_native/tools/golden_gen/` import `package:layuv/models/...` which resolved via the root `pubspec.yaml` to `lib/`. After the Flutter archive both are in `archive/flutter/`. Running the generators now requires `cd archive/flutter && dart run ../../android_native/tools/golden_gen/<tool>.dart` — fragile, requires a Flutter SDK, and couples golden generation to archived code.

**Why this matters:** if the anchoring algorithm, model serialization, or XML entity decoder changes, the JUnit golden tests need fresh input files. Right now you can't regenerate them without the Flutter toolchain.

**What each tool generates (for the Kotlin rewrite):**

| Dart tool | Outputs | Purpose |
|---|---|---|
| `gen_engine_goldens.dart` | `golden/anchoring/locate.json`, `wordsnap.json`, `golden/model/annotations.json`, `model/position.json` | Anchoring test cases (copies of `_locateInPlain`/`snapToWordBoundaries` run against known inputs); model JSON round-trip (Annotation/ReadingPosition toJson) |
| `gen_goldens.dart` | `golden/clean/*.offsets.json`, `golden/legacy/*.offsets.json` | PlainTextMapper golden offsets for all test fixtures |
| `gen_import_goldens.dart` | `golden/import/*.json` | NativeImport test cases |
| `writeback_golden_test.dart` | `golden/writeback/document.xml`, `comments.xml`, `comments.xml.rels`, etc. | Full DocxStore.write() output byte-equality |

**Rewrite plan (one Kotlin JUnit generator per tool):**
- The `gen_goldens.dart` and `gen_import_goldens.dart` tools copy logic VERBATIM from Dart — the Kotlin equivalents already exist (`PlainTextMapper`, `NativeImport`). A Kotlin generator just calls the real Kotlin code and writes JSON/XML files. No Dart logic to port.
- `gen_engine_goldens.dart` logic to port: `_locateInPlain`, `_findClosest`, `_normaliseQuotes` (from docx_store.dart) and `snapToWordBoundaries`/`_isWordBoundary` (from annotation_utils.dart). The Kotlin versions of these functions already exist (`Anchoring.kt`). The model JSON cases use Annotation/ReadingPosition `.toMap()` — also already Kotlin.
- `writeback_golden_test.dart` is the easiest: it just calls `DocxStore.write()` with known input annotations and writes the ZIP entries. Pure Kotlin, no Dart logic.

**Concrete steps:**
1. Write `android_native/tools/golden_gen/GenerateGoldens.kt` — a Kotlin `main()` that reproduces all four tools:
   - Call `PlainTextMapper.build(xml)` for each test fixture XML, write `.offsets.json`
   - Call `NativeImport.importNativeFormatting(...)` for import fixtures, write `.json`
   - Call `Anchoring.locateInPlain(...)` for anchoring cases, write `locate.json`/`wordsnap.json`
   - Serialize known `Annotation`/`ReadingPosition` via `.toMap()`, write model JSONs
   - Call `DocxStore.write()` with `writeback/input.docx` + known annotations, write each ZIP entry to a golden file
2. Wire it as a Gradle task (`./gradlew :docx:generateGoldens`) so it's one command
3. Delete the four Dart tools (or keep in `archive/flutter/` for reference)
4. Never run `flutter test .../writeback_golden_test.dart` again

**Swift engine parity — DONE (2026-06-17):** All 9 engine bug fixes were ported to `macos_native/Packages/LeamhDocx/` and the Swift writeback goldens were already written with the correct values when the engine was first committed (`d8fc277`): `comments.xml` has `id="2"`, `document.xml` has `w:id="100000"`. `swift test` reports 10/10 passing.

**Swift golden generator — also needed:** `macos_native/Packages/LeamhDocx/Tests/LeamhDocxTests/` currently has hand-authored golden files that can't be regenerated without manually running the engine. When the Kotlin generator (`GenerateGoldens.kt`) is written, write a parallel Swift equivalent — an XCTest helper (or a Swift executable target) that calls `DocxStore.write()`, `PlainTextMapper.build()`, etc. and writes the golden files to disk. Same outputs as the Kotlin generator but for the Swift test resource tree.

**Files written:**
- `android_native/docx/src/generators/kotlin/com/afluffywaffle/layuv/docx/GenerateGoldens.kt` — single `main()` covering all four suites
- `android_native/docx/build.gradle.kts` — `generators` source set + `generateGoldens` JavaExec task

**Dart tools in `android_native/tools/golden_gen/` remain for reference** but are no longer the path to regenerate goldens.

### Known bugs — Android native (e-ink)

- [ ] **Pen dragging text selection handles renders dashed lasso lines** — `EinkPen.configureLasso()` sets drawPath to dotted lasso mode at startup; any stylus drag (including handle-drag) renders the lasso visual. Need to switch drawPath pen mode to a non-lasso config when handles are being dragged, and restore lasso mode after drag ends.
- [ ] **Text selection highlight in annotations panel is unreadable on e-ink** — system selection colour is a dark filled rect; on greyscale e-ink it renders near-black, making selected text invisible. Override the selection colour to a light dotted underline or low-opacity tint that is legible on e-ink.

### macOS reader UX pass (2026-06-29, from on-device review)

All macOS; iOS parity items flagged where relevant.
- [x] **Dark-mode follow (écri-style)** — `DocumentStore.followsDarkMode` pref. OFF (default) = app pinned light, reader always uses the chosen `paperTheme`. ON = app follows OS; reader switches to **Night** while OS is dark (the user's `paperTheme` stays the light pick). `LeamhAppApp` root `preferredColorScheme = followsDarkMode ? nil : .light`; `ReaderScreen` computes `effectiveTheme(systemDark: colorScheme == .dark)` and feeds it to the reader; toggle in Format menu + Typography toolbar menu. **iOS TODO:** same pref + trait-based switch (iOS reader still always `store.paperTheme`).
- [x] **Font auto-apply bug fixed** — macOS reader rebuilt attributed only on content/theme/size change; `font` wasn't in the change set, so a font pick didn't apply until you also nudged size/theme. Now `font: FontChoice` is threaded into `ReaderViewController.update` + included in `contentChanged`. (This also fixed the écri-import "font didn't auto-apply" symptom — the import set `fontChoice` but the reader didn't rebuild.)
- [x] **Font preview** — Typography menu renders each font name in its OWN typeface (`FontChoice.previewFont` = `.system(design:)`); New York shows serif, San Francisco sans. (macOS toolbar menu; the menu-bar Format Picker can't style item fonts reliably — left plain there.)
- [x] **Keyboard + trackpad navigation (macOS reader)** — local `NSEvent` keyDown monitor in `ReaderViewController` (skips when an editable/field-editor responder like Find is focused, and ignores Cmd/Ctrl chords). **Scroll mode:** ↑/↓ line, PageUp/PageDown/Space page (via `scrollLineUp/Down`/`scrollPageUp/Down`). **Screen-flip:** →/↓/Space → next, ←/↑ → previous; page keys follow the requested RTL-friendly semantics **PageUp = advance (next), PageDown = previous**; plus trackpad — vertical wheel/scroll moves horizontally and two-finger swipe flips naturally (`handlePagedScroll` accumulates deltaX/Y to a ±40 threshold; forwarded from paged columns + container via `onScrollWheelForward`). **iOS TODO:** screen-flip already has edge-tap; consider hardware-keyboard support on iPad.
- [x] **Left-handed navigation (WASD)** — `DocumentStore.leftHandedNav` pref (toggle in Format + Typography menus). Scroll: W/S line, Q/E page. Screen-flip: D/S → next, A/W → prev, Q (=PageUp) → next, E (=PageDown) → prev. **iOS TODO:** N/A (no hardware keyboard assumption) unless iPad Magic Keyboard.
- [x] **Ink pressure + Delete (macOS)** — the custom NSView ink canvas now captures `NSEvent.pressure` on tablet points (`.tabletPoint` subtype) → per-point width (`InkPoint{x,y,w}`, ~0.35×–1.6× pen width); mouse/trackpad use flat width. Variable-width render (per-segment) on screen + in the exported PNG; `macink` strokes JSON stores `[x,y,w]` per point (back-compat: 2-element points fall back to a per-stroke width). Works with Apple Pencil over **Sidecar** and Wacom/Supernote tablets feeding `NSEvent` pressure. Added a **Delete (trash) button** to the ink editor header for existing ink — previously the only removal was erasing every stroke (the "no way to dismiss ink" report). Cancel (Esc) / Done unchanged. **NOTE:** tilt not captured; macink not re-editable on iPad (PNG displays everywhere — by design).

### macOS reader UX pass 2 (2026-06-29, second review)

- [x] **macOS "Screen Flip" → "Page Flip" + horizontal slide** — the macOS paged mode is page-flip-without-curl, so it's renamed "Page Flip" (`NavMode.pageFlip`; rawValue stays `"screenFlip"` so the persisted pref carries over). Flipping now does a horizontal **slide** (CATransition `.push` fromRight/fromLeft, 0.22s) on the layer-backed `pagedContainer` instead of an instant swap — conveys direction, still no curl. iPad keeps its own `screenFlip` = vertical scroll, 1 column (intentional platform difference; not changed).
- [x] **Overflow "More" menu (macOS toolbar)** — added a `•••` (`ellipsis.circle`) toolbar menu (the macOS-native "more" affordance; not the hamburger) holding **Open…** (now a **folder** icon, was the import-looking arrow-in-box), **Night Mode (Follow System Dark)**, and **Left-Handed Navigation**. Moved the Night/left-hand toggles OUT of the Typography menu (Typography keeps font/size/theme/two-columns); they remain in the menu-bar Format menu too.
- [ ] **iPad Screen Flip review (noted, not done)** — user observed iPad screen-flip is vertical + 1 column. Decide whether iPad should also offer a horizontal "Page Flip" (the iPad has `pageTurn` = horizontal curl + `screenFlip` = vertical); possibly unify naming/behaviour with macOS. iOS parity follow-up.

### Branding + chrome (2026-06-29)

- [x] **App renamed "Léamh" → "Layuv"** (the phonetic spelling; avoids "Leamh"/insipid misread). Swift: `CFBundleDisplayName` + `PRODUCT_NAME` → "Layuv" for both app targets (macOS menu bar uses CFBundleName=PRODUCT_NAME, so both were changed; bundle is now `Layuv.app`; bundle id `com.afluffywaffle.layuv` and internal Xcode target name `LeamhApp` unchanged). User-facing strings + code comments scrubbed (sidebar title, iPad doc-title fallback, AI export header, header comments). Android: `app_name` was already "Layuv dev"; scrubbed the stale "exported from Léamh" in `ai/AiExporter.kt`. NOTE: `getSharedPreferences("leamh")` keys + the `LeamhDialog`/`LeamhApp` class/target identifiers are internal and intentionally left.
- [x] **macOS glass toolbar — DONE/tuned** — `.toolbarBackgroundVisibility(.hidden, for: .windowToolbar)` (wrapped in `HiddenToolbarBackground` ViewModifier, `#available(macOS 15)`, no-op on the macOS 14 floor) so buttons float as Liquid Glass over the reader on macOS 26. Three companion pieces make it read cleanly: (1) a **top fade scrim** in `ReaderScreen` — a themed `LinearGradient` (paper→clear), 150pt, eased multi-stop falloff so there's no visible cut line, `.ignoresSafeArea(.top)`, non-interactive; (2) reader **top inset raised** (`ReaderViewController.vInset` = 72) so the first lines render below the toolbar+fade; (3) **window background painted with the paper colour** (`applyWindowBackground()` on theme-change + `viewDidAppear`) so the hidden-toolbar strip blends instead of showing system window grey — this was the Night-mode "toolbar bar still showing" fix. User-approved 2026-06-29. **iOS parity TODO:** the iOS reader has no equivalent fade/glass treatment yet.

### Android theming / e-ink gating (2026-06-29)

Context: the Swift apps gained écri-style colour `PaperTheme`s. On `android_native` (Supernote e-ink) colour themes are N/A — see memory `android_eink_gating.md`. Recorded items:

- [ ] **No device-model gating today (by design — keep it).** `android_native` has NO `Build.MODEL`/`MANUFACTURER` checks; it degrades purely on runtime service availability: `reader/RattaEink.kt` `available()` (= `getSystemService("eink") != null`; `sendOneFullFrame` no-ops otherwise, `Epd.kt` falls back to `invalidate()`) and `reader/DrawPathClient.kt` `available()` (= `ServiceManager.getService("service_myservice") != null`; ink ops gated at ~15 call-sites). So the app already runs on normal Android with EPD + hardware ink as no-ops. Colour is the ONE thing not gated — `reader/ReaderTheme.kt` hardcodes warm paper `0xFFF5F0E8` + black ink.
- [ ] **If colour themes ever come to NORMAL Android (phones/tablets):** add a `DeviceCapabilities` singleton (init once in `Application.onCreate`: `supportsEink = RattaEink.available(ctx)`, `supportsDrawPath = DrawPathClient.available()`), then gate `ReaderTheme` PAPER/INK/highlight on `!supportsEink` — colour palette only off e-ink, fixed warm-paper/black on Supernote. Mirror the Swift `PaperTheme` palette (parchment/bone/dusk/sage/night) for non-e-ink only. On e-ink honour **font only** (serif/sans, already in `ReaderTheme`/`body_font`), never colour.
- [ ] **A dedicated e-ink theme (pure black/white) — modest real win, via waveform not colour-compute (answered 2026-06-29).** It does NOT save render CPU (the app rasterises an ARGB framebuffer either way; grey vs colour pixels cost the same to draw). The actual benefit is the **EPD waveform**: a pure 1-bit black-on-white surface can refresh with a fast 2-level waveform (DU/A2) with minimal ghosting, whereas mid-tone greys (e.g. the current ~12% black highlight fill) force the slower full-grayscale waveform (GC16). Léamh's reader is already near-optimal (warm paper + black text; the only mid-tone is the highlight/ink grey fill). A true "e-ink theme" would pin pure white `#FFFFFF` bg + pure black text and render annotations as **patterns/lines instead of grey fills**, so every partial refresh stays 1-bit-fast. Lever lives in the waveform mode passed to `RattaEink`/`postRectForPw` (dispMode), not the palette per se — so the gain is snappier partial refresh + less ghosting, not lower CPU. Low priority; revisit only if reader refresh feels sluggish.
  - **DELVE-INTO (investigation, when picked up):**
    1. Confirm the waveform claim on a real Nomad/Manta: render the current reader (warm paper + 12% grey highlight) vs a pure-1-bit variant (`#FFFFFF` bg, `#000000` text, hatch/underline annotations instead of grey fills) and compare partial-refresh latency + ghosting on page-turn and annotate. Capture via `adb logcat` waveform/dispMode traces + visual.
    2. Map which dispMode constants the reader currently requests through `RattaEink`/`EinkPwInternalY.postRectForPw` (GLUI/DU/A2/GC16 — see memory `supernote_re_system_apks.md` / `eink_epd_refresh.md`) and whether a 1-bit surface actually lets us drop to DU/A2 for text pages while keeping GC16 only for full clears.
    3. Decide the annotation rendering for an e-ink theme: replace the `HIGHLIGHT_FILL` ~12% grey with a 1-bit-safe treatment (dotted/hatched underline or outline) so highlighted runs don't force a grayscale waveform. Check legibility on e-ink (CLAUDE.md greyscale rules).
    4. If it pans out, gate it behind the `DeviceCapabilities.supportsEink` flag above as the DEFAULT e-ink reader theme (not user-facing colour choice) — i.e. e-ink devices get the 1-bit theme automatically; normal Android gets the `PaperTheme` palette.
    5. Quantify: is the latency/ghosting delta actually perceptible enough to justify the annotation-rendering change? If marginal, drop it. Record findings in a memory.

### Android AI menu — next-up polish (2026-06-26)

- [ ] **Import rewrite with no import folder set opens the browser at storage root** — after removing the "Set an import folder first" guard toast, tapping "Import rewrite…" with neither import folder nor AI export folder configured drops the user at `/sdcard` with no context. Consider a brief non-animated top-of-screen status label (no toast, no animation — static overlay that auto-hides after 2.5s using a Handler) saying "No folder set — pick one below", or redirect directly to the "Set import folder…" flow instead.
- [ ] **`ai_create_subfolder` SharedPreferences orphan** — the "create subfolder per export" checkbox was removed and its preference key `KEY_AI_CREATE_SUBFOLDER` deleted from code, but any device that had it checked retains the stale key in `leamh` prefs. Harmless, but a future "Reset AI settings" action should include it in the clear set.
- [ ] **AI menu button always visible in toolbar pill** — previously the AI Chat bubble was hidden when AI wasn't configured (`updateAiButtonVisibility()`). Now it always shows (the button opens the submenu which contains Export/Import/Set folder — all useful without AI configured). Worth a UX review pass to confirm this is the intended behaviour; if not, restore the gate but show a dimmed version that still reaches the folder-setting items.
- [x] ✅ **Comment tool (toolbar) didn't save annotations** — `commitAnnotationFromPanel` was missing the optimistic update that `commitAnnotation` has; the smart-merge in `saveAnnotations.onSuccess` saw a stale `currentBook.doc` and fell into the mismatch branch, never calling `readerView.updateAnnotations`. Fixed 2026-06-21: added optimistic `book` + `readerView.updateAnnotations` before `saveAnnotations`. `ReaderActivity.kt:1082–1130`.
- [x] ✅ **Ink annotations displayed in panel with thumbnail + tap-to-edit** — `AnnotationsPanelActivity` now decodes ink PNGs in the IO thread (`loadAnnotations`) and stores them in `inkBitmaps`. `buildRow` shows a 96dp greyscale thumbnail for any annotation with `hasInk=true`. Tapping an ink row returns `EXTRA_OPEN_INK_ID` to `ReaderActivity`, which calls `editAnnotationNote` to open the ink editor. Fixed 2026-06-21.

### Ink image visibility in Pages and Google Docs

Currently ink PNG lives inside `word/comments.xml` via `<w:drawing>` — Word renders it but Pages and Google Docs do not (neither app supports images inside comment bodies). Three options evaluated (demo DOCX verified in Pages + Google Docs, 2026-06-19):

- [ ] **Option 1 — Floating anchor in document.xml (preferred, most work):** write ink PNG as `<wp:anchor>` in `word/document.xml` pinned to the right margin at the annotated paragraph's vertical position. Visually identical to a margin sticky note. Requires: (a) inserting a new `<w:p>` with the anchor after the bookmark, (b) finding and removing the old anchor paragraph on re-edit/delete by scanning for `<wp:docPr name="ink_{id}"/>`, (c) wiring the image relationship into `document.xml.rels` rather than (or in addition to) `comments.xml.rels`. Does NOT currently touch `document.xml` outside bookmark injection — this would be the first write to body content.
- [ ] **Option 2 — Inline block after annotated paragraph (simplest, universal):** write ink PNG as `<wp:inline>` in a new `<w:p>` immediately after the bookmark's paragraph, indented slightly. Renders as a block image in the text flow — slightly disruptive but visible in all three apps. Same cleanup requirement as option 1 (find + remove paragraph on edit/delete). Simpler XML (no anchor positioning math).
- [ ] **Option 3 — "Ink Notes" appendix section (least invasive):** append a styled section at the end of the document listing each ink annotation's PNG beside its quoted passage. No changes to existing body paragraphs. Easy to implement cleanly but images are disconnected from their in-text location.

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

### Swift/SwiftUI rewrite (macOS + iOS/iPad)
- Two targets in one Xcode project (`macos_native/LeamhApp/`): `LeamhApp` (macOS) + `LeamhApp-iOS` (iPad/iPhone)
- Shared pure-Swift `LeamhDocx` engine unchanged across both targets
- [x] M1 — open DOCX + TextKit 2 render (commit `c14c089`) ✅
- [x] M2 — selection annotations, tap-to-edit, annotations panel (commit `5d0edde`) ✅
- [x] M3 — Apple Pencil ink notes via PencilKit (commit `e891351`) ✅
- [x] M3.5 — icon toolbar, full-text search, nav mode picker, panel sort ✅
- [x] M4a — engine port: `ManuscriptSerializer`, `RewriteProtocol`, `DocxFromText`, aichat r/w (commit `ca5ea07`) ✅
- [x] M4b–M4d — provider stack (`OpenAiCompatibleProvider`, `CleartextPolicy`, `SecureKeyStore`), `AskAiView`, `AiSettingsView`, Export for AI (commit `7c9fa9f`) ✅
- [x] **iPhone parity** — size-class-adaptive root: iPad keeps NavigationSplitView; iPhone (compact) = reader-first NavigationStack, 3-tab panel as a sheet, secondary actions in an overflow menu; recents in the empty state (commit `349f783`) ✅
- [x] **Font system** — dropped the broken bundled Literata/Source Sans 3 (fell back to system). Reader body = user-selectable **New York (serif) / San Francisco (sans)** picker; default New York; UI chrome stays San Francisco. Also **reader text size** S/M/L (15/17/20pt, reader-only) (commits `267096d`, `19551bf`, `393c9b9`) ✅
- [x] **Light-appearance lock** — warm-paper reader pinned to light so dark mode doesn't wash out ink/chrome (commit `377971b`) ✅
- [x] **macOS sidebar = iPad 3-tab panel** — `SidebarPanelView` promoted to Shared; macOS sidebar is Annotations/Bookmarks/Find; recents → empty state; macOS reader find + scroll-to-annotation via `ReaderCoordinator` (commit `b17024f`) ✅
- [x] **Paginated reader + page-curl** — `TextPaginator` + `AnnotatingTextSurface` (offset-aware selection/annotation on every page); **Page Turn = UIPageViewController(.pageCurl)**, **Screen Flip = .vertical scroll**; **two columns** on iPad (avail width ≥ 380pt), one on iPhone (commits `679db3a`, `db82e25`) ✅
- [x] **AI submenu parity** — single AI bubble button (iPad toolbar) / "AI" submenu (iPhone overflow) mirroring Android `aiMenuButton`: AI Chat (disabled when provider unconfigured), AI Settings… (opens `AiSettingsView`, previously only reachable from chat), Export for AI… (→ export folder if set, else share sheet), Import rewrite… (auto-finds "<doc> Draft.docx" in import folder else file picker; overwrites + reloads), Set AI export/import folder… (security-scoped folder bookmarks, folder shown in label). `DocumentStore`: aiExportFolder/aiImportFolder, exportForAi(toFolder:), importRewrite(from:), autoFindRewrite() (commit `706886f`). Built; on-device AI flows pending test (not core). ✅
- [x] **`ThreadEntry` parity** — full comment-thread read/write/serialize ported to LeamhDocx: `ThreadEntry` + `Annotation.threadEntries` (JSON round-trip); `LegacyComments` reply-flattens via `commentsExtended.xml` (root-ancestor walk, multi-level + multi-paragraph); `CommentWriter` per-entry paragraphs + `emptyCommentsExtended`; `Timestamps.formatThreadPrefix`; `ManuscriptSerializer` folds the thread; empty thread = byte-identical. Also fixed a latent `\u{2014}`-in-raw-string regex bug. 7 ThreadCommentTests, `swift test` 31/31 (commit `715a11f`). NOTE: engine-only — no iOS thread *editor* UI yet (the bottom-pane thread/ink editor like Android's NoteActivity is a separate future app-layer task). ✅
- [ ] **UX polish (batched — device pass):** two-column should be tied to the *reader's* width, not just the screen — when the sidebar/inspector is open and the reader column gets narrow enough, it should auto-drop to one column (and back to two when it widens). Re-pagination already keys off the reader bounds via `viewDidLayoutSubviews`; the `twoColumnMinWidth` (currently 380pt) likely needs tuning so a cramped reader falls back to 1 column. Verify the sidebar-toggle resize actually re-triggers pagination. (Plus general reader UX refinements to revisit in a focused device pass.)
- [ ] **Line spacing parity** — Android has a reader line-spacing preference (overflow "LINE SPACING" cycle row, `ReaderTheme.lineSpacingMult`); the Swift reader uses fixed spacing. Add a line-spacing preference (à la `bodyTextSize`) applied to the reader attributed string / paragraph style on both paged + scroll surfaces.
- [x] **Reader theming (écri palette) — DONE on both Swift apps (2026-06-28)** — `Shared/PaperTheme.swift`: 5 themes **parchment / bone / dusk / sage / night** copied verbatim from écri's `Theme.swift` (paper bg = `paperPageUI`, ink text = `inkDarkUI`, highlight fill = `highlightColor`; case names + rawValues match écri 1:1 for free interop). `DocumentStore.paperTheme` (@Published, persisted, syncs `AppTheme.currentTheme` static like `fontChoice`→`current`). Reader bg + body text + underline/strike colour + highlight fill driven from the theme on both macOS (`ReaderView.makeAttributedString`/`applyTheme`) and iOS (`ReaderTextView.makeAttributedString`/`setTheme`); ink canvases themed too. Pickers: macOS Format menu + Typography toolbar menu; iOS typography menus (iPad + iPhone overflow). `night` is an explicit dark *paper* (dark bg, light ink set explicitly) so the forced-light chrome lock is untouched. Per-tool accent colours (wavy=teal, comment=green, bookmark=orange, ink=purple) kept as-is — still legible on all 5 papers. **NOT YET:** on-device contrast check of `night`; theme is GLOBAL (app-wide), not per-document (écri stores it per-doc — see interop note below).
  - **Android (Supernote e-ink): paper themes are N/A — do NOT port the colour palette.** The Nomad/Manta panel is greyscale, the reader is pinned to warm-paper-behind-black-text by the e-ink design rules (CLAUDE.md), and coloured paper/ink/highlight would just render as muddy greys. The ONLY écri/theming concept worth honouring on Android is **font** (serif vs sans, which Android already has via `ReaderTheme`/`body_font`). So: skip the `PaperTheme` mirror entirely on Android; only the `écri-font` field (below) matters there.
- [x] **Text import (.txt/.md → .docx) + écri front-matter interop — DONE on both Swift apps (2026-06-29)** — `LeamhDocx/TextImport.swift` (pure engine): `parse(raw)` strips the écri front-matter header and returns body + neutral fields (`ecriThemeRaw`/`ecriFontSerif`/`ecriPage`); `docx(from:text)` assembles a complete minimal valid DOCX from scratch (Content_Types + package rels + `word/document.xml` with paragraphs + Letter sectPr) — no template file needed, stays pure. 7 `TextImportTests`, `swift test` 38/38. App: `DocumentStore.openAny(url:)` routes by extension (docx → `load`; else → `importTextFile`); `importTextFile` reads the text, maps écri prefs (`ecriThemeRaw`→`PaperTheme(rawValue:)` 1:1, `ecriFontSerif`→`FontChoice.serif/.sans`), builds the docx, writes a NEW working `.docx` (sibling of source if writable, else app Documents — source never modified), and opens it. Open dialogs accept docx + plainText/text/md/markdown (macOS `NSOpenPanel`, iOS `.fileImporter`). écri's `---\nécri-theme/écri-font/écri-page/écri-dark…\n---\n` header (from `écri/DocumentStore.swift`) is recognised; `écri-dark` (colour) ignored. **NOT YET:** `écri-page` → reading-position seeding (parsed but not applied); .rtf/.epub (only .txt/.md plain text today); per-document theme/font storage (Léamh theme/font are still GLOBAL — écri stores them per-doc; open design Q since position is already per-doc in `leamh/`).
  - **Android honoring (text import, when it lands there too):** strip the same `---\nécri-*…\n---\n` header and honour **only `écri-font` (serif/sans) and `écri-page`** → map to `ReaderTheme`/`body_font` + reading position. **Ignore `écri-theme`/`écri-dark` colour fields** — greyscale e-ink (see Android note above). Same strip logic, fewer fields mapped.
- [ ] **Help & About + AI disclosure not ported** — Android gates AI Chat on `ai_disclosure_accepted` (set via the Help & About → Ask AI disclosure checkboxes). iOS has no Help & About screen, so there's no way to accept the AI disclosures; the iOS AI Chat gate currently uses only `AiProviderSettings.isConfigured` (base URL). Clone Help & About (incl. the disclosure-acceptance flow) from Android, then add the disclosure to the AI Chat gate.
- [x] **Reader: suppress system edit menu + Copy in annotation bar** — selection now shows ONLY the floating annotation bar (`editMenuForTextIn` returns an empty menu); added a Copy button (leads the bar, copies selection to pasteboard) (2026-06-27). ✅
- [x] **Reader: Apple Pencil drag-to-select** — a pencil-only pan gesture selects text immediately (highlighter feel) without a long-press; finger scroll / page-curl unaffected (2026-06-27, on-device test pending). ✅
- [ ] Paged-mode polish — Find searches the current page only in paged modes (works fully in scroll); cross-column selection doesn't span the gap (each column is its own text view).
- [ ] **`RunPropertyInjector` perf parity (engine, low urgency on Apple)** — `LeamhDocx/RunPropertyInjector.swift` (line ~71) still rebuilds the whole-document plain map + run lists `PlainTextMapper.build(xml)` **per annotation** inside `for a in annotations`, i.e. `O(annotations × document length)`; every save re-injects ALL annotations. The Android engine was fixed 2026-06-27 (`android_native` commit `f3d3051`): build the map + run positions ONCE, then maintain them incrementally per rPr-insert/run-split (suffix shift for an insert; small local `PlainTextMapper.build` re-derive of just the split run — plain text is invariant so the `xmlOffsets` array never resizes). **Byte-identical output** (Android write goldens unchanged). Result there: 10–18× faster, the 30–50s/save pile-up on the Supernote gone.
  - **Why low urgency:** output is unchanged (correctness is fine), and Apple Silicon runs the transform ~50–100× faster than the e-ink CPU — the 478K×88-mark worst case is ~10s on the Nomad but only ~200ms on a Mac. Only worth porting if iPad/Mac saves on a *large* doc ever feel sluggish, OR for engine-parity hygiene (a future injector *bug* fix would otherwise need applying to two now-different implementations).
  - **How (same rigor as Android):** port the incremental `Doc` (xml + xmlOffsets + runOpens/runCloses with `insert()`/`split()` mutators); add a Swift equivalence test asserting byte-identity against a **frozen copy of the current Swift injector** across a randomized battery (tabs, overlaps, all tools, bookmarks, comments), with a per-mutation self-check that re-derives the structures from scratch. Do **not** regenerate the Swift goldens — unchanged goldens are the proof. A "compute against the original doc in one batch" approach was tried on Android and FAILED byte-identity (a prior split changes a later annotation's run mapping) — sequential + incremental bookkeeping is the only byte-identical fast path. Full write-up: memory `native_save_perf_injector.md`.
  - **Not applicable to Apple:** the wake-lock (Supernote-only e-ink CPU-freeze) and the 1.5s save coalescing (app-layer in Android `ReaderActivity`) shipped alongside the Android injector fix — Apple needs neither, though coalescing could be a nice-to-have if iPad rapid-annotation ever piles up.

### App icon
- [x] Icon designed in Affinity and generated via `flutter_launcher_icons` ✅
- [x] **Swift apps (macOS + iOS) app icon — DONE 2026-06-29** — the **serif-"L" Icon Composer design** (warm-paper rounded square + document/page + pencil + big serif capital L), rendered from `~/Library/Mobile Documents/.../Projects/Icons/léamh/leamh-icon-d.svg` (the flat 1024 vector export of `layvu.icon`) via `cairosvg` into `LeamhApp/Resources/Assets.xcassets/AppIcon.appiconset` — mac sizes 16–512 @1x/2x + a 1024 iOS-universal entry; one shared appiconset, both targets. `AppIcon.icns` + `Assets.car` confirmed in the bundle. (First pass used the simpler android_native open-book vector `drawable/ic_launcher.xml`; swapped to the serif-L on request — that was the intended icon.) Rounded corners are baked into the SVG (mac-native look). Source of truth: the `layvu.icon` Icon Composer package if a layered/liquid-glass variant is wanted later.

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
- **dart:ui Paragraph reader (`ReaderView`)** — complete, golden on Manta (Jun 2026). One whole-book `Paragraph` laid out once, sliced into columns by line ranges via clip+translate (`drawParagraph`); cross-column selection; pointer-state-machine selection (scrub + long-press); grab handles; margin indicators; `RepaintBoundary` splits base (text) from overlay (decorations) so a highlight change never re-runs `drawParagraph`. `EinkPen.configureLasso()` called at `initState` — drawPath penType 4 (dotted lasso) provides hardware selection-stroke feedback during any drag. `kDebugForceReaderView = true` in `reader_screen.dart` (reset to `false` before ship).
- **Circle gesture → toolbar model** (Jun 2026): both linear scrub AND circle over text → text selected → annotation toolbar appears (circle-to-confirm bypass removed). `CircleTappable` (`lib/utils/circle_tappable.dart`) — `Listener`-based widget (below gesture arena); fires `onTap` if pen-up is within 90dp of pen-down AND press < `kLongPressTimeout`; pointer capture means pen-up reaches the widget even when the circle exits its visual bounds mid-stroke. `ToolButton` on e-ink uses `CircleTappable` + `GestureDetector(onLongPressStart only)`; toolbar buttons are 64×64dp, toolbar 80dp tall. `_dismissToolbar` calls `EinkPen.clearInk()` on e-ink to clear any lasso strokes drawn during a circle-over-icon gesture.
- **RE dead ends confirmed (Jun 2026):** `setWalcomEmrInfo` (drawPath code 9) — probed values 0/1/50/100 on Manta, no pen-up delay difference; pen-up early-commit (pressure < 0.03f) remains the workaround. `EinkPwInternalY.postRectForPw` — operates on the drawPath PW hardware overlay layer only, not the app View layer; `view.invalidate()` / auto-EPD remains the correct path for app UI refreshes.

### Native Android app — full reader + annotation flow ✅ (2026-06-21)
- `:app` module builds sideloadable APK (`com.afluffywaffle.layuv.dev`)
- `ReaderView` (`LAYER_TYPE_SOFTWARE`): per-column draw, edge-strip nav (80dp strips, both/left/right/none), bookmark scrubber
- `Paginator`: whole-book `StaticLayout` sliced into column pages
- Navigation + position persistence (saves on `onPause` + `onSaveInstanceState`; restores from DOCX `position.json` + SharedPrefs fallback)
- Text selection → annotation: long-press/stylus drag + word-snap → `AnnotationPopup` (8 tools: highlight, underline, doubleUnderline, strikethrough, wavyUnderline, bookmark, comment, inkAnnotation)
- `HighlightPainter`: dotted underline (highlight), solid underline/double/strikethrough, margin icons (`ToolIconRenderer`)
- `AnnotationPopup`: floating tool picker + copy/share + locked-tool mode (`LockSlotView`) + undo pill
- `AnnotationsPanelActivity`: filter chips, sectioned + paginated list, swipe-to-next-page, edit mode (checkboxes, delete, select-all); ink thumbnail display + tap-to-edit
- `NoteActivity`: full annotation editor — tool selector, quote box, ink button (launches `InkNoteActivity`), note field, tag chips, save/update
- `InkNoteActivity` + `InkCanvasView`: drawPath hardware overlay, 3 tools (THIN/THICK/ERASER where ERASER = lasso-circle-erase), rule lines, save PNG + stroke JSON; real-time per-MOVE invalidate; clears drawPath on exit
- `SearchActivity`: full-text search with page-jump
- `FileBrowserActivity`: split-view (recents top 1/3, browser bottom 2/3), folder icon, breadcrumb multi-column popup
- `PageJumpOverlay`: scrub track with preview text, bookmark markers
- `DrawPathClient`: binder wrapper, lasso (penType=4) for selection feedback, disable chrome band (flag=0), clearScreen (code 6); circle gesture = draw circle over text → text selected → popup appears
- `LeamhDialog`: confirm dialog with "don't ask again" support
- Overflow menu: font size, line spacing, columns, nav side, RTL, font family, flatten ink
- `RattaEink`: `EinkManager` reflection wrapper (`sendOneFullFrame`, `screenRefresh`)
- App icon: mipmap PNGs from `layuv.icon` SVG
- Bug fix: comment tool wasn't saving — `commitAnnotationFromPanel` missing optimistic update; fixed 2026-06-21
- Bug fix: ink annotation thumbnails in panel + tap-to-open-ink-editor; fixed 2026-06-21
- **Pen-optimised annotation toolbar (2026-06-26):** `AnnotationPopup.show/showActions` accept `penMode` — 48dp buttons / 20dp icons for stylus, 64dp / 28dp for finger. `ReaderView.lastSelectionWasPen` tracks input device at `finaliseSelection()` and `onAnnotationTapped`; `ReaderActivity` passes it through.
- **"Tap outside: off" hardened (2026-06-26):** stylus slop raised 3× (24dp) when pref=off + committed selection — pen wobble no longer starts a new drag and accidentally cancels selection. Stylus taps now always dismiss (pen is always intentional); only finger/palm taps are gated by the pref.

---

## Device Testing

### Running on macOS (Swift app)
```bash
DEVELOPER_DIR=/Applications/Xcode-beta.app/Contents/Developer xcodebuild \
  -project macos_native/LeamhApp/LeamhApp.xcodeproj \
  -scheme LeamhApp -configuration Debug -sdk macosx build
# Or open macos_native/LeamhApp/LeamhApp.xcodeproj in Xcode and run
```

### Running on Supernote Nomad/Manta (sideload)

**Step 1** — Enable sideloading: Settings → Privacy/Security → "Allow Installation from Unknown Sources"

**Step 2** — Build debug APK:
```bash
cd android_native && ./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

**Step 3** — Install via ADB (USB or WiFi) or copy APK to device and tap to install:
```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Step 4** — Logs via WiFi ADB:
```bash
~/Library/Android/sdk/platform-tools/adb connect <device-ip>:5555
~/Library/Android/sdk/platform-tools/adb logcat -s LeamhActivity LeamhAnnotPanel InkNoteActivity
```

**Supernote constraints:** greyscale only; no Play Services; 2-col off on Nomad (auto-2-col threshold ≥ 1200dp); drawPath requires hidden-API policy 0 on test builds

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
