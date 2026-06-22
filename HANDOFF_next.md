# Léamh Android — Handoff (2026-06-22)

Branch: `native-port-drawpath-ink`  
Build: `cd android_native && ./gradlew :app:assembleDebug`  
Install: `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## Done this session (2026-06-22) — Tasks 1–8 all complete

### Tasks 1–7 — Run 2 review + prior features

See git log for full detail. Summary:

| Task | Summary |
|---|---|
| 1 | De-Onyx build — removed onyxsdk-device from build files |
| 2 | Handle drag lasso — onHandleDragStart/End callbacks, ACTION_CANCEL split |
| 3 | E-ink text selection color — setHighlightColor in NoteActivity, SearchActivity, LeamhDialog |
| 4 | Ink in Pages/Google Docs — InkAnchorInjector inline drawing paragraph |
| 5 | Style-based formatting — StyleResolver parses styles.xml with basedOn inheritance |
| 6 | Build APK — BUILD SUCCESSFUL |
| 7 | Run 2 review — 6 confirmed bugs fixed (see table below) |

**Run 2 bug fix table:**

| # | File | Summary | Fix |
|---|---|---|---|
| 1 | `PlainTextMapper.kt` | CRITICAL: xmlOffsets wrong after XML entities | Raw XML walk; entity start offset recorded, not decoded index |
| 2 | `DocxWriteQueue.kt` | Zero-byte transform output silently destroys document | Guard: throw if out.isEmpty() |
| 3 | `AnnotationPopup.kt` | onDismiss not fired on external dismiss (HOME key etc.) | suppressDismissCallback flag; listener fires onDismiss when not suppressed |
| 4 | `ReaderView.kt` | ACTION_CANCEL on handle drag called finishHandleAdjust() → spurious popup | Split ACTION_CANCEL from ACTION_UP; cancel only resets drag state |
| 5 | `InkAnchorInjector.kt` | Drawing paragraph inserted inside table cell | Detect </w:tc> before next <w:p>; advance to after </w:tbl> |
| 6 | `AnnotationsPanelActivity.kt` + `SearchActivity.kt` | Missing ACTION_CANCEL reset for swipe tracking | Added ACTION_CANCEL → reset swipe origin |

### Task 8 — FileBrowserActivity recents not showing

**Root cause:** `addOnLayoutChangeListener` on `recentsSection` called `renderRecents()` synchronously inside a layout pass. Android's loop-guard silently drops `requestLayout()` calls made during a layout pass, leaving all added child views with `h=0 w=0` — present in the view tree but never measured, invisible.

**Fix:** Changed listener to `post { renderRecents() }` — defers the re-render to after the current layout pass completes. Children are then measured normally on the next frame.

**File:** `android_native/app/src/main/kotlin/com/afluffywaffle/layuv/reader/FileBrowserActivity.kt`

---

## Next session — Annotation bottom pane: Thread tab + Ink tab

**Use Opus for this session.** Multi-layer change touching the DOCX engine, data model, and NoteActivity UI simultaneously.

### Background (read before starting)

Léamh needs to display ink notes and Word-imported comment threads without corrupting DOCX round-trip. This feature adds a bottom pane to `NoteActivity` with two tabs: **Ink** and **Thread**.

A key DOCX finding: `CommentWriter` currently rebuilds `comments.xml` with new `<w:p>` paragraphs that have no `w:paraId` attributes. Word's `commentsExtended.xml` references those IDs for reply threading — they become dangling after any Léamh save. The fix is to clear `commentsExtended.xml` on write and represent threads as multi-paragraph comment bodies instead.

Round-trip is confirmed safe: Léamh annotations round-trip cleanly in both directions. Word-imported reply threads are flattened to Léamh's format on first open+save (content preserved; Word's separate reply-comment structure converted to multi-paragraph single comment). This is intentional and acceptable.

### Data model changes

Add to `Annotation`:
```kotlin
val threadEntries: List<ThreadEntry> = emptyList()
```

New class (pure JVM, in `docx/` module):
```kotlin
data class ThreadEntry(
    val text: String,
    val timestamp: Long,
    val source: String,   // "leamh" or "word"
)
```

Persist in `leamh/annotations.json` as before. `note` field stays unchanged for backward compatibility — it is NOT replaced by threadEntries.

### DOCX engine — read (`LegacyComments.kt`)

- Parse `word/commentsExtended.xml` alongside `comments.xml`.
- `<w15:commentEx w15:paraIdParent="..."/>` identifies reply comments.
- Replies become `ThreadEntry(source="word")` on their parent annotation — NOT standalone `Annotation` objects.
- The `w15:paraId` on the parent comment paragraph links `commentsExtended` entries to comments.

### DOCX engine — write (`CommentWriter.kt` + `DocxStore.kt`)

- `buildNoteComment()`: emit one `<w:p>` per `ThreadEntry` beyond the first. Each paragraph prefixed with formatted timestamp (ISO or human-readable).
- `writeIntoEntries()` in `DocxStore`: write `commentsExtended.xml` as empty (`<w:commentsExtended .../>`) on every save to prevent dangling paraId references. Add it to `[Content_Types].xml` if not present.
- All DocxWriteQueue invariants unchanged — reads fresh from disk, serialized, fsync+atomic-rename.

### Golden tests

New fixture: DOCX with parent comment + one reply + `commentsExtended.xml` with `w15:paraIdParent` link.

Tests verify:
1. Reply is flattened to `ThreadEntry` on parent (not a standalone annotation)
2. After round-trip write, `commentsExtended.xml` in output is empty
3. Parent comment body in output has two `<w:p>` elements

Run after every engine change: `cd android_native && ./gradlew :docx:test`

### NoteActivity UI changes

**Three structural tweaks first (before adding the pane):**

1. **Remove "Add Note" title** from the top bar (it sits next to the back arrow and is confusing). If any label is needed, center it so it is visually distinct from the back arrow. Most likely just remove it.

2. **Move tag chips inline with the tool selector.** The tag buttons (voice, pacing continuity, query) currently sit in their own row. Move them to be on the same row as the tool-type selector (highlight, underline, double underline, strikethrough, etc.). This frees vertical space for the bottom pane.

3. **Adaptive row count.** Calculate the number of visible thread entries from `resources.displayMetrics.heightPixels` — same pattern as `FileBrowserActivity.recentsCapFromHeight()`. Nomad and Manta have different screen sizes; larger screens show more entries. Never hardcode a row count.

**Bottom pane — two tabs:**

Both tabs are always visible once any relevant data exists; greyed out when empty for that tab.

- **Ink tab**: Shows ink PNG at display size + Edit button → `InkNoteActivity`. Greyed if no ink PNG.
- **Thread tab**: Chronological `ThreadEntry` list, paginated (Prev/Next, no scrolling). Each row: entry text + formatted timestamp.
  - `source="leamh"` entries: tappable → pre-populates the existing text input field, button label changes to "Save" instead of "Add". Delete with confirmation dialog ("Delete this comment?" single tap confirm).
  - `source="word"` entries: read-only. Subtle visual indicator (lighter text or small badge). No edit or delete.
  - **Reply shortcut** on each entry: "Reply" button → inserts the first ~8 words of that entry wrapped in quotation marks into the text field. User types their response after the quote and taps Add. The whole thing (quote + response) is stored as one flat thread entry. No structural threading — purely a text convenience.

**Add flow:**
- User types in text field → taps **Add** → entry appears in Thread tab → Thread tab auto-selects so user sees the entry land.
- User can then tap the entry to edit (pre-populates field, button = "Save") or delete (confirmation dialog).

**Deletion of entire annotation:**
- If `threadEntries` is non-empty: confirmation dialog — *"Delete this annotation and its N comments?"* — single confirm tap.
- If no thread entries: delete immediately as today (no dialog).

### Key files to read before starting

| File | Why |
|---|---|
| `android_native/app/.../reader/NoteActivity.kt` | Current annotation edit UI — layout, save flow, tool picker |
| `android_native/docx/.../docx/CommentWriter.kt` | buildNoteComment() — needs multi-paragraph support |
| `android_native/docx/.../docx/DocxStore.kt` | writeIntoEntries() — add commentsExtended.xml clearing |
| `android_native/docx/.../docx/LegacyComments.kt` | parseComments() — add commentsExtended.xml read + reply flattening |
| `android_native/docx/.../docx/model/Annotation.kt` | Add threadEntries field + fromMap/toMap |
| `android_native/app/.../reader/AnnotationsPanelActivity.kt` | Panel that taps into NoteActivity — confirm integration points |

### Invariants — never break

1. **DocxWriteQueue is the only write path.** All DOCX writes: read fresh from disk, serialized executor, fsync+atomic-rename. No exceptions.
2. **Engine purity.** `docx/` module has zero `android.*` imports. `ThreadEntry` and all new model classes live in `docx/`.
3. **No scrolling on e-ink.** Thread list is paginated. Row count is calculated, never hardcoded.
4. **Run `./gradlew :docx:test` after every engine change.** 49 tests must stay green (add new ones for thread round-trip).
5. **`note` field is backward-compatible.** Do not replace it with threadEntries — existing saved annotations have only `note`.

---

## Key references

- `leamh_tracker.md` — full task history
- `android_native/README.md` — module layout, build environment
- `CLAUDE.md` — architecture invariants, e-ink rules, typography rules, coding standards
- Nomad WiFi ADB: `~/Library/Android/sdk/platform-tools/adb connect <device-ip>:5555`
- Logcat: `adb logcat -s LeamhActivity LeamhAnnotPanel InkNoteActivity DrawPathClient`
