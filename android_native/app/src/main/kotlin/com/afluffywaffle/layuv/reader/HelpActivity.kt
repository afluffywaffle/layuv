package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
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
 * ⋯ overflow menu.
 *
 * Navigation mirrors the reader (see [EdgeNavView]): tap the edge strips — top half =
 * next, bottom half = prev — or swipe horizontally. No animation; a slim page
 * indicator sits at the bottom.
 */
class HelpActivity : Activity() {

    private var pageIndex = 0
    private lateinit var pageContainer: FrameLayout
    private lateinit var pageLabel: TextView

    // Swipe tracking for dispatchTouchEvent — mirrors SearchActivity / the reader.
    private var swipeDownX = 0f
    private var swipeDownY = 0f

    // Topic-per-page. Each builder must fit one screen (no scroll on e-ink).
    private val pages: List<Pair<String, () -> View>> by lazy {
        listOf(
            "Reading"         to { buildReadingPage() },
            "Annotating text" to { buildAnnotatePage() },
            "Comments & ink"  to { buildCommentsPage() },
            "Search"          to { buildSearchPage() },
            "Settings"        to { buildSettingsPage() },
            "Ask AI"          to { buildAiPage() },
            "Directing the AI" to { buildAiNotesPage() },
            "About"           to { buildAboutPage() },
            "Thanks"          to { buildThanksPage() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReaderTheme.seedBodyFont(this)
        pageIndex = (savedInstanceState?.getInt(STATE_PAGE) ?: startPageIndex()).coerceIn(0, pages.size - 1)
        setContentView(buildUi())
        showPage(pageIndex)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, pageIndex)
    }

    /**
     * Horizontal swipe turns the page, matching the reader and [SearchActivity].
     * Edge taps are handled by the [EdgeNavView] overlay; observed here regardless of
     * which view consumes them. A swipe that begins in a strip is reconciled in
     * [EdgeNavView] (its tap handler ignores dragged gestures), so only this fires.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swipeDownX = ev.x; swipeDownY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (Math.abs(dx) > dp(60f) && Math.abs(dx) > Math.abs(dy)) {
                    showPage(if (dx < 0) pageIndex + 1 else pageIndex - 1)
                }
            }
            MotionEvent.ACTION_CANCEL -> { swipeDownX = 0f; swipeDownY = 0f }
        }
        return super.dispatchTouchEvent(ev)
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

        // Content region: padded page content, with the edge-nav overlay full-bleed on
        // top so its strips sit at the true screen edges (not inside the page padding).
        // Inset the content horizontally PAST the nav strips (plus a small margin) so
        // text clears the chevrons and the dotted rail (mirrors the reader, whose
        // columns sit a margin in from the strip edge).
        val content = FrameLayout(this)
        val sidePad = dp(EdgeNavView.NAV_STRIP_DP + 16f)
        pageContainer = FrameLayout(this).apply { setPadding(sidePad, dp(20f), sidePad, dp(16f)) }
        content.addView(pageContainer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        content.addView(
            EdgeNavView(
                this,
                onNext = { showPage(pageIndex + 1) },
                onPrev = { showPage(pageIndex - 1) },
            ),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))
        root.addView(buildFooter(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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

    /** Slim centred page indicator — paging is by the edge strips + swipe. */
    private fun buildFooter(): View {
        pageLabel = TextView(this).apply {
            typeface = ReaderTheme.chrome(this@HelpActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_54)
            gravity = Gravity.CENTER
            setPadding(0, dp(10f), 0, dp(10f))
        }
        return pageLabel
    }

    private fun showPage(index: Int) {
        val clamped = index.coerceIn(0, pages.size - 1)
        if (clamped == pageIndex && pageContainer.childCount > 0) return // already showing
        pageIndex = clamped
        pageContainer.removeAllViews()
        pageContainer.addView(
            pages[pageIndex].second(),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
        pageLabel.text = "${pageIndex + 1} / ${pages.size}"
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

    /** A bold in-page section heading, smaller than the page title. */
    private fun subheading(text: String, topGap: Float = 26f): TextView = TextView(this).apply {
        this.text = text
        typeface = ReaderTheme.chromeBold(this@HelpActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTextColor(ReaderTheme.INK_87)
        setPadding(0, dp(topGap), 0, dp(2f))
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
        col.addView(para("Layuv shows your document one page at a time, like a book — nothing scrolls.", 0f))
        col.addView(
            EdgeNavView(this, diagram = true),
            LinearLayout.LayoutParams(MATCH_PARENT, dp(150f)).also { it.topMargin = dp(20f) },
        )
        col.addView(para("A tall strip runs down both side edges. Tap the top half of either edge to turn to the next page, the bottom half to turn back. A sideways swipe turns the page too — and is how you turn pages if you set Page turn to None in Settings."))
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
        col.addView(para("To mark up quickly, long-press a tool: a small card offers “Apply once” or “Lock tool”. Locking keeps that tool active, so every new selection is annotated with it automatically — no need to reopen the picker. The locked tool shows in the bottom bar; tap it to unlock.", topGap = 18f))
        return col
    }

    private fun buildCommentsPage(): View {
        val col = pageColumn("Comments & ink")
        col.addView(para("Tap any annotation in the reader to bring up its toolbar, then tap the comment icon to open the editor.", 0f))
        col.addView(para("In the editor, the highlighted passage sits at the top of a thread. Add a comment, reply to one, or edit and delete your own — they stack up under the passage."))
        col.addView(para("Switch to the Ink tab to attach a handwritten note with the pen instead of typing. The expand icon opens a full-screen writing area with the passage kept in view, so you can quote from it while you write."))

        col.addView(subheading("Tags"))
        col.addView(para("Tag a comment to classify the kind of note:", topGap = 2f))
        col.addView(definitionRow("Voice", "The voice doesn't sound like the character."))
        col.addView(definitionRow("Pacing", "This section feels too fast or too slow."))
        col.addView(definitionRow("Continuity", "A possible inconsistency with elsewhere."))
        col.addView(definitionRow("Query", "A question to come back to."))
        return col
    }

    private fun buildSearchPage(): View {
        val col = pageColumn("Search")
        col.addView(
            drawableIconCell(R.drawable.ic_search, "Search").also { it.setPadding(0, 0, 0, dp(8f)) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        col.addView(para("Tap the search icon in the bottom bar to find any text across the whole document.", 0f))
        col.addView(para("Tap a result to see it with more surrounding text in a popup; tap Go to page to jump there, where the match is briefly highlighted so you can spot it."))
        return col
    }

    private fun buildSettingsPage(): View {
        val col = pageColumn("Settings")
        col.addView(para("Open the ⋯ menu in the bottom bar to adjust the reader. Tap a row to cycle its options:", 0f))
        col.addView(definitionRow("Page turn", "Which screen edges turn the page — choose None to turn by swipe only."))
        col.addView(definitionRow("Font size · Line spacing", "How large and how airy the text is."))
        col.addView(definitionRow("Columns", "One or two columns per page."))
        col.addView(definitionRow("Font family", "Literata (serif) or Source Sans 3."))
        col.addView(definitionRow("Right to left", "For right-bound texts."))
        col.addView(definitionRow("Flatten ink", "Bake ink strokes into a flat image — freehand erase only afterward, and it can't be undone."))
        return col
    }

    /** Open at the page named by [EXTRA_PAGE] (e.g. deep-linked "Ask AI"), else page 0. */
    private fun startPageIndex(): Int {
        val title = intent.getStringExtra(EXTRA_PAGE) ?: return 0
        return pages.indexOfFirst { it.first == title }.coerceAtLeast(0)
    }

    // -------------------------------------------------------------------------
    // Ask AI page — a required-acknowledgments gate. The user must open and accept
    // each topic dialog before "AI settings" (key entry) unlocks. All AI config is
    // deliberate and lives here, not in the reader's quick-settings overflow.
    // -------------------------------------------------------------------------

    private data class AiAck(val key: String, val label: String, val text: String, val button: String)

    private fun aiAcks(): List<AiAck> = listOf(
        AiAck("ai_ack_privacy",    "Privacy & sending your data", AI_PRIVACY_TEXT,    "I understand and accept"),
        AiAck("ai_ack_storage",    "How your key is stored",      AI_STORAGE_TEXT,    "I understand"),
        AiAck("ai_ack_encryption", "Encryption in transit",       AI_ENCRYPTION_TEXT, "I understand"),
        AiAck("ai_ack_verify",     "Verifying this yourself",     AI_VERIFY_TEXT,     "I understand"),
    )

    private fun buildAiPage(): View {
        val col = pageColumn("Ask AI")
        col.addView(para("Ask AI rewrites the whole chapter to address your annotations — a complete new draft, not edits to just the parts you marked. It needs an AI provider's API key, not a chat subscription: a paid Claude or ChatGPT plan won't work (Gemini's API key is free). With no AI set up, Layuv connects to nothing and stays a fully offline annotator.", 0f))
        col.addView(para("Open and accept each point below; AI settings then unlocks so you can add your key.", topGap = 14f))

        val prefs = getSharedPreferences("leamh", MODE_PRIVATE)
        val acks = aiAcks()

        val settingsRow = definitionRow("AI settings", "Add, change, or remove your API key.")
        fun refreshSettings() {
            val ok = acks.all { prefs.getBoolean(it.key, false) }
            prefs.edit().putBoolean("ai_disclosure_accepted", ok).apply()
            settingsRow.alpha = if (ok) 1f else 0.4f
            settingsRow.isEnabled = ok
            settingsRow.setOnTouchListener(
                if (ok) PenTapListener(this) { startActivity(Intent(this, AiSettingsActivity::class.java)) }
                else null,
            )
        }

        acks.forEach { ack ->
            col.addView(ackRow(ack, prefs.getBoolean(ack.key, false)) {
                prefs.edit().putBoolean(ack.key, true).apply()
                refreshSettings()
            })
        }
        refreshSettings()
        col.addView(settingsRow)
        return col
    }

    /** How annotations drive the rewrite — its own page so the gated "Ask AI" page stays one screen. */
    private fun buildAiNotesPage(): View {
        val col = pageColumn("Directing the AI")
        col.addView(para("What you mark tells the AI where to focus and why:", 0f))
        col.addView(definitionRow("Marks", "The passage you select and the tool — a highlight says \"look here,\" strikethrough means \"cut this.\""))
        col.addView(definitionRow("Typed notes", "A note tells it why — \"show, don't tell,\" \"tighten this.\""))
        col.addView(definitionRow("Handwritten ink", "A note you write with the pen — print or cursive — is read too (sent as an image). Scrawl an instruction and it acts on it."))
        col.addView(para("If your intent isn't obvious from the passage, add a note — typed or handwritten — saying so.", topGap = 14f))
        return col
    }

    /** A checklist row: a check box (filled once acknowledged) + label; tap opens the topic dialog. */
    private fun ackRow(ack: AiAck, initiallyDone: Boolean, onAccept: () -> Unit): View {
        val icon = ImageView(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPadding(0, dp(14f), 0, 0)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(24f), dp(24f)))
        row.addView(TextView(this).apply {
            text = ack.label
            typeface = ReaderTheme.chromeBold(this@HelpActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ReaderTheme.INK_87)
            setPadding(dp(10f), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        var done = initiallyDone
        fun refreshIcon() {
            val d = getDrawable(if (done) R.drawable.ic_check_box else R.drawable.ic_check_box_blank)!!.mutate()
            d.setTint(if (done) ReaderTheme.INK_87 else ReaderTheme.INK_38)
            icon.setImageDrawable(d)
        }
        refreshIcon()
        row.setOnTouchListener(PenTapListener(this) {
            LeamhDialog.info(this, ack.text, ack.button) {
                if (!done) { done = true; refreshIcon() }
                onAccept()
            }
        })
        return row
    }

    private fun buildAboutPage(): View {
        val col = pageColumn("About")
        col.addView(nameEntry())
        col.addView(para("A focused reader and annotator for manuscripts on e-ink. Marks and comments are stored as native DOCX comments, so they round-trip with Word, Pages and Google Docs.", topGap = 16f))
        col.addView(subheading("Background"))
        col.addView(para("Layuv was built — with heavy help from AI — to fill a gap in a literary project of my own. I wanted one reader that came with me across phone, Supernote and laptop, so I could review, critique and leave feedback on the work of an AI writer wherever I was. A private workaround grew into the app you’re using now.", topGap = 2f))
        col.addView(definitionRow("Version", appVersion()))
        col.addView(definitionRow("Bundle ID", "com.afluffywaffle.layuv"))
        col.addView(definitionRow("Licence", "GPL v3 — free, open source"))
        col.addView(definitionRow("Source", "github.com/afluffywaffle/layuv"))
        col.addView(definitionRow("Contact · Support", "afluffywaffle.com/stack").apply {
            setOnClickListener { openUrl("https://afluffywaffle.com/stack/") }
        })
        return col
    }

    /** Open [url] in a browser; quietly does nothing if no handler is available. */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { /* no browser on device — the URL is shown as text anyway */ }
    }

    /** Dictionary-style entry for the app name: headword, pronunciation, origin. */
    private fun nameEntry(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Headword + pronunciation, baseline-aligned like a dictionary lemma.
        addView(LinearLayout(this@HelpActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@HelpActivity).apply {
                text = "Layuv"
                typeface = ReaderTheme.bodyBold(this@HelpActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                setTextColor(ReaderTheme.INK_87)
            })
            addView(TextView(this@HelpActivity).apply {
                text = "| lay-uv |  v."
                typeface = ReaderTheme.bodyItalic(this@HelpActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(ReaderTheme.INK_54)
                setPadding(dp(12f), 0, 0, 0)
            })
        })
        addView(TextView(this@HelpActivity).apply {
            text = "Irish · from léamh, “to read.”"
            typeface = ReaderTheme.bodyItalic(this@HelpActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ReaderTheme.INK_54)
            setPadding(0, dp(5f), 0, 0)
        })
    }

    private fun buildThanksPage(): View {
        val col = pageColumn("Thanks")
        col.addView(para("Layuv stands on the work of others.", 0f))
        col.addView(definitionRow(
            "Ratta · Supernote",
            "For sharing how to drive the low-latency drawPath ink layer — it's what makes the pen feel instant.",
        ))
        col.addView(definitionRow(
            "Literata · Source Sans 3",
            "The open-licensed typefaces Layuv reads and writes in.",
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

        /** Intent extra: a page title to open at (e.g. "Ask AI"). */
        const val EXTRA_PAGE = "help_start_page"

        private const val AI_PRIVACY_TEXT =
            "Ask AI always sends the whole chapter — its full text, your annotations, and any " +
                "handwritten ink notes (as images) — to the AI provider you configure, over the " +
                "internet. It works on the entire chapter at once and returns a complete new draft, " +
                "not edits to only the parts you marked. It is a third-party service, so don't " +
                "use Ask AI for confidential work you can't share.\n\n" +
                "Nothing is sent anywhere until you add a key and tap Send. With no AI configured, " +
                "Layuv connects to nothing and stays a fully offline reader and annotator.\n\n" +
                "Anthropic's commercial API does not train on data you submit."

        private const val AI_STORAGE_TEXT =
            "Your API key is encrypted on this device using the Android Keystore, and it is tied to " +
                "this device — a copy of the file is useless anywhere else. It is never shown on screen " +
                "and never written to logs.\n\n" +
                "Layuv has no server of its own and no analytics, so your key is only ever sent to the " +
                "AI provider you configure — never to the developer."

        private const val AI_ENCRYPTION_TEXT =
            "Cloud providers such as Anthropic's Claude are reached over HTTPS, so your chapter, " +
                "annotations, key and the replies are encrypted in transit with TLS, and the server's " +
                "certificate is verified.\n\n" +
                "If you later point Layuv at a model on your own network, that connection is usually " +
                "plain HTTP — not encrypted by the app, but it never leaves your Wi-Fi. To reach a home " +
                "model from elsewhere, use a VPN such as Tailscale, which encrypts the whole connection. " +
                "Never expose a plain-HTTP model to the internet."

        private const val AI_VERIFY_TEXT =
            "You don't have to take our word for any of this:\n\n" +
                "•  Layuv is open source (GPL-3.0). You can read exactly how your key is stored and where " +
                "requests go: github.com/afluffywaffle/layuv\n\n" +
                "•  Watch the network with an on-device proxy such as PCAPdroid — you'll see only your " +
                "chosen provider's address, and nothing sent to the developer.\n\n" +
                "•  Scan the app with a privacy checker like exodus-privacy or MobSF: it has no trackers.\n\n" +
                "•  Install from F-Droid, which builds the app from that public source — so the app you " +
                "run matches the code you can read."
    }
}
