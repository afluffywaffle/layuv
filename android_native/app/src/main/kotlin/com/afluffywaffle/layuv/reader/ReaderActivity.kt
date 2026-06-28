package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.ai.AiExporter
import com.afluffywaffle.layuv.docx.DocxStore
import com.afluffywaffle.layuv.docx.LoadedDocument
import com.afluffywaffle.layuv.docx.ResolvedAnnotation
import com.afluffywaffle.layuv.docx.TextSpan
import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.ReadingMode
import com.afluffywaffle.layuv.docx.model.ReadingPosition
import com.afluffywaffle.layuv.docx.model.ThreadEntry
import com.afluffywaffle.layuv.docx.model.newId
import java.io.File
import java.time.Instant
import kotlin.math.roundToInt
import org.json.JSONArray

/**
 * The single reader screen. Classic Views (a thin chrome toolbar) above the
 * software-layer [ReaderView]. Picks a DOCX via SAF, resolves it to a real file
 * path, and reads/writes it DIRECTLY (java.io.File) — the Supernote's SAF
 * providers return read-only URIs, so direct-path is the only reliable
 * write-back route (matches the Flutter app). Reading position is persisted back
 * into the DOCX (`leamh/position.json`) so it round-trips with Flutter / Word.
 */
class ReaderActivity : Activity() {

    private lateinit var readerView: ReaderView
    private lateinit var pageIndicator: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var rootOverlay: FrameLayout
    private var lastPageIndex = 0
    private var lastPageCount = 1
    private var pageJumpOverlay: PageJumpOverlay? = null

    // In-reader "Ask AI" conversation panel (top half; toggled from the toolbar).
    private var aiPanel: AskAiPanel? = null

    // AppBarPill — icon pill replacing the old text-button toolbar.
    private lateinit var pillRow: LinearLayout
    private lateinit var annotationsButton: ChromeIconButton
    private lateinit var moreButton: ChromeIconButton
    private var lockSlot: LockSlotView? = null

    // Bookmark button — inside the pill; dimmed when current page has no bookmark.
    private lateinit var bookmarkButton: ChromeIconButton
    private lateinit var searchButton: ChromeIconButton
    private lateinit var aiMenuButton: AiChatButton

    // Title/filename label — bottom right of toolbar.
    private lateinit var titleLabel: TextView

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var book: OpenBook? = null
    @Volatile private var savingPosition = false
    // Char offset of the last position successfully written into the DOCX, so onPause
    // can skip a full re-zip when the reader hasn't moved since. -1 = nothing saved yet.
    private var lastSavedCharOffset = -1

    private val annotationPopup by lazy { AnnotationPopup(this) }
    private var pendingSelStart = -1
    private var pendingSelEnd = -1
    // Set when the user taps an existing annotation and then picks "comment" to edit its note.
    private var pendingAnnotation: ResolvedAnnotation? = null
    // Currently locked tool — new selections auto-annotate with this tool (no popup shown).
    private var lockedTool: AnnotationTool? = null
    // Annotation ID pre-allocated when launching InkNoteActivity so PNG and annotation share it.
    private var pendingInkId: String? = null
    // In-memory cache of the latest stroke JSON per annotation ID. Updated immediately on save
    // so editAnnotationNote can pass current strokes even before the background write completes.
    private val latestStrokes = mutableMapOf<String, String>()
    // Transient delete pill — floats above the annotation's selection anchor.
    private var lastAnchorX = 0
    private var lastAnchorY = 0
    private var undoPillView: LinearLayout? = null
    private val undoDismissRunnable = Runnable { dismissUndoPill() }
    private val undoRenderer by lazy { ToolIconRenderer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        DocxWriteQueue.init(this) // hold the CPU awake while saves drain (Supernote freezes it otherwise)
        ReaderTheme.seedBodyFont(this)
        setContentView(buildUi())
        readerView.setNavSide(prefs.getString(KEY_NAV_SIDE, "both") ?: "both")
        readerView.setNavReversed(prefs.getBoolean(KEY_NAV_REVERSED, false))
        applyTypographyPrefs()
        Log.i(TAG, "smallestScreenWidthDp=${resources.configuration.smallestScreenWidthDp} (auto 2-col >= $AUTO_TWO_COL_MIN_DP)")
        reopenLastOrPrompt()
    }

    // --- UI ------------------------------------------------------------------

    private fun buildUi(): View {
        rootOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // FrameLayout toolbar: pillRow pinned start, page pill truly centred.
        val toolbar = FrameLayout(this).apply {
            setBackgroundColor(ReaderTheme.PAPER)
            setPadding(dp(12f), dp(6f), dp(12f), dp(10f))
        }

        pillRow = buildPill()

        pageIndicator = TextView(this).apply {
            typeface = ReaderTheme.body(this@ReaderActivity)
            setTextColor(ReaderTheme.INK_87)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            minWidth = dp(80f)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = ReaderTheme.dp(this@ReaderActivity, ReaderTheme.RADIUS_PILL)
                setColor(ReaderTheme.PAPER)
                setStroke(dp(1f), ReaderTheme.INK_26)
            }
            setOnTouchListener(PenTapListener(this@ReaderActivity) {
                if (lastPageCount > 1) {
                    val overlay = pageJumpOverlay ?: PageJumpOverlay(
                        this@ReaderActivity,
                        previewProvider = { page -> readerView.previewTextForPage(page) },
                        onConfirm = { page -> readerView.jumpToPage(page) },
                        bodySizeSp = ReaderTheme.bodySizeSp(prefs.getString(KEY_FONT_SIZE, "medium") ?: "medium"),
                        onDismiss = { initDrawPathLasso() },
                    ).also { pageJumpOverlay = it }
                    if (DrawPathClient.available()) {
                        // Clear any drawn ink and blacklist the full screen so DrawPath
                        // doesn't render over the overlay. initDrawPathLasso() on dismiss restores.
                        val sw = resources.displayMetrics.widthPixels
                        val sh = resources.displayMetrics.heightPixels
                        DrawPathClient.clearScreen(packageName)
                        DrawPathClient.setWritableAreas(
                            packageName,
                            listOf(intArrayOf(0, 0, sw, sh, 0)),
                            "overlay-suppress",
                        )
                    }
                    overlay.show(
                        pageIndicator, lastPageIndex, lastPageCount,
                        readerView.bookmarkScrubberFractions(),
                        readerView.bookmarkPageIndices(),
                    )
                }
            })
            text = ""
        }

