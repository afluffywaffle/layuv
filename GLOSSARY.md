# GLOSSARY — UI terminology ↔ code names

A legend so we use the same words. When you (the reviewer) describe a fix, use
the **"You might call it"** column; I'll map it to the **Code name / file**.
If a term you use isn't here, add it — this file is the source of truth for
naming, per platform.

Bundle ID: `com.afluffywaffle.layuv` · App name: **Layuv**

---

## macOS — Swift app (`macos_native/LeamhApp/`)

| You might call it | Code name | File |
|---|---|---|
| The reading area / page view | ReaderView (NSTextView `AnnotatingTextView`) | `LeamhApp/ReaderView.swift` |
| Pop-up after selecting text (pick a tool) | Tool picker popover / `ToolPickerView` | `LeamhApp/ToolPickerView.swift` |
| Sidebar list of all annotations | Annotations panel / `AnnotationsPanel` | `LeamhApp/AnnotationsPanel.swift` |
| The box that opens to edit ONE annotation (note/tag/tool) | Annotation **edit sheet** / `AnnotationEditSheet` | `LeamhApp/AnnotationEditSheet.swift` |
| Recents list / left sidebar on home | Recents sidebar (`NavigationSplitView`) | `macOS/HomeView.swift` |
| The window itself (title, multi-window) | `HomeWindow` / `WindowGroup` | `macOS/LeamhAppApp.swift` |
| Top menu bar commands (Open/Save/Format/AI) | `DocumentCommands` | `macOS/LeamhAppApp.swift` |
| State store (open/save, annotation CRUD) | `DocumentStore` (`@MainActor`) | `Shared/DocumentStore.swift` |
| Ask-AI chat panel | Ask AI panel / `AskAiViewModel` | `Shared/` + macOS view |
| Ink drawing screen | `InkEditorView` | macOS ink view |
| Colours / fonts / paper | `AppTheme`, `PaperTheme` (écri palette) | `LeamhApp/AppTheme.swift`, `Shared/PaperTheme.swift` |

### Tools (the 8 annotation types)
Reader term → `AnnotationTool` case (`Packages/LeamhDocx/.../Model/Enums.swift`):

| Menu label | enum case |
|---|---|
| Highlight | `.highlight` |
| Underline | `.underline` |
| Double Underline | `.doubleUnderline` |
| Strikethrough | `.strikethrough` |
| Wavy Underline | `.wavyUnderline` |
| Bookmark | `.bookmark` |
| Comment | `.comment` |
| **Highlight Paragraph** (blockquote) | `.blockquote` |
| Ink | `.inkAnnotation` |

---

## Android — Kotlin app (`android_native/app/`)

| You might call it | Code name | File |
|---|---|---|
| The reading area / page view | ReaderView (software-layer View) | `.../reader/ReaderView.kt` |
| Main reader screen (nav, EPD, flow) | `ReaderActivity` | `.../reader/ReaderActivity.kt` |
| Sidebar/list of all annotations | Annotations panel | `.../reader/AnnotationsPanelActivity.kt` |
| The screen to edit ONE annotation | Note screen / `NoteActivity` | `.../reader/NoteActivity.kt` |
| Ink drawing screen | `InkNoteActivity` | `.../reader/InkNoteActivity.kt` |
| Underline/strikethrough line rendering | `HighlightPainter` | `.../reader/HighlightPainter.kt` |
| Colours / fonts | `ReaderTheme` | `.../reader/ReaderTheme.kt` |

Note: Android is e-ink (greyscale, no windows, no colour affordances). No
paper-theme colours, no multi-window.

---

## Engine (shared, both platforms mirror each other)

| Concept | Swift | Kotlin |
|---|---|---|
| DOCX read/write entry point | `DocxStore.swift` | `DocxStore.kt` |
| Writes comments.xml | (in `DocxStore`) | `CommentWriter.kt` |
| Anchoring bookmarks | `RunPropertyInjector` | `RunPropertyInjector.kt` |
| Annotation model | `Model/Annotation.swift` | `docx/model/Annotation.kt` |

macOS engine = `macos_native/Packages/LeamhDocx/`, Android engine =
`android_native/docx/`. Same fixtures, separate goldens.
