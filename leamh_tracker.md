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

**Native Android port (`android_native/`) — THE active product:** Native Kotlin app for Supernote Nomad/Manta. Flutter codebase archived to `archive/flutter/` 2026-06-17 — Flutter's compositor fought e-ink partial refresh. The pure-JVM `docx/` engine (parse, anchor, read incl. native-format + comment import fallback, full write-back) is complete and golden-tested (`cd android_native && ./gradlew :docx:test`). Engine is the authority — goldens are generated from correct native output, not Dart. Reader uses one clean canonical plain-text string for both render and anchor, drawn by `StaticLayout` over a `LAYER_TYPE_SOFTWARE` `View` owning the EPD waveforms (not Compose). See `android_native/README.md` and CLAUDE.md.

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

### Code health — large-file review (leanness + bugs + corruption pathways)
Several reader/store files have grown past ~1000 lines. Review each for cleaner/leaner
structure, bugs, and (especially) DOCX corruption / data-loss pathways. **Do this ONE
FILE AT A TIME** — the multi-agent review fans out many Opus agents and the cache-write
cost adds up fast; reviewing per-file keeps usage budgeted (run, read the report, decide,
then move on).
- [x] `lib/reader_screen.dart` (~1046 lines) — reviewed 2026-06-16 (multi-agent: map → 5-lens find → dedup → 3-lens adversarial verify)
  - **Fixed 2026-06-16 (analyze clean):** corruption seam — list-panel open now flushes pending saves via `_toggleAnnotationsPanel` (mirrors `_openAnnotationPanel`); e-ink panel double-push guard (`_annotationsRouteOpen`); `_emphasisTimer` mounted guard; leanness — removed dead `kDebugForceReaderView` + vestigial `_modeSetByUser`, de-duped `AnnotationPanel` construction, centralized the 5 `EinkPen.refresh` post-frame idioms into `_refreshEinkAfterFrame()`, moved `_saveDebounce` cancel up in `dispose`, fixed false "identical constructor" comment.
  - **Presentation extraction done 2026-06-16 (analyze clean):** pulled pure-widget chrome out of `reader_screen.dart` (1054 → 915 lines) — dialogs to `lib/reader/reader_dialogs.dart` (`DeleteAnnotationDialog`, `CloseDocumentDialog`, `SavingDialog`); title/bottom-bar to `lib/reader/reader_chrome.dart` (`ReaderTitleText`, `ReaderBottomBar`). Behavior-preserving; the don't-ask-again pref write + saving-flow flush stay in `_ReaderScreenState`.
  - **File-breakup decision:** NOT splitting further for now. The remaining size is the `_ReaderScreenState` god-object (save lifecycle + overlays + annotation CRUD + reader-mode switch). The high-value move is decoupling a `SaveCoordinator` + `OverlayController` — but that is HIGH risk (touches the documented dual-write corruption seam + golden-tested `_writeAllAnnotations`), so defer until there's a concrete reason to touch the save path (e.g. the macOS atomic-write fix, or a `saveAll` merge-by-id change). Splitting it into files for its own sake buys little and risks a lot.
  - **Still open from the review:** (corruption, medium) macOS/iOS `safeWriteBytes` is non-atomic — crash mid-write can truncate the working DOCX; needs a directory-scoped security-scoped bookmark + same-dir temp+rename (`platform_utils.dart:27-28`; not a reader_screen change). (low) lifecycle background flush is fire-and-forget vs OS suspension. (deferred, high effort/risk) the `SaveCoordinator`/`OverlayController` decoupling above.
