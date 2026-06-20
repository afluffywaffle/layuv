# Léamh Android — Handoff (2026-06-19)

Branch: `native-port-drawpath-ink`  
Build: `cd android_native && ./gradlew :app:assembleDebug`  
Install: `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## Task 1 — Title label: use available space

### What it looks like now

Bottom toolbar has three FrameLayout children:
- LEFT `pillRow` (Gravity.START) — 4-button icon pill, ~200dp wide
- CENTER `pageIndicator` (Gravity.CENTER) — "38 / 182" pill, ~90dp wide
- RIGHT `titleLabel` (Gravity.END) — file name, **fixed 110dp / 13sp** → truncates to "leamh_large_do…"

On a Nomad (~994dp) or Manta (~1280dp) the right wing has ~440–600dp available. The 110dp cap wastes almost all of it.

### Exact change needed

**File:** `android_native/app/src/main/kotlin/com/afluffywaffle/layuv/reader/ReaderActivity.kt`

Around line 174 — the `titleLabel` `TextView` definition:
```kotlin
titleLabel = TextView(this).apply {
    typeface = ReaderTheme.body(this@ReaderActivity)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)   // ← too small
    setTextColor(ReaderTheme.INK_87)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    gravity = Gravity.END or Gravity.CENTER_VERTICAL
}
```

Change to 15sp and `chromeBold()` so it reads as a chrome label rather than body text:
```kotlin
titleLabel = TextView(this).apply {
    typeface = ReaderTheme.chromeBold(this@ReaderActivity)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    setTextColor(ReaderTheme.INK_87)
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    gravity = Gravity.END or Gravity.CENTER_VERTICAL
}
```

Around line 193 — the `FrameLayout.LayoutParams` for titleLabel:
```kotlin
toolbar.addView(
    titleLabel,
    FrameLayout.LayoutParams(dp(110f), WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL),
)
```

Increase the fixed width. `dp(280f)` fits safely on the Nomad (994dp) without overlapping the centered indicator (indicator center ≈ 497dp, title occupies 970–690dp = no conflict). Use `dp(320f)` on Manta if you want even more room — the auto-2-col threshold means Manta runs 2 columns so the name is shorter, but a wider slot still looks good:
```kotlin
toolbar.addView(
    titleLabel,
    FrameLayout.LayoutParams(dp(280f), WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL),
)
```

No other files need changing. Build and screenshot to confirm.

---

## Task 2 — Pen lasso gesture: global stylus-circle-to-select

### Desired behavior (user-specified)

1. **Primary selection — draw a circle:** user puts stylus down anywhere on text, draws a closed (or open) loop around words, lifts pen → the text inside the loop is selected → annotation popup appears.
2. **Additive selection — tap-hold then draw:** after a selection exists (popup showing), user tap-holds with the stylus → enters additive mode → draws another circle → the union of old + new selection is used → popup refreshes.
3. **Apply globally:** the same lasso gesture should work inside every screen where the user annotates text — specifically `ReaderActivity` / `ReaderView` and any future activity that hosts a text selection UI.

### Current state

`DrawPathClient` (penType 4) draws a hardware-level dotted lasso stroke during any stylus drag in the reader — visual feedback is correct. However the underlying **selection logic** in `ReaderView` uses a **scrub accumulator** (`scrubMin`/`scrubMax`) that tracks the min/max char indices touched by the pen path.

For a straight drag (left to right across words) this works well. For a **lasso circle** the scrub accumulator also works, because as the stylus traces the circle it physically passes over the text — `extendScrubTo(x, y)` samples every point and widens the [scrubMin, scrubMax] range. The dotted lasso closes and `finaliseSelection()` fires on pen-up.

**What broke:** the user reports the gesture "doesn't seem to be working as it was." Likely causes to investigate:
- `initDrawPathLasso()` may not be running at the right time after returning from an overlay/activity, leaving DrawPath in a wrong state (pen type reset, writable areas cleared, etc.).
- The annotation popup (`PopupWindow`) consumes the stylus DOWN event before `ReaderView` sees it, so a new lasso drawn while the popup is open doesn't start a selection.

### Key files and code paths

```
ReaderView.kt
  handleStylusEvent()          ← stylus ACTION_DOWN/MOVE/UP → scrub selection
    extendScrubTo(x, y)        ← accumulates scrubMin/scrubMax
    finaliseSelection()        ← fires onSelectionReady

