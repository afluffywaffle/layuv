# Léamh Android — Handoff (2026-06-23)

Branch: `native-port-drawpath-ink`
Build: `cd android_native && ./gradlew :app:assembleDebug`
Install: `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`

Device this session: Manta (`SN100C10008955`, USB, reports as Nomad, 1920×2560).
Engine (`docx/`) untouched all session — no `:docx:test` needed.

---

## This session — landed + committed

Ran the whole Tracker: **P1, P4, P2, P3, P5** all complete, verified on-device, committed.
Plus paste-gate fixes and a Help "Thanks" page. Working tree clean (only the untracked
`test_threaded_comments.docx` fixture at repo root, left untracked by decision).

Commits (newest first):
- `2b15213` Help — Thanks page (credits **Ratta/Supernote** for the drawPath ink method) + fleshed-out About
- `39d64e8` **P5** — paginated Help & About screen (`HelpActivity`)
- `711db58` **P3** — seed `ReaderTheme.bodyFont` in every Activity (+ CLAUDE.md rule)
- `f298553` **P2** — light grey fill for highlight/comment annotations
- `6a89d05` **P4** — shared button builders; dedup `textButton`
- `8e38a03` **P4** foundation — shared `UiBuilders` (dp/hDivider/rowDivider)
- `10f84df` fix — paste button starts faded (seed `lastPastedClip` on first focus)
- `f48a2d2` **P1** — NoteActivity compose-field cluster + toolbar/full-screen redesign
- (`e9e703e`, `4a6af00` — NoteActivity redesign; prior)

### Per-item notes
- **P1** (NoteActivity): paste icon greys via `imageAlpha` (a translucent-black colour
  filter is invisible on the black vector — that was the "not greying" bug); paste
  **re-enables when the clipboard changes (next copy)**, not on field-clear; seeded on
  first window-focus so it starts faded. `ComposeEditText` + read-only `SelectableBodyText`
  share one dotted-underline + themed Cut/Copy/Paste/Select-all popup. Full-screen compose
  (expand → sheet) with a **selectable** reference passage, mirrored Tag+Paste, **collapse
  icon sits exactly over the expand button** so they toggle in place. "Add Comment" lives
  in the toolbar (field full-width). Passage label is **tool-aware**.
- **P2**: `ReaderView.buildSpanned()` now sets a **`BackgroundColorSpan(ReaderTheme.HIGHLIGHT_FILL)`**
  (`0x1F000000`, ~12%) for highlight/comment (black text on a grey band); ink keeps its
  grey `ForegroundColorSpan`. Verified readable on the e-ink panel. ⚠️ `ReaderTheme` carries
  a note that light greys can wash out under GC16 — if it ever looks too faint, nudge the
  alpha toward the jump-highlight's ~20% (`Color.argb(50,0,0,0)`).
