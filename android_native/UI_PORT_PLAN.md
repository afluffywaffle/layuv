# Léamh Native Chrome Port Plan — Faithful Flutter UI Re-creation (Supernote e-ink)

> Generated 2026-06-14 from a fidelity audit of the Flutter UI vs the current native state.
> **Scope:** chrome only. `ReaderView.kt` (Canvas reading surface) + `HighlightPainter.kt` are FROZEN.

**Core architectural decision:** floating overlay chrome (selection toolbar, undo pill,
action toolbar, app-bar pill) stays `PopupWindow` + programmatic Views over the reader.
Full-screen / static chrome (annotations panel, note/edit panel, settings, dialogs) moves to
XML layouts inflated under `LayoutInflater` with the existing `Theme.Leamh` (Material Light,
**no AndroidX** — no AppCompat/Material3/Compose). Icons go through a hardened
`ToolIconRenderer` + a growing vector-drawable set.

---

## 1. Icon vocabulary

### Annotation tool glyphs (ToolIconRenderer)
| Tool | Flutter glyph | Native approach | Status |
|---|---|---|---|
| highlight | filled rect w=size h=size*0.7, fill 0x26000000, border black38 0.5px | fix aspect 1:0.7, thin ink_38 border, fill alpha 38 | PARTIAL — fix |
| underline | bold Literata "U" (0.875 cell) + 1dp bar | bump glyph 0.54→~0.70, thin bar full inset | PARTIAL — tune |
| doubleUnderline | "U" + two 1dp bars (gap 2dp) | match 2dp gap | PARTIAL — tune |
| strikethrough | bold "S" + font-intrinsic lineThrough | use STRIKE_THRU_TEXT_FLAG | PARTIAL — improve |
| bookmark | Icons.bookmark_border | keep ic_bookmark_outline, scale 0.70→0.80 | MATCH (tune) |
| comment | Icons.chat_bubble_outline | keep ic_chat_outline, scale 0.70→0.80 | MATCH (tune) |
| inkAnnotation | Icons.edit_outlined (list-only) | add ic_edit_outline + case | MISSING |
| wavyUnderline | bold "W" + wavy underline (dormant) | low priority | MISSING (low) |

### Chrome / action glyphs
| Action | Flutter glyph | Native | Status |
|---|---|---|---|
| annotations list (pill) | Icons.list_alt | add ic_list_alt, dim state ink vs 0x42 | MISSING |
| undo | Icons.undo | add ic_undo (pill + UndoToolbar) | MISSING |
| close | Icons.close | add ic_close (black87 / black54 tints) | MISSING |
| lock badge | Icons.lock | keep drawLockBadge | MATCH |
| long-press lock hint | CUSTOM 6×6 filled right-triangle bottom-right, black54 | replace top chevron in ToolIconView | MISMATCH — fix |
| settings | Icons.settings_outlined | add ic_settings_outline | MISSING |
| settings back | Icons.arrow_back | add ic_arrow_back | MISSING |
| settings check | Icons.check | add ic_check (20dp) | MISSING |
| overflow (pill) | Icons.more_horiz | keep drawOverflow (3 dots) | MATCH |
| note-expanded | Icons.chat_bubble (FILLED) | add ic_chat_filled | MISSING |
| delete | Icons.delete_outline | add ic_delete_outline | MISSING |
| edit-mode checkbox | Icons.check_box / check_box_outline_blank | add both | MISSING |

**Priority drawables:** ic_list_alt, ic_undo, ic_close, ic_delete_outline,
ic_settings_outline, ic_check, ic_arrow_back, ic_chat_filled, ic_check_box,
ic_check_box_blank, ic_edit_outline. Deferred: ic_view_column, ic_view_agenda,
ic_draw, ic_draw_outline, ic_chevron_left/right.

---

