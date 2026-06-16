package com.afluffywaffle.layuv.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.afluffywaffle.layuv.docx.Anchoring
import com.afluffywaffle.layuv.docx.FormatSpan
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.ResolvedAnnotation
import com.afluffywaffle.layuv.docx.TextSpan
import java.util.concurrent.Executors

/**
 * The reader surface. A LAYER_TYPE_SOFTWARE [View] that owns its own [onDraw]
 * (translate + clip + StaticLayout.draw, one pass per column) and every EPD
 * waveform via [Epd] — no Compose, no framework compositor.
 *
 * Navigation: tap left half = prev, tap right half = next (no swipe, e-ink rules).
 * Selection: long-press (finger) or drag (stylus) → word-snap → [onSelectionReady]
 * callback fires with the final char range and popup anchor coordinates.
 */
class ReaderView(context: Context) : View(context) {

    var onPageChanged: ((page: Int, pageCount: Int) -> Unit)? = null
    var onSelectionReady: ((start: Int, end: Int, anchorX: Int, anchorY: Int) -> Unit)? = null
    /** Fired when the user taps inside an existing annotation span (no drag, no selection). */
    var onAnnotationTapped: ((ResolvedAnnotation, anchorX: Int, anchorY: Int) -> Unit)? = null
    /** Asks the host to dismiss the tool popup (selection cleared, or a handle drag began). */
    var onHidePopup: (() -> Unit)? = null

    private val epd = Epd()
    private val highlights = HighlightPainter(context)
    private val toolIcons = ToolIconRenderer(context)
    private val marginIconSize = ReaderTheme.dp(context, MARGIN_ICON_DP)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val hPadding = ReaderTheme.dp(context, ReaderTheme.H_PADDING_DP)
    private val vPadding = ReaderTheme.dp(context, ReaderTheme.V_PADDING_DP)
    private val columnGap = ReaderTheme.dp(context, ReaderTheme.COLUMN_GAP_DP)
    private var bodySize = ReaderTheme.sp(context, ReaderTheme.BODY_TEXT_SP)
    private var lineSpacingMult = ReaderTheme.LINE_SPACING_MULT

