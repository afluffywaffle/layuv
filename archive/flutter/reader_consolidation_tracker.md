# Reader consolidation — working tracker (temporary)

**Status: DIRECTION SHIFT (2026-06-12) — see the section below. The pen-gesture
toolbar experiment (Slices 1–6) is being WOUND DOWN in favour of traditional
Flutter. Reader stays on Flutter; drawPath is being pulled OUT of annotating and
reserved for real ink notes. All Onyx code removed from the Flutter app.**
Last updated: 2026-06-12.

This is a scratch tracker for the effort to consolidate the reader on Flutter
and decide how the new `dart:ui` reader fits across platforms. Fold into
`leamh_tracker.md` once decisions land, or delete.

---

## DIRECTION SHIFT (2026-06-12) — read this first

After driving the pen-gesture toolbar on the Manta, the verdict from the user:
**everything's there except speed; the gesture toolbar fights the platform and
feels slow/flaky. Go traditional.** Decisions:

1. **Reader stays on FLUTTER.** It's the furthest along and looks right; the
   native Kotlin reader's toolbar is the dealbreaker there. Refinement, not a
   rewrite. (The `android_native/` port stays reference-only; may be archived.)
2. **Pull drawPath OUT of annotating.** Selection + annotation go fully
   traditional Flutter (painted selection band, tap toolbar). The OS held pen-up
   is the OS (`setStylusGuesture`), independent of drawPath — so dropping drawPath
   doesn't remove the held-up, but it DOES delete the per-mark `clearScreen` +
   the whole gesture-dwell path, which is most of the felt lag.
3. **Reserve drawPath for real INK NOTES** (Supernote low-latency handwriting) —
   its proper job, and the replacement for the (now-removed) Onyx `InkActivity`.
4. **Top priority: brisk locked-tool marking** (scrub-select → auto-apply →
   repeat). Per-mark latency = commit dwell + store-write + EPD full refresh (no
   regional refresh API on Ratta). Measure which dominates before optimizing.
5. **All Onyx/Boox code REMOVED from the Flutter app** (2026-06-12, verified:
   `flutter analyze` clean, `:app:compileDebugKotlin` BUILD SUCCESSFUL): deleted
   `InkActivity`/`InkCanvasView`, slimmed `MainActivity` to the Ratta `drawpath`
   + `eink_spike` channels, dropped `onyxsdk-device` + `repo.boox.com`, ink button
   now iOS-PencilKit only, deleted `ink_spike_screen`. `android_native/` Onyx left
   in place with a removal TODO in its README (load-bearing there: `reader/Epd.kt`
   → swap to `spike/EinkClient`).

**Kept from the gesture session (orthogonal, still good):** EPD refresh after a
pen-applied annotation (`EinkPen.refresh` in `_reloadAnnotations`); the stylus
selection dwell-commit (`ReaderView._selDwellTimer` — held-up is unavoidable);
`PenTappable` (pen-down-dwell tap, piloted on the pill + e-ink settings);
the toolbar-capture supersede fix.

**Stage 1 DONE (2026-06-12, coded + `flutter analyze` clean, NOT yet device-tested):**
strip it down → plain tap tool-selection.
- `annotation_toolbar.dart`: deleted the whole circle/scribble/X system — the
  opaque pointer-capture `Listener` + 64px margin ring, the path classifier
  (`_finalizeGesture`/`_applyNearest`/`_norm`/`_selfCrosses`/`_segmentsCross`,
  dwell/reversal/rotation thresholds), and the "circle to pick…" hint. The
  toolbar is now a `Row` of `_ToolbarButton`s. Each: **tap = apply once**
  (finger/mouse tap-on-release; a Supernote pen fires on a 90ms pen-down dwell,
  the `PenTappable` model, since the OS holds the pen-UP); **finger/mouse
  long-press = the apply-once / lock picker** (`LockPickerOverlay`, kept). The
  pen does NOT long-press/lock — locking is a finger action (pen = selection,
  finger = chrome). `passThrough`/`dismissOnTapOutside` barrier behaviour
  preserved. `ToolButton`/`LockPickerOverlay`/`_PickerRow` unchanged
  (`ToolButton` still used by `annotation_panel.dart`). Dropped `dart:math` +
  `eink_pen` imports.
