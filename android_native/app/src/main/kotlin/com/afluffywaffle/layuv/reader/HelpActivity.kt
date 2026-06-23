package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.model.AnnotationTool

/**
 * Paginated in-app Help + About. Each page is a topic rendered from the app's own
 * live components (real tool icons via [ToolIconView], simple diagrams, style
 * samples) with labels — so it stays current when the UI changes and renders crisp
 * on e-ink, with no bundled screenshots to maintain. Reachable from the reader's
 * ⋯ overflow menu. E-ink rules: no animation, no swipe — plain Prev/Next paging.
 */
class HelpActivity : Activity() {

    private var pageIndex = 0
    private lateinit var pageContainer: FrameLayout
    private lateinit var pageLabel: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView

    // Topic-per-page. Each builder must fit one screen (no scroll on e-ink).
    private val pages: List<Pair<String, () -> View>> by lazy {
        listOf(
            "Reading"         to { buildReadingPage() },
            "Annotating text" to { buildAnnotatePage() },
            "Tags"            to { buildTagsPage() },
            "Comments & ink"  to { buildCommentsPage() },
            "Search"          to { buildSearchPage() },
            "Settings"        to { buildSettingsPage() },
            "About"           to { buildAboutPage() },
            "Thanks"          to { buildThanksPage() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderTheme.seedBodyFont(this)
        pageIndex = (savedInstanceState?.getInt(STATE_PAGE) ?: 0).coerceIn(0, pages.size - 1)
        setContentView(buildUi())
        showPage(pageIndex)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, pageIndex)
    }

    // -------------------------------------------------------------------------
    // Scaffold
    // -------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
        pageContainer = FrameLayout(this).apply { setPadding(dp(24f), dp(20f), dp(24f), dp(16f)) }
        root.addView(pageContainer, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
        root.addView(buildPager(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(8f), dp(16f), dp(8f))
        }
        header.addView(ChromeIconButton(this, R.drawable.ic_arrow_back) { finish() })
        header.addView(TextView(this).apply {
            text = "Help & About"
            typeface = ReaderTheme.chromeBold(this@HelpActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(ReaderTheme.INK_87)
            setPadding(dp(8f), 0, 0, 0)
        })
        return header
    }

    private fun buildPager(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            minimumHeight = dp(56f)
        }
        prevBtn = textButton("← Prev", bold = true) { showPage(pageIndex - 1) }
        nextBtn = textButton("Next →", bold = true) { showPage(pageIndex + 1) }
        pageLabel = TextView(this).apply {
            typeface = ReaderTheme.chrome(this@HelpActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER
        }
        bar.addView(prevBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        bar.addView(pageLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(nextBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return bar
    }

    private fun showPage(index: Int) {
        pageIndex = index.coerceIn(0, pages.size - 1)
        pageContainer.removeAllViews()
        pageContainer.addView(
            pages[pageIndex].second(),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        pageLabel.text = "${pageIndex + 1} / ${pages.size}"
        // Hide the end-stop button rather than dim it (alpha washes out on e-ink).
        prevBtn.visibility = if (pageIndex == 0) View.INVISIBLE else View.VISIBLE
        nextBtn.visibility = if (pageIndex == pages.size - 1) View.INVISIBLE else View.VISIBLE
    }

    // -------------------------------------------------------------------------
    // Page-content helpers
    // -------------------------------------------------------------------------

    /** Vertical page column seeded with the topic heading. */
    private fun pageColumn(title: String): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            typeface = ReaderTheme.chromeBold(this@HelpActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
            setTextColor(ReaderTheme.INK_87)
            setPadding(0, 0, 0, dp(14f))
        })
        return col
    }

    /** Body paragraph (Literata). */
    private fun para(text: String, topGap: Float = 12f): TextView = TextView(this).apply {
        this.text = text
        typeface = ReaderTheme.body(this@HelpActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTextColor(ReaderTheme.INK_87)
        setLineSpacing(0f, 1.3f)
        setPadding(0, dp(topGap), 0, 0)
    }

    /** Name + description row (used for tags + settings). */
    private fun definitionRow(name: String, desc: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14f), 0, 0)
            addView(TextView(this@HelpActivity).apply {
                text = name
                typeface = ReaderTheme.chromeBold(this@HelpActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(ReaderTheme.INK_87)
            })
            addView(TextView(this@HelpActivity).apply {
                text = desc
                typeface = ReaderTheme.body(this@HelpActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ReaderTheme.INK_54)
                setPadding(0, dp(2f), 0, 0)
            })
        }

    /** A drawable icon above a small label. */
    private fun drawableIconCell(iconRes: Int, label: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            addView(ImageView(this@HelpActivity).apply {
                setImageResource(iconRes)
                setColorFilter(ReaderTheme.INK_87)
            }, LinearLayout.LayoutParams(dp(32f), dp(32f)))
            addView(TextView(this@HelpActivity).apply {
                text = label
                typeface = ReaderTheme.chrome(this@HelpActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ReaderTheme.INK_54)
                gravity = Gravity.CENTER
                setPadding(0, dp(5f), 0, 0)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

    /** A live tool icon above a small label. */
    private fun toolIconCell(tool: AnnotationTool, label: String): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            addView(ToolIconView(this@HelpActivity, tool), LinearLayout.LayoutParams(dp(56f), dp(56f)))
            addView(TextView(this@HelpActivity).apply {
                text = label
                typeface = ReaderTheme.chrome(this@HelpActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ReaderTheme.INK_54)
                gravity = Gravity.CENTER
                setPadding(0, dp(2f), 0, 0)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

    /** Lay [cells] out in rows of [perRow], padding the last row with spacers. */
    private fun grid(perRow: Int, cells: List<View>): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        cells.forEachIndexed { i, cell ->
            if (i % perRow == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .also { it.topMargin = dp(12f) })
            }
            row!!.addView(cell, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        // Pad the final row so cells stay column-aligned. Use Space, NOT View: a
        // WRAP_CONTENT View returns the full AT_MOST size from getDefaultSize and
        // would inflate the row to the remaining height, shoving siblings off-screen.
        val remainder = cells.size % perRow
        if (remainder != 0) repeat(perRow - remainder) {
            row!!.addView(Space(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return grid
    }

    // -------------------------------------------------------------------------
    // Pages
    // -------------------------------------------------------------------------

    private fun buildReadingPage(): View {
        val col = pageColumn("Reading")
        col.addView(para("Léamh shows your document one page at a time, like a book — nothing scrolls.", 0f))
        col.addView(
            pageTurnDiagram(),
            LinearLayout.LayoutParams(MATCH_PARENT, dp(120f)).also { it.topMargin = dp(20f) },
        )
        col.addView(para("Tap the left or right edge of a page to turn back or forward."))
        col.addView(para("The bar at the bottom of the reader:", topGap = 22f))
        col.addView(
            grid(4, listOf(
                drawableIconCell(R.drawable.ic_list_alt, "Annotations"),
                drawableIconCell(R.drawable.ic_bookmark_outline, "Bookmarks"),
                drawableIconCell(R.drawable.ic_search, "Search"),
                drawableIconCell(R.drawable.ic_more_horiz, "Settings · Help"),
            )),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(4f) },
        )
        return col
    }

    /** A framed page with shaded left/right tap zones. */
    private fun pageTurnDiagram(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@HelpActivity, ReaderTheme.RADIUS_CARD)
                setColor(ReaderTheme.PAPER)
                setStroke(dp(2f), ReaderTheme.INK_26)
            }
        }
        fun zone(arrow: String, label: String): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ReaderTheme.FILL_06)
            addView(TextView(this@HelpActivity).apply {
                text = arrow
                typeface = ReaderTheme.chromeBold(this@HelpActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setTextColor(ReaderTheme.INK_54)
            })
            addView(TextView(this@HelpActivity).apply {
                text = label
                typeface = ReaderTheme.chrome(this@HelpActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ReaderTheme.INK_54)
            })
        }
        box.addView(zone("‹", "Prev"), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        box.addView(TextView(this).apply {
            text = "page"
            gravity = Gravity.CENTER
            typeface = ReaderTheme.bodyItalic(this@HelpActivity)
            setTextColor(ReaderTheme.INK_38)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }, LinearLayout.LayoutParams(0, MATCH_PARENT, 2f))
        box.addView(zone("›", "Next"), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        return box
    }

    private fun buildAnnotatePage(): View {
        val col = pageColumn("Annotating text")
        col.addView(para("Long-press a word — or circle it with the pen — to select text, then choose a tool:", 0f))
        col.addView(
            grid(4, listOf(
                toolIconCell(AnnotationTool.highlight, "Highlight"),
                toolIconCell(AnnotationTool.underline, "Underline"),
                toolIconCell(AnnotationTool.doubleUnderline, "Double"),
                toolIconCell(AnnotationTool.strikethrough, "Strikethrough"),
                toolIconCell(AnnotationTool.bookmark, "Bookmark"),
                toolIconCell(AnnotationTool.comment, "Comment"),
                toolIconCell(AnnotationTool.inkAnnotation, "Ink note"),
            )),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also { it.topMargin = dp(8f) },
        )
        col.addView(para("Highlights and comments show a light grey fill behind the text; underline, double-underline and strikethrough draw a line. A bookmark adds a margin marker.", topGap = 22f))
        return col
    }

    private fun buildTagsPage(): View {
        val col = pageColumn("Tags")
        col.addView(para("When you write a comment you can tag it, to classify the kind of note:", 0f))
        col.addView(definitionRow("Voice", "The voice doesn't sound like the character."))
        col.addView(definitionRow("Pacing", "This section feels too fast or too slow."))
        col.addView(definitionRow("Continuity", "A possible inconsistency with elsewhere."))
        col.addView(definitionRow("Query", "A question to come back to."))
        return col
    }

    private fun buildCommentsPage(): View {
        val col = pageColumn("Comments & ink")
        col.addView(para("Tap any annotation in the reader to bring up its toolbar, then tap the comment icon to open the editor.", 0f))
        col.addView(para("In the editor, the highlighted passage sits at the top of a thread. Add a comment, reply to one, or edit and delete your own — they stack up under the passage."))
        col.addView(para("Switch to the Ink tab to attach a handwritten note with the pen instead of typing."))
        col.addView(para("The expand icon opens a full-screen writing area with the passage kept in view, so you can quote from it while you write."))
        return col
    }

    private fun buildSearchPage(): View {
        val col = pageColumn("Search")
        col.addView(
            drawableIconCell(R.drawable.ic_search, "Search").also { it.setPadding(0, 0, 0, dp(8f)) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        col.addView(para("Tap the search icon in the bottom bar to find any text across the whole document.", 0f))
        col.addView(para("Tap a result to jump straight to that page; the match is briefly highlighted so you can spot it."))
        return col
    }

    private fun buildSettingsPage(): View {
        val col = pageColumn("Settings")
        col.addView(para("Open the ⋯ menu in the bottom bar to adjust the reader. Tap a row to cycle its options:", 0f))
        col.addView(definitionRow("Page turn", "Which screen edges turn the page."))
        col.addView(definitionRow("Font size · Line spacing", "How large and how airy the text is."))
        col.addView(definitionRow("Columns", "One or two columns per page."))
        col.addView(definitionRow("Font family", "Literata (serif) or Source Sans 3."))
        col.addView(definitionRow("Right to left", "For right-bound texts."))
        return col
    }

    private fun buildAboutPage(): View {
        val col = pageColumn("About")
        col.addView(TextView(this).apply {
            text = "Léamh"
            typeface = ReaderTheme.bodyBold(this@HelpActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(ReaderTheme.INK_87)
        })
        col.addView(para("A focused reader and annotator for manuscripts on e-ink. Marks and comments are stored as native DOCX comments, so they round-trip with Word, Pages and Google Docs.", topGap = 4f))
        col.addView(definitionRow("Version", appVersion()))
        col.addView(definitionRow("Bundle ID", "com.afluffywaffle.layuv"))
        col.addView(definitionRow("Licence", "GPL v3 — free, open source"))
        col.addView(definitionRow("Source", "github.com/afluffywaffle/layuv"))
        return col
    }

    private fun buildThanksPage(): View {
        val col = pageColumn("Thanks")
        col.addView(para("Léamh stands on the work of others.", 0f))
        col.addView(definitionRow(
            "Ratta · Supernote",
            "For sharing how to drive the low-latency drawPath ink layer — it's what makes the pen feel instant.",
        ))
        col.addView(definitionRow(
            "Literata · Source Sans 3",
            "The open-licensed typefaces Léamh reads and writes in.",
        ))
        col.addView(definitionRow(
            "Open source",
            "Built in Kotlin on Android, and released under the GPL so others can build on it too.",
        ))
        return col
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
    } catch (_: Exception) { "—" }

    companion object {
        private const val STATE_PAGE = "help_page"
    }
}
