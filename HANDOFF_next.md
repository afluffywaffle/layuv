# Léamh Android — Handoff (2026-06-22)

Branch: `native-port-drawpath-ink`  
Build: `cd android_native && ./gradlew :app:assembleDebug`  
Install: `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## Done this session (2026-06-22)

### Annotation bottom pane — Thread tab + Ink tab (commit d81ae32)

Full implementation of the NoteActivity rewrite with Thread+Ink bottom pane. See the memory file at `~/.claude/projects/.../memory/annotation_thread_ink_pane.md` for resolved design decisions and the adversarial review summary.

**Engine (`docx/`, 54 tests green):**
- `ThreadEntry{text, timestamp, source}` + `Annotation.threadEntries` (backward-compat)
- `LegacyComments`: parses `commentsExtended.xml`, flattens Word reply chains onto the root parent annotation (handles multi-level + multi-paragraph)
- `CommentWriter`: one `<w:p>` per thread entry; entry[0] plain = old note output; entries[1+] timestamp-prefixed
- `DocxStore`: clears `commentsExtended.xml` only when present

**App:**
- `ThreadJson`: `List<ThreadEntry>` ↔ JSON for Intent extras
- `NoteActivity` rewritten: header no title, tags inline with tool selector, Ink/Thread bottom pane, paginated thread, compose Add/Save, Reply shortcut, per-entry edit/delete, `onSaveInstanceState`
- `ReaderActivity`: passes/consumes `threadEntries`; `EXTRA_INITIAL_TAG` fixes pre-existing tag-drop bug
- `AnnotationsPanelActivity`: thread-aware delete confirmation

**Adversarial review fixes applied:** multi-level reply chain, multi-paragraph parent linkage, tag drop on edit, no instance-state, 44→48dp tap targets, bold pager labels, keyboard-aware row count, stale-highlight after startReply, unconditional page-jump on edit.

---

### NoteActivity layout crash fixes (this session, commit pending)

**Bug 1 — Header spacer inflated to screen height**

`buildHeader()` used `View(this)` with `LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)` as the flex spacer between the back arrow and Save button. `View.getDefaultSize()` returns the full `AT_MOST` spec size for content-less views, so the spacer measured to the full screen height (1872px), making the header consume the entire root LinearLayout and leaving all other children with zero space.

**Fix:** Replaced `View(this)` spacer with `Space(this)`. `android.widget.Space.onMeasure` returns 0 for `AT_MOST`, giving the header its natural height (~90px).

**Bug 2 — Quote box border inflated container to screen height**

`buildQuoteBox()` used a FrameLayout with a border `View` having `FrameLayout.LayoutParams(dp(3f), MATCH_PARENT)`. Android's `FrameLayout` only re-measures MATCH_PARENT children in a second pass when `mMatchParentChildren.size() > 1` — with a single MATCH_PARENT child, no re-measurement occurs. The border View received `AT_MOST ~1600` and inflated to 1600px, pushing the compose field and tab bar off screen.

**Fix:** Eliminated the border View entirely. `buildQuoteBox()` now returns a single `TextView` with a custom background `Drawable` that draws only the leftmost 3dp strip in `INK_38`.

**Also fixed:** `AndroidManifest.xml` — NoteActivity `windowSoftInputMode` changed from `stateAlwaysVisible|adjustResize` to `stateHidden|adjustResize`. The `stateAlwaysVisible` was left over from the old single-field NoteActivity; on Supernote the handwriting IME is large and was shrinking the window to near-zero.

---

## Current feature status (post this session)

| Feature | Status |
|---|---|
| File open + recents sidebar | ✓ Done |
| Text rendering (bold/italic, warm paper) | ✓ Done |
| Text selection → tool picker → annotation | ✓ Done |
| Per-tool annotation rendering | ✓ Done |
| Annotations panel (search, tag filter) | ✓ Done |
| Annotation edit sheet (NoteActivity) | ✓ Done — Thread + Ink pane |
| Save (atomic, DocxWriteQueue) | ✓ Done |
| App Sandbox + security-scoped bookmarks | ✓ Done |
| Ink annotations (InkNoteActivity) | ✓ Done |
| drawPath low-latency ink | ✓ Done |
| **Font size preference** | Pending |
| **Two-column layout** | Pending |
| **Reader full-text search** | Pending |
| **App icon** | Pending (placeholder only) |

---

## Next session — Font size preference

The reader body text is currently fixed at `ReaderTheme.BODY_TEXT_SP`. Add a user preference to change it. This is the highest-priority pending feature per CLAUDE.md.

### Scope

1. **Preference storage** — Save chosen size to `SharedPreferences` with key `"font_size"` and values `"small"` / `"medium"` / `"large"`. `ReaderTheme.bodySizeSp(pref: String)` already exists and maps these to SP values (NoteActivity already reads this pref). Just needs to be writeable from a settings UI.

2. **Settings entry point** — A settings button already exists in the ReaderActivity toolbar (or needs to be wired up — verify with grep). It should open a simple picker: three choices, current selection highlighted, tap to apply. No custom Activity needed; use `LeamhDialog` or a simple popup with three rows.

3. **Apply to reader** — After saving the preference, `ReaderActivity` must re-paginate with the new font size. The reader already re-paginates on window size changes; the same path should be triggerable after a preference change. Verify with `grep -n "paginate\|BODY_TEXT"` in `ReaderActivity.kt` and `ReaderView.kt`.

4. **NoteActivity** — Already reads the pref on `onCreate`. No change needed.

5. **No other activities need changes** — Font size only affects reader body text and NoteActivity. Chrome text (tabs, buttons, labels) stays fixed.

### Key files to read before starting

| File | Why |
|---|---|
| `android_native/app/.../reader/ReaderActivity.kt` | Settings button location, paginate trigger |
| `android_native/app/.../reader/ReaderView.kt` | StaticLayout build — uses BODY_TEXT_SP |
| `android_native/app/.../reader/ReaderTheme.kt` | `bodySizeSp()` mapping, constants |
| `android_native/app/.../reader/NoteActivity.kt` | Shows how the pref is already read |

### Invariants

- E-ink rules apply: no animations, no swipe, taps only, greyscale-safe.
- The picker can be a simple dialog or inline popup — not a full separate Activity.
- After applying, the reader must re-paginate immediately (not on next open).
- `ReaderTheme.BODY_TEXT_SP` stays as the default fallback — do not remove it.

---

## Key references

- `~/.claude/projects/.../memory/annotation_thread_ink_pane.md` — Thread pane design decisions
- `android_native/README.md` — module layout, build environment
- `CLAUDE.md` — architecture invariants, e-ink rules, typography rules, coding standards
- Nomad WiFi ADB: `~/Library/Android/sdk/platform-tools/adb connect <device-ip>:5555`
- Logcat: `adb logcat -s LeamhActivity LeamhAnnotPanel InkNoteActivity DrawPathClient`