- `reader_view.dart`: removed all four selection-path EinkPen calls
  (`configureLasso` in initState; `clearInk` on commit / cancel / handle-up) +
  the `eink_pen` import. The hardware dotted lasso is gone; the Flutter-painted
  dotted selection band (`ReaderPainter._paintSelection`) + the stylus
  dwell-commit (`_selDwellTimer`) stay. `EinkPen` (configureLasso/clearInk/
  fullClear) is now unused by the reader but left in `eink_pen.dart` for the
  future ink-notes work; `EinkPen.refresh` still used in `reader_screen.
  _reloadAnnotations`.

**NEXT (planned, not yet done):**
- Stage 2 — make marking brisk: instrument the locked-mark path (commit dwell vs
  `DocxStore.saveAnnotation` write vs `_reloadAnnotations` reload vs the
  `EinkPen.refresh` full-frame EPD refresh — measure ON-DEVICE first), cut the
  dominant cost (debounce/batch store write, one coalesced refresh, tighten
  commit); region-limit the painted-band repaint.
- pen-tap-to-open/edit-annotation in `ReaderView` (was finger-only + held-up).
- `PenTappable` sweep across panels/dialogs/jump UI + pen-friendly popup menus.

**Device note:** the `flutter attach`/`run` session has dropped repeatedly
(USB/sleep/Vulkan contention). Onyx removal was verified by Kotlin compile (JDK
21 = Android Studio JBR; system default JDK 26 fails the AGP jlink transform).

---

## Context / goal

- The e-ink spike passed (2026-06-10) → decision to consolidate on Flutter (one
  codebase for macOS/iOS/Supernote) and treat the native Kotlin port in
  `android_native/` as reference only.
- Task done this session: port the native Kotlin reader core to a new Flutter
  widget so the page reader uses ONE `dart:ui.Paragraph` (custom-painted),
  fixing two bugs in the old `SelectableText`-per-column `PageFlipReader`:
  line clipping at column/page boundaries, and no cross-column selection.

## What is built (done)

- **`lib/reader/reader_view.dart`** — new widget, three parts:
  - `PageLayout` — one whole-book `ui.Paragraph` laid out once at column width,
    sliced into columns by line ranges. Reconstructs all of Android
    StaticLayout's missing line APIs from `computeLineMetrics` /
    `getLineNumberAt` / `getLineBoundary` / `getBoxesForRange` /
    `getPositionForOffset` (verified against Flutter 3.44.1 `dart:ui` source).
    Column packing includes a line only if its bottom fits (the clipping fix).
  - `ReaderPainter` — clip+translate `drawParagraph` per column, highlights,
    selection, handles, margin glyphs, nav chevrons.
  - `ReaderView` — drop-in constructor identical to `PageFlipReader`; raw-pointer
    gesture machine ported from native `onTouchEvent`.
- **Wire-in** (`lib/reader_screen.dart`, pageFlip case): `ReaderView` on e-ink,
  `PageFlipReader` (SelectableText) on desktop. Currently overridden by a debug
  flag (see reminder).
- `flutter analyze` clean. Adversarial review workflow ran: 6 findings (all
  med/low) fixed (pen-vs-finger tap, long-press timer cancel, strikethrough
  `unscaledAscent`, emphasized margin size, no `onPositionChanged` on open).

## Decisions already made

- Line pitch uses `kReaderTextStyle.height` (1.85 e-ink / 1.6) via a
  forceStrutHeight strut — not the native 1.32.
- Annotation visuals are painter-drawn from `findAnnotationOffset`-resolved
  ranges, NOT baked into the Paragraph → annotation edits repaint without
  re-paginating.
- Position save/restore is fraction-based (char offset), resize-robust.
- Shared across all renderers regardless of choice: DOCX engine, annotations in
  `comments.xml`, panels, reading-position model, fonts/colors.

## Open decisions (the pause points)