    // --- Edge navigation strips ----------------------------------------------
    // One-handed nav: a tall strip on the left and/or right edge, each split
    // top (= next) / bottom (= prev). navSide is "both" | "left" | "right".
    private val navStripWidth = ReaderTheme.dp(context, NAV_STRIP_DP)
    private var navSide = "both"
    private val navPath = Path()
    private val navHairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        alpha = 90 // visible divider between the top (next) and bottom (prev) zones
        style = Paint.Style.STROKE
        strokeWidth = ReaderTheme.dp(context, 1.5f)
    }
    private val navChevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        alpha = 55
        style = Paint.Style.STROKE
        strokeWidth = ReaderTheme.dp(context, 2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val navChevronHalfW = ReaderTheme.dp(context, 8f)
    private val navChevronHalfH = ReaderTheme.dp(context, 12f)

    private fun leftStripActive()  = navSide != "right" && navSide != "none"
    private fun rightStripActive() = navSide != "left"  && navSide != "none"
    private fun leftPad() = hPadding + if (leftStripActive()) navStripWidth else 0f
    private fun rightPad() = hPadding + if (rightStripActive()) navStripWidth else 0f

    private var text: CharSequence? = null
    private var annotations: List<ResolvedAnnotation> = emptyList()
    private var rawText: CharSequence? = null
    private var rawFormatSpans: List<FormatSpan> = emptyList()
    private var desiredColumns = 1

    private var pageLayout: PageLayout? = null
    private var currentPage = 0
    private var pendingCharOffset = 0
    private var paginateGeneration = 0

    // --- Selection state ------------------------------------------------------

    // Char offsets in the canonical plain text. -1 = no selection.
    private var selectionStart = -1
    private var selectionEnd = -1
    // True while the user is still actively dragging the selection (before
    // onSelectionReady fires); kept false after the popup is shown so that
    // tapping outside the popup without picking a tool falls through to
    // cancelSelection() rather than re-entering selection mode.
    private var isSelecting = false

    // Pointer-drag selection — Down = anchor word; drag past slop = live
    // word-level selection; tap = page turn (or dismiss a shown selection).
    private var ptrDownX = 0f
    private var ptrDownY = 0f
    private var ptrMoved = false
    private var ptrAnchorChar = -1
    private val tapSlopPx = ReaderTheme.dp(context, 8f)

    // Finger swipe (page turn) — raw X/Y delta is more reliable than GestureDetector
    // velocity on e-ink panels that may emit very few ACTION_MOVE events.
    private var fingerSwipeDownX = -1f
    private var fingerSwipeDownY = -1f

    // Jump-to-search highlight — grey box behind matched text, auto-cleared after 3 s.
    private var jumpHighlightStart = -1
    private var jumpHighlightEnd = -1
    private val jumpHighlightPaint = Paint().apply {
        color = android.graphics.Color.argb(50, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val clearJumpHighlight = Runnable {
        jumpHighlightStart = -1
        jumpHighlightEnd = -1
        epd.pageTurn(this)
    }

    fun setJumpHighlight(start: Int, end: Int) {
        removeCallbacks(clearJumpHighlight)
        jumpHighlightStart = start
        jumpHighlightEnd = end
        invalidate()
        postDelayed(clearJumpHighlight, 3000L)
    }

    // Scrub-select (stylus only): accumulates the min/max char offsets touched
    // along the entire drawn path so the selection spans everything the pen
    // passed over — not just anchor→lift-point. Grows monotonically; never shrinks.
    private var scrubMin = Int.MAX_VALUE
    private var scrubMax = -1

    // --- Selection handles ----------------------------------------------------
    // Grab handles below each end of a committed selection (drawn once the popup
    // is up, i.e. !isSelecting), draggable to adjust the range char-by-char.
    private enum class Handle { NONE, START, END }
    private var draggingHandle = Handle.NONE

    private val handleRadius = ReaderTheme.dp(context, 9f)
    private val handleStem = ReaderTheme.dp(context, 6f)
    private val handleGrab = ReaderTheme.dp(context, 32f) // large e-ink/finger grab radius
    // The handle sits BELOW its glyph line; lift the touch sample back up onto the
    // text when adjusting, so dragging along a line stays on that line.
    private val handleTouchLift: Float get() = handleStem + handleRadius + bodySize * 0.7f
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        style = Paint.Style.FILL
    }
    private val handleStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        style = Paint.Style.STROKE
        strokeWidth = ReaderTheme.dp(context, 2f)
    }

    // --- Hint / loading -------------------------------------------------------

    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        typeface = ReaderTheme.body(context)
        textSize = ReaderTheme.sp(context, 16f)
        alpha = 150
    }
    private var hint: String? = null

    // Reused for partial (band-only) invalidation during a selection drag.
    private val dirtyRect = Rect()

    // FINGER input uses long-press to begin a selection, then drag to extend; a
    // tap turns the page (or dismisses a shown selection). STYLUS input drags to
    // select directly (see handleStylusEvent) — no long-press.
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selectionStart >= 0) { cancelSelection(); return true }
            if (pageLayout == null) return false
            if (navTap(e.x, e.y)) return true
            val char = charAtPoint(e.x, e.y)
            if (char != null) {
                val hit = annotationAtChar(char)
                if (hit != null) {
                    val pt = charPointInView(char, bottom = false)
                    onAnnotationTapped?.invoke(
                        hit,
                        pt?.first?.toInt() ?: e.x.toInt(),
                        pt?.second?.toInt() ?: e.y.toInt(),
                    )
                    return true
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            ptrAnchorChar = charAtPoint(e.x, e.y) ?: return
            isSelecting = true
            extendSelectionTo(e.x, e.y) // selects the word under the press
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }

        // Non-stylus fling (finger/unknown): swipe left = next page, swipe right = prev page.
        // Stylus input is owned by DrawPath and must not trigger page turns.
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1?.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) return false
            if (selectionStart >= 0) return false // don't navigate while selection is shown
            val threshold = ReaderTheme.dp(context, 200f) // ~200 dp/s — lenient for e-ink
            return when {
                velocityX < -threshold -> { next(); true } // left-fling = next page
                velocityX >  threshold -> { prev(); true } // right-fling = prev page
                else -> false
            }
        }
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isLongClickable = true
    }

    // --- Public API -----------------------------------------------------------

    fun showContent(
        text: CharSequence,
        annotations: List<ResolvedAnnotation>,
        columns: Int,
        startCharOffset: Int,
        formatSpans: List<FormatSpan> = emptyList(),
    ) {
        this.rawText = text
        this.rawFormatSpans = formatSpans
        this.annotations = annotations
        this.text = buildSpanned(text, formatSpans, annotations)
        this.desiredColumns = columns.coerceIn(1, 2)
        this.hint = null
        this.pendingCharOffset = startCharOffset
        this.pageLayout = null
        this.currentPage = 0
        cancelSelection()
        repaginate(fullClear = true)
    }

    fun showHint(message: String) {
        text = null
        pageLayout = null
        hint = message
        invalidate()
    }

    fun setColumns(columns: Int) {
        val c = columns.coerceIn(1, 2)
        if (c == desiredColumns) return
        desiredColumns = c
        repaginate(fullClear = true)
    }

    fun columns(): Int = desiredColumns

    /** Set which edge(s) show the nav strip: "both" | "left" | "right" | "none". */
    fun setNavSide(side: String) {
        val s = if (side in listOf("left", "right", "none")) side else "both"
        if (s == navSide) return
        navSide = s
        // The strips inset the text, so the content width changed → repaginate.
        if (text != null) repaginate(fullClear = true) else invalidate()
    }

    /** Update the annotation list; rebuilds highlight colour spans and redraws. */
    fun updateAnnotations(annotations: List<ResolvedAnnotation>) {
        this.annotations = annotations
        val raw = rawText
        if (raw != null) {
            // ForegroundColorSpan (grey text) is baked into the StaticLayout at
            // paginate time — we must rebuild the layout so new highlights appear.
            // Colour spans don't affect line metrics, so page breaks are unchanged.
            // repaginate calls epd.fullClear itself after the new layout is ready.
            this.text = buildSpanned(raw, rawFormatSpans, annotations)
            repaginate(fullClear = true)
        }
    }

    /** The plain text string, or null if no document is loaded. */
    fun textString(): String? = text?.toString()

    /** Cancel any active selection and redraw. */
    fun cancelSelection() {
        selectionStart = -1
        selectionEnd = -1
        isSelecting = false
        draggingHandle = Handle.NONE
        onHidePopup?.invoke()
        epd.selection(this)
    }

    /** Trigger a full EPD clear on this view. */
    fun fullClear() = epd.fullClear(this)

    fun next() {
        val pl = pageLayout ?: run { Log.d("LeamhSwipe", "next() — pageLayout null, aborting"); return }
        if (currentPage >= pl.pageCount - 1) { Log.d("LeamhSwipe", "next() — already last page $currentPage/${pl.pageCount}"); return }
        val before = currentPage
        currentPage++
        pendingCharOffset = pl.charStartOfPage(currentPage)
        Log.d("LeamhSwipe", "next() page $before → $currentPage / ${pl.pageCount}")
        notifyPage()
        epd.pageTurn(this)
    }

    fun prev() {
        val pl = pageLayout ?: run { Log.d("LeamhSwipe", "prev() — pageLayout null, aborting"); return }
        if (currentPage <= 0) { Log.d("LeamhSwipe", "prev() — already first page"); return }
        val before = currentPage
        currentPage--
        pendingCharOffset = pl.charStartOfPage(currentPage)
        Log.d("LeamhSwipe", "prev() page $before → $currentPage / ${pl.pageCount}")
        notifyPage()
        epd.pageTurn(this)
    }

    fun currentCharOffset(): Int = pageLayout?.charStartOfPage(currentPage) ?: pendingCharOffset

    /** Navigate to the page containing [charOffset] and apply a full EPD clear. */
    fun jumpToChar(charOffset: Int) {
        val pl = pageLayout
        if (pl != null) {
            currentPage = pl.pageForChar(charOffset)
            pendingCharOffset = charOffset
            notifyPage()
            epd.fullClear(this)
        } else {
            pendingCharOffset = charOffset
        }
    }

    fun textLength(): Int = text?.length ?: 0

    fun pageInfo(): Pair<Int, Int> = currentPage to (pageLayout?.pageCount ?: 1)

    /** Page-start char offsets for all pages — passed to SearchActivity so it can show page numbers. */
    fun pageStartOffsets(): IntArray? {
        val pl = pageLayout ?: return null
        return IntArray(pl.pageCount) { pl.charStartOfPage(it) }
    }

    /** Jump to a specific 0-indexed page; clamped to valid range. */
    fun jumpToPage(pageIndex: Int) {
        val pl = pageLayout ?: run { pendingCharOffset = 0; return }
        val clamped = pageIndex.coerceIn(0, pl.pageCount - 1)
        jumpToChar(pl.charStartOfPage(clamped))
    }

    /** First [maxChars] characters of [pageIndex], trimmed — used by the page scrubber preview. */
    /**
     * Returns one scrubber fraction (0.0–1.0) per page that has at least one annotation,
     * computed from the current [PageLayout]. Called at overlay show-time so fractions
     * always reflect the current pagination, not a stale layout.
     */
    fun annotationScrubberFractions(): List<Float> {
        val pl = pageLayout ?: return emptyList()
        val t = text ?: return emptyList()
        if (pl.pageCount <= 1) return emptyList()
        return annotations
            .filter { it.annotation.tool != AnnotationTool.bookmark }
            .mapNotNull { resolved ->
                val charOffset = resolved.span?.start
                    ?: (resolved.annotation.position * t.length).toInt()
                pl.pageForChar(charOffset.coerceIn(0, t.length))
            }
            .distinct()
            .map { page -> page.toFloat() / (pl.pageCount - 1).coerceAtLeast(1) }
            .sorted()
    }

    /** Scrubber fractions (0–1) for bookmark annotations only — drawn as bold marks on the track. */
    fun bookmarkScrubberFractions(): List<Float> {
        val pl = pageLayout ?: return emptyList()
        val t = text ?: return emptyList()
        if (pl.pageCount <= 1) return emptyList()
        return annotations
            .filter { it.annotation.tool == AnnotationTool.bookmark }
            .mapNotNull { resolved ->
                val charOffset = resolved.span?.start
                    ?: (resolved.annotation.position * t.length).toInt()
                pl.pageForChar(charOffset.coerceIn(0, t.length))
            }
            .distinct()
            .map { page -> page.toFloat() / (pl.pageCount - 1).coerceAtLeast(1) }
            .sorted()
    }

    /** 0-based page indices of all bookmark annotations, sorted, for the scrubber list. */
    fun bookmarkPageIndices(): List<Int> {
        val pl = pageLayout ?: return emptyList()
        val t = text ?: return emptyList()
        return annotations
            .filter { it.annotation.tool == AnnotationTool.bookmark }
            .mapNotNull { resolved ->
                val charOffset = resolved.span?.start
                    ?: (resolved.annotation.position * t.length).toInt()
                pl.pageForChar(charOffset.coerceIn(0, t.length))
            }
            .distinct()
            .sorted()
    }

    /** Page index (0-based) containing [offset], or 0 if no layout yet. */
    fun pageForCharOffset(offset: Int): Int =
        pageLayout?.pageForChar(offset.coerceIn(0, text?.length ?: 0)) ?: 0

    fun previewTextForPage(pageIndex: Int, maxChars: Int = 140): String {
        val pl = pageLayout ?: return ""
        val t = text ?: return ""
        val start = pl.charStartOfPage(pageIndex.coerceIn(0, pl.pageCount - 1))
        val end = (start + maxChars).coerceAtMost(t.length)
        return t.substring(start, end).trim()
    }

    // --- Layout / pagination --------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (text != null) repaginate(fullClear = true)
    }

    private fun contentWidthPx(): Int = (width - leftPad() - rightPad()).toInt()
    private fun contentHeightPx(): Int = (height - vPadding * 2).toInt()

    /** Update font size and line spacing. Triggers repagination if content is loaded. */
    fun setTypography(fontSizeSp: Float, newLineSpacingMult: Float) {
        val newBodySize = ReaderTheme.sp(context, fontSizeSp)
        val changed = newBodySize != bodySize || newLineSpacingMult != lineSpacingMult
        bodySize = newBodySize
        lineSpacingMult = newLineSpacingMult
        if (changed && text != null) repaginate(fullClear = true)
    }

    private fun makePaint(): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTheme.INK
        typeface = ReaderTheme.body(context)
        textSize = bodySize
    }

    /**
     * Overlay bold/italic runs onto the plain text as spans. Highlight decoration
     * (dotted underline) is drawn by [HighlightPainter] on the canvas, not as a span.
     * `toString()`/`length` are unchanged, so anchoring + selection still operate
     * on the plain text.
     */
    private fun buildSpanned(
        plain: CharSequence,
        formats: List<FormatSpan>,
        annotations: List<ResolvedAnnotation> = emptyList(),
    ): CharSequence {
        if (formats.isEmpty() && annotations.isEmpty()) return plain
        val sp = SpannableString(plain)
        val len = sp.length
        val regular = ReaderTheme.body(context)
        val italic = ReaderTheme.bodyItalic(context)
        for (f in formats) {
            if (f.start < 0 || f.end > len || f.end <= f.start) continue
            val tf = if (f.italic) italic else regular
            sp.setSpan(RunStyleSpan(tf, f.bold), f.start, f.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (resolved in annotations) {
            val span = resolved.span ?: continue
            if (resolved.annotation.tool != AnnotationTool.highlight &&
                resolved.annotation.tool != AnnotationTool.comment) continue
            val s = span.start.coerceIn(0, len)
            val e = span.end.coerceIn(0, len)
            if (e <= s) continue
            sp.setSpan(ForegroundColorSpan(ReaderTheme.HIGHLIGHT_TEXT), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    private fun repaginate(fullClear: Boolean) {
        val t = text ?: return
        val w = contentWidthPx()
        val h = contentHeightPx()
        if (w <= 0 || h <= 0) return

        val gen = ++paginateGeneration
        val cols = desiredColumns
        val gap = columnGap.toInt()
        val targetChar = pageLayout?.charStartOfPage(currentPage) ?: pendingCharOffset
        val paint = makePaint()
        val spacingMult = lineSpacingMult

        executor.execute {
            val t0 = System.nanoTime()
            val pl = PageLayout.paginate(t, paint, w, h, cols, gap, spacingMult)
            val ms = (System.nanoTime() - t0) / 1_000_000
            main.post {
                if (gen != paginateGeneration) return@post
                pageLayout = pl
                currentPage = pl.pageForChar(targetChar)
                pendingCharOffset = targetChar
                Log.i(
                    TAG,
                    "paginate: chars=${t.length} lines=${pl.layout.lineCount} " +
                        "cols=$cols pages=${pl.pageCount} in ${ms}ms",
                )
                notifyPage()
                if (fullClear) epd.fullClear(this) else invalidate()
            }
        }
    }

    private fun notifyPage() {
        val pl = pageLayout ?: return
        onPageChanged?.invoke(currentPage, pl.pageCount)
    }

    // --- Drawing --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        Log.d("LeamhSwipe", "onDraw page=$currentPage pageLayout=${if (pageLayout == null) "null" else "ok"}")
        canvas.drawColor(ReaderTheme.PAPER)
        val pl = pageLayout
        if (pl == null) {
            drawHint(canvas)
            return
        }

        val contentLeft = leftPad()
        val contentTop = vPadding
        val colWidth = pl.columnWidthPx
        val colHeight = pl.columnHeightPx
        val firstCol = pl.firstColumnOfPage(currentPage)

        for (colInPage in 0 until pl.columns) {
            val column = firstCol + colInPage
            if (column >= pl.columnCount) break
            val startLine = pl.lineStartOfColumn(column)
            val endLine = pl.lineEndOfColumn(column)
            if (endLine <= startLine) continue

            val colX = contentLeft + colInPage * (colWidth + pl.columnGapPx)
            val lineTop = pl.layout.getLineTop(startLine)
            val packedHeight = (pl.layout.getLineBottom(endLine - 1) - lineTop)
                .coerceAtMost(colHeight)

            canvas.save()
            canvas.clipRect(colX, contentTop, colX + colWidth, contentTop + packedHeight)
            canvas.translate(colX, contentTop - lineTop)

            // Draw jump-highlight grey box behind matched text (drawn before text so text shows on top).
            if (jumpHighlightStart >= 0 && jumpHighlightEnd > jumpHighlightStart) {
                val textLen = pl.layout.text.length
                val hlS = jumpHighlightStart.coerceIn(0, textLen)
                val hlE = jumpHighlightEnd.coerceIn(0, textLen)
                if (hlS < hlE) {
                    val hlStartLine = pl.layout.getLineForOffset(hlS)
                    val hlEndLine   = pl.layout.getLineForOffset(maxOf(hlS, hlE - 1))
                    val overlapS = maxOf(hlStartLine, startLine)
                    val overlapE = minOf(hlEndLine, endLine - 1)
                    for (hl in overlapS..overlapE) {
                        val rx1 = if (hl == hlStartLine) pl.layout.getPrimaryHorizontal(hlS) else 0f
                        val rx2 = if (hl == hlEndLine)   pl.layout.getPrimaryHorizontal(hlE) else colWidth.toFloat()
                        canvas.drawRect(
                            minOf(rx1, rx2), pl.layout.getLineTop(hl).toFloat(),
                            maxOf(rx1, rx2), pl.layout.getLineBottom(hl).toFloat(),
                            jumpHighlightPaint,
                        )
                    }
                }
            }

            pl.layout.draw(canvas)
            if (annotations.isNotEmpty()) {
                highlights.drawColumn(canvas, pl.layout, startLine, endLine, annotations)
            }
            // Active selection: dotted underline (same weight as comment/bookmark marks).
            if (selectionStart >= 0 && selectionEnd > selectionStart) {
                val colStartChar = pl.layout.getLineStart(startLine)
                val colEndChar = pl.layout.getLineEnd(endLine - 1)
                if (selectionStart < colEndChar && selectionEnd > colStartChar) {
                    highlights.drawSelection(
                        canvas, pl.layout, startLine, endLine,
                        TextSpan(selectionStart, selectionEnd),
                    )
                }
            }
            canvas.restore()
        }

        if (annotations.isNotEmpty()) drawMarginIcons(canvas, pl)

        // Grab handles, in view space (after the column translates are restored).
        // Only once the selection is committed (popup shown), not mid-creation.
        if (selectionStart >= 0 && selectionEnd > selectionStart && !isSelecting) {
            charPointInView(selectionStart, bottom = true)?.let { drawHandleAt(canvas, it) }
            charPointInView(selectionEnd, bottom = true)?.let { drawHandleAt(canvas, it) }
        }

        drawNavStrips(canvas)
    }

    /**
     * Per-annotation tool icon in the margin to the left of the annotation's
     * column, level with the line its anchor starts on. Only annotations whose
     * start is on the current page are shown.
     */
    private fun drawMarginIcons(canvas: Canvas, pl: PageLayout) {
        for (resolved in annotations) {
            if (resolved.annotation.note.isNullOrEmpty()) continue
            val span = resolved.span ?: continue
            val colInPage = columnOfChar(span.start) ?: continue // not on this page
            val top = charPointInView(span.start, bottom = false) ?: continue
            val bottom = charPointInView(span.start, bottom = true) ?: continue
            val cy = (top.second + bottom.second) / 2f
            val colLeft = leftPad() + colInPage * (pl.columnWidthPx + pl.columnGapPx).toFloat()
            val gap = if (colInPage == 0) hPadding else columnGap
            val cx = colLeft - gap / 2f
            // Any annotation with a note shows the chat-bubble icon — the note is the
            // primary indicator regardless of the underlying tool (e.g. a highlight
            // with a comment stays tool=highlight in storage).
            toolIcons.draw(canvas, AnnotationTool.comment, cx, cy, marginIconSize)
        }
    }

    /** Faint edge-nav affordances: a midline split + chevrons (top=next, bottom=prev). */
    private fun drawNavStrips(canvas: Canvas) {
        if (leftStripActive()) drawNavStrip(canvas, 0f, navStripWidth)
        if (rightStripActive()) drawNavStrip(canvas, width - navStripWidth, width.toFloat())
    }

    private fun drawNavStrip(canvas: Canvas, left: Float, right: Float) {
        val midY = height / 2f
        val cx = (left + right) / 2f
        canvas.drawLine(left, midY, right, midY, navHairlinePaint)
        drawChevron(canvas, cx, height / 4f, pointRight = true)   // top = next
        drawChevron(canvas, cx, height * 3f / 4f, pointRight = false) // bottom = prev
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, pointRight: Boolean) {
        val w = navChevronHalfW
        val h = navChevronHalfH
        navPath.rewind()
        if (pointRight) {
            navPath.moveTo(cx - w, cy - h); navPath.lineTo(cx + w, cy); navPath.lineTo(cx - w, cy + h)
        } else {
            navPath.moveTo(cx + w, cy - h); navPath.lineTo(cx - w, cy); navPath.lineTo(cx + w, cy + h)
        }
        canvas.drawPath(navPath, navChevronPaint)
    }

    private fun drawHandleAt(canvas: Canvas, stemTop: Pair<Float, Float>) {
        val cx = stemTop.first
        val top = stemTop.second
        canvas.drawLine(cx, top, cx, top + handleStem, handleStemPaint)
        canvas.drawCircle(cx, top + handleStem + handleRadius, handleRadius, handlePaint)
    }

    private fun drawHint(canvas: Canvas) {
        val message = hint ?: return
        val x = width / 2f - hintPaint.measureText(message) / 2f
        val y = height / 2f
        canvas.drawText(message, x.coerceAtLeast(hPadding), y, hintPaint)
    }

    // --- Touch handling -------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Adjusting a committed selection via its grab handles takes priority over
        // page-turn / new-selection gestures, for both finger and stylus.
        if (selectionStart >= 0 && !isSelecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = handleHitTest(event.x, event.y)
                    if (hit != Handle.NONE) {
                        draggingHandle = hit
                        onHidePopup?.invoke() // hide the popup while dragging
                        epd.selection(this)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (draggingHandle != Handle.NONE) {
                    adjustHandle(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingHandle != Handle.NONE) {
                    draggingHandle = Handle.NONE
                    finishHandleAdjust()
                    return true
                }
            }
        }

        // Finger swipe → page turn. Track raw X/Y delta; require predominantly horizontal
        // motion so vertical scrolling content doesn't accidentally turn pages, and so
        // a clearly horizontal gesture always wins over any vertical interference.
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    fingerSwipeDownX = event.x
                    fingerSwipeDownY = event.y
                    Log.d("LeamhSwipe", "DOWN tool=${event.getToolType(0)} x=${event.x} y=${event.y}")
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - fingerSwipeDownX
                    val dy = event.y - fingerSwipeDownY
                    val swipeMin = ReaderTheme.dp(context, 60f)
                    Log.d("LeamhSwipe", "UP dx=$dx dy=$dy swipeMin=$swipeMin selStart=$selectionStart absDx=${Math.abs(dx)} absDy=${Math.abs(dy)}")
                    fingerSwipeDownX = -1f
                    fingerSwipeDownY = -1f
                    if (selectionStart < 0 && Math.abs(dx) > swipeMin && Math.abs(dx) > Math.abs(dy)) {
                        Log.d("LeamhSwipe", "TURNING PAGE dx=$dx")
                        if (dx < 0) next() else prev()
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    Log.d("LeamhSwipe", "CANCEL — gesture stolen by parent")
                    fingerSwipeDownX = -1f
                    fingerSwipeDownY = -1f
                }
            }
        }

        // Stylus drags to select directly; finger uses long-press (gestures) then
        // drag-to-extend while isSelecting; a tap falls through to the gesture detector.
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            return handleStylusEvent(event)
        }
        if (isSelecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> { extendSelectionTo(event.x, event.y); return true }
                MotionEvent.ACTION_UP -> { finaliseSelection(); return true }
                MotionEvent.ACTION_CANCEL -> { cancelSelection(); return true }
            }
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /**
     * Page-turn for a tap at ([x],[y]): only the active edge strip(s) navigate —
     * top half = next, bottom half = prev. Taps in the central reading area do
     * nothing. Returns true if the tap landed in a nav strip.
     */
    private fun navTap(x: Float, y: Float): Boolean {
        val inLeft = leftStripActive() && x < navStripWidth
        val inRight = rightStripActive() && x > width - navStripWidth
        if (!inLeft && !inRight) return false
        if (y < height / 2f) next() else prev()
        return true
    }

    /**
     * Stylus handler: drawing a line over text selects it (scrub-select). The
     * selection spans the min→max char offset touched across the ENTIRE drawn
     * path, so everything the pen passes over is selected — not just anchor→lift.
     * The band updates live during the drag; [finaliseSelection] snaps both ends
     * to word boundaries on lift. Taps turn the page or dismiss a shown selection.
     */
    private fun handleStylusEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ptrDownX = event.x
                ptrDownY = event.y
                ptrMoved = false
                ptrAnchorChar = charAtPoint(event.x, event.y) ?: -1
                // Seed the scrub range at the initial touch point.
                if (ptrAnchorChar >= 0) { scrubMin = ptrAnchorChar; scrubMax = ptrAnchorChar }
                else { scrubMin = Int.MAX_VALUE; scrubMax = -1 }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (ptrAnchorChar < 0) return true
                val dx = event.x - ptrDownX
                val dy = event.y - ptrDownY
                if (!ptrMoved && dx * dx + dy * dy > tapSlopPx * tapSlopPx) {
                    ptrMoved = true
                    isSelecting = true
                    onHidePopup?.invoke() // a new drag supersedes any shown selection's popup
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
                if (ptrMoved) extendScrubTo(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (ptrMoved) {
                    finaliseSelection()
                } else if (selectionStart >= 0) {
                    cancelSelection() // tap dismisses a shown selection
                } else if (pageLayout != null) {
                    val char = charAtPoint(event.x, event.y)
                    val hit = if (char != null) annotationAtChar(char) else null
                    if (hit != null) {
                        val pt = charPointInView(char!!, bottom = false)
                        onAnnotationTapped?.invoke(
                            hit,
                            pt?.first?.toInt() ?: event.x.toInt(),
                            pt?.second?.toInt() ?: event.y.toInt(),
                        )
                    } else {
                        navTap(event.x, event.y) // edge strips turn the page
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isSelecting) cancelSelection()
                ptrMoved = false
                return true
            }
        }
        return true
    }

    /**
     * Word-level, bidirectional selection from the drag anchor to ([x],[y]).
     * Repaints only the changed band (old ∪ new rows) so the e-ink does a fast
     * partial refresh during the drag instead of a full-screen update.
     */
    private fun extendSelectionTo(x: Float, y: Float) {
        val t = text?.toString() ?: return
        val cur = charAtPoint(x, y) ?: return
        val lo = minOf(ptrAnchorChar, cur)
        val hi = (maxOf(ptrAnchorChar, cur) + 1).coerceAtMost(t.length)
        val snapped = Anchoring.snapToWordBoundaries(t, lo, hi)
        if (snapped.start != selectionStart || snapped.end != selectionEnd) {
            val prevStart = selectionStart
            val prevEnd = selectionEnd
            selectionStart = snapped.start
            selectionEnd = snapped.end
            invalidateSelectionBand(prevStart, prevEnd)
        }
    }

    /**
     * Scrub-select (stylus only): accumulates the path's min/max char range so the
     * selection grows monotonically as the pen draws over text. Calls the same
     * partial-invalidate path as [extendSelectionTo] for fast regional refresh.
     */
    private fun extendScrubTo(x: Float, y: Float) {
        val t = text?.toString() ?: return
        val cur = charAtPoint(x, y) ?: return
        scrubMin = minOf(scrubMin, cur)
        scrubMax = maxOf(scrubMax, cur)
        val lo = scrubMin
        val hi = (scrubMax + 1).coerceAtMost(t.length)
        val snapped = Anchoring.snapToWordBoundaries(t, lo, hi)
        if (snapped.start != selectionStart || snapped.end != selectionEnd) {
            val prevStart = selectionStart
            val prevEnd = selectionEnd
            selectionStart = snapped.start
            selectionEnd = snapped.end
            invalidateSelectionBand(prevStart, prevEnd)
        }
    }

    /**
     * Invalidate just the vertical band spanned by the old ∪ new selection.
     * `invalidate(Rect)` is deprecated for hardware-accelerated views (where it's
     * a no-op), but this is a LAYER_TYPE_SOFTWARE e-ink view: the dirty rect is
     * what lets the panel do a fast partial refresh instead of a full update.
     */
    @Suppress("DEPRECATION")
    private fun invalidateSelectionBand(prevStart: Int, prevEnd: Int) {
        val ys = ArrayList<Float>(4)
        fun addSpan(s: Int, e: Int) {
            if (s < 0 || e <= s) return
            charPointInView(s, bottom = false)?.let { ys.add(it.second) }
            charPointInView(e, bottom = true)?.let { ys.add(it.second) }
        }
        addSpan(prevStart, prevEnd)
        addSpan(selectionStart, selectionEnd)
        if (ys.size < 2) { invalidate(); return }
        val top = (ys.min() - bodySize).toInt().coerceAtLeast(0)
        val bottom = (ys.max() + bodySize).toInt().coerceAtMost(height)
        dirtyRect.set(0, top, width, bottom)
        invalidate(dirtyRect)
    }

    /** Snap the current raw selection to word boundaries and fire [onSelectionReady]. */
    private fun finaliseSelection() {
        val t = text?.toString() ?: run { cancelSelection(); return }
        if (selectionStart < 0 || selectionEnd <= selectionStart) {
            cancelSelection()
            return
        }
        val snapped = Anchoring.snapToWordBoundaries(t, selectionStart, selectionEnd)
        selectionStart = snapped.start
        selectionEnd = snapped.end
        isSelecting = false
        epd.selection(this)
        val anchor = selectionAnchorInView()
        onSelectionReady?.invoke(
            selectionStart, selectionEnd,
            anchor?.first ?: (width / 2),
            anchor?.second ?: (height / 4),
        )
    }

    // --- Coordinate helpers ---------------------------------------------------

    /**
     * Maps a view-relative touch point to the nearest char offset in the
     * canonical plain text (using the layout for the current page). Returns null
     * if the layout isn't ready or the point is outside the content area.
     */
    private fun charAtPoint(vx: Float, vy: Float): Int? {
        val pl = pageLayout ?: return null
        val cx = vx - leftPad()
        val cy = vy - vPadding
        if (cy < 0) return null

        val cols = pl.columns
        val colWidth = pl.columnWidthPx.toFloat()
        val colGap = pl.columnGapPx.toFloat()
        val firstCol = pl.firstColumnOfPage(currentPage)

        val colInPage = when {
            cols == 1 -> 0
            cx < colWidth -> 0
            else -> 1
        }.coerceIn(0, cols - 1)

        val colX = colInPage * (colWidth + colGap)
        val localX = cx - colX

        val column = firstCol + colInPage
        if (column >= pl.columnCount) return null

        val startLine = pl.lineStartOfColumn(column)
        val endLine = pl.lineEndOfColumn(column)
        if (endLine <= startLine) return null

        val lineTopOfCol = pl.layout.getLineTop(startLine).toFloat()
        val layoutY = (cy + lineTopOfCol).toInt()
        val line = pl.layout.getLineForVertical(layoutY).coerceIn(startLine, endLine - 1)

        return pl.layout.getOffsetForHorizontal(line, localX)
    }

    /**
     * View-relative coordinates of the midpoint above the first line of the
     * current selection — used to anchor the annotation popup.
     */
    private fun selectionAnchorInView(): Pair<Int, Int>? {
        val pl = pageLayout ?: return null
        if (selectionStart < 0 || selectionEnd <= selectionStart) return null

        val firstCol = pl.firstColumnOfPage(currentPage)
        var colInPage = 0
        for (c in 0 until pl.columns) {
            val col = firstCol + c
            if (col >= pl.columnCount) break
            val colStart = pl.layout.getLineStart(pl.lineStartOfColumn(col))
            val colEnd = pl.layout.getLineEnd(pl.lineEndOfColumn(col) - 1)
            if (selectionStart in colStart..colEnd) {
                colInPage = c
                break
            }
        }

        val column = firstCol + colInPage
        if (column >= pl.columnCount) return null
        val startLine = pl.lineStartOfColumn(column)
        val endLine = pl.lineEndOfColumn(column)
        val lineTopOfCol = pl.layout.getLineTop(startLine).toFloat()

        val selLine = pl.layout.getLineForOffset(selectionStart).coerceIn(startLine, endLine - 1)
        val lineTop = pl.layout.getLineTop(selLine).toFloat()

        val xStart = pl.layout.getPrimaryHorizontal(selectionStart)
        val lineEndChar = pl.layout.getLineEnd(selLine)
        val xEnd = pl.layout.getPrimaryHorizontal(minOf(selectionEnd, lineEndChar - 1).coerceAtLeast(selectionStart))
        val midX = (xStart + xEnd) / 2f

        val colX = leftPad() + colInPage * (pl.columnWidthPx + pl.columnGapPx).toFloat()
        val viewX = (colX + midX).toInt()
        val viewY = (vPadding + lineTop - lineTopOfCol).toInt()

        return Pair(viewX, viewY)
    }

    // --- Selection handle geometry --------------------------------------------

    /** Which on-page column [char] falls in (0-based within the page), or null. */
    private fun columnOfChar(char: Int): Int? {
        val pl = pageLayout ?: return null
        val firstCol = pl.firstColumnOfPage(currentPage)
        for (c in 0 until pl.columns) {
            val col = firstCol + c
            if (col >= pl.columnCount) break
            val colStart = pl.layout.getLineStart(pl.lineStartOfColumn(col))
            val colEnd = pl.layout.getLineEnd(pl.lineEndOfColumn(col) - 1)
            if (char in colStart..colEnd) return c
        }
        return null
    }

    /**
     * View-relative point at the top ([bottom]=false) or bottom ([bottom]=true) of
     * [char]'s glyph on the current page, or null if [char] is not on this page.
     */
    private fun charPointInView(char: Int, bottom: Boolean): Pair<Float, Float>? {
        val pl = pageLayout ?: return null
        val colInPage = columnOfChar(char) ?: return null
        val column = pl.firstColumnOfPage(currentPage) + colInPage
        if (column >= pl.columnCount) return null
        val startLine = pl.lineStartOfColumn(column)
        val endLine = pl.lineEndOfColumn(column)
        val lineTopOfCol = pl.layout.getLineTop(startLine).toFloat()
        val line = pl.layout.getLineForOffset(char).coerceIn(startLine, endLine - 1)

        var x = pl.layout.getPrimaryHorizontal(char)
        // At a line-end/newline offset getPrimaryHorizontal collapses to the line
        // start; fall back to the right text edge so the end handle reads correctly.
        if (char > pl.layout.getLineStart(line) && x <= pl.layout.getPrimaryHorizontal(pl.layout.getLineStart(line))) {
            x = pl.layout.getLineRight(line)
        }
        val yLayout = if (bottom) pl.layout.getLineBottom(line).toFloat() else pl.layout.getLineTop(line).toFloat()
        val colX = leftPad() + colInPage * (pl.columnWidthPx + pl.columnGapPx).toFloat()
        return (colX + x) to (vPadding + (yLayout - lineTopOfCol))
    }

    /** Returns the annotation whose resolved span contains [char], preferring the narrowest. */
    private fun annotationAtChar(char: Int): ResolvedAnnotation? =
        annotations
            .filter { it.span != null && char >= it.span!!.start && char < it.span!!.end }
            .minByOrNull { it.span!!.end - it.span!!.start }

    /** Centre of the grab circle for the handle anchored at [char], in view space. */
    private fun handleCenter(char: Int): Pair<Float, Float>? {
        val a = charPointInView(char, bottom = true) ?: return null
        return a.first to (a.second + handleStem + handleRadius)
    }

    private fun handleHitTest(x: Float, y: Float): Handle {
        val grab2 = handleGrab * handleGrab
        val sc = handleCenter(selectionStart)
        val ec = handleCenter(selectionEnd)
        val ds = sc?.let { sq(x - it.first, y - it.second) } ?: Float.MAX_VALUE
        val de = ec?.let { sq(x - it.first, y - it.second) } ?: Float.MAX_VALUE
        return when {
            ds <= grab2 && ds <= de -> Handle.START
            de <= grab2 -> Handle.END
            else -> Handle.NONE
        }
    }

    private fun sq(a: Float, b: Float): Float = a * a + b * b

    /** Move the dragged handle's endpoint to the char under the touch (no crossing). */
    private fun adjustHandle(x: Float, y: Float) {
        val char = charAtPoint(x, y - handleTouchLift) ?: return
        val len = text?.length ?: return
        when (draggingHandle) {
            Handle.START -> selectionStart = char.coerceIn(0, selectionEnd - 1)
            Handle.END -> selectionEnd = char.coerceIn(selectionStart + 1, len)
            Handle.NONE -> return
        }
        epd.selection(this)
    }

    /** Re-anchor and re-show the tool popup at the adjusted selection. */
    private fun finishHandleAdjust() {
        epd.selection(this)
        val anchor = selectionAnchorInView()
        onSelectionReady?.invoke(
            selectionStart, selectionEnd,
            anchor?.first ?: (width / 2),
            anchor?.second ?: (height / 4),
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "LeamhReader"
        private const val NAV_STRIP_DP = 80f // matches the Flutter e-ink edge strip
        private const val MARGIN_ICON_DP = 24f // per-annotation margin glyph (icon extent; ToolIconRenderer fills it)
        // Light grey for highlighted text — clearly lighter than INK but still legible.
        // Negative literal = 0xFFAAAAAA (alpha=FF, R/G/B=AA).
    }
}

/** Sets a run's typeface (real italic) plus optional synthesized bold on a span. */
private class RunStyleSpan(private val tf: Typeface, private val fakeBold: Boolean) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) = apply(p)
    override fun updateDrawState(tp: TextPaint) = apply(tp)
    private fun apply(p: TextPaint) {
        p.typeface = tf
        if (fakeBold) p.isFakeBoldText = true
    }
}
