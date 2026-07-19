# Layuv — open bugs / follow-ups

## macOS Find (reported 2026-07-13)

### 1. cmd+F does nothing — must click the Find button
**Symptom:** Pressing ⌘F in the reader has no effect; the find bar only appears when the
user clicks Find (sidebar Find tab / button), which calls `coordinator.find()` →
`ReaderViewController.presentFind()`.
**Root cause:** No Find command is registered. `macOS/LeamhAppApp.swift` `.commands { … }`
replaces `.newItem`, adds Open/Save/Format, but never adds a `CommandGroup(replacing: .textEditing)`
(or an explicit `.find`) with a ⌘F item bound to `presentFind()`. AppKit's default ⌘F would
target the first responder's find action, but the reader's responder chain doesn't surface it here.
**Fix sketch:** Add a `CommandGroup` (or `CommandMenu("Find")`) with a Button `.keyboardShortcut("f")`
that routes to the active reader's `presentFind()` — needs the coordinator reachable from the
`.commands` scope (e.g. via a focused-value or a shared reference).

### 2. Find should present in the SIDEBAR with a results list, not the native find bar
**Symptom / expectation:** Current behaviour presents the native `NSTextView` find bar docked at
the top of the reader. Expected: Find happens in the **sidebar Find tab**, with the match results
listed BELOW the search field — tap a result to jump to it in the reader.
**Notes:** This is a design change, not just a relocation. Needs: a search field + incremental
full-text search over the document (the reader already has `fullText`), a results model
(match snippet + char offset), a results List in `FindPanel` (Shared/SidebarPanelView.swift),
and jump-to-offset via the existing `scrollToCharOffset` path. The native find bar can then be
dropped (or kept as a ⌘F fallback per bug #1).

### 3. (already known / out of scope) Find in Page-Flip mode
Paged columns aren't inside an `NSScrollView`, so the native find bar has nowhere to dock in
screen/page-flip mode. Superseded if bug #2 (sidebar find) lands, since sidebar find is
mode-independent.