1. **Reader strategy: platform-adaptive vs universal `ReaderView`.**
   - The two bugs (clipping, no cross-column selection) are ARCHITECTURAL to the
     per-column-`SelectableText` model — they affect mac/iOS too, not just
     Supernote. The single-Paragraph model fixes both by construction.
   - Several "keep SelectableText for native UX" cons are weaker than they look:
     the app already suppresses the native context menu (custom toolbar), and
     iOS Scribble lives in the note `TextField`, not the reader.
   - Leaning toward **universal `ReaderView` for the page/two-column mode**,
     pending the perf measurement in #2.
   - **HARD CONSTRAINT (user):** keep `ScrollReader` (continuous scroll) and
     `ScreenFlipReader` as selectable modes on mac/iOS — their displays allow it.
     `ReaderView` replaces ONLY the page-flip / two-column path, not all reading.
   - If universal, mac/iOS gaps to close: mouse/trackpad selection (double-click
     word, click-drag, shift-extend), `Semantics`/accessibility (VoiceOver — a
     `CustomPaint` is opaque to screen readers; the must-do), optional iOS
     magnifier loupe.

2. **Large-document performance of `ReaderView` (OPEN — needs measurement).**
   - Old `PageFlipReader` is WINDOWED: lays out only ~2× a page (≤80k chars),
     bounded regardless of doc size — that's why it survived the stress test.
   - `ReaderView` lays out ONE whole-book Paragraph, resident for the session:
     - Memory: O(whole doc) resident (shaped paragraph + per-line metrics +
       line-range cache). Novel-length manuscripts could be tens of MB.
     - Open/resize latency: full-document shaping + `computeLineMetrics` once per
       (re)paginate, on the MAIN isolate (Paragraph can't cross isolates) →
       possible hitch on open and each resize.
     - Per-frame paint: heavier but Skia culls to the clip; page turns cheaper
       than old reader (no re-layout).
   - Front-loaded cost (big one-time + resident) vs old amortized windowing.
   - TODO: run the SAME stress doc through `ReaderView`, compare DevTools peak
     memory + time-to-first-page + frame timings during resize/selection drag.
   - Caveat to verify: does `ScrollReader` already lay out the whole doc in one
     `SelectableText`? If so, scroll mode already pays similar cost today.
   - Mitigation if needed: SEGMENT the doc into multiple Paragraphs (per chapter
     / per N-thousand chars), keep only nearby segments resident (windowed
     Paragraph). Keeps clean column/selection within a segment; boundaries rare.

3. **Paragraph-atomic pagination (NEW idea — don't split a paragraph across a
   column/page).**
   - Rationale (user): a paragraph carries a concept; an editor often wants to
     annotate a whole paragraph, so a paragraph shouldn't be cut mid-way by a
     column/page break.
   - Synergy: if paragraphs are never split, whole-paragraph annotation never
     needs to cross a column/page break → largely dissolves the cross-column
     selection problem for the common case. Also: every column/page starts at a
     paragraph boundary (no mid-sentence column tops) — a readability win.
   - Cost: wasted vertical whitespace at column bottoms when a paragraph won't
     fit → more pages, esp. dense two-column prose (classic keep-together vs
     density typesetting tradeoff).
   - Must-have fallback: a paragraph taller than a full column MUST split (line
     packing for that one); cross-column selection (already supported by the
     single-Paragraph model) covers that rare case.
   - Implementation: localized to `PageLayout` — a paragraph-aware variant of
     `_computeColumnStarts` (split content on `\n` → map each paragraph's char
     range to its line range via `lineForChar` → pack WHOLE paragraphs into
     columns, oversized → split). Much easier in the single-Paragraph model than
     in per-column SelectableText.
   - Sub-decision (DECIDED 2026-06-11): heuristic, NOT strict keep-together.
     Threshold X = max bottom-of-column whitespace tolerated to keep a paragraph
     whole; keep whole only if the gap ≤ X%, else split. User wants conservative
     space usage — willing to break paragraphs to avoid obvious wasted space, so
     X is LOW (start ~10%, calibrate visually on real manuscripts; X must be
     >0 or it degenerates to current line-packing). Paragraphs taller than a
     full column split regardless (cross-split selection still works).
   - Bonus this enables: "select whole paragraph" affordance (paragraphs become
     atomic units).

## On-device findings (2026-06-11, Supernote Manta, profile build)

First look at `ReaderView` on the Manta (profile APK, Impeller/Vulkan backend),
large stress doc `leamh_large_doc_test` → 118 pages. Rendering looks correct:
body/bold/heading, dotted-underline annotation, nav chevrons, page counter,
bottomTrailing. Rendered SINGLE column (verify two_column pref). Page-bottom cut
is on a full line (clipping fix holds) but mid-paragraph (the baseline the
paragraph-atomic change targets).

Issues observed (these are e-ink REFRESH-OWNERSHIP gaps, NOT reader-render bugs;
ReaderView issues no EPD calls yet):
- **OS pen overlay**: `D/EinkManager setStylusGuesture: enable=true` is the
  Supernote OS auto-engaging its low-latency pen layer on stylus input (NOT app
  code — MainActivity only registers pull-based channels). It hardware-draws the
  pen path (the "fast native line") and ReaderView reads the pointers underneath
  to scrub-select.
- **Stroke ghost artifacts**: the OS strokes are never cleared + no EPD refresh
  fires → ghosting. Needs a clear/refresh (matches [[drawpath_lowlatency_ink]]
  code6 clearScreen / [[eink_epd_refresh]] fullRefresh/screenRefresh). ReaderView
  calls none.
- **Selection highlight (and handles) vanish when the toolbar shows** — diagnosis
  CORRECTED: NOT an EPD/visibility issue (the band renders & refreshes fine under
  Impeller, persists until cleared). It's a STATE bug: `_showToolbarOverlay` calls
  `_dismissToolbar()` defensively before inserting (reader_screen.dart:311), and
  `_dismissToolbar` bumps `_cancelSelectionNotifier` (line 277); ReaderView's
  `_onCancelSelection` treats that as "clear selection", wiping `_selStart/_selEnd`
  (and thus the handles, same gate) ~300ms after lift. PageFlipReader is immune
  (native selection isn't tied to the notifier). Semantic mismatch I introduced
  (I made the notifier = "clear"; the screen uses it = "a toolbar was torn down",
  fired both on real dismiss AND defensively before showing).
  FIX (decided, screen-side): the pre-show `_dismissToolbar()` must NOT bump the
  notifier — only genuine dismissals (tap-outside / tool-picked / lock-tool)
  should. Restores BOTH highlight and grab handles for touch AND stylus; safe for
  PageFlipReader (just skips cancelling an already-fired debounce). Handles are
  already implemented (hit-test + drag fine-tune) — no extra work.
- **logcat**: recurring `W/libEGL EGLNativeWindowType disconnect failed` +
  `E/Surface freeAllBuffers: buffers freed while being dequeued` during
  interaction (eink/pen layer vs Flutter Vulkan surface contention — watch).
  `Skipped 40-43 frames` + `Davey 755ms` DURING selection = per-pointer-move
  repaint of the whole 118-page paragraph is heavy (ties to perf #2; throttle /
  region-limit the repaint). Load stall ~4.7s still mostly the full-doc XML
  debugPrint.

Second look (2026-06-11 cont.), clarifications & a design decision:

- **Pen line + artifacts are APP-WIDE, not reader-only.** Every stylus
  interaction draws the OS ink and leaves ghosts — confirmed on the quick-jump
  SCRUBBER (drag worked, but a line + artifacts appeared) and applies to the
  toolbar and handle-drags too. A forced full refresh (Manta sidebar gesture)
  wipes them cleanly → they're just uncleared panel content. Needed regardless of
  the design decision: (a) EPD clear/refresh after a stylus interaction settles;
  (b) SUPPRESS the ink over UI controls (scrubber/toolbar/handles/buttons/nav —
  pen is a pointer there, not a brush). OPEN: can we scope/disable the *system*
  `setStylusGuesture` layer per-region (vs the app's own drawPath layer)? Device
  experiment vs `DrawPathClient` disable / `setWritableAreas`.

- **DESIGN DECISION (open, user leaning toward keeping the line): during-drag
  selection feedback — hardware pen line vs our own live highlight.**
  - Cross-column selection ALREADY works: selection is a char range (scrub
    min→max), and text flows col1→col2, so dragging pen from col1 into col2 grows
    the range across the break; highlight renders in both columns.
  - The apparent line-vs-columns conflict is purely representational: a freeform
    stroke can't REPRESENT a column-spanning range. Resolved in EITHER option by
    making the line TRANSIENT-only (drawn during drag, cleared on lift) and never
    the selection record; the committed selection is always the precise text
    highlight + handles.
  - Tradeoff: (A) keep hardware pen line = feels fresh because hardware-fast;
    price = clear-on-lift + suppress-over-UI + verify system-layer scoping. (B)
    drop it, make our own live highlight responsive (bolder band that grows while
    scrubbing + partial EPD refresh) = clean/precise/no-bleed but only as fast as
    our repaint+refresh.
  - Claude's lean: (B) first (kills artifacts/bleed/ambiguity, keeps model
    honest); fall back to a transient text-only pen line if (B) feels laggy
    on-device. User to decide; "feels fresh" is a real value.

- Single column CONFIRMED expected (persisted from prior session); two-column
  toggled on and looks good. No column bug.

Implied future work (the "EinkManager plugin / ink ownership" next-step): wire
ReaderView to the EPD layer — partial refresh of the selection band on commit +
a bolder active-selection treatment; clear/suppress the OS pen strokes per the
decision above; throttle/region-limit the per-move repaint (the 40-43 dropped
frames during selection on the 118-page doc). (ink_channel.dart already has
partial-refresh plumbing; RattaEinkSpike has fullRefresh/screenRefresh.)

## On-device findings (2026-06-11 cont. — Slice 5 deploy)

Slice 5 deployed to the Manta (profile). Validated: the option ON gives the
one-motion re-select (draw a new lasso while the toolbar is up → new selection +
toolbar swaps); buttons still tap; tap-outside still dismisses; OFF restores
today's behavior. `EinkPen.configureLasso()` confirmed on-device (`reset[c1]`,
`pen[c2]`, `disable(1920x0)[c1]` all ok; `setStylusGuesture: enable=true`).

- **CONFIRMED PRE-EXISTING (independent of Slice 5): tapping a toolbar button
  with the PEN defers the action until the next pen tap/interaction.** Isolated
  with the user: it defers in BOTH option states (ON and OFF), and only with the
  STYLUS (not finger). So it is NOT the translucent pass-through barrier — it is
  the OS stylus-gesture overlay (`setStylusGuesture: enable=true`, logged on every
  pen touch) intercepting/holding the pen-UP over Flutter UI, so the button's tap
  never resolves in Flutter's gesture arena until the next pointer event flushes
  it. Matches the existing open follow-up ("SUPPRESS the pen over UI controls" /
  "can we scope/disable the system setStylusGuesture layer per-region?").
  - Candidate fix (untested): when the toolbar/handles/scrubber/nav are shown,
    register their screen rects as drawPath NON-WRITABLE (`setWritableAndNon
    WritableArea`, flag 0 — the blacklist that "protects the toolbar", see
    [[drawpath-lowlatency-ink]]) so the system pen layer treats them as pointer
    regions, not brush regions. Needs a new EinkPen method + native channel
    support + device iteration. Verify it actually fixes EVENT delivery (not just
    ink suppression — they may be separate layers).
  - Workaround today: operate the toolbar / UI chrome with FINGER (consistent with
    the design split — pen = selection tool, finger = UI/annotation taps).

- **Slice 6 (IN PROGRESS, on device for tuning): pen-circle tool selection.**
  Decision (user): pick a toolbar tool with the PEN by *circling* it — organic,
  intent-bearing, and (crucially) read from the live pen MOVE stream so it never
  waits on the OS-held pen-UP. ONE loop = apply once; TWO continuous loops = lock
  that tool. All in `annotation_toolbar.dart`:
  - An OPAQUE pointer-capture `Listener` over the toolbar strip + a 64px margin
    ring sits above the (now display-only) icon row. Opaque so a circle never
    leaks to the reader underneath (would otherwise scrub-select through the
    translucent pass-through barrier). Gesture math uses GLOBAL coords.
  - Stylus path is collected from moves; finalised on a 250ms DWELL (pen stops),
    not on pen-up. At finalise: bbox center = pivot; sum of per-step angle deltas
    → |rotation|; `>= ~288°` = a circle (else ignored), `>= ~612°` = two loops.
    Tool = button whose center-x is nearest the circle center. comment is not
    lockable (double-loop on it just applies once). `EinkPen.clearInk()` flushes
    the OS lasso ink on finalise.
  - FINGER still works via the same capture layer: tap a button = apply once;
    long-press = the apply-once/lock picker (`LockPickerOverlay`).
  - Discoverability: a small "circle a tool · twice to lock" caption under the bar.
  - `ToolButton` kept (no longer used by the floating toolbar, but still the tool
    selector in `annotation_panel.dart`).
  - TUNING KNOBS (consts in `_AnnotationToolbarState`): `_captureMargin` (64),
    `_dwell` (250ms), `_loopOnce` (1.6π), `_loopLock` (3.4π), `_minCircleExtent`
    (22), `_minCirclePoints` (8), `_tapSlop` (12). Expect on-device tuning.
  - Iterating in a DEBUG `flutter run` (profile is AOT = no hot reload); hot
    reload via SIGUSR1 to the run process. Final pass should re-verify in profile.

## Experiment: dotted-lasso selection via drawPath penType 4 — VALIDATED (2026-06-11)

On-device experiment (`lib/spike_ink.dart`, penType sweep + text/checkbox ghost
test) to validate path 2 (own the low-latency ink with a dotted pen). RESULT:
feasible — strong win. Findings folded into memory [[drawpath_lowlatency_ink]].
- penType MAP: 3=eraser, **4=native dotted lasso**, 15=thin, 17=marker, 6-14 empty.
- penType 4 = fast HARDWARE dotted stroke = the selection affordance we want
  (dotted reads as "selecting", not permanent underline). SINGLE clean stroke
  (drawPath fully owns it — no system solid companion, no doubling), auto-clears
  on lift.
- Ghosting: NONE at selection stroke counts over text / graphics / white. Only
  heavy in-place scribbling accumulates ghosts (not a selection scenario); device
  auto-regional-refreshes ~every 8 in-place strokes (driver-level, not logged).
- REFRESH gap RESOLVED (2026-06-11 follow-up): `sendOneFullFrame()` is SOFT (won't
  clear heavy ghosts). **App-driven true full-clear (gesture-bar equiv) =
  `clearScreen`(drawPath code 6) → `setScreenMode(CLEAR/0)` → `screenRefresh(
  force=true, mode=1)`**, in that order. Mode constants: CLEAR==DEFAULT==0,
  SMOOTH==1, SPEED==2. `screenRefresh` alone can't remove drawPath ink (it redraws
  its buffer — only code-6 clearScreen flushes it). GOTCHA: SMOOTH/SPEED suppress
  auto-refresh (panel freezes) — never park there. Two layers cleared
  independently: drawPath hardware ink (clearScreen) vs Flutter-painted
  highlight/strokes (state-controlled, no EPD refresh touches them).