ReaderActivity.kt
  initDrawPathLasso()          ← sendReset → penType 4 → disableChromeBand
  disableChromeBand()          ← setWritableAreas flag=0 for toolbar strip only
  onResume()                   ← calls initDrawPathLasso()
  onActivityResult() REQ_NOTE/REQ_INK/REQ_SEARCH/REQ_ANNOTATIONS
                               ← each calls readerView.post { initDrawPathLasso() }

DrawPathClient.kt              ← binder wrapper around drawPath service
```

### Investigation steps

1. **Confirm DrawPath state after popup dismiss:** add a log in `initDrawPathLasso()` and tap the annotation popup's ✕ button. If you don't see the log, find where the popup dismiss path should call `initDrawPathLasso()` and add it.  
   - Current: `onHidePopup = { annotationPopup.dismiss() }` in `ReaderActivity.buildUi` — this does NOT reinitialise DrawPath. Add `initDrawPathLasso()` after the dismiss call.

2. **Popup touch interception:** when `annotationPopup` is visible (`PopupWindow` with `isOutsideTouchable = false`), a stylus DOWN inside the popup window is consumed by the popup, not `ReaderView`. Stylus DOWN **outside** the popup should still reach `ReaderView`. Verify by logging in `handleStylusEvent` ACTION_DOWN — if the log appears the view is receiving input.

3. **Additive selection (tap-hold then draw):** not yet implemented. Design sketch:
   - In `handleStylusEvent` ACTION_DOWN: detect a "hold" (dwell ≥ 300ms without movement) while a selection already exists → set `additiveMode = true`, keep existing `scrubMin`/`scrubMax`.
   - In `extendScrubTo`: if `additiveMode`, don't reset — just widen the range.
   - In `finaliseSelection`: clear `additiveMode`.
   - Visually: the existing dotted-underline selection stays visible during the additive drag (the current code already paints the selection during `isSelecting`).

4. **Global (non-reader screens):** `NoteActivity` and `InkNoteActivity` don't do text selection — this is reader-only. "Apply globally" probably means: ensure the same lasso gesture works consistently regardless of which toolbar overlay or popup is on screen.

### Recommended first fix

In `ReaderActivity.buildUi`, change the `onHidePopup` lambda:
```kotlin
// Before:
onHidePopup = { annotationPopup.dismiss() }

// After:
onHidePopup = { annotationPopup.dismiss(); initDrawPathLasso() }
```

And add `initDrawPathLasso()` to the `annotationPopup.dismiss()` call chain in `AnnotationPopup.dismiss()` — or better, expose an `onDismiss` callback from `AnnotationPopup` and reinitialise there, since dismiss can also be called internally by the popup itself (on tool selection, on ✕, on outside-touch).

The cleanest hook: add `var onDismiss: (() -> Unit)? = null` to `AnnotationPopup`, call `onDismiss?.invoke()` at the top of `dismiss()`, and in `ReaderActivity` set `annotationPopup.onDismiss = { initDrawPathLasso() }`.

---

## Notes / constraints

- No animations, no swipe gestures, no colour-only affordances — all e-ink rules from `CLAUDE.md` apply.
- After every change: `./gradlew :app:assembleDebug` must succeed.
- The logging APK (with `PenTapListener` tagged `UndoPill` / `ActionPopup/*`) is already installed. Logcat: `adb logcat -s UndoPill ActionPopup/Delete ActionPopup/Comment LeamhActivity AnnotationPopup PenTapListener`
- The delete-pill fix (optimistic update + smart-merge guard) was completed in this session and is in the current APK. Commit it before starting new work: the modified file is `ReaderActivity.kt` (also `PenTapListener.kt`, `AnnotationPopup.kt` gained logging — those can be committed or stripped first if noisy).