- **P3**: added `ReaderTheme.seedBodyFont(context)`; called in `onCreate` (before building
  UI) of all 6 Activities — it was missing in **AnnotationsPanelActivity** + **SearchActivity**
  (so on process-death recreation they'd render the default Literata). Rule documented in CLAUDE.md.
- **P4**: `UiBuilders.kt` = shared `Context` extensions: `dp`, `hDivider`, `rowDivider`,
  `textButton(bold=…)`, `pillButton`, `smallAction`, `iconButton`, `chipBackground`,
  `popupBackground`. NoteActivity **1948 → 1865**. Genuine cross-file duplication is now
  exhausted; outliers deliberately keep their own copy (member shadows the extension):
  `ReaderActivity.dp` (roundToInt), `PageJumpOverlay.dp` (Float), `AnnotationPopup`/`LeamhDialog`
  (held Context). `AnnotationPopup.iconButton` is a different method-local helper.
- **P5**: `HelpActivity` — 8 paginated topic pages (Reading, Annotating, Tags, Comments & ink,
  Search, Settings, About, Thanks), built from **live components** (`ToolIconView` tool icons,
  drawn diagrams) — no bundled screenshots, stays current, crisp on e-ink. Reached from the
  reader's **⋯ overflow → "Help & About"**. Gotcha: `grid()` pads its last row with **`Space`,
  not `View`** (a WRAP_CONTENT `View` returns the full AT_MOST size and inflates the row,
  shoving siblings off-screen — same trap as the NoteActivity header).

---

## ⏭️ OUTSTANDING — start here: Help navigation, make it reader-consistent

The user reviewed `HelpActivity` on-device and wants its nav reworked. Three parts:

1. **Page-1 "Reading" diagram is messy.** `pageTurnDiagram()` draws shaded (`FILL_06`)
   Prev/Next blocks with **square corners inside a rounded border** → corner clash; it reads
   like a confusing 3-segment toolbar. Redesign clean (a page outline + edge chevrons, no
   shaded blocks) **or** drop the diagram (the text already explains it).

2. **Page-1 description is INACCURATE — fix it.** It says "tap the left or right edge to turn
   back/forward." The reader's REAL nav is **80dp edge strips on BOTH left + right, each split
   vertically: TOP half = next, BOTTOM half = prev** (top chevron points right=next at h/4;
   bottom chevron points left=prev at 3h/4; a midline hairline divides them). Update the help
   text to describe this.

3. **Replace Help's bottom Prev/Next pager (`buildPager()`) with the reader's nav, for
   CONSISTENCY** — both **edge-tap strips** and **swipe**:
   - **Edge-tap strips** matching `ReaderView`. Specs to copy (all in `ReaderView.kt`):
     `navStripWidth = dp(NAV_STRIP_DP = 80f)`; `navChevronPaint` = INK, **alpha 55**, STROKE,
     `strokeWidth dp(2.5)`, round cap+join; `navHairlinePaint` = INK, **alpha 90**, STROKE,
     `dp(1.5)`; chevron `halfW = dp(8)`, `halfH = dp(12)`; top chevron `pointRight` (next) at
     `h/4`, bottom (prev) at `3h/4`. See `drawNavStrips`/`drawNavStrip`/`drawChevron` (~lines
     652–676) and nav tap handling (`inLeft = x < navStripWidth`, `inRight = x > width-…`,
     ~786–826).
   - **Swipe gestures too.** The user confirmed the reader + paginated panels use swipe.
     `SearchActivity` does it via `dispatchTouchEvent` (`swipeDownX/Y`, on ACTION_UP if
     `|dx| > dp(60)` and `|dx| > |dy|` → `navigateResultPage(±1)`). `ReaderView` uses a
     `GestureDetector` (`gestures.onTouchEvent`). Add equivalent to Help.
   - ⚠️ **CLAUDE.md says "No swipe gestures (unreliable on e-ink)"**, but actual practice
     (reader, Search) uses swipe on paginated surfaces, and the user explicitly wants it on
     Help. **Reconcile with the user** — likely update the CLAUDE.md rule to "paginated nav may
     use swipe; avoid swipe elsewhere," or carve the exception.
   - Remove the bottom pager once edge-tap + swipe land (optionally keep a tiny page indicator).

   Bigger-but-cleaner option: extract a **shared edge-nav component** so reader + Help truly
   share it (reader nav is currently baked into `ReaderView.onDraw`). A faithful copy in Help
   is the pragmatic first step; the shared extraction is the proper consistency win.

---

## App icon — DECISION: keep the original

The launcher icon is **not a placeholder** (the old note was stale). It's a real designed icon:
a serif **"L"** with a yellow highlighter band on a manuscript page with a folded corner — the
`@mipmap/ic_launcher` PNGs (in use). **The user prefers it.**
- An adaptive redesign was attempted (a sans-serif L + band) and **reverted** — the user wants
  the serif L. Icon is back to the original; build green.
- "Greyscale-safe adaptive assets": the original PNGs render fine on the Supernote (greyscale);
  adaptive + monochrome only benefit colour Android launchers / Android-13 themed icons (not the
  target). A faithful adaptive means hand-porting a **12-layer Apple Icon Composer design**
  (serif L, manuscript rules, highlight band, folded corner, a raster layer, gradient + shadow)
  — high effort, low value, easy to diverge.
- **Icon source of record (with layers):**
  `/Users/jayromacorda/Library/Mobile Documents/com~apple~CloudDocs/Projects/Icons/léamh/layvu.icon`
  (Icon Composer bundle: `icon.json` manifest + `Assets/slice*.svg` + `slice13.png`).
- **Recommendation:** consider the icon done. If adaptive is ever wanted, export a foreground
  PNG (logo in the adaptive safe zone, ~432–512px) + a background from Icon Composer on the Mac,
  then wire `mipmap-anydpi-v26/ic_launcher.xml` (a couple of minutes of Android plumbing).

---

## Tracker status

| Item | Status |
|---|---|
| P1 — Compose-field cluster | ✓ Done (`f48a2d2`, `10f84df`) |
| P2 — Highlight light-grey fill | ✓ Done (`f298553`) |
| P3 — Font preference app-wide | ✓ Done (`711db58`) |
| P4 — Code-size reduction (UiBuilders) | ✓ Done (`8e38a03`, `6a89d05`) |
| P5 — Help / About screen | ✓ Shipped (`39d64e8`, `2b15213`) — **nav rework outstanding (above)** |
| App icon | Kept original (done); optional adaptive deferred |

**Pending hands-on verification** (couldn't script on the IME-less Supernote — no soft keyboard
for `adb input text`, long-press hard to drive): the **editable compose field's selection popup**
(does the focusable popup keep the selection alive when it steals focus?) is the main unverified
behaviour. Paste fade, tool-aware label, full-screen open/collapse, and the Help pages were all
verified via screenshots.

---

## Handoff prompt for a new conversation

> I'm working on the Léamh Android app (`android_native/` at `/Users/jayromacorda/Develop/layuv`),
> branch `native-port-drawpath-ink`. Read `CLAUDE.md` and `HANDOFF_next.md` in full first.
>
> The whole Tracker (P1–P5) is **done, committed, and verified on the Manta** — see the
> "This session — landed" section. **Pick up the OUTSTANDING item: make `HelpActivity`'s
> navigation consistent with the reader** (see "OUTSTANDING — Help navigation" in HANDOFF):
> (1) the page-1 "Reading" page-turn diagram is messy (square shaded blocks in a rounded
> border) — redesign or drop it; (2) its description is wrong — the reader's nav is 80dp
> left+right edge strips, **top half = next, bottom half = prev** (not left/right) — fix the
> text; (3) replace Help's bottom Prev/Next pager with the reader's nav for consistency:
> **edge-tap strips** (copy `ReaderView`'s chevron/strip specs) **and swipe gestures** (the
> reader + `SearchActivity` use swipe; note CLAUDE.md currently says "no swipe" — reconcile
> that with me, since I want swipe on paginated nav).
>
> Build: `cd android_native && ./gradlew :app:assembleDebug`.
> Install: `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.
> Reach Help on-device: reader → ⋯ (bottom bar) → "Help & About". To reach NoteActivity:
> reader → tap an annotation → floating toolbar → comment icon. `test_threaded_comments.docx`
> is on the device at `/sdcard/Documents/`. Engine (`docx/`) untouched — only run `:docx:test`
> if you change it. NOTE the Manta sometimes rotates; reset with
> `adb shell settings put system accelerometer_rotation 0; ... user_rotation 0`.

---

## Key references

- **Help nav specs:** `ReaderView.kt` — `drawNavStrips`/`drawNavStrip`/`drawChevron` (~652–676),
  nav tap (~786–826), strip/chevron/hairline paint defs (~66–84). `SearchActivity.dispatchTouchEvent`
  = swipe pattern. Reader nav side pref: `KEY_NAV_SIDE` ("both"/"left"/"right"/"none").
- **Help entry point:** `ReaderActivity.showOverflowMenu()` — the "Help & About" `overflowActionRow`
  → `HelpActivity`.
- **Shared UI builders:** `UiBuilders.kt` (P4) — `dp`, dividers, buttons, chip/popup backgrounds.
- **Icon source:** `/Users/jayromacorda/Library/Mobile Documents/com~apple~CloudDocs/Projects/Icons/léamh/layvu.icon`
- `CLAUDE.md` — architecture invariants, e-ink rules (incl. the swipe rule to reconcile),
  typography rules, coding standards (incl. the new `seedBodyFont` rule).
- `android_native/README.md` — module layout, build environment.
- Logcat: `adb logcat -s LeamhActivity LeamhAnnotPanel InkNoteActivity DrawPathClient`
