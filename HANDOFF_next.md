# Léamh Android — Handoff (2026-06-22)

Branch: `native-port-drawpath-ink`  
Build: `cd android_native && ./gradlew :app:assembleDebug`  
Install: `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## ⚠️ This session — UNCOMMITTED changes

All of this session's work is **uncommitted** on `native-port-drawpath-ink`.
Build is green (`./gradlew :app:assembleDebug` BUILD SUCCESSFUL) and every path
below was verified on-device (Manta, reports as Nomad, 1404×1872). Nothing has
been committed — the next session should review and commit.

**Files changed:**
- `android_native/.../reader/NoteActivity.kt` — modified (all the work below)
- `android_native/app/src/main/res/drawable/ic_paste.xml` — **new** (Material clipboard icon)
- `test_threaded_comments.docx` — **new** test file (also pushed to device `/sdcard/Documents/`)
- `HANDOFF_next.md` — this file

**1. Paste + compose selection polish**
- Compose paste button is the `ic_paste` clipboard icon (was the text "Paste").
- `pasteClipboardWithQuotes()` wraps pasted clipboard text in quotes at the cursor.
- `SelectableBodyText` (the entry-detail overlay text): suppresses Android's
  system selection (`highlightColor = 0` + a no-op `customSelectionActionModeCallback`
  that returns `true`/clears the menu — returning `false` blocks selection entirely),
  draws the **reader dotted-underline** for the selection, and shows a themed
  Copy / Select-all `PopupWindow`. Gotcha fixed: Select-all must call
  `onTextContextMenuItem(android.R.id.selectAll)` — `Selection.setSelection` on
  `editableText` NPEs because `setTextIsSelectable` buffers as SPANNABLE, not EDITABLE.

**2. NoteActivity layout restructure + toolbar redesign**
- **Pinned quote** is now the **root of the Comments thread**: grey left bar +
  italic, 2-line truncate, "Highlighted passage" label, tap → `showEntryDetail`
  with a `metaOverride`. No action buttons. Rendered in `renderThreadPane()`
  above the paginated list (always visible). The old top quote box is gone.
- **Toolbar**: `Dismiss` + `Save` paired left (Dismiss = `handleBack`, keeps the
  unsaved-changes confirm); `Tool · Tag · Paste` cluster right; `Add` (compose
  commit) sits **beside the field**.
- **Tool** + **Tag** carry the bottom-right **corner-hint triangle** (the
  annotation-toolbar's "more underneath" affordance), drawn as a **foreground
  Drawable** (`cornerHintDrawable()`). ⚠️ Gotcha: `onDraw`/`draw()` overrides
  silently fail to paint on a `TextView` here — the tag is a `FrameLayout`
  wrapper (`tagButton`) around a `TextView` (`tagLabel`); the FrameLayout's
  foreground draws reliably. The tool button is a plain `View` (foreground works).
- "Thread" tab → **"Comments"**.

**3. Adversarial-review fixes** (from a multi-agent review of the diff)
- Pinned-quote pagination height (`threadRowsPerPage` subtracts `dp(96f)`) so a
  row can't overflow the pane on Nomad.
- Tag label `isSingleLine` + ellipsize + `maxWidth = dp(150f)` so "Continuity"
  can't crowd Save.
- Tag **re-tap toggle** restored in `selectTag()` (tapping the active tag clears it).
- Tool/tag pickers use `showAsDropDown(anchor, 0, dp(4f), Gravity.END)` to stay
  on-screen near the right edge.
- `onPause()` dismisses both pickers **and** the detail overlay; the overlay's
  `OnGlobalLayoutListener` is stored (`entryDetailLayoutListener`) and removed in
  `dismissEntryDetail()` (no listener leak).
- Compose edit label "Save" → **"Update"** (was colliding with the toolbar Save).

> Engine (`docx/`) was NOT touched this session — no `:docx:test` run needed.

---

## Current feature status (post this session)

| Feature | Status |
|---|---|
| File open + recents sidebar | ✓ Done |
| Text rendering (bold/italic, warm paper) | ✓ Done |
| Text selection → tool picker → annotation | ✓ Done |
| Per-tool annotation rendering | ✓ Done |
| Annotations panel (search, tag filter) | ✓ Done |
| Annotation edit sheet (NoteActivity) | ✓ Done — toolbar (Dismiss/Save · Tool/Tag/Paste) + pinned-quote Comments thread + Ink pane *(uncommitted)* |
| Save (atomic, DocxWriteQueue) | ✓ Done |
| App Sandbox + security-scoped bookmarks | ✓ Done |
| Ink annotations (InkNoteActivity) | ✓ Done |
| drawPath low-latency ink | ✓ Done |
| Font size preference | ✓ Done |
| Two-column layout | ✓ Done |
| Reader full-text search | ✓ Done |
| **App icon** | Pending (placeholder only) |

---

## Tracker — open work (priority order, grouped for efficiency)

Ordered so work stays in the same file/context where possible: finish all
compose-field work in NoteActivity first (it's the hot file), then the reader
highlight change, then the cross-cutting sweeps, then the refactor + new screens.

**DONE — NoteActivity layout restructure + toolbar redesign + review fixes:**
- Pinned quote is the **root of the Comments thread** (grey left bar + italic,
  2-line truncate, tap-to-expand via `showEntryDetail`, no action buttons).
- Toolbar: **Dismiss + Save** paired left; **Tool · Tag · Paste** cluster right
  (Tool + Tag carry the bottom-right **corner-hint triangle** via a foreground
  Drawable — note: onDraw/draw() overrides silently fail on a TextView; the tag
  uses a FrameLayout wrapper). **Add** sits beside the compose field.
- "Thread" tab → **"Comments"**.
- Adversarial-review fixes: pinned-quote pagination height, tag
  ellipsize/maxwidth, tag re-tap toggle, pickers `Gravity.END` (on-screen),
  `onPause` dismisses the detail overlay + removes its layout listener, compose
  edit label "Save" → "Update" (no collision with toolbar Save).

### P1 — Compose-field cluster (NoteActivity — do next, file still hot)
- **Paste: grey out when clipboard has no pasteable text.** Dim the toolbar paste
  icon (INK_26, tap no-op) when the primary clip is empty/non-text; re-check on
  resume / window-focus.
- **Paste: one-time only per compose.** After a successful paste, disable the
  paste button until the field is committed/cleared — there is NO undo, so this
  stops repeated dumping of large chunks.
- **Compose-field text selection must match the established style.** The compose
  `EditText` still shows Android's default fill + handles + system context menu.
  Make it match the reader/overlay selection: suppress the fill
  (`highlightColor = 0`), draw the dotted underline, and use the themed
  Copy/Paste popup (same pattern as `SelectableBodyText`). Editable field is
  trickier than the read-only overlay — handles + paste path must still work.
- **Full-screen compose-on-overflow** — compose field stays fixed (no scroll, no
  char cap — both rejected as un-e-ink). An expand button opens a full-screen
  compose view showing the entry being replied to (read-only quote box) above a
  full-height field, so the user can re-reference while writing. Tapping the
  compose field auto-dismisses any open detail overlay (read vs. write modes).

### P2 — Annotation highlight: light grey fill
Replace the grey `ForegroundColorSpan` text tint for highlight/comment
annotations with a light grey background fill (~10–12% opacity) in
`HighlightPainter`/`ReaderView.buildSpanned()`. Keep the dotted underline for
active selection drag only. (Decided in design chat: grey fill is easier to catch
in a body of text than grey text on e-ink.)

### P3 — Font preference app-wide
Ensure EVERY Activity/dialog seeds `ReaderTheme.bodyFont` from SharedPreferences
(`"body_font"`) before building UI; Literata / Source Sans 3 must apply to all
text surfaces, not just the reader. Cross-cutting sweep — do in one pass. Update
CLAUDE.md with the rule.

### P4 — Code-size reduction pass
NoteActivity and the other View-building files are very long (inline `apply{}`
builder boilerplate). After P1–P3 land, factor the repeated patterns (themed
button, popup, divider, row, icon button, chip background) into shared
UI-builder helpers so each Activity shrinks. No-behaviour-change refactor with
screenshots before/after.

### P5 — Help / About screen
In-app help explaining navigation + each function/tool (reader nav, selection →
tool picker, tag meaning, ink, comments thread, search, settings), plus the
**About** section (app name, version, bundle ID `com.afluffywaffle.layuv`,
GPL v3, repo link). Reachable from settings. Greyscale-safe, Source Sans 3 /
Literata per the typography rules, paginate like the reader if long.

---

## Next session — start here

1. **First: review + commit this session's uncommitted work** (see the
   ⚠️ UNCOMMITTED section above). Build is green and verified on-device; it just
   needs review and a commit (or split commits — e.g. paste/selection, then the
   toolbar/quote restructure, then the review fixes).
2. **Then: P1 — Compose-field cluster** (Tracker above). NoteActivity is the hot
   file, so clear all compose-field work next: paste grey-when-empty, paste
   once-per-compose, compose `EditText` selection matched to the dotted-underline
   style, then full-screen compose-on-overflow.
3. Continue down the Tracker (P2 highlight fill → P3 font sweep → P4 code-size →
   P5 Help/About). **App icon** (greyscale-safe adaptive assets under
   `android_native/app/src/main/res/`, bundle `com.afluffywaffle.layuv`) remains
   the last pending *feature* — fold it in whenever; it's no longer the front of
   the queue.

Other CLAUDE.md features are all done: font size (`"font_size"`,
`ReaderTheme.bodySizeSp()`), two-column (`"columns"`, `resolveColumns()`), reader
full-text search (`SearchActivity`).

---

## Handoff prompt for a new conversation

> I'm working on the Léamh Android app (`android_native/` at
> `/Users/jayromacorda/Develop/layuv`), branch `native-port-drawpath-ink`.
> Read `CLAUDE.md` and `HANDOFF_next.md` in full before doing anything.
>
> Status: the previous session's NoteActivity work (toolbar redesign — Dismiss/Save
> left, Tool/Tag/Paste right with corner-hint triangles, Add beside the field;
> pinned-quote "Comments" thread; paste icon + themed text selection; and a batch
> of adversarial-review fixes) is **complete, built green, verified on-device, but
> UNCOMMITTED**. See the "⚠️ This session — UNCOMMITTED changes" section of
> HANDOFF_next.md for the full list and the gotchas (TextView corner-hint needs a
> FrameLayout wrapper; Select-all needs `onTextContextMenuItem`).
>
> First, review the uncommitted diff with me and commit it (sensibly split).
> Then start **P1 — the compose-field cluster** from the Tracker in HANDOFF_next.md:
> (a) grey out the paste button when the clipboard has no pasteable text;
> (b) limit paste to one time per compose (no undo, avoid large repeated dumps);
> (c) make the compose `EditText` text selection match the established
> dotted-underline style (suppress system fill/handles/menu, themed popup, like
> `SelectableBodyText`); (d) full-screen compose-on-overflow.
>
> Build: `cd android_native && ./gradlew :app:assembleDebug`.
> Install: `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.
> To reach NoteActivity on-device: open the reader, tap an annotation to summon
> the floating toolbar, tap the comment (speech-bubble) icon. `test_threaded_comments.docx`
> is already on the device at `/sdcard/Documents/`. Engine (`docx/`) untouched —
> only run `:docx:test` if you change it.

---

## Key references

- `~/.claude/projects/.../memory/annotation_thread_ink_pane.md` — Thread pane design decisions
- `android_native/README.md` — module layout, build environment
- `CLAUDE.md` — architecture invariants, e-ink rules, typography rules, coding standards
- Nomad WiFi ADB: `~/Library/Android/sdk/platform-tools/adb connect <device-ip>:5555`
- Logcat: `adb logcat -s LeamhActivity LeamhAnnotPanel InkNoteActivity DrawPathClient`
- Reach NoteActivity: reader → tap annotation → floating toolbar → comment icon