## 2. Components (target spec → mechanism → file)
- **2.1 AnnotationToolbar** — 80dp tall, 64×64 buttons ×6, paper, radius 8, border 0x1F 1dp, no shadow. PopupWindow + LinearLayout (AnnotationPopup.kt) + new toolbar_bg.xml.
- **2.2 ToolButton + corner-hint** — 64×64, 28dp icon; 6×6 bottom-right filled triangle black54; comment = no triangle. ToolIconView.kt, AnnotationPopup.kt.
- **2.3 LockPickerOverlay** — 148dp card, two 44dp rows, radius 10, black12 border. AnnotationPopup.kt + picker_bg.xml.
- **2.4 UndoToolbar** — 80×80 pill, Icons.undo 28dp, no tap-outside barrier. new UndoPopup.kt + ic_undo.
- **2.5 AnnotationActionToolbar** — 80dp, two 120dp icon+label buttons (Comment/Delete), 1dp divider. AnnotationPopup edit-mode branch + ic_delete_outline.
- **2.6 AppBarPill** — biggest gap. pill fill 0x0F radius 20, icon buttons + 1dp×40dp dividers: list_alt|undo|close|·|(lock)|more_horiz. icons 28dp, enabled black87 / dimmed black26. Rebuild ReaderActivity bottom toolbar + ChromeIconButton.kt + pill_bg.xml.
- **2.7 AnnotationsPanel** — full-screen XML. header Source Sans 3 bold 18, static-underline tabs, filter chips 32×32 radius 6, sectioned expandable tiles, edit-mode bar (greyscale destructive). Rewrite AnnotationsPanelActivity + layouts.
- **2.8 AnnotationPanel / NoteActivity** — full-screen sheet XML. tool selector chips (border-swap selected), quote box, note field (no autofocus), tag chips, solid black87 Save button. Rewrite NoteActivity + activity_note.xml + chip drawables.
- **2.9 Settings** — full-screen SettingsActivity XML. 3 radio groups, uppercase Source Sans 3 headers, check glyph, flat AppBar. Prefs keys EXACT: eink_nav_side, eink_nav_reversed, ink_rule_lines.
- **2.10 Dialogs** — themed LeamhDialog (paper, Literata) replacing stock AlertDialog (CLAUDE.md violation). delete-confirm "don't ask again" → pref delete_confirm_skip:<path>. Saving = static text, no spinner.
- **2.11 Nav strips / bottom zone** — FROZEN (ReaderView already faithful). Settings must write same pref keys.

---

## 3. Shared tokens (ReaderTheme.kt + res/values)
Greyscale alpha-over-black: INK_87 0xDD, INK_54 0x8A, INK_45 0x73, INK_38 0x61,
INK_26 0x42, INK_12 0x1F; fills FILL_06 0x0F, FILL_08 0x14, FILL_04 0x0A,
HL_FILL 0x26. Radii: pill 20, menu 12, card 10, btn 8, chip 6, tag 20, sheet 16.
Sizes: tool_btn 64, toolbar_h 80, icon 28, icon_sm 16, row_min 48.
Pen primitive: dwell 90ms / slop 14dp; circle 90dp / <500ms.
Drop opaque chrome_disabled #FF9A968E → use INK_26 over paper.
New files: dimens.xml, styles.xml, shape drawables (toolbar_bg, pill_bg, picker_bg,
chip_active/inactive, quote_box_bg, note_field_bg, save_btn_bg, tool_chip_selected/unselected).

---

## 4. Build order
**Phase 0 — Foundation:** ReaderTheme tokens + confirm body()==Literata; colors.xml + dimens.xml;
priority vectors; fix ToolIconRenderer (highlight aspect/border/alpha, underline/strike sizing,
STRIKE_THRU flag, scale tune); fix ToolIconView (corner triangle); pen-dwell + circle-tap primitive.
**Phase 1 — Overlay chrome:** shape drawables; AppBarPill rebuild; AnnotationToolbar restyle +
corner-hint + LockPicker card; ActionToolbar; UndoToolbar.
**Phase 2 — Panels:** LeamhDialog (first); AnnotationsPanel XML; NoteActivity XML.
**Phase 3 — Settings & polish:** SettingsActivity XML; page-counter pill; deferred drawables.

**Dependency flags:** chrome work NOT blocked on de-Onyx/EinkManager. But every overlay
show/dismiss + destructive op must call Epd refresh post-action (pen edits emit no touch event).
If de-Onyx changes Epd API, update the new chrome call sites.

---

## 5. E-ink gotchas
- No Material TabLayout (slides) → static underline. No animated expand → View.GONE toggle.
- No Slider, no RecyclerView animator/over-scroll glow (overScrollMode=never). Zero-duration routes.
- No ripple → stateListAnimator=null, no ripple bg.
- Destructive NOT red (reads mid-grey). Highlight swatch 0x26000000 never yellow.
- Selection = border swap / check glyph, never colour fill. Disabled = black26 alpha.
- Stylus pen-up held by OS → plain OnClickListener may not fire → use dwell-tap (90ms/14dp);
  CircleTap (90dp/<500ms) where Flutter uses CircleTappable; must yield to long-press.
- Tap targets ≥48dp (prefer 64). Widen 32dp filter-chip touch area.

**Frozen:** ReaderView.kt, HighlightPainter.kt.