        titleLabel = TextView(this).apply {
            typeface = ReaderTheme.body(this@ReaderActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ReaderTheme.INK_87)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        toolbar.addView(
            pillRow,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL),
        )
        toolbar.addView(
            pageIndicator,
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER),
        )
        // Fixed-width slot so the title never overlaps the centred page indicator.
        toolbar.addView(
            titleLabel,
            FrameLayout.LayoutParams(dp(280f), WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL),
        )

        readerView = ReaderView(this).apply {
            onPageChanged = { page, count ->
                lastPageIndex = page
                lastPageCount = count
                pageIndicator.text = "${page + 1} / $count"
                updateBookmarkButton()
            }
            onSelectionReady = { start, end, anchorX, anchorY ->
                pendingSelStart = start
                pendingSelEnd = end
                lastAnchorX = anchorX
                lastAnchorY = anchorY
                val selText = readerView.textString()
                    ?.substring(start, end)?.trim() ?: ""
                val locked = lockedTool
                if (locked != null) {
                    // Auto-annotate with the locked tool — no popup.
                    // inkAnnotation cannot be usefully locked (it requires launching a full
                    // canvas activity per selection), so it falls back to highlight.
                    when (locked) {
                        AnnotationTool.comment -> startActivityForResult(
                            Intent(this@ReaderActivity, NoteActivity::class.java)
                                .putExtra(NoteActivity.EXTRA_SELECTED_TEXT, selText)
                                .putExtra(NoteActivity.EXTRA_INITIAL_TOOL, locked.name),
                            REQ_NOTE,
                        )
                        AnnotationTool.inkAnnotation -> commitAnnotation(AnnotationTool.highlight, null)
                        else -> commitAnnotation(locked, null)
                    }
                } else {
                annotationPopup.show(
                    anchor = this,
                    anchorX = anchorX,
                    anchorY = anchorY,
                    penMode = readerView.lastSelectionWasPen,
                    onTool = { tool ->
                        when (tool) {
                            AnnotationTool.comment -> startActivityForResult(
                                Intent(this@ReaderActivity, NoteActivity::class.java)
                                    .putExtra(NoteActivity.EXTRA_SELECTED_TEXT, selText)
                                    .putExtra(NoteActivity.EXTRA_INITIAL_TOOL, tool.name),
                                REQ_NOTE,
                            )
                            AnnotationTool.inkAnnotation -> launchInkCanvas(selText)
                            else -> commitAnnotation(tool, null)
                        }
                    },
                    onCopy = { copyText(selText) },
                    onShare = { shareText(selText) },
                    lockedTool = lockedTool,
                    onLockTool = { tool ->
                        // inkAnnotation can't be usefully locked — redirect to highlight.
                        val effectiveTool = if (tool == AnnotationTool.inkAnnotation) AnnotationTool.highlight else tool
                        lockedTool = effectiveTool
                        updateLockSlot()
                        // Apply to current selection immediately, then stay locked.
                        when (effectiveTool) {
                            AnnotationTool.comment -> startActivityForResult(
                                Intent(this@ReaderActivity, NoteActivity::class.java)
                                    .putExtra(NoteActivity.EXTRA_SELECTED_TEXT, selText)
                                    .putExtra(NoteActivity.EXTRA_INITIAL_TOOL, effectiveTool.name),
                                REQ_NOTE,
                            )
                            else -> commitAnnotation(effectiveTool, null)
                        }
                    },
                    onUnlock = { lockedTool = null; updateLockSlot() },
                )
                } // end else (no locked tool)
            }
            onHidePopup = { annotationPopup.dismissQuiet() }
            onHandleDragStart = {
                if (DrawPathClient.available()) {
                    val sw = resources.displayMetrics.widthPixels
                    val sh = resources.displayMetrics.heightPixels
                    DrawPathClient.setWritableAreas(
                        packageName,
                        listOf(intArrayOf(0, 0, sw, sh, 0)), // flag 0 = disable all ink during handle drag
                        "handle-drag",
                    )
                }
            }
            onHandleDragEnd = { initDrawPathLasso() }
            onAnnotationTapped = { resolved, anchorX, anchorY ->
                Log.d(TAG, "onAnnotationTapped: id=${resolved.annotation.id} tool=${resolved.annotation.tool} anchorX=$anchorX anchorY=$anchorY")
                // Faithful to Flutter's AnnotationActionToolbar: Comment | Delete.
                annotationPopup.showActions(
                    anchor = this,
                    anchorX = anchorX,
                    anchorY = anchorY,
                    penMode = readerView.lastSelectionWasPen,
                    onComment = {
                        Log.d(TAG, "action popup: Comment tapped for ${resolved.annotation.id}")
                        editAnnotationNote(resolved)
                    },
                    onDelete = {
                        Log.d(TAG, "action popup: Delete tapped for ${resolved.annotation.id}")
                        deleteAnnotation(resolved)
                    },
                )
            }
        }

        root.addView(readerView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // Ask-AI panel: a visibility-toggled child ABOVE the reader (index 0), equal
        // weight so it takes the top half when shown and zero height when GONE — the
        // reader stays usable below for reference. NOT a PopupWindow (alpha
        // compositing breaks on the e-ink software layer).
        aiPanel = AskAiPanel(
            this,
            onHide = { closeAiChat() },
            onOpenDraft = { openDraft(it) },
        ).also { root.addView(it, 0, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)) }
        // Dotted hairline anchors the bottom bar (it has no solid border) and matches
        // the edge-nav rails, so the chrome reads as one system instead of floating.
        root.addView(DottedDivider(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(2f)))
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        rootOverlay.addView(root)
        updatePillState()
        annotationPopup.onDismiss = { initDrawPathLasso() }
        return rootOverlay
    }

    /** The AppBarPill: annotations | bookmark | search | (lock) | more on a 6%-black rounded pill. */
    private fun buildPill(): LinearLayout {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.pill_bg)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        annotationsButton = ChromeIconButton(this, R.drawable.ic_list_alt) { launchAnnotationsPanel() }
        bookmarkButton = ChromeIconButton(this, R.drawable.ic_bookmark_outline) { togglePageBookmark() }.also {
            it.dimmed = true
        }
        moreButton = ChromeIconButton(this, R.drawable.ic_more_horiz) { showOverflowMenu() }
        searchButton = ChromeIconButton(this, R.drawable.ic_search) { launchSearch() }
        aiMenuButton = AiChatButton(this) { showAiMenu() }
        pill.addView(annotationsButton)
        pill.addView(divider())
        pill.addView(bookmarkButton)
        pill.addView(divider())
        pill.addView(searchButton)
        pill.addView(divider())
        pill.addView(aiMenuButton)
        pill.addView(divider())
        pill.addView(moreButton)
        return pill
    }

    /** A 1dp × 40dp black12 vertical divider, matching the Flutter pill divider. */
    private fun divider(): View = View(this).apply {
        setBackgroundColor(ReaderTheme.INK_12)
        layoutParams = LinearLayout.LayoutParams(dp(1f), dp(40f))
    }

    /** Insert/remove the lock-slot icon (tool + lock badge) before the more button. */
    private fun updateLockSlot() {
        lockSlot?.let { slot ->
            val idx = pillRow.indexOfChild(slot)
            if (idx >= 0) {
                pillRow.removeViewAt(idx + 1) // trailing divider
                pillRow.removeViewAt(idx)     // slot
            }
        }
        lockSlot = null
        val tool = lockedTool ?: return
        val slot = LockSlotView(this, tool) { lockedTool = null; updateLockSlot() }
        val insertAt = pillRow.indexOfChild(moreButton).coerceAtLeast(0)
        pillRow.addView(slot, insertAt)
        pillRow.addView(divider(), insertAt + 1)
        lockSlot = slot
    }

    /** Dim the annotations button when empty. */
    private fun updatePillState() {
        val anns = book?.doc?.annotations
        annotationsButton.dimmed = anns.isNullOrEmpty()
        updateBookmarkButton()
    }

    /** Filled icon = page is bookmarked; outline + dimmed = not bookmarked. */
    private fun updateBookmarkButton() {
        val (page, _) = readerView.pageInfo()
        val textLen = readerView.textLength()
        val hasBookmark = book?.doc?.annotations?.any { resolved ->
            if (resolved.annotation.tool != AnnotationTool.bookmark) return@any false
            val charOffset = resolved.span?.start
                ?: (resolved.annotation.position * textLen).toInt()
            readerView.pageForCharOffset(charOffset) == page
        } ?: false
        bookmarkButton.setIconRes(
            if (hasBookmark) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
        )
        bookmarkButton.dimmed = !hasBookmark
    }

    /**
     * Toggle a page bookmark: if the current page already has one, remove it;
     * otherwise create one anchored to the first character of the current page.
     */
    private fun togglePageBookmark() {
        val opened = book ?: return
        val file = opened.file ?: run {
            Toast.makeText(this, "File is read-only — can't save bookmark.", Toast.LENGTH_SHORT).show()
            return
        }
        val (page, _) = readerView.pageInfo()
        val textLen = readerView.textLength()
        val existing = opened.doc.annotations.firstOrNull { resolved ->
            if (resolved.annotation.tool != AnnotationTool.bookmark) return@firstOrNull false
            val charOffset = resolved.span?.start
                ?: (resolved.annotation.position * textLen).toInt()
            readerView.pageForCharOffset(charOffset) == page
        }
        if (existing != null) {
            val newList = opened.doc.annotations.map { it.annotation }.filter { it.id != existing.annotation.id }
            val optimistic = opened.doc.annotations.filter { it.annotation.id != existing.annotation.id }
            val optimisticDoc = LoadedDocument(opened.doc.plainMap, optimistic, opened.doc.position)
            book = OpenBook(opened.displayName, opened.bytes, optimisticDoc, file)
            readerView.updateAnnotations(optimistic)
            updateBookmarkButton()
            saveAnnotations(opened, file, newList)
        } else {
            val charOffset = readerView.currentCharOffset()
            val position = charOffset.toDouble() / textLen.coerceAtLeast(1)
            val annotation = Annotation(
                id = newId(),
                selectedText = "",
                prefix = "",
                suffix = "",
                tool = AnnotationTool.bookmark,
                position = position,
                timestamp = java.time.Instant.now(),
            )
            val optimistic = ResolvedAnnotation(annotation, null)
            val optimisticAnnotations = opened.doc.annotations + optimistic
            val optimisticDoc = LoadedDocument(opened.doc.plainMap, optimisticAnnotations, opened.doc.position)
            book = OpenBook(opened.displayName, opened.bytes, optimisticDoc, file)
            readerView.updateAnnotations(optimisticAnnotations)
            updateBookmarkButton()
            saveAnnotations(opened, file, optimisticAnnotations.map { it.annotation })
        }
    }

    /** Overflow menu: open document + all reader settings as tap-to-cycle rows. */
    private fun showOverflowMenu() {
        // Migrate ink_rule_lines from legacy Boolean to String on first open.
        try {
            prefs.getString(KEY_RULE_LINES, null)
        } catch (_: ClassCastException) {
            val wasOn = try { prefs.getBoolean(KEY_RULE_LINES, false) } catch (_: Exception) { false }
            prefs.edit().remove(KEY_RULE_LINES).putString(KEY_RULE_LINES, if (wasOn) "wide" else "none").commit()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.popup_bg)
        }
        var popup: PopupWindow? = null

        root.addView(overflowActionRow(getString(R.string.open_document)) {
            popup?.dismiss()
            launchOpen()
        }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_folder, 0, 0, 0)
            compoundDrawablePadding = dp(10f)
        })
        root.addView(overflowMenuDivider())
        root.addView(overflowCycleRow(
            "PAGE TURN",
            prefs.getString(KEY_NAV_SIDE, "both") ?: "both",
            listOf("both" to "Both sides", "left" to "Left only", "right" to "Right only", "none" to "None"),
        ) { value ->
            prefs.edit().putString(KEY_NAV_SIDE, value).apply()
            readerView.setNavSide(value)
        })
        root.addView(overflowMenuDivider())
        root.addView(overflowCycleRow(
            "FONT SIZE",
            prefs.getString(KEY_FONT_SIZE, "medium") ?: "medium",
            listOf("small" to "Small", "medium" to "Medium", "large" to "Large"),
        ) { value ->
            prefs.edit().putString(KEY_FONT_SIZE, value).apply()
            applyTypographyPrefs()
        })
        root.addView(overflowMenuDivider())
        root.addView(overflowCycleRow(
            "LINE SPACING",
            prefs.getString(KEY_LINE_SPACING, "comfortable") ?: "comfortable",
            listOf("normal" to "Normal", "comfortable" to "Comfortable", "spacious" to "Spacious"),
        ) { value ->
            prefs.edit().putString(KEY_LINE_SPACING, value).apply()
            applyTypographyPrefs()
        })
        root.addView(overflowMenuDivider())
        root.addView(overflowCycleRow(
            "COLUMNS",
            resolveColumns().toString(),
            listOf("1" to "1 column", "2" to "2 columns"),
        ) { value ->
            prefs.edit().putInt(KEY_COLUMNS, value.toInt()).apply()
            applyColumns()
        })
        root.addView(overflowMenuDivider())
        root.addView(overflowCycleRow(
            "RIGHT TO LEFT",
            if (prefs.getBoolean(KEY_NAV_REVERSED, false)) "true" else "false",
            listOf("false" to "Off", "true" to "On"),
        ) { value ->
            val reversed = value == "true"
            prefs.edit().putBoolean(KEY_NAV_REVERSED, reversed).apply()
            readerView.setNavReversed(reversed)
        })
        root.addView(overflowMenuDivider())
        root.addView(overflowCycleRow(
            "FONT FAMILY",
            prefs.getString(KEY_BODY_FONT, "literata") ?: "literata",
            listOf("literata" to "Literata", "source_sans" to "Source Sans"),
        ) { value ->
            prefs.edit().putString(KEY_BODY_FONT, value).apply()
            ReaderTheme.bodyFont = value
            recreate()
        })
        root.addView(overflowMenuDivider())
        root.addView(overflowActionRow("Flatten ink…") {
            popup?.dismiss()
            confirmFlattenInk()
        })
        root.addView(overflowMenuDivider())
        root.addView(overflowActionRow("Help & About") {
            popup?.dismiss()
            startActivity(Intent(this, HelpActivity::class.java))
        })
        val popupW = dp(310f)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupH = root.measuredHeight
        val loc = IntArray(2)
        moreButton.getLocationInWindow(loc)
        val x = (loc[0] + moreButton.width - popupW).coerceAtLeast(dp(8f))
        val y = (loc[1] - popupH - dp(4f)).coerceAtLeast(dp(8f))

        val pw = PopupWindow(root, popupW, WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            elevation = ReaderTheme.dp(this@ReaderActivity, 6f)
            isOutsideTouchable = true
        }
        popup = pw
        pw.showAtLocation(readerView, Gravity.TOP or Gravity.START, x, y)
    }

    private fun showAiMenu() {
        var aiPopup: PopupWindow? = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.popup_bg)
        }
        if (isAiConfigured()) {
            root.addView(overflowActionRow("AI Chat") { aiPopup?.dismiss(); toggleAiChat() })
        } else {
            root.addView(overflowActionRowWithSubtitle(
                label = "AI Chat",
                subtitle = "Configure in Help & About",
                dimmed = true,
            ))
        }
        root.addView(overflowMenuDivider())
        root.addView(overflowActionRow("Export for AI…") { aiPopup?.dismiss(); exportForAi() })
        root.addView(overflowActionRow("Import rewrite…") { aiPopup?.dismiss(); importRewrite() })
        root.addView(overflowMenuDivider())
        val exportFolderName = prefs.getString(KEY_AI_EXPORT_FOLDER, null)?.let { p ->
            val f = File(p); "${f.parentFile?.name ?: ""}/${f.name}"
        }
        root.addView(overflowActionRowWithSubtitle(
            label = "Set AI export folder…",
            subtitle = exportFolderName,
        ) {
            aiPopup?.dismiss()
            if (ensureAllFilesAccess()) {
                startActivityForResult(
                    Intent(this, FileBrowserActivity::class.java)
                        .putExtra(FileBrowserActivity.EXTRA_PICK_DIR, true),
                    REQ_PICK_AI_DIR,
                )
            }
        })
        val importFolderName = prefs.getString(KEY_IMPORT_FOLDER, null)?.let { p ->
            val f = File(p); "${f.parentFile?.name ?: ""}/${f.name}"
        }
        root.addView(overflowActionRowWithSubtitle(
            label = "Set import folder…",
            subtitle = importFolderName,
        ) {
            aiPopup?.dismiss()
            if (ensureAllFilesAccess()) {
                val startDir = prefs.getString(KEY_IMPORT_FOLDER, null)?.let(::File)?.takeIf { it.isDirectory }
                    ?: prefs.getString(KEY_AI_EXPORT_FOLDER, null)?.let(::File)?.takeIf { it.isDirectory }
                startActivityForResult(
                    Intent(this, FileBrowserActivity::class.java)
                        .putExtra(FileBrowserActivity.EXTRA_PICK_DIR, true)
                        .apply { if (startDir != null) putExtra(FileBrowserActivity.EXTRA_START_DIR, startDir.absolutePath) },
                    REQ_SET_IMPORT_FOLDER,
                )
            }
        })
        val popupW = dp(310f)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupH = root.measuredHeight
        val loc = IntArray(2)
        moreButton.getLocationInWindow(loc)
        val x = (loc[0] + moreButton.width - popupW).coerceAtLeast(dp(8f))
        val y = (loc[1] - popupH - dp(4f)).coerceAtLeast(dp(8f))
        val pw = PopupWindow(root, popupW, WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            elevation = ReaderTheme.dp(this@ReaderActivity, 6f)
            isOutsideTouchable = true
        }
        aiPopup = pw
        pw.showAtLocation(readerView, Gravity.TOP or Gravity.START, x, y)
    }

    private fun overflowCycleRow(
        label: String,
        initialValue: String,
        options: List<Pair<String, String>>,
        onPick: (String) -> Unit,
    ): View {
        var current = initialValue
        val valueLabel = TextView(this).apply {
            typeface = ReaderTheme.bodyBold(this@ReaderActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ReaderTheme.INK_87)
            text = options.firstOrNull { it.first == current }?.second ?: current
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58f)
            setPadding(dp(16f), dp(8f), dp(12f), dp(8f))
            isClickable = true
            isFocusable = true
            stateListAnimator = null
            addView(
                TextView(context).apply {
                    text = label
                    typeface = ReaderTheme.bodyBold(this@ReaderActivity)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(ReaderTheme.INK_54)
                    letterSpacing = 0.08f
                },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
            addView(valueLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(object : View(context) {
                override fun onDraw(canvas: Canvas) =
                    undoRenderer.drawVecIcon(canvas, R.drawable.ic_arrow_back, width / 2f, height / 2f, ReaderTheme.dp(this@ReaderActivity, 14f))
            }.apply { rotation = 180f }, LinearLayout.LayoutParams(dp(20f), dp(20f)))
            setOnTouchListener(PenTapListener(context) {
                val idx = options.indexOfFirst { it.first == current }
                val next = options[(idx + 1) % options.size]
                current = next.first
                valueLabel.text = next.second
                onPick(current)
            })
        }
    }

    private fun overflowActionRow(label: String, onClick: () -> Unit): android.widget.TextView =
        TextView(this).apply {
            text = label
            typeface = ReaderTheme.bodyBold(this@ReaderActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK)
            minimumHeight = dp(58f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            isClickable = true
            isFocusable = true
            stateListAnimator = null
            setOnTouchListener(PenTapListener(this@ReaderActivity) { onClick() })
        }

    private fun overflowActionRowWithSubtitle(
        label: String,
        subtitle: String?,
        dimmed: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = dp(58f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            stateListAnimator = null
            setOnTouchListener(PenTapListener(this@ReaderActivity) { onClick() })
        }
        if (dimmed) alpha = 0.45f
        addView(TextView(this@ReaderActivity).apply {
            text = label
            typeface = ReaderTheme.bodyBold(this@ReaderActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK)
        })
        if (!subtitle.isNullOrBlank()) {
            addView(TextView(this@ReaderActivity).apply {
                text = subtitle
                typeface = ReaderTheme.chrome(this@ReaderActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ReaderTheme.INK)
                alpha = 0.6f
                setPadding(0, dp(2f), 0, 0)
            })
        }
    }

    private fun overflowMenuDivider(): View = View(this).apply {
        setBackgroundColor(ReaderTheme.INK_12)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f))
    }

    /**
     * Ask the user to confirm flattening all ink annotations to PNG-only.
     * After flattening, lasso erase works at pixel level instead of removing
     * whole strokes, and the DOCX can no longer be un-flattened.
     */
    private fun confirmFlattenInk() {
        val opened = book ?: return
        val file   = opened.file ?: run {
            Toast.makeText(this, "File is read-only — can't flatten.", Toast.LENGTH_SHORT).show()
            return
        }
        val hasStrokes = opened.bytes.let { DocxStore.hasAnyInkStrokes(it) }
        if (!hasStrokes) {
            Toast.makeText(this, "No stroke data to flatten.", Toast.LENGTH_SHORT).show()
            return
        }

        LeamhDialog.confirm(
            this,
            message = "This removes all saved stroke data from the document. " +
                "After flattening, ink annotations can't be edited with the lasso eraser — " +
                "only the freeform eraser will work. This cannot be undone.",
            positiveLabel = "Flatten",
            negativeLabel = "Cancel",
            onConfirm = { flattenInk(opened, file) },
        )
    }

    private fun flattenInk(opened: OpenBook, file: File) {
        DocxWriteQueue.submit(
            file,
            transform = { base -> DocxStore.removeAllInkStrokes(base) },
            onSuccess = { newBytes ->
                val freshDoc = DocxStore.load(newBytes)
                main.post {
                    book = OpenBook(opened.displayName, newBytes, freshDoc, file)
                    Toast.makeText(this, "Ink flattened.", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { ex ->
                Log.e(TAG, "flattenInk failed", ex)
                main.post { Toast.makeText(this, "Could not flatten.", Toast.LENGTH_SHORT).show() }
            },
        )
    }

    /** Delete a specific annotation by ID. Called from the transient delete pill. */
    private fun deleteAnnotation(id: String) {
        Log.d(TAG, "deleteAnnotation(id=$id) called from undo pill")
        dismissUndoPill()
        val opened = book ?: return
        val file = opened.file ?: return
        val newList = opened.doc.annotations.map { it.annotation }.filter { it.id != id }
        // Optimistic update — remove from view immediately without waiting for the background write.
        val optimisticAnnotations = opened.doc.annotations.filter { it.annotation.id != id }
        val optimisticDoc = LoadedDocument(opened.doc.plainMap, optimisticAnnotations, opened.doc.position)
        book = OpenBook(opened.displayName, opened.bytes, optimisticDoc, opened.file)
        readerView.updateAnnotations(optimisticAnnotations)
        saveAnnotations(opened, file, newList, deletedId = id)
    }

    // --- Permission ----------------------------------------------------------

    /**
     * All-files access (Android 11+) so we can read/write DOCX files by path.
     * Returns true if already granted; otherwise sends the user to the system
     * settings toggle and returns false (they re-tap Open afterwards).
     */
    private fun ensureAllFilesAccess(): Boolean {
        if (Environment.isExternalStorageManager()) return true
        Toast.makeText(
            this,
            "Grant “All files access” so Layuv can open and save annotations, then tap Open again.",
            Toast.LENGTH_LONG,
        ).show()
        val appSpecific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(appSpecific)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                Log.e(TAG, "no All-files-access settings screen", e2)
            }
        }
        return false
    }

    // --- Open ----------------------------------------------------------------

    private fun launchOpen() {
        if (!ensureAllFilesAccess()) return
        startActivityForResult(Intent(this, FileBrowserActivity::class.java), REQ_BROWSE)
    }

    /**
     * Write a clean `<chapter>_for_ai.md` (chapter + annotations, via [AiExporter]) plus
     * any handwritten-note PNGs into the AI export folder — the configured one if set and
     * still a directory, otherwise the chapter's own folder. Sync layer (Syncthing /
     * Supernote Private Cloud) carries it to a Mac, where `claude` reads it. Never touches
     * the source `.docx`; runs on the write-queue thread so it sees the latest disk bytes.
     */
    private fun exportForAi() {
        val opened = book ?: run {
            Toast.makeText(this, "Open a document first.", Toast.LENGTH_SHORT).show()
            return
        }
        val configured = prefs.getString(KEY_AI_EXPORT_FOLDER, null)?.let(::File)?.takeIf { it.isDirectory }
        val dir = configured ?: opened.file?.parentFile ?: run {
            if (ensureAllFilesAccess()) {
                startActivityForResult(
                    Intent(this, FileBrowserActivity::class.java)
                        .putExtra(FileBrowserActivity.EXTRA_PICK_DIR, true),
                    REQ_PICK_AI_DIR,
                )
            }
            return
        }
        val rawName = opened.file?.name ?: opened.displayName
        val baseName = (if (rawName.endsWith(".docx", ignoreCase = true)) rawName.dropLast(5) else rawName)
            .ifBlank { "chapter" }
        // Strip _draft_vN suffix so all passes for the same chapter share one container.
        val draftVersionMatch = Regex("""_draft_v(\d+)$""", RegexOption.IGNORE_CASE).find(baseName)
        val fileVersion = draftVersionMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val cleanBase = (if (draftVersionMatch != null) baseName.substring(0, draftVersionMatch.range.first)
                         else baseName).ifBlank { "chapter" }
        // Version is always fileVersion + 1. The source file is bumped to this version on export,
        // so it’s always deterministic — no scanning needed.
        val version = fileVersion + 1
        val hasInkAnnotations = opened.doc.annotations.any { it.annotation.hasInk }

        val chapterDir = File(dir, cleanBase).also { it.mkdirs() }
        val outputDir: File
        val mdName: String
        val pngPrefix: String
        if (hasInkAnnotations) {
            outputDir = File(chapterDir, "${cleanBase}_v${version}_export").also { it.mkdirs() }
            mdName = "chapter.md"
            pngPrefix = "ink"
        } else {
            outputDir = chapterDir
            mdName = "${cleanBase}_v${version}_for_ai.md"
            pngPrefix = "${cleanBase}_v${version}_for_ai_image"
        }
        DocxWriteQueue.enqueueRead {
            try {
                val src = opened.file?.readBytes() ?: opened.bytes
                val export = AiExporter.build(
                    plainText = opened.doc.plainText,
                    annotations = opened.doc.annotations.map { it.annotation },
                    sourceDocxBytes = src,
                    mdName = mdName,
                    pngPrefix = pngPrefix,
                    cleanBase = cleanBase,
                    version = version,
                )
                export.files.forEach { DocxWriteQueue.writeAtomicDurable(File(outputDir, it.name), it.bytes) }

                // Bump the working file: create draft_vN copy alongside source, then open it.
                // Keep at most 3 draft_vN files in the working folder; archive the rest.
                val nextDraftFile: File? = opened.file?.parent?.let { parentPath ->
                    val parent = File(parentPath)
                    val nextFile = File(parent, "${cleanBase}_draft_v${version}.docx")
                    if (!nextFile.exists()) {
                        val tmp = File(parent, nextFile.name + ".tmp")
                        tmp.writeBytes(src)
                        tmp.renameTo(nextFile)
                    }
                    val draftRe = Regex(
                        """${Regex.escape(cleanBase)}_draft_v(\d+)\.docx$""",
                        RegexOption.IGNORE_CASE,
                    )
                    val draftFiles = parent.listFiles()
                        ?.mapNotNull { f ->
                            draftRe.find(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { v -> v to f }
                        }
                        ?.sortedByDescending { (v, _) -> v } ?: emptyList()
                    val toArchive = draftFiles.drop(3)
                    if (toArchive.isNotEmpty()) {
                        val archiveDir = File(parent, "$cleanBase archive").also { it.mkdirs() }
                        toArchive.forEach { (_, f) ->
                            val dest = File(archiveDir, f.name)
                            if (!dest.exists()) f.renameTo(dest)
                        }
                    }
                    nextFile
                }

                main.post {
                    if (nextDraftFile != null) loadFromFile(nextDraftFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI export failed", e)
            }
        }
    }

    private fun importRewrite() {
        val workingFile = book?.file ?: run {
            Toast.makeText(this, "Open a document first.", Toast.LENGTH_SHORT).show()
            return
        }

        val rawName = workingFile.name
        val baseName = if (rawName.endsWith(".docx", ignoreCase = true)) rawName.dropLast(5) else rawName
        val draftVersionMatch = Regex("""_draft_v(\d+)$""", RegexOption.IGNORE_CASE).find(baseName)
        val aiDir = prefs.getString(KEY_AI_EXPORT_FOLDER, null)?.let(::File)?.takeIf { it.isDirectory }
        val importFolder = prefs.getString(KEY_IMPORT_FOLDER, null)?.let(::File)?.takeIf { it.isDirectory }

        // Build candidate list: saved import folder first (most likely place Claude put the file),
        // then derived locations from the AI export folder.
        val autoFound: File? = if (draftVersionMatch != null) {
            val v = draftVersionMatch.groupValues[1].toIntOrNull() ?: 0
            val base = baseName.substring(0, draftVersionMatch.range.first)
            val rewriteName = "${base}_draft_v${v}.docx"
            buildList {
                if (importFolder != null) add(File(importFolder, rewriteName))
                if (aiDir != null) {
                    add(File(aiDir, "$base/$rewriteName"))
                    add(File(aiDir, rewriteName))
                    add(File(aiDir, "$base/${base}_v${v}_export/$rewriteName"))
                }
            }.firstOrNull { it.exists() }
        } else null

        if (autoFound != null) {
            doImportRewrite(autoFound, workingFile)
        } else {
            // No rewrite found (or no folder set) — open the browser pre-navigated to the import/AI folder.
            val startDir = importFolder ?: aiDir
            val intent = Intent(this, FileBrowserActivity::class.java)
            if (startDir != null) intent.putExtra(FileBrowserActivity.EXTRA_START_DIR, startDir.absolutePath)
            startActivityForResult(intent, REQ_IMPORT_REWRITE)
        }
    }

    private fun doImportRewrite(rewriteFile: File, workingFile: File) {
        DocxWriteQueue.enqueueRead {
            try {
                val rewriteBytes = rewriteFile.readBytes()
                val tmp = File(workingFile.parent!!, workingFile.name + ".tmp")
                tmp.writeBytes(rewriteBytes)
                tmp.renameTo(workingFile)
                main.post { loadFromFile(workingFile) }
            } catch (e: Exception) {
                Log.e(TAG, "Import rewrite failed", e)
            }
        }
    }

    private fun launchAnnotationsPanel() {
        val opened = book ?: run {
            Toast.makeText(this, "Open a document first.", Toast.LENGTH_SHORT).show()
            return
        }
        val file = opened.file ?: run {
            Toast.makeText(this, "File is read-only.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(
            Intent(this, AnnotationsPanelActivity::class.java)
                .putExtra(AnnotationsPanelActivity.EXTRA_DOCX_PATH, file.absolutePath),
            REQ_ANNOTATIONS,
        )
    }

    // --- Ask AI --------------------------------------------------------------

    private fun toggleAiChat() {
        val panel = aiPanel ?: return
        if (panel.isOpen) { closeAiChat(); return }
        val opened = book ?: run {
            Toast.makeText(this, "Open a document first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (opened.file == null) {
            Toast.makeText(this, "File is read-only.", Toast.LENGTH_SHORT).show()
            return
        }
        annotationPopup.dismissQuiet()
        panel.open(opened)
        readerView.post { initDrawPathLasso() }
    }

    private fun closeAiChat() {
        aiPanel?.close()
        readerView.post { initDrawPathLasso() }
    }

    /** Ask AI is "set up" once the disclosure is accepted AND an endpoint (base URL) is
     *  configured. The key is NOT part of the gate — a local server may legitimately have none. */
    private fun isAiConfigured(): Boolean =
        prefs.getBoolean("ai_disclosure_accepted", false) &&
            !prefs.getString("ai_base_url", "").isNullOrBlank()

    /** Keep the AI chat button (+ its divider) hidden until Ask AI is set up, so it isn't
     *  an idle affordance for users who haven't opted in. Re-checked in onResume. */

    /** Open a just-saved AI draft like a freshly browsed file (updates last-path + recents). */
    private fun openDraft(file: File) {
        prefs.edit().putString(KEY_LAST_PATH, file.absolutePath).apply()
        saveRecent(file.absolutePath)
        loadFromFile(file)
    }

    private fun launchSearch() {
        val file = book?.file ?: run {
            Toast.makeText(this, "Open a document first.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(
            Intent(this, SearchActivity::class.java)
                .putExtra(SearchActivity.EXTRA_DOCX_PATH, file.absolutePath)
                .apply {
                    val starts = readerView.pageStartOffsets()
                    if (starts != null) putExtra(SearchActivity.EXTRA_PAGE_STARTS, starts)
                },
            REQ_SEARCH,
        )
    }

    private fun applyColumns() {
        val opened = book ?: return
        val columns = resolveColumns()
        val offset = readerView.currentCharOffset()
        readerView.showContent(opened.doc.plainText, opened.doc.annotations, columns, offset, opened.doc.formatSpans)
    }

    private fun applyTypographyPrefs() {
        val fontSizeSp = ReaderTheme.bodySizeSp(prefs.getString(KEY_FONT_SIZE, "medium") ?: "medium")
        val spacingMult = ReaderTheme.lineSpacingMult(prefs.getString(KEY_LINE_SPACING, "comfortable") ?: "comfortable")
        readerView.setTypography(fontSizeSp, spacingMult)
        // Overlay captures bodySizeSp at construction — discard it so it rebuilds with the new size.
        pageJumpOverlay?.dismiss()
        pageJumpOverlay = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_BROWSE -> if (resultCode == RESULT_OK) {
                val path = data?.getStringExtra(FileBrowserActivity.EXTRA_PATH) ?: return
                val file = File(path)
                if (file.canRead()) {
                    prefs.edit().putString(KEY_LAST_PATH, file.absolutePath).apply()
                    saveRecent(file.absolutePath)
                    loadFromFile(file)
                } else {
                    readerView.showHint("Couldn’t read $path")
                }
            }
            REQ_IMPORT_REWRITE -> if (resultCode == RESULT_OK) {
                val path = data?.getStringExtra(FileBrowserActivity.EXTRA_PATH) ?: return
                val rewriteFile = File(path)
                val workingFile = book?.file ?: return
                // Remember the folder so future auto-detect checks here first.
                rewriteFile.parent?.let { parent ->
                    prefs.edit().putString(KEY_IMPORT_FOLDER, parent).apply()
                }
                doImportRewrite(rewriteFile, workingFile)
            }
            REQ_PICK_AI_DIR -> if (resultCode == RESULT_OK) {
                val dir = data?.getStringExtra(FileBrowserActivity.EXTRA_PATH) ?: return
                prefs.edit().putString(KEY_AI_EXPORT_FOLDER, dir).apply()
            }
            REQ_SET_IMPORT_FOLDER -> if (resultCode == RESULT_OK) {
                val dir = data?.getStringExtra(FileBrowserActivity.EXTRA_PATH) ?: return
                prefs.edit().putString(KEY_IMPORT_FOLDER, dir).apply()
            }
            REQ_NOTE -> {
                Log.d(TAG, "REQ_NOTE: resultCode=$resultCode note=${data?.getStringExtra(NoteActivity.EXTRA_NOTE)}")
                if (resultCode == RESULT_OK) {
                    val tool = AnnotationTool.fromName(data?.getStringExtra(NoteActivity.EXTRA_RESULT_TOOL))
                    val note = data?.getStringExtra(NoteActivity.EXTRA_NOTE)
                    val tag  = AnnotationTag.fromName(data?.getStringExtra(NoteActivity.EXTRA_RESULT_TAG))
                    val threads    = ThreadJson.decode(data?.getStringExtra(NoteActivity.EXTRA_THREAD_JSON))
                    val ink        = TempFiles.readBytes(this, NoteActivity.FILE_RESULT_PNG)
                    val inkId      = data?.getStringExtra(NoteActivity.EXTRA_INK_ID)
                    val strokeJson = TempFiles.readText(this, NoteActivity.FILE_RESULT_JSON)
                    commitAnnotationFromPanel(tool, note, tag, ink, inkId, strokeJson, threads)
                } else {
                    readerView.cancelSelection()
                }
                readerView.post { initDrawPathLasso() }
            }
            REQ_INK -> {
                val inkId = pendingInkId
                pendingInkId = null
                Log.d(TAG, "REQ_INK: resultCode=$resultCode inkId=$inkId")
                if (resultCode == RESULT_OK && inkId != null) {
                    val pngBytes   = TempFiles.readBytes(this, InkNoteActivity.FILE_RESULT_PNG)
                    val strokeJson = TempFiles.readText(this, InkNoteActivity.FILE_RESULT_JSON)
                    Log.d(TAG, "REQ_INK: pngBytes=${pngBytes?.size} strokeJsonLen=${strokeJson?.length}")
                    commitInkAnnotation(inkId, pngBytes, strokeJson)
                } else {
                    Log.w(TAG, "REQ_INK: skipped — resultCode=$resultCode inkId=$inkId")
                    readerView.cancelSelection()
                }
                readerView.post { initDrawPathLasso() }
            }
            REQ_RETOOL_NOTE -> {
                Log.d(TAG, "REQ_RETOOL_NOTE: resultCode=$resultCode pendingAnnotation=${pendingAnnotation?.annotation?.id} note=${data?.getStringExtra(NoteActivity.EXTRA_NOTE)}")
                val ann = pendingAnnotation ?: run {
                    Log.e(TAG, "REQ_RETOOL_NOTE: pendingAnnotation is null — note will not be saved")
                    return
                }
                pendingAnnotation = null
                if (resultCode == RESULT_OK) {
                    val tool  = AnnotationTool.fromName(data?.getStringExtra(NoteActivity.EXTRA_RESULT_TOOL))
                    val note  = data?.getStringExtra(NoteActivity.EXTRA_NOTE)?.takeIf { it.isNotEmpty() }
                    val tag   = AnnotationTag.fromName(data?.getStringExtra(NoteActivity.EXTRA_RESULT_TAG))
                    val threads    = ThreadJson.decode(data?.getStringExtra(NoteActivity.EXTRA_THREAD_JSON))
                    val ink        = TempFiles.readBytes(this, NoteActivity.FILE_RESULT_PNG)
                    val inkId      = data?.getStringExtra(NoteActivity.EXTRA_INK_ID)
                    val strokeJson = TempFiles.readText(this, NoteActivity.FILE_RESULT_JSON)
                    val opened = book ?: run {
                        Log.e(TAG, "REQ_RETOOL_NOTE: book is null — cannot save")
                        return
                    }
                    val file = opened.file ?: run {
                        Log.e(TAG, "REQ_RETOOL_NOTE: book.file is null (read-only) — cannot save")
                        return
                    }
                    val updated = ann.annotation.copy(
                        tool    = tool,
                        note    = note,
                        tag     = tag,
                        hasInk  = ink != null || ann.annotation.hasInk,
                        threadEntries = threads,
                    )
                    val newList = opened.doc.annotations.map { it.annotation }
                        .map { if (it.id == updated.id) updated else it }
                    val inkPng    = if (ink != null && inkId != null) Pair(inkId, ink) else null
                    val inkStroke = if (strokeJson != null && inkId != null) Pair(inkId, strokeJson) else null
                    Log.d(TAG, "REQ_RETOOL_NOTE: inkId=$inkId inkPngBytes=${ink?.size} strokeJsonLen=${strokeJson?.length} inkPng=${inkPng != null} inkStroke=${inkStroke != null}")
                    // Cache strokes immediately so editAnnotationNote can find them even
                    // before the background write updates book.bytes.
                    if (strokeJson != null && inkId != null) latestStrokes[inkId] = strokeJson
                    // Optimistic update: show the new note immediately on the main thread
                    // instead of waiting for the background DOCX write to complete.
                    val updatedResolved = ResolvedAnnotation(updated, ann.span)
                    val optimisticAnnotations = opened.doc.annotations
                        .map { if (it.annotation.id == updated.id) updatedResolved else it }
                    val optimisticDoc = LoadedDocument(opened.doc.plainMap, optimisticAnnotations, opened.doc.position)
                    book = OpenBook(opened.displayName, opened.bytes, optimisticDoc, opened.file)
                    readerView.updateAnnotations(optimisticAnnotations)
                    saveAnnotations(opened, file, newList, inkPng, inkStroke)
                }
                readerView.post { initDrawPathLasso() }
            }
            REQ_SEARCH -> {
                if (resultCode == RESULT_OK) {
                    val charOffset = data?.getIntExtra(SearchActivity.EXTRA_CHAR_OFFSET, -1) ?: -1
                    val charEnd = data?.getIntExtra(SearchActivity.EXTRA_CHAR_END, -1) ?: -1
                    if (charOffset >= 0) {
                        readerView.jumpToChar(charOffset)
                        if (charEnd > charOffset) readerView.setJumpHighlight(charOffset, charEnd)
                    }
                }
                readerView.post { initDrawPathLasso() }
            }
            REQ_ANNOTATIONS -> {
                when (resultCode) {
                    RESULT_OK -> {
                        val inkId = data?.getStringExtra(AnnotationsPanelActivity.EXTRA_OPEN_INK_ID)
                        if (inkId != null) {
                            // User tapped an ink annotation row — open the ink editor.
                            val opened = book ?: return
                            val resolved = opened.doc.annotations.find { it.annotation.id == inkId }
                            if (resolved != null) editAnnotationNote(resolved)
                        } else {
                            // User tapped a text annotation row — jump to its position.
                            val fraction = data?.getDoubleExtra(AnnotationsPanelActivity.EXTRA_FRACTION, -1.0) ?: -1.0
                            if (fraction >= 0.0) {
                                val opened = book ?: return
                                val length = readerView.textLength()
                                val targetChar = (fraction * length).toInt().coerceIn(0, length)
                                readerView.jumpToChar(targetChar)
                            }
                        }
                    }
                    RESULT_FIRST_USER -> {
                        // An annotation was deleted — reload the book to reflect it.
                        val opened = book ?: return
                        val file = opened.file ?: return
                        loadFromFile(file)
                    }
                }
            }
            else -> @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun reopenLastOrPrompt() {
        val path = prefs.getString(KEY_LAST_PATH, null)
        val file = path?.let(::File)
        if (file != null && file.canRead()) {
            loadFromFile(file)
        } else {
            readerView.showHint(getString(R.string.empty_hint))
        }
    }

    private fun saveRecent(path: String) {
        val list = try {
            val raw = prefs.getString(KEY_RECENTS, null) ?: "[]"
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }
        list.remove(path)
        list.add(0, path)
        // removeAt(lastIndex), NOT removeLast() — List.removeLast() is JDK21/API-35 SequencedCollection
        // and throws NoSuchMethodError on the Supernote's older Android.
        while (list.size > MAX_RECENTS) list.removeAt(list.size - 1)
        prefs.edit().putString(KEY_RECENTS, JSONArray(list).toString()).apply()
    }

    private fun loadFromFile(file: File) {
        readerView.showHint("Loading…")
        DocxWriteQueue.enqueueRead {
            try {
                val opened = BookLoader.loadFromFile(file)
                main.post { onBookLoaded(opened) }
            } catch (e: Exception) {
                Log.e(TAG, "failed to load ${file.absolutePath}", e)
                main.post { readerView.showHint("Couldn’t open this document.") }
            }
        }
    }

    private fun onBookLoaded(opened: OpenBook) {
        book = opened
        title = opened.displayName
        updateTitle()

        val columns = resolveColumns()

        val length = opened.doc.plainText.length
        val fraction = opened.doc.position?.fraction
            ?: prefs.getFloat("pos:${opened.file?.absolutePath ?: ""}", 0f).toDouble()
        val startChar = (fraction * length).roundToInt().coerceIn(0, length)

        readerView.showContent(opened.doc.plainText, opened.doc.annotations, columns, startChar, opened.doc.formatSpans)
        updatePillState()
        dismissUndoPill()
        aiPanel?.onBookChanged(opened)
    }

    private fun updateTitle() {
        titleLabel.text = book?.displayName ?: ""
    }

    // --- Columns -------------------------------------------------------------

    /** Explicit user choice from prefs, else auto by screen width. */
    private fun resolveColumns(): Int {
        val stored = prefs.getInt(KEY_COLUMNS, 0)
        if (stored == 1 || stored == 2) return stored
        return if (resources.configuration.smallestScreenWidthDp >= AUTO_TWO_COL_MIN_DP) 2 else 1
    }

    // --- Annotation write-back -----------------------------------------------

    private fun commitAnnotation(tool: AnnotationTool, note: String?) {
        val opened = book ?: return
        val file = opened.file ?: run {
            Toast.makeText(this, "File is read-only — can't save annotation.", Toast.LENGTH_SHORT).show()
            readerView.cancelSelection()
            return
        }
        val text = readerView.textString() ?: run { readerView.cancelSelection(); return }
        val s = pendingSelStart
        val e = pendingSelEnd
        if (s < 0 || e <= s || e > text.length) {
            readerView.cancelSelection()
            return
        }

        val selectedText = text.substring(s, e)
        val prefix = text.substring(maxOf(0, s - 20), s)
        val suffix = text.substring(e, minOf(text.length, e + 20))
        val position = s.toDouble() / text.length.coerceAtLeast(1)

        val annotation = Annotation(
            id = newId(),
            selectedText = selectedText,
            prefix = prefix,
            suffix = suffix,
            tool = tool,
            note = note?.takeIf { it.isNotEmpty() },
            timestamp = Instant.now(),
            position = position,
        )

        readerView.cancelSelection()
        showUndoPill(lastAnchorX, lastAnchorY, annotation.id)

        // Optimistic update: show the highlight immediately using the known char offsets.
        // book is updated so rapid locked-tool commits accumulate the full list correctly.
        val optimistic = ResolvedAnnotation(annotation, TextSpan(s, e))
        val optimisticAnnotations = opened.doc.annotations + optimistic
        val optimisticDoc = LoadedDocument(opened.doc.plainMap, optimisticAnnotations, opened.doc.position)
        book = OpenBook(opened.displayName, opened.bytes, optimisticDoc, file)
        readerView.updateAnnotations(optimisticAnnotations)

        saveAnnotations(opened, file, optimisticAnnotations.map { it.annotation })
    }

    /** Start InkNoteActivity for a new ink annotation on the pending selection. */
    private fun launchInkCanvas(selText: String) {
        if (book?.file == null) {
            Toast.makeText(this, "File is read-only — can't save annotation.", Toast.LENGTH_SHORT).show()
            readerView.cancelSelection()
            return
        }
        pendingInkId = newId()
        startActivityForResult(
            Intent(this, InkNoteActivity::class.java)
                .putExtra(InkNoteActivity.EXTRA_SELECTED_TEXT, selText),
            REQ_INK,
        )
    }

    /** Commit an ink annotation using the pre-allocated [inkId] and optional PNG + stroke data. */
    private fun commitInkAnnotation(inkId: String, pngBytes: ByteArray?, strokeJson: String? = null) {
        Log.d(TAG, "commitInkAnnotation: inkId=$inkId pngBytes=${pngBytes?.size} book=${book != null} pendingSel=[$pendingSelStart,$pendingSelEnd]")
        val opened = book ?: run {
            Log.e(TAG, "commitInkAnnotation: book is null — activity may have been recreated"); return
        }
        val file = opened.file ?: run {
            Log.e(TAG, "commitInkAnnotation: file is null — read-only")
            Toast.makeText(this, "File is read-only — can't save annotation.", Toast.LENGTH_SHORT).show()
            readerView.cancelSelection()
            return
        }
        val text = readerView.textString() ?: run {
            Log.e(TAG, "commitInkAnnotation: textString is null — no book displayed")
            readerView.cancelSelection(); return
        }
        val s = pendingSelStart
        val e = pendingSelEnd
        if (s < 0 || e <= s || e > text.length) {
            Log.e(TAG, "commitInkAnnotation: invalid selection s=$s e=$e textLen=${text.length}")
            readerView.cancelSelection(); return
        }

        val selectedText = text.substring(s, e)
        val prefix = text.substring(maxOf(0, s - 20), s)
        val suffix = text.substring(e, minOf(text.length, e + 20))
        val position = s.toDouble() / text.length.coerceAtLeast(1)

        val annotation = Annotation(
            id = inkId,
            selectedText = selectedText,
            prefix = prefix,
            suffix = suffix,
            tool = AnnotationTool.inkAnnotation,
            timestamp = Instant.now(),
            position = position,
            hasInk = pngBytes != null,
        )

        Log.d(TAG, "commitInkAnnotation: creating annotation hasInk=${pngBytes != null} sel='${selectedText.take(40)}'")
        readerView.cancelSelection()
        showUndoPill(lastAnchorX, lastAnchorY, inkId)

        // Optimistic update — show the dotted underline immediately without waiting for
        // the background DOCX write (same pattern as commitAnnotation).
        val resolvedAnnotation = ResolvedAnnotation(annotation, TextSpan(s, e))
        val optimisticAnnotations = opened.doc.annotations + resolvedAnnotation
        val optimisticDoc = LoadedDocument(opened.doc.plainMap, optimisticAnnotations, opened.doc.position)
        book = OpenBook(opened.displayName, opened.bytes, optimisticDoc, file)
        readerView.updateAnnotations(optimisticAnnotations)

        val inkPng     = if (pngBytes != null) Pair(inkId, pngBytes) else null
        val inkStrokes = if (strokeJson != null) Pair(inkId, strokeJson) else null
        if (strokeJson != null) latestStrokes[inkId] = strokeJson
        saveAnnotations(opened, file, optimisticAnnotations.map { it.annotation }, inkPng, inkStrokes)
    }

    /** Open the note editor for an existing annotation. Panel pre-selects its current tool. */
    private fun editAnnotationNote(resolved: ResolvedAnnotation) {
        if (book?.file == null) {
            Toast.makeText(this, "File is read-only — can't edit annotation.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingAnnotation = resolved
        Log.d(TAG, "editAnnotationNote: set pendingAnnotation=${resolved.annotation.id} note=${resolved.annotation.note}")
        // Write large ink data to cache files to avoid Binder IPC size limit.
        TempFiles.writeBytes(this, NoteActivity.FILE_LAUNCH_PNG, null)
        TempFiles.writeText(this, NoteActivity.FILE_LAUNCH_JSON, null)
        if (resolved.annotation.hasInk) {
            val id = resolved.annotation.id
            val bytes = book?.bytes
            // Vector strokes drive editing in the ink canvas. Prefer the in-memory
            // cache — it's updated immediately on save, before the background write
            // finishes updating book.bytes.
            val strokeJson = latestStrokes[id] ?: bytes?.let { DocxStore.readInkStrokes(it, id) }
            if (strokeJson != null) {
                TempFiles.writeText(this, NoteActivity.FILE_LAUNCH_JSON, strokeJson)
            }
            // The note pane's ink preview (NoteActivity.renderInkPane) renders from the
            // PNG, so pass it whenever present — even for stroke-based notes — or the
            // Ink tab shows blank on reopen.
            val inkBytes = bytes?.let { DocxStore.readInkPng(it, id) }
            if (inkBytes != null) {
                TempFiles.writeBytes(this, NoteActivity.FILE_LAUNCH_PNG, inkBytes)
            }
        }
        val intent = Intent(this, NoteActivity::class.java)
            .putExtra(NoteActivity.EXTRA_NOTE, resolved.annotation.note ?: "")
            .putExtra(NoteActivity.EXTRA_THREAD_JSON, ThreadJson.encode(resolved.annotation.threadEntries))
            .putExtra(NoteActivity.EXTRA_TIMESTAMP, resolved.annotation.timestamp.toEpochMilli())
            .putExtra(NoteActivity.EXTRA_SELECTED_TEXT, resolved.annotation.selectedText)
            .putExtra(NoteActivity.EXTRA_INITIAL_TOOL, resolved.annotation.tool.name)
            .putExtra(NoteActivity.EXTRA_INITIAL_TAG, resolved.annotation.tag?.name)
            .putExtra(NoteActivity.EXTRA_INITIAL_INK_ID, resolved.annotation.id)
        startActivityForResult(intent, REQ_RETOOL_NOTE)
    }

    /**
     * Commit a new annotation from the NoteActivity panel result — handles the
     * chosen tool, optional note, optional tag, and optional ink PNG in one call.
     */
    private fun commitAnnotationFromPanel(
        tool: AnnotationTool,
        note: String?,
        tag: AnnotationTag?,
        inkBytes: ByteArray?,
        inkId: String?,
        strokeJson: String? = null,
        threadEntries: List<ThreadEntry> = emptyList(),
    ) {
        val opened = book ?: return
        val file = opened.file ?: run {
            Toast.makeText(this, "File is read-only — can't save annotation.", Toast.LENGTH_SHORT).show()
            readerView.cancelSelection()
            return
        }
        val text = readerView.textString() ?: run { readerView.cancelSelection(); return }
        val s = pendingSelStart
        val e = pendingSelEnd
        if (s < 0 || e <= s || e > text.length) { readerView.cancelSelection(); return }

        val selectedText = text.substring(s, e)
        val prefix = text.substring(maxOf(0, s - 20), s)
        val suffix = text.substring(e, minOf(text.length, e + 20))
        val position = s.toDouble() / text.length.coerceAtLeast(1)
        val id = if (inkBytes != null && inkId != null) inkId else newId()

        val annotation = Annotation(
            id           = id,
            selectedText = selectedText,
            prefix       = prefix,
            suffix       = suffix,
            tool         = tool,
            note         = note?.takeIf { it.isNotEmpty() },
            tag          = tag,
            timestamp    = java.time.Instant.now(),
            position     = position,
            hasInk       = inkBytes != null,
            threadEntries = threadEntries,
        )

        readerView.cancelSelection()
        showUndoPill(lastAnchorX, lastAnchorY, annotation.id)

        // Optimistic update — show the annotation immediately so the reader reflects
        // it without waiting for the background DOCX write (same pattern as commitAnnotation).
        val optimistic = ResolvedAnnotation(annotation, TextSpan(s, e))
        val optimisticAnnotations = opened.doc.annotations + optimistic
        val optimisticDoc = LoadedDocument(opened.doc.plainMap, optimisticAnnotations, opened.doc.position)
        book = OpenBook(opened.displayName, opened.bytes, optimisticDoc, file)
        readerView.updateAnnotations(optimisticAnnotations)

        val existing   = opened.doc.annotations.map { it.annotation }
        val inkPng     = if (inkBytes != null && inkId != null) Pair(inkId, inkBytes) else null
        val inkStrokes = if (strokeJson != null && inkId != null) Pair(inkId, strokeJson) else null
        saveAnnotations(opened, file, existing + annotation, inkPng, inkStrokes)
    }

    private fun copyText(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Layuv", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        if (text.isBlank()) return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            null,
        ))
    }

    private fun deleteAnnotation(resolved: ResolvedAnnotation) {
        Log.d(TAG, "deleteAnnotation(resolved=${resolved.annotation.id} tool=${resolved.annotation.tool}) called from action popup")
        val opened = book ?: return
        val file = opened.file ?: run {
            Toast.makeText(this, "File is read-only — can't delete annotation.", Toast.LENGTH_SHORT).show()
            return
        }
        LeamhDialog.confirmDelete(
            context = this,
            message = "Delete \"${resolved.annotation.selectedText.take(40)}\"?",
            skipPrefKey = "delete_confirm_skip:${file.absolutePath}",
            onConfirm = {
                dismissUndoPill()
                val newList = opened.doc.annotations.map { it.annotation }
                    .filter { it.id != resolved.annotation.id }
                // Optimistic update — remove from view immediately before I/O completes
                val optimisticAnnotations = opened.doc.annotations
                    .filter { it.annotation.id != resolved.annotation.id }
                val optimisticDoc = LoadedDocument(opened.doc.plainMap, optimisticAnnotations, opened.doc.position)
                book = OpenBook(opened.displayName, opened.bytes, optimisticDoc, opened.file)
                readerView.updateAnnotations(optimisticAnnotations)
                saveAnnotations(opened, file, newList)
            },
        )
    }

    // Char offset / pending-save coalescing — a rapid burst of annotation edits (e.g.
    // highlighting many passages) otherwise queues one full DOCX write each, which on the
    // Supernote piles up badly. We debounce annotation-list saves so a burst collapses to a
    // single write (each write persists the WHOLE list, so the latest wins), and ALWAYS flush
    // the pending write before backgrounding ([onPause]) / teardown so nothing is lost. Ink
    // and delete saves carry one-shot data, so they flush the pending write and persist
    // immediately rather than coalescing.
    private var pendingSave: (() -> Unit)? = null
    private val flushSaveRunnable = Runnable { flushPendingSave() }

    private fun saveAnnotations(
        opened: OpenBook,
        file: File,
        newList: List<Annotation>,
        inkPng: Pair<String, ByteArray>? = null,
        inkStrokes: Pair<String, String>? = null,
        deletedId: String? = null,
    ) {
        if (inkPng != null || inkStrokes != null || deletedId != null) {
            flushPendingSave() // persist any coalesced annotation edits first, in order
            doSaveAnnotations(opened, file, newList, inkPng, inkStrokes, deletedId)
            return
        }
        pendingSave = { doSaveAnnotations(opened, file, newList) }
        main.removeCallbacks(flushSaveRunnable)
        main.postDelayed(flushSaveRunnable, SAVE_DEBOUNCE_MS)
    }

    /** Run the pending coalesced annotation save now (no-op if none). Idempotent. */
    private fun flushPendingSave() {
        main.removeCallbacks(flushSaveRunnable)
        val save = pendingSave ?: return
        pendingSave = null
        save()
    }

    private fun doSaveAnnotations(
        opened: OpenBook,
        file: File,
        newList: List<Annotation>,
        inkPng: Pair<String, ByteArray>? = null,
        inkStrokes: Pair<String, String>? = null,
        deletedId: String? = null,
    ) {
        DocxWriteQueue.submit(
            file,
            transform = { base ->
                if (inkPng != null) Log.d(TAG, "saveAnnotations: writing inkPng id=${inkPng.first} bytes=${inkPng.second.size}")
                if (inkStrokes != null) Log.d(TAG, "saveAnnotations: writing inkStrokes id=${inkStrokes.first} len=${inkStrokes.second.length}")
                // writeWithInk does a single ZIP read+write pass instead of three separate ones.
                DocxStore.writeWithInk(base, newList, inkPng, inkStrokes)
            },
            onSuccess = { newBytes ->
                // The write never changes the canonical plain text P (load reads the CLEAN
                // snapshot), so the spans already resolved in book.doc are canonical — no
                // need to reparse the body and re-anchor every annotation (the old
                // DocxStore.load(newBytes) here was O(annotations × text length) on EVERY
                // edit). Instead do a cheap read-back canary that only confirms
                // annotations.json round-tripped with the expected ids.
                val t0 = System.nanoTime()
                val savedIds = DocxStore.readAnnotationIds(newBytes)
                Log.d(TAG, "saveAnnotations: wrote ${newList.size} annotations, " +
                    "validated ${savedIds?.size ?: -1} in ${(System.nanoTime() - t0) / 1_000_000}ms")
                main.post {
                    val currentBook = book
                    if (currentBook != null) {
                        // Canary: a non-empty write that reads back as empty/unparseable means
                        // the bytes look torn — repair in-memory from newList + existing spans
                        // (disk is already the atomic-rename result, so it's correct).
                        val loadFailed = newList.isNotEmpty() && savedIds.isNullOrEmpty()
                        if (loadFailed) {
                            Log.w(TAG, "saveAnnotations: read-back found no annotations after writing ${newList.size} — repairing from newList")
                            val spanById = currentBook.doc.annotations.associate { it.annotation.id to it.span }
                            val repairedAnnotations = newList.map { a -> ResolvedAnnotation(a, spanById[a.id]) }
                            val repairedDoc = LoadedDocument(currentBook.doc.plainMap, repairedAnnotations, currentBook.doc.position)
                            book = OpenBook(opened.displayName, newBytes, repairedDoc, file)
                            readerView.updateAnnotations(repairedAnnotations)
                        } else {
                            // Healthy write. book.doc already reflects the current (optimistic)
                            // annotations with correct spans (P unchanged), and the view is
                            // already showing them — just republish the fresh bytes so the next
                            // write/read uses the right base. No reparse, no redraw.
                            book = OpenBook(opened.displayName, newBytes, currentBook.doc, file)
                        }
                    }
                    updatePillState()
                }
            },
            onError = { ex ->
                Log.e(TAG, "saveAnnotations failed", ex)
                main.post {
                    Toast.makeText(this, "Could not save changes.", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    // --- Position persistence ------------------------------------------------

    override fun onResume() {
        super.onResume()
        // Discard the cached overlay so it's recreated with the current font size pref
        // if the user changed it in Settings while this activity was paused.
        pageJumpOverlay?.dismiss()
        pageJumpOverlay = null
        readerView.post { initDrawPathLasso() }
    }

    override fun onPause() {
        super.onPause()
        flushPendingSave() // persist any coalesced annotation edits before we may be killed
        savePosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        flushPendingSave()
        aiPanel?.destroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back closes the AI panel first, leaving the reader intact.
        if (aiPanel?.isOpen == true) {
            closeAiChat()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /** Write the current reading position back into the DOCX file (off-main). */
    private fun savePosition() {
        val opened = book ?: return
        val file = opened.file ?: return // read-only fallback: nothing to save
        val length = readerView.textLength()
        if (length <= 0 || savingPosition) return

        val offset = readerView.currentCharOffset()
        // The position write re-zips the whole DOCX (the position travels inside the
        // file so it carries across devices). Skip it when the page hasn't moved since
        // the last successful save — otherwise every onPause rewrites an identical position.
        if (offset == lastSavedCharOffset) return
        val fraction = offset.toDouble() / length
        val position = ReadingPosition(
            mode = ReadingMode.screenFlip, // page-at-a-time, no animation
            page = readerView.pageInfo().first,
            scrollOffset = 0.0,
            fraction = fraction,
        )

        // Synchronous write so position survives process death even if the
        // DocxWriteQueue task below doesn't complete before the process is killed.
        prefs.edit().putFloat("pos:${file.absolutePath}", fraction.toFloat()).commit()

        savingPosition = true
        try {
            DocxWriteQueue.submit(
                file,
                // Read the current on-disk bytes so the position layers onto the
                // latest committed annotations rather than a stale in-memory base.
                transform = { base -> DocxStore.writePosition(base, position) },
                onSuccess = { newBytes ->
                    Log.i(TAG, "saved position fraction=$fraction to ${file.name}")
                    lastSavedCharOffset = offset
                    main.post {
                        // Republish the in-memory base so a later annotation save
                        // layers onto this position instead of reverting it. Guard on
                        // file identity so a freshly-opened document isn't clobbered.
                        book?.let { if (it.file == file) book = OpenBook(it.displayName, newBytes, it.doc, it.file) }
                    }
                    savingPosition = false
                },
                onError = { e ->
                    Log.w(TAG, "could not save position", e)
                    savingPosition = false
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "savePosition: submit threw synchronously", e)
            savingPosition = false
        }
    }

    /**
     * Float a small "Delete" pill above [anchorX, anchorY] (ReaderView-relative coords)
     * for [UNDO_TIMEOUT_MS] ms. Tapping it deletes the annotation with [annotationId].
     * Dismissed by any new annotation, book load, or the tap itself.
     *
     * The pill is a regular child of [rootOverlay] (not a PopupWindow) so touch events
     * are guaranteed to reach it regardless of the Supernote input pipeline.
     */
    private fun showUndoPill(anchorX: Int, anchorY: Int, annotationId: String) {
        Log.d(TAG, "showUndoPill: anchorX=$anchorX anchorY=$anchorY annotationId=$annotationId")
        dismissUndoPill()
        val iconExtent = ReaderTheme.dp(this, 26f)
        val hPad = dp(12f)
        val vPad = dp(8f)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.toolbar_bg)
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
            setOnTouchListener(PenTapListener(this@ReaderActivity, "UndoPill") {
                Log.d(TAG, "UndoPill tapped — calling deleteAnnotation($annotationId)")
                deleteAnnotation(annotationId)
            })
            addView(object : View(this@ReaderActivity) {
                override fun onDraw(canvas: Canvas) =
                    undoRenderer.drawVecIcon(canvas, R.drawable.ic_delete_outline, width / 2f, height / 2f, iconExtent)
            }, LinearLayout.LayoutParams(dp(26f), dp(26f)))
            addView(TextView(this@ReaderActivity).apply {
                text = "Delete"
                typeface = ReaderTheme.body(this@ReaderActivity)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(ReaderTheme.INK_87)
                setPadding(dp(8f), 0, 0, 0)
            })
        }
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val pillW = pill.measuredWidth
        val pillH = pill.measuredHeight
        // Position relative to rootOverlay using screen coordinates.
        val readerLoc = IntArray(2)
        readerView.getLocationOnScreen(readerLoc)
        val overlayLoc = IntArray(2)
        rootOverlay.getLocationOnScreen(overlayLoc)
        val x = (readerLoc[0] - overlayLoc[0] + anchorX - pillW / 2).coerceAtLeast(dp(8f)).toFloat()
        val y = (readerLoc[1] - overlayLoc[1] + anchorY - pillH - dp(12f)).coerceAtLeast(dp(8f)).toFloat()
        Log.d(TAG, "showUndoPill: pillW=$pillW pillH=$pillH " +
            "readerOnScreen=(${readerLoc[0]},${readerLoc[1]}) " +
            "overlayOnScreen=(${overlayLoc[0]},${overlayLoc[1]}) " +
            "finalX=$x finalY=$y")
        pill.x = x
        pill.y = y
        rootOverlay.addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        undoPillView = pill
        main.postDelayed(undoDismissRunnable, UNDO_TIMEOUT_MS)
    }

    private fun dismissUndoPill() {
        main.removeCallbacks(undoDismissRunnable)
        undoPillView?.let { rootOverlay.removeView(it) }
        undoPillView = null
    }

    private fun dp(value: Float): Int = ReaderTheme.dp(this, value).roundToInt()

    // --- drawPath lasso ----------------------------------------------------------

    private fun initDrawPathLasso() {
        if (!DrawPathClient.available()) return
        DrawPathClient.sendReset(packageName)
        // penType 4 = dotted lasso — live selection-stroke feedback during drag
        DrawPathClient.sendPen(packageName, type = 4, width = 150, color = 0)
        disableChromeBand()
    }

    private fun disableChromeBand() {
        val toolbarH = pillRow.height + ReaderTheme.dp(this, 60f).toInt()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        DrawPathClient.setWritableAreas(
            packageName,
            listOf(intArrayOf(0, screenH - toolbarH, screenW, toolbarH, 0)), // flag 0 = disable
            "disable-chrome",
        )
    }

    companion object {
        private const val TAG = "LeamhActivity"
        private const val REQ_BROWSE = 1002
        private const val REQ_NOTE = 1003
        private const val REQ_ANNOTATIONS = 1004
        private const val REQ_RETOOL_NOTE = 1005
        private const val REQ_INK = 1007
        private const val REQ_SEARCH = 1008
        private const val REQ_PICK_AI_DIR = 1009
        private const val REQ_IMPORT_REWRITE = 1010
        private const val REQ_SET_IMPORT_FOLDER = 1011
        private const val KEY_IMPORT_FOLDER = "ai_import_folder"
        private const val PREFS = "leamh"
        private const val KEY_AI_EXPORT_FOLDER = "ai_export_folder"
        private const val KEY_LAST_PATH = "last_path"
        private const val KEY_COLUMNS = "columns"
        private const val KEY_NAV_SIDE = "eink_nav_side"
        private const val KEY_NAV_REVERSED = "eink_nav_reversed"
        private const val KEY_FONT_SIZE = "body_font_size"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_RULE_LINES = "ink_rule_lines"
        private const val KEY_BODY_FONT = "body_font"
        private const val KEY_RECENTS = "recent_files"
        private const val MAX_RECENTS = 8
        // The Nomad reports smallestScreenWidthDp=1024 and reads best at 1 col,
        // so the auto-2-col threshold sits above it; the larger Manta should land
        // above this and default to 2 col. Confirm the Manta's logged value and
        // tune. The toggle (persisted per device) overrides either way.
        private const val AUTO_TWO_COL_MIN_DP = 1200
        private const val UNDO_TIMEOUT_MS = 4_000L
        // Coalesce annotation-list saves within this window into a single DOCX write. Sized
        // a bit above a comfortable highlighting cadence so a burst of marks collapses to one
        // write instead of one-per-mark. Always flushed on pause/teardown, so the delay never
        // costs data and is invisible (the marks render optimistically the instant you make them).
        private const val SAVE_DEBOUNCE_MS = 1500L
    }
}
