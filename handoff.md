# Handoff — Layuv

**Threads:**
- **reading-marker** — persistent reading-position marker (macOS + iOS). Latest: 2026-07-11, commit `fa25d4c`.
- **nav-pane** — Outline|Bookmarks nav pane + heading extraction. Latest: 2026-06-30, commit `c5a5be0`.

---

## Thread: reading-marker

**Updated 2026-07-11. Commit `fa25d4c` on `native-port-drawpath-ink` (pushed).** Both platforms build; macOS runtime-verified, iOS NOT device-tested.

### What & why

User wanted a "you are here" reading-position indicator that appears on single click/tap, persists **per-document** (not per-session), and shows up when the same file is opened on another device (iPad). Delivered on macOS + iOS.

### Key decisions

- **Storage = `leamh/position.json` inside the DOCX** (a 0.0–1.0 plain-text `fraction`), reusing the existing cross-platform `ReadingPosition` engine part that Android already writes. No new engine code, no separate sync layer — the marker rides the file (iCloud/AirDrop/Word round-trip). Note: this is the SAME part Android uses for scroll-restore, so the concept is shared, not marker-specific (intentional).
- **Rendering = soft line band + triangle in the left margin.** THE key gotcha: `NSTextView`/`UITextView` leave a clip on the graphics context that excludes the inset margins, so anything painted in the margin was silently swallowed. **Fix: reset the clip to full bounds** (`NSBezierPath(rect: bounds).setClip()` / `ctx.resetClip()`) before drawing. This is why the band (over text) worked immediately but every margin-glyph attempt was invisible. Also: an emoji glyph ("✳") with a plain foregroundColor renders invisible — use drawn shapes, not font glyphs.
- **Gesture toggle** (`DocumentStore.markerOnDoubleClick`, Settings › Reader): off (default) = single marks / double-click edits an annotation; on = single edits / double marks. Resolves the single-click-overload (annotation edit vs marker).
- **"Pick up where you left off?" banner** slides in on open when a marker exists >2% into the doc; Jump scrolls to it. Non-modal capsule, auto-dismiss ~6s.

### Files changed

- `Packages/LeamhDocx/…` — NO engine change (reused `writePosition`/`loadPosition`/`ReadingPosition`/`LoadedDocument.position`).
- `Shared/DocumentStore.swift` — `readingMarkerFraction` (seeded from `doc.position` on load), `setReadingMarker(fraction:)` (serialized write-queue → `writePosition`), `markerOnDoubleClick` pref (UserDefaults).
- `macOS/ReaderView.swift` — `AnnotatingTextView` marker draw (`drawReadingMarker` w/ clip reset, band + margin triangle; `markerLineRect` handles TK1+TK2), `mouseDown` gesture logic, `charIndex(at:)`, VC `refreshReadingMarkers`/`markerOnDoubleClick`, `update(...markerFraction:)`, representable wiring.
- `macOS/AppSettingsView.swift` — "Reading Marker on Double-Click" toggle + footer copy.
- `macOS/HomeView.swift` — resume banner (`resumeBanner`, `maybeShowResumeBanner`, `jumpToReadingMarker` → `coordinator.scrollToCharOffset`).
- `iOS/PaginatedReader.swift` — `MarkerTextView` (UITextView subclass, same band+pointer+clip reset), `AnnotatingTextSurface` marker props + single/double-tap handlers + `dropMarker`.
- `iOS/ReaderTextView.swift` — `onReadingPositionChanged`, `markerFraction`, `refreshReadingMarkers`, `markerOnDoubleClick` fan-out, makePage seeding.
- `iOS/HomeView_iOS.swift` — resume banner in `ReaderScreen` + `onJumpToOffset` wiring.

### Unfinished / next steps

1. **iOS/iPad on-device verify** (the one real open item): band + margin triangle render, single-tap marks, double-tap edits, toggle flips behavior, resume banner + Jump. Paged-column margin pointer uses `back = max(2, inset.left-17)` since columns have 0 inset — check it's not clipped/ugly.
2. Optional tuning: band opacity (0.18), triangle size/color (ink @ 0.9).

### Gotchas

- Install script (`build_and_install.sh`) refuses because repo `CLAUDE.md` has an unrelated pre-existing modification → installed Release manually (`rm -rf /Applications/Layuv.app && cp -R`). Uncommitted & intentionally excluded: `CLAUDE.md` mod, untracked `GLOSSARY.md`, `handoff.md`.
- macOS scroll reader is **TextKit 1** at runtime (`layoutManager` non-nil, `textLayoutManager` nil) despite using `textContentStorage` — the marker draw handles both but TK1 is what actually runs.
- Marker fraction is UTF-16 (`NSString.length`) based on both platforms — keep consistent.