- DECISION RESOLVED (the lasso A/B in the design-decision above): **keep the
  hardware lasso, configured as penType 4 (DOTTED).** Integration: on drag-start
  configure drawPath penType 4 + writable-area=text → hardware dotted lasso → on
  commit clearScreen(code6) + refresh + committed dotted-underline highlight +
  handles. Pairs with paragraph-atomic pagination to minimize cross-gutter lassos.

## Build slices — status (2026-06-11)

- Slice 1 DONE+VALIDATED: selection-toolbar fix. `_dismissToolbar({bool
  cancelSelection=true})`; the pre-show call in `_showToolbarOverlay` passes
  `cancelSelection:false` so showing the toolbar no longer wipes the selection.
  Highlight + handles now persist while the toolbar is up. (reader_screen.dart)
- Slice 2 DONE: `lib/utils/eink_pen.dart` — wraps the drawpath/eink channels:
  `configureLasso()` (penType 4), `clearInk()` (code 6 flush), `fullClear()`
  (clearScreen→setScreenMode(CLEAR/0)→screenRefresh(force,1)). No-op off e-ink.
- Slice 3 DONE+VALIDATED: ReaderView calls `EinkPen.configureLasso()` in initState
  and `EinkPen.clearInk()` on selection commit / handle-adjust / cancel. The
  stylus selection drag now draws the hardware dotted lasso; commit leaves the
  Flutter dotted-underline highlight + handles. (lib/reader/reader_view.dart)