- [x] `lib/reader/reader_view.dart` (~1898 lines) — reviewed 2026-06-16 (hybrid: Opus finders + Sonnet verify, high-sev escalated to Opus; run hit the session limit mid-verify, resumed from the journal). 5 confirmed bugs, 7 refuted, 9 leanness recs.
  - **Confirmed bugs (none write the store — reader_view raises callbacks only):** (med, was high) `charAtPoint` gutter / right-edge taps map to the wrong char — no cx bounds + gutter not modeled (`:311-326`); fix = reject out-of-bounds + gutter taps, clamp localX. (med) Material `Slider` in the jump overlay is reachable on e-ink, violates the no-animation gate (`:1830-1897`); gate behind `!isEink`, keep the chevrons. (low) page-counter tap target <48dp (`:1807-1828`). (low) annotation position is a lossy start-fraction → panel-jump emphasis can mark the WRONG annotation on long docs (`:1472-1474` + reader_screen `:692-694`); match by id or tighten tolerance to ~2/len. (low, borderline 2/3) `_extendScrubTo` mutates `_selStart/_selEnd` outside setState (`:1409-1426`).
  - **Refuted by verify (7):** programmatic-jump-no-refresh, saved-position rounding, stale-page snap-back, col-0 margin overdraw, oversized-line clip, re-pagination formatSpans key, first-covering-span-only — all traced and cleared.
  - **Leanness (all vetted safe):** BIG WINS — extract `PageLayout` → `lib/reader/page_layout.dart` (~450 lines) and `ReaderPainter` → `lib/reader/reader_painter.dart` (~450 lines); together ~halve the file, zero behavior change. Quick: remove dead SELCOMMIT debug timing (`:1442,1452-1455`); nav-strip draw helper (`:945-978`); collapse 4 near-identical span-rect loops into one helper (`:730-868`, medium). Deferred/riskier: extract the pointer/selection state machine into a mixin/controller (`:1390-1665`).
  - **Fixed 2026-06-16 (analyze clean):** `charAtPoint` bounds + gutter reject + localX clamp (bug #1); `Slider` gated behind `!isEink`, chevrons kept (bug #2); page-counter 48dp hit region (bug #5); removed dead SELCOMMIT timing (leanness #1); documented the intentional out-of-setState scrub write (leanness #2, the safe treatment of borderline bug #4).
  - **Still deferred:** bug — lossy-fraction panel-jump emphasis can mark the wrong annotation on long docs (`reader_view.dart:1472-1474` + `reader_screen.dart:692-694`); proper fix is match-by-id, which needs an `onJumpTo` interface change (low severity, left for a focused pass). Leanness — the two BIG extractions (`PageLayout` → page_layout.dart, `ReaderPainter` → reader_painter.dart; ~900 lines, ~halve the file) + span-rect loop helper + nav-strip helper + pointer/selection mixin.
- [ ] `lib/models/docx_store.dart` (~1419 lines) — KEY FILE; deepest data-integrity pass (write-archive crash window, comments.xml/OOXML round-trip, _serialized coverage, never-throw rule). Conservative cleanup only.
- [ ] `lib/reader/annotations_panel.dart` (~1006 lines) — list panel; direct-write-then-reload vs reader's in-memory list
- [ ] `lib/reader/page_flip_reader.dart` (~988 lines) — verify against the documented sizing contract; do NOT implement the deferred column-width race fix
- [ ] `lib/reader/annotation_toolbar.dart` (~641 lines) — moderate; lower priority

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

**Still open (7 remaining):**
- [ ] `splitRunAt` drops non-`<w:t>` children (tab/br/field) on split (low, `RunPropertyInjector.kt:289-310`)
- [ ] `RunPropertyInjector` O(annotations×docSize) — per-annotation `PlainTextMapper.build` + full `findAll` re-scans (medium perf, `RunPropertyInjector.kt:86-195`)
- [ ] onPause position write can be lost on process death; no `onSaveInstanceState` fallback (medium)
- [ ] Reads bypass write queue → stale display on concurrent load/write (low)
- [ ] `savingPosition` flag threading — not reset on error in all paths (low)
- [ ] Static `@Volatile` cross-Activity handoff (`NoteActivity`/`InkNoteActivity` `pendingResult`/`pendingLaunch`) — unsynchronized global; fix = FileProvider URI or temp file (low)
- [ ] `DocxArchive.write` re-deflates all entries (drops ZipEntry compression metadata) — low perf impact
- **Run 2 (pending):** app reader UI in depth — ReaderView, Paginator, HighlightPainter, selection, AnnotationsPanel/Search/popups, EPD/Epd/EinkClient.

### Kotlin golden generator rewrite — eliminate Flutter/Dart dependency
**Status:** blocked/broken as of 2026-06-17 Flutter archive.

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

**Immediate workaround (until rewrite):** to regenerate goldens now, run:
```bash
cd archive/flutter
dart run ../../android_native/tools/golden_gen/gen_engine_goldens.dart
# (requires Flutter SDK and `dart pub get` in archive/flutter/)
```

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
- **dart:ui Paragraph reader (`ReaderView`)** — complete, golden on Manta (Jun 2026). One whole-book `Paragraph` laid out once, sliced into columns by line ranges via clip+translate (`drawParagraph`); cross-column selection; pointer-state-machine selection (scrub + long-press); grab handles; margin indicators; `RepaintBoundary` splits base (text) from overlay (decorations) so a highlight change never re-runs `drawParagraph`. `EinkPen.configureLasso()` called at `initState` — drawPath penType 4 (dotted lasso) provides hardware selection-stroke feedback during any drag. `kDebugForceReaderView = true` in `reader_screen.dart` (reset to `false` before ship).
- **Circle gesture → toolbar model** (Jun 2026): both linear scrub AND circle over text → text selected → annotation toolbar appears (circle-to-confirm bypass removed). `CircleTappable` (`lib/utils/circle_tappable.dart`) — `Listener`-based widget (below gesture arena); fires `onTap` if pen-up is within 90dp of pen-down AND press < `kLongPressTimeout`; pointer capture means pen-up reaches the widget even when the circle exits its visual bounds mid-stroke. `ToolButton` on e-ink uses `CircleTappable` + `GestureDetector(onLongPressStart only)`; toolbar buttons are 64×64dp, toolbar 80dp tall. `_dismissToolbar` calls `EinkPen.clearInk()` on e-ink to clear any lasso strokes drawn during a circle-over-icon gesture.
- **RE dead ends confirmed (Jun 2026):** `setWalcomEmrInfo` (drawPath code 9) — probed values 0/1/50/100 on Manta, no pen-up delay difference; pen-up early-commit (pressure < 0.03f) remains the workaround. `EinkPwInternalY.postRectForPw` — operates on the drawPath PW hardware overlay layer only, not the app View layer; `view.invalidate()` / auto-EPD remains the correct path for app UI refreshes.

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
