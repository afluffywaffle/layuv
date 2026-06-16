package com.afluffywaffle.layuv.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.reader.DrawPathClient
import java.io.ByteArrayOutputStream

enum class InkTool { THIN, THICK, ERASER }

/**
 * Ink canvas for stylus annotation. Mirrors Flutter's InkCanvasScreen:
 * - Thin / Thick / Erase tools, Lines toggle, Clear, Done
 * - Reference quote box showing the selected passage
 * - drawPath provides sub-millisecond hardware ink overlay; we capture
 *   MotionEvents ourselves for app-side stroke geometry and PNG export
 * - Toolbar + quote box are excluded from drawPath via setWritableAreas(flag=0)
 */
class InkNoteActivity : Activity() {

    private lateinit var canvas: InkCanvasView
    private var activeTool = InkTool.THIN
    private lateinit var thinBtn: TextView
    private lateinit var thickBtn: TextView
    private lateinit var eraseBtn: TextView
    private lateinit var linesBtn: TextView
    private var ruleStyle = "none"
    private var exclusionPx = 0

    private val pkg get() = packageName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val selectedText = intent.getStringExtra(EXTRA_SELECTED_TEXT) ?: ""
        val prefs = getSharedPreferences("leamh", Context.MODE_PRIVATE)
        ruleStyle = try {
            prefs.getString("ink_rule_lines", "none") ?: "none"
        } catch (_: ClassCastException) {
            val wasOn = try { prefs.getBoolean("ink_rule_lines", false) } catch (_: Exception) { false }
            prefs.edit().remove("ink_rule_lines").putString("ink_rule_lines", if (wasOn) "wide" else "none").commit()
            if (wasOn) "wide" else "none"
        }
        setContentView(buildUi(selectedText))
        // Pre-load existing ink as a background bitmap so the user can draw on top.
        intent.getByteArrayExtra(EXTRA_EXISTING_INK)?.let { bytes ->
            canvas.setExistingInk(bytes)
        }
    }

    override fun onResume() {
        super.onResume()
        canvas.post { initDrawPath() }
    }

    private fun initDrawPath() {
        if (!DrawPathClient.available()) return
        DrawPathClient.sendReset(pkg)
        applyPenToDrawPath()
        disableExclusionArea()
    }

    private fun applyPenToDrawPath() {
        when (activeTool) {
            InkTool.THIN    -> DrawPathClient.sendPen(pkg, 10, 150, 0)
            InkTool.THICK   -> DrawPathClient.sendPen(pkg, 10, 450, 0)
            InkTool.ERASER  -> DrawPathClient.sendPen(pkg, 10, 400, 254) // white pen
        }
    }

    private fun disableExclusionArea() {
        if (exclusionPx <= 0) return
        val w = resources.displayMetrics.widthPixels
        DrawPathClient.setWritableAreas(
            pkg,
            listOf(intArrayOf(0, 0, w, exclusionPx, 0)), // flag 0 = blacklist (non-writable)
            "disable-chrome",
        )
    }

    // -------------------------------------------------------------------------
    // UI
    // -------------------------------------------------------------------------

    private fun buildUi(selectedText: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val header = buildHeader()
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        val refBox = buildReferenceBox(selectedText)
        root.addView(refBox, LinearLayout.LayoutParams(MATCH_PARENT, dp(96f)))

        val toolbar = buildToolbar()
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, dp(64f)))
        root.addView(hDivider(), LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)))

        canvas = InkCanvasView(this)
        canvas.ruleStyle = ruleStyle
        root.addView(canvas, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.post {
            exclusionPx = header.height + dp(1f) + refBox.height + toolbar.height + dp(1f)
            if (DrawPathClient.available()) disableExclusionArea()
        }
        return root
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(8f), dp(16f), dp(4f))
        }
        header.addView(
            ChromeIconButton(this, R.drawable.ic_arrow_back) {
                setResult(RESULT_CANCELED)
                finish()
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        header.addView(TextView(this).apply {
            text = "Ink note"
            typeface = Typeface.create(ReaderTheme.body(context), Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ReaderTheme.INK_87)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return header
    }

    private fun buildReferenceBox(selectedText: String): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(0x0A000000) // ~4% black tint
        }
        frame.addView(TextView(this).apply {
            text = "“${selectedText.take(200)}”"
            typeface = ReaderTheme.bodyItalic(this@InkNoteActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ReaderTheme.INK_54)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.45f)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return frame
    }

    private fun buildToolbar(): View {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            setPadding(dp(4f), 0, dp(4f), 0)
        }

        thinBtn  = toolToggleView("Thin",  activeTool == InkTool.THIN)  { setTool(InkTool.THIN) }
        thickBtn = toolToggleView("Thick", activeTool == InkTool.THICK) { setTool(InkTool.THICK) }
        eraseBtn = toolToggleView("Erase", activeTool == InkTool.ERASER) { setTool(InkTool.ERASER) }
        linesBtn = toolbarTextBtn(rulesLabel()) { cycleRules() }
        val clearBtn = toolbarTextBtn("Clear") { onClear() }
        val doneBtn  = toolbarTextBtn("Done")  { onDone() }

        val btnW = dp(72f)
        toolbar.addView(thinBtn,  LinearLayout.LayoutParams(btnW, MATCH_PARENT))
        toolbar.addView(thickBtn, LinearLayout.LayoutParams(btnW, MATCH_PARENT))
        toolbar.addView(eraseBtn, LinearLayout.LayoutParams(btnW, MATCH_PARENT))
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        toolbar.addView(linesBtn, LinearLayout.LayoutParams(btnW, MATCH_PARENT))
        toolbar.addView(clearBtn, LinearLayout.LayoutParams(btnW, MATCH_PARENT))
        toolbar.addView(doneBtn,  LinearLayout.LayoutParams(btnW, MATCH_PARENT))
        return toolbar
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private fun setTool(tool: InkTool) {
        val wasEraser = activeTool == InkTool.ERASER
        activeTool = tool
        canvas.activeTool = tool
        refreshToolButtons()
        // Clear white eraser drawPath strokes before switching to ink
        if (wasEraser && DrawPathClient.available()) DrawPathClient.clearScreen(pkg)
        if (DrawPathClient.available()) {
            DrawPathClient.sendReset(pkg)
            applyPenToDrawPath()
            disableExclusionArea()
        }
        canvas.invalidate()
    }

    private fun onClear() {
        if (DrawPathClient.available()) DrawPathClient.clearScreen(pkg)
        canvas.clearStrokes()
    }

    private fun onDone() {
        val pngBytes = canvas.renderToPng() // null if no meaningful ink
        val result = Intent()
        if (pngBytes != null) result.putExtra(EXTRA_INK_PNG, pngBytes)
        // Always RESULT_OK — caller decides what to do with a null/absent PNG
        setResult(RESULT_OK, result)
        finish()
    }

    private fun cycleRules() {
        ruleStyle = when (ruleStyle) {
            "none"    -> "wide"
            "wide"    -> "college"
            else      -> "none"
        }
        canvas.ruleStyle = ruleStyle
        linesBtn.text = rulesLabel()
        canvas.invalidate()
    }

    private fun rulesLabel() = when (ruleStyle) {
        "wide"    -> "Wide"
        "college" -> "College"
        else      -> "Lines"
    }

    private fun refreshToolButtons() {
        fun boldIf(tv: TextView, b: Boolean) =
            tv.setTypeface(if (b) ReaderTheme.bodyBold(this) else ReaderTheme.body(this), Typeface.NORMAL)
        boldIf(thinBtn,  activeTool == InkTool.THIN)
        boldIf(thickBtn, activeTool == InkTool.THICK)
        boldIf(eraseBtn, activeTool == InkTool.ERASER)
    }

    // -------------------------------------------------------------------------
    // Widget helpers
    // -------------------------------------------------------------------------

    private fun toolToggleView(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = if (selected) ReaderTheme.bodyBold(context) else ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            setOnTouchListener(PenTapListener(this@InkNoteActivity, onClick))
        }

    private fun toolbarTextBtn(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            typeface = ReaderTheme.body(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ReaderTheme.INK_87)
            gravity = Gravity.CENTER
            setOnTouchListener(PenTapListener(this@InkNoteActivity, onClick))
        }

    private fun hDivider(): View = View(this).apply { setBackgroundColor(ReaderTheme.INK_12) }
    private fun dp(v: Float): Int = ReaderTheme.dp(this, v).toInt()

    companion object {
        const val EXTRA_SELECTED_TEXT = "selected_text"
        const val EXTRA_INK_PNG       = "ink_png"
        /** Optional: ByteArray of an existing ink PNG to display as a background layer. */
        const val EXTRA_EXISTING_INK  = "existing_ink"
    }
}

// =============================================================================
// InkCanvasView
// =============================================================================

/**
 * Full-screen drawing canvas for InkNoteActivity.
 *
 * drawPath renders the hardware ink overlay (no screencap visibility); this
 * view captures the same MotionEvents to build a parallel app-side Path list
 * for PNG export. On pen-up the committed stroke is added and invalidate()
 * triggers the EPD auto-refresh so the software layer appears under drawPath.
 */
class InkCanvasView(context: Context) : View(context) {

    private data class Stroke(val path: Path, val tool: InkTool)

    private val committed = ArrayList<Stroke>()
    private var current: Path? = null
    private var lastX = 0f
    private var lastY = 0f
    private var existingBitmap: Bitmap? = null

    var activeTool = InkTool.THIN
    var ruleStyle  = "none"

    /** Decode [pngBytes] and store as a background layer drawn beneath new strokes. */
    fun setExistingInk(pngBytes: ByteArray) {
        existingBitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        invalidate()
    }

    private val thinPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.STROKE
        strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val thickPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.STROKE
        strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val eraserPaint = Paint().apply {
        // Paper-colour stroke so erased region is visible on the software canvas
        color = 0xFFF5F0E8.toInt(); style = Paint.Style.STROKE
        strokeWidth = 36f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val rulePaint = Paint().apply {
        color = 0x4D000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }

    fun clearStrokes() {
        committed.clear(); current = null; invalidate()
    }

    fun hasMeaningfulInk(): Boolean = committed.any { it.tool != InkTool.ERASER }

    /**
     * Render to a transparent PNG. Eraser strokes punch holes via
     * PorterDuff.CLEAR; ink drawn after an erase survives correctly.
     * Existing ink (from a prior edit session) is composited as the base layer.
     * Returns null when there is no ink at all (no existing bitmap and no new strokes).
     */
    fun renderToPng(): ByteArray? {
        val hasNew = hasMeaningfulInk()
        val base   = existingBitmap
        if (!hasNew && base == null) return null
        val w = width; val h = height
        if (w <= 0 || h <= 0) return null

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (base != null) {
            c.drawBitmap(base, null, Rect(0, 0, w, h), null)
        }
        for (stroke in committed) {
            c.drawPath(stroke.path, exportPaint(stroke.tool))
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun exportPaint(tool: InkTool): Paint = when (tool) {
        InkTool.THIN  -> Paint(thinPaint).apply  { isAntiAlias = true }
        InkTool.THICK -> Paint(thickPaint).apply { isAntiAlias = true }
        InkTool.ERASER -> Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            style = Paint.Style.STROKE; strokeWidth = 36f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
    }

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                current = Path().apply { moveTo(lastX, lastY) }
            }
            MotionEvent.ACTION_MOVE -> {
                val p = current ?: return true
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i); val hy = event.getHistoricalY(i)
                    p.quadTo(lastX, lastY, (hx + lastX) / 2f, (hy + lastY) / 2f)
                    lastX = hx; lastY = hy
                }
                p.quadTo(lastX, lastY, (event.x + lastX) / 2f, (event.y + lastY) / 2f)
                lastX = event.x; lastY = event.y
                // drawPath provides live display — no per-move invalidate needed
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { committed.add(Stroke(it, activeTool)) }
                current = null
                invalidate() // committed strokes visible; triggers EPD auto-refresh
                if (activeTool == InkTool.ERASER) {
                    // Flush the white-pen eraser strokes from drawPath's buffer so
                    // the EPD redraw shows our corrected software layer
                    DrawPathClient.clearScreen(context.packageName)
                }
            }
        }
        return true
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFF5F0E8.toInt()) // paper background

        val density = resources.displayMetrics.density
        val spacing = when (ruleStyle) {
            "wide"    -> 40f * density
            "college" -> 32f * density
            else      -> 0f
        }
        if (spacing > 0f) {
            var y = spacing
            while (y < height) {
                canvas.drawLine(0f, y, width.toFloat(), y, rulePaint)
                y += spacing
            }
        }

        existingBitmap?.let { bmp ->
            canvas.drawBitmap(bmp, null, Rect(0, 0, width, height), null)
        }
        for (stroke in committed) canvas.drawPath(stroke.path, displayPaint(stroke.tool))
        current?.let { canvas.drawPath(it, displayPaint(activeTool)) }
    }

    private fun displayPaint(tool: InkTool) = when (tool) {
        InkTool.THIN   -> thinPaint
        InkTool.THICK  -> thickPaint
        InkTool.ERASER -> eraserPaint
    }
}