- Slice 4 DONE (coded, analyze-clean, NOT yet on device): paragraph-atomic
  pagination in `PageLayout._computeColumnStarts` — paragraph boundaries from
  `LineMetrics.hardBreak`; push a cut paragraph whole to the next column only if
  the gap ≤ `_kKeepWholeMaxGap` (0.10), else split; oversized paragraph splits.
- Slice 5 DONE (coded, analyze-clean, NOT yet on device): "start selecting over
  the toolbar" option (the "Next task" below). New pref `start_selecting_with_
  toolbar` (default false = today's behavior), plumbed in reader_screen.dart and
  surfaced in eink_settings_screen.dart under a new "SELECTION" section. THREE
  pieces (all gated on the pref):
  1. Pass-through barrier — `AnnotationToolbar` gains `passThrough`; when on, the
     full-screen tap-outside barrier is `HitTestBehavior.translucent` (not
     opaque), so a drag reaches the `ReaderView` Listener underneath and starts a
     new selection. Buttons sit above the barrier and still absorb their taps;
     tap-outside dismiss still honors `dismissOnTapOutside`.
  2. Suppress-window bypass — `_onSelection` (and its 300ms debounce) skip the
     post-show `_suppressToolbarUntil` window when the pref is on, so a genuine
     fast re-selection always shows its toolbar.
  3. Auto-dismiss the stale toolbar — `ReaderView` fires a new `onSelectionStart`
     callback when a NEW selection drag begins (stylus scrub past slop / finger
     long-press); reader_screen tears down the current toolbar with
     `cancelSelection:false` (so the forming selection is NOT cleared). Empty
     drags then leave no orphaned toolbar. When the pref is off / no toolbar is
     up the callback is a no-op (opaque barrier blocks the gesture anyway).
- KNOWN FOLLOW-UPS (not blocking): UI-suppression of the lasso over chrome
  (scrubber/nav/bottom — currently global penType 4; refine via penType-switch
  to an empty type 6-14 when idle, or native writable-area rects); wire
  `EinkPen.fullClear()` into page turns if ghosting appears; large-doc perf
  measurement (#2) + platform strategy (#1) still open.

## Next task — DONE (2026-06-11, see Slice 5 above; NOT yet device-tested)

**Make "start selecting immediately while the toolbar is shown" a user option.**
Implemented as Slice 5. Touch points landed: reader_screen.dart (`_onSelection`
+ debounce suppress bypass, `_showToolbarOverlay` passes `passThrough`, new
`_startSelectingWithToolbar` pref field/load/reload, `onSelectionStart` wiring on
ReaderView), reader_view.dart (`onSelectionStart` field + two fire sites),
annotation_toolbar.dart (`passThrough` → translucent barrier), and
eink_settings_screen.dart (SELECTION section toggle). On-device validation on the
Manta still pending. Original spec below for reference.

- Observed on device: after a selection commits and the annotation toolbar pops
  up, starting a NEW lasso/selection drag does NOT begin a new selection until
  the toolbar is first dismissed (then you re-draw). The user wants a one-motion
  flow — drawing a new lasso while the toolbar is up immediately starts a new
  selection (auto-dismissing the old toolbar) — but as an OPTION (some prefer the
  explicit-dismiss behavior).
- This was a deliberate "decision": the `_suppressToolbarUntil` window (600ms,
  set in `_dismissToolbar`, checked in `_onSelection`) + the toolbar overlay's
  tap-outside barrier (`AnnotationToolbar` `dismissOnTapOutside` /
  `_dismissToolbarOnTapOutside` pref) cause the first drag to dismiss rather than
  re-select.
- Plumb a new pref (mirror `_twoColumnEnabled`/`_einkNavSide`/
  `dismissToolbarOnTapOutside` in reader_screen.dart): when ON, a new selection
  drag while the toolbar is shown immediately starts selecting (pass-through the
  barrier + skip/relax the suppress window for a genuine new drag); when OFF,
  keep today's behavior. Surface it in the e-ink settings UI.
- Touch points: reader_screen.dart (`_onSelection`, `_showToolbarOverlay`,
  `_dismissToolbar`, `_suppressToolbarUntil`, AnnotationToolbar barrier),
  ReaderView pointer handling (lib/reader/reader_view.dart), and the settings
  screen (eink_settings_screen.dart).

## Reminders / cleanup

- **`kDebugForceReaderView` in `lib/reader_screen.dart` is currently `true`**
  (forces `ReaderView` + pageFlip on all platforms for desktop testing).
  **Set back to `false` before committing** or desktop loses native
  `SelectableText` selection.
- `.claude/`, `lib.zip`, `tools/`, `devtools_options.yaml` are untracked
  (pre-existing in git status).

## Next actions (when resumed)

1. Run the large-doc profiling comparison (#2) on the existing stress document.
2. Decide strategy (#1) from that data.
3. Decide paragraph-atomic policy (#3): strict vs heuristic; design the
   paragraph-aware packer.
4. If universal: scope mac/iOS selection + accessibility work.
