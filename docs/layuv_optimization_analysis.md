# Léamh — Optimization Analysis

_2026-07-12, read-only audit of both codebases. Finding → impact → fix → suggested model tier. Line numbers as of this date._

**Already well-optimized (leave alone):** Android binary-search pagination (`Paginator.kt`), the fsync/atomic-rename `DocxWriteQueue`, the annotation-save fast path avoiding full reload (`ReaderActivity.kt:1779`), in-place span mutation (`ReaderView.kt:324-349`), macOS `DocumentStore` detached parse/serialized writes, `ReaderViewController.update()` dirty-checks, explicit-submit search (no per-keystroke O(N) anywhere), lean startup on both platforms.

## android_native

1. **Full EPD GC16 flash on every annotation add, even on the span-only fast path.** `reader/ReaderView.kt:341` — fast path still calls `epd.fullClear(this)`; `Epd.kt` has cheaper `selection()`/`region()` (plain invalidate) primitives it bypasses. Every highlight/comment commit flashes the whole panel. **Fix:** route the span-only path through a partial refresh; reserve `fullClear` for repagination/book-open; keep the every-N-turns ghost flush. Needs on-device verification (file history documents abandoned waveform experiments). _Sonnet + device check._
2. **Every save re-inflates + re-deflates every ZIP entry, including unchanged ink PNGs.** `docx/DocxArchive.kt:38-53` (read) and `:62-89` (write). One annotation edit re-compresses all embedded media on the Supernote CPU (deflate level already tuned to BEST_SPEED, but unchanged entries shouldn't round-trip at all). **Fix:** keep original compressed bytes + CRC per entry; raw-copy bit-identical entries on write. Golden tests must pass (CLAUDE.md invariants). _Sonnet._
3. **`Anchoring.locateInPlain` re-anchors every annotation over the full text on every load.** `docx/Anchoring.kt:69-84` from `DocxStore.kt:103-105` — O(annotations × text) per open/panel-return even when text is unchanged. **Fix:** skip re-anchoring when a hash/length check shows the CLEAN document part unchanged since last resolve; preserve the survives-external-edits contract. _Sonnet, dedicated pass._
4. **`toMutableEntries()` full defensive copy (incl. media bytes) per write.** `DocxArchive.kt:28`. Fold into #2's copy-on-write design. _Haiku after #2._

## macos_native

5. **No compression-method preservation in the Swift engine at all.** `LeamhDocx/DocxArchive.swift:44-60` re-adds every part per save (Android at least preserves STORED). **Fix:** port Android's `entryMethods()` scheme; parity requirement makes this expected. _Sonnet, keep golden tests in both trees in sync._
6. **O(n) linear entry lookups.** `DocxArchive.swift:15-21` — `entries.first(where:)` vs Kotlin's HashMap; grows O(annotations × entries) with ink PNG presence checks. **Fix:** dictionary-backed storage preserving insertion order (round-trip compat); verify with `swift test`. _Haiku._
7. **Full re-pagination on annotation-only changes in `.pageFlip` mode.** `ReaderView.swift:602-720` — `contentChanged` conflates "text/font changed" with "annotations changed"; annotation spans are non-metric so page breaks can't move. **Fix:** split the dirty key into re-paginate vs restyle-only. _Sonnet, dedicated pass._
8. **Minor:** no worst-case bound in `findClosest` for very short selectedText — do alongside #3. _Haiku._

## Execution order

1. #1 (EPD flash — user-visible, cheapest), 2. #2+#4 and #5 (save-path recompression), 3. #6 (parity/lookup), 4. #3/#7 as a dedicated invalidation pass.

## Status

- _2026-07-12: analysis written. **Implemented same session, left uncommitted for review** (`:docx:test` 77/77, `swift test` 43/43, `:app:assembleDebug` green): #1 (partial refresh on span-only path — **needs on-device verify**: several consecutive annotation edits, watch for ghosting the old GC16 flash was masking), #2+#4 (raw-copy of bit-identical ZIP entries on write; `toMutableEntries` copy retained, documented), #5 (dictionary-backed Swift archive, insertion order preserved). #6 partial: compression method now preserved (STORED stays STORED) but full raw-copy of unchanged DEFLATED bytes not ported — ZIPFoundation's public API can't take pre-compressed bytes and hand-rolling a ZIP writer is forbidden by CLAUDE.md. #3/#7/#8 deferred._