### Next session — paste this to start

```
Continuing Layuv, thread `reading-marker`, on branch `native-port-drawpath-ink`
(last commit `fa25d4c`, pushed). Persistent reading-position marker is done and
runtime-verified on macOS; iOS/iPad is built but NOT device-tested. Read the
`## Thread: reading-marker` section of HANDOFF.md and memory
`handoff_reading-marker.md` before starting.

First task: on-device verify the iPad reader — single-tap drops the line band +
left-margin triangle, double-tap edits an annotation (and the Settings ›
Reader › "Reading Marker on Double-Click" toggle flips those), and the "Pick up
where you left off?" banner appears on open with a working Jump. Fix any wiring
gaps found.
```

---

## Thread: nav-pane

**Updated 2026-06-30 (session 3). Commit `c5a5be0`** on `native-port-drawpath-ink`. Android engine 54 tests green. macOS Swift engine 43/43 green. Both platforms build.

### What was done & why

User asked how DOCX headers are handled → evolved into exposing heading paragraphs as a navigable outline. Full Outline|Bookmarks nav pane built on Android and Swift (macOS + iOS) in one session.

#### Engine — heading extraction (Android + Swift)

- **StyleResolver.kt** — added `outlineLevel: Int?` to `Props`; captures `<w:outlineLvl>` or infers from `Heading N` styleId convention
- **PlainTextMapper.kt / PlainTextMapper.swift** — `Heading(text, level: Int 0-based, charOffset: Int)` data type; `PlainMap` gains `headings: List<Heading>`; tracking vars per paragraph; blank headings skipped
- **DocxStore.kt / DocxStore.swift** — `headings` forwarded from `LoadedDocument`
- **Tests** — `HeadingExtractionTest.kt` (5 tests, Android) + `HeadingExtractionTests.swift` (5 tests, Swift); all pass; pure read-only change so existing goldens untouched

Swift note: Swift mapper infers level from styleId only (no `StyleResolver` equivalent in Swift engine); parity gap documented but harmless for standard Word/Pages/Google Docs documents.

#### Android — PageJumpOverlay (full rewrite) + ReaderActivity

- Full-screen `PopupWindow (MATCH_PARENT)` with **Outline | Bookmarks** tab strip
- `SwipeListLayout` — custom `LinearLayout` subclass using `onInterceptTouchEvent` to intercept horizontal flings; fixes the classic problem where child `PenTapListener` rows consume `ACTION_DOWN` and prevent the parent from seeing fling gestures
- Pagination nav row pinned to bottom via `weight=1f` spacer (no more floating up on short pages)
- `← N/N →` buttons remain as stylus fallback
- `computeItemsPerPage()` uses `0.55 × screenHeightDp − 48dp` (pagination row reserved) to keep nav always visible
- **Bookmark icon** in preview left margin: 66dp, horizontally centered in gap via computed `marginStart`, filled/outline reflects target page (not current reader page); tapping calls `onBookmarkPage` callback
- `ic_bookmark_outline` redrawn as thin stroked path (`strokeWidth=1.5`) — was thick filled inner frame
- `ic_toc.xml` added for Contents button
- **ReaderActivity**: pill `bookmarkToggle` replaced with inline `ImageView` (20dp) inside `pageCapsule` LinearLayout sharing one pill border (`5 / 53 ⌖`); tapping capsule opens nav pane; `contentsButton` (ic_toc) in the pill also opens it
- `toggleBookmarkForPage(pageIndex)` + `isPageBookmarkedAt(pageIndex)` helpers; use `readerView.pageStartOffsets()` → `charStartOfPage()` for accurate anchor
- `outlineItems()` maps `doc.headings` → `OutlineItem` via `readerView.pageForCharOffset()`

#### Swift — SidebarPanelView + ReaderView + HomeViews

- **SidebarPanelView.swift** (Shared) — Outline tab with indented heading list (`12pt × level`, capped at 4); tap → `onScrollToCharOffset`; `PageShuttleView` with live `Slider` + ±1 chevron buttons pinned at bottom when `paged && pageCount > 1`; new params: `onScrollToCharOffset`, `onGoToPage`, `currentPage: Binding<Int>`, `pageCount: Binding<Int>`, `paged: Binding<Bool>`
- **ReaderView.swift (macOS)** — `goToPage(_:)`, `scrollToCharOffset(_:)` on `ReaderViewController`; bridge methods on `ReaderCoordinator`
- **HomeView.swift (macOS)** — `Binding(get:set:)` wrappers around `readerCoordinator` `@Published` props
- **ReaderTextView.swift (iOS)** — `scrollToCharOffsetValue`, `goToPageValue` one-shot jump params; `onPageChanged: ((Int, Int) -> Void)?` fires after page transitions; `NavMode.isPaged` computed var
- **HomeView_iOS.swift** — `@State` vars for `readerCurrentPage`, `readerPageCount`; both `SidebarPanelView` calls updated

### ⚠️ Swift parity gap — next priority

The nav pane is **built** on Swift but NOT yet verified on device (iPad or macOS). Specifically:

1. **Outline tab** — `scrollToCharOffset` on macOS calls `ReaderViewController.scrollToCharOffset` which finds the page range and calls `goToPage`. On iOS it goes through `scrollToCharOffsetValue` one-shot binding. Neither has been tapped on a real device.
2. **PageShuttleView** — live slider drives `onGoToPage` → `readerCoordinator.goToPage` (macOS) or `goToPageValue` binding (iOS). Logic looks correct but untested at runtime.
3. **`NavMode.isPaged`** on iOS — added `var isPaged: Bool { self != .scroll }`; shuttle visibility depends on this being wired through `ReaderTextView` → `onPageChanged` → `HomeView_iOS @State`.

### Pre-existing uncommitted work (not part of that commit)

Still sitting dirty as of 2026-06-30 (Android AI reference library + Ask AI UX polish, 2026-06-28/29; see memory `handoff_android_ai_reflib.md`): `.claude/settings.json`, `HANDOFF_next.md`, `android_native/.../ai/AiProviderFactory.kt`, `AiReplyActivity.kt`, `AiSettingsActivity.kt`, `AskAiPanel.kt`. Pending on-device verify before commit.

### Gotchas

- `SwipeListLayout.onInterceptTouchEvent`: intercepts when `|dx| > touchSlop && |dx| > |dy| × 1.5`. Diagonal swipe may steal from vertical scroll — acceptable since the list doesn't scroll.
- `pageCapsule` tap opens nav overlay AND inner `bookmarkToggle` has its own tap; Android dispatches inner first, so bookmark tap doesn't bubble — correct.
- `pageStartOffsets()` may return null if `pageLayout` isn't ready; `toggleBookmarkForPage` falls back to `position = pageIndex / pageCount`.
- Swift engine tests require `unset GIT_CONFIG_COUNT …` (see CLAUDE.md).

### Next steps

1. **Swift parity on-device verify** — open nav pane on iPad, tap an outline row (scrolls to position), scrub shuttle (live page-turn), verify bookmark icon state
2. **macOS on-device verify** — same; also `PageShuttleView` chevrons/slider sync when reader turns pages by other means
3. **iOS parity gaps from prior session**: dark-follow pref, glass/fade chrome, hardware-keyboard nav
4. **Android AI reference library commit** — verify on-device then commit dirty AI files

### Previous sessions (nav-pane lineage)

<details>
<summary>2026-06-29 session 2 — AI export versioning + per-doc theming (bb0eaae)</summary>

DocumentStore.swift only. Export versioning mirrors Android (filename _draft_vN→N+1, _vN_export folders, keep-3 archive, reopen draft). Per-doc theming app-side (docThemes map, no engine change). Pending: sandbox draft-reopen write scope verify.
</details>

<details>
<summary>2026-06-29 session 1 — macOS Swift parity, écri themes, rename, glass chrome (bdd9694)</summary>

AI layer, reader nav (scroll+pageFlip), ink (InkCanvasView + macink format), écri PaperTheme palette, text import (.txt/.md→docx), Léamh→Layuv rename, serif-L icon, glass toolbar.
</details>

### Next session — paste this to start

```
Continuing Layuv, thread `nav-pane`, on branch `native-port-drawpath-ink` (commit
`c5a5be0`). Outline|Bookmarks nav pane is built on Android (verified on Nomad) and
Swift (built, not device-tested). Read the `## Thread: nav-pane` section of
HANDOFF.md and memory `handoff_nav_pane.md` first.

First task: Swift parity on-device verify for the nav pane — open the nav pane on
iPad, tap an outline row (should scroll to that heading), scrub the page shuttle
(should live page-turn), tap the bookmark icon in the preview (should toggle).
Then the same checks on macOS.
```
