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
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.DocxStore
import com.afluffywaffle.layuv.docx.LoadedDocument
import com.afluffywaffle.layuv.docx.ResolvedAnnotation
import com.afluffywaffle.layuv.docx.TextSpan
import com.afluffywaffle.layuv.docx.model.Annotation
import com.afluffywaffle.layuv.docx.model.AnnotationTag
import com.afluffywaffle.layuv.docx.model.AnnotationTool
import com.afluffywaffle.layuv.docx.model.ReadingMode
import com.afluffywaffle.layuv.docx.model.ReadingPosition
import com.afluffywaffle.layuv.docx.model.newId
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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

    // AppBarPill — icon pill replacing the old text-button toolbar.
    private lateinit var pillRow: LinearLayout
    private lateinit var annotationsButton: ChromeIconButton
    private lateinit var undoButton: ChromeIconButton
    private lateinit var moreButton: ChromeIconButton
    private var lockSlot: LockSlotView? = null

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var book: OpenBook? = null
    @Volatile private var savingPosition = false

    private val annotationPopup by lazy { AnnotationPopup(this) }
    private var pendingSelStart = -1
    private var pendingSelEnd = -1
    // Set when the user taps an existing annotation and then picks "comment" to edit its note.
    private var pendingAnnotation: ResolvedAnnotation? = null
    // Currently locked tool — new selections auto-annotate with this tool (no popup shown).
    private var lockedTool: AnnotationTool? = null
    // Annotation ID pre-allocated when launching InkNoteActivity so PNG and annotation share it.
    private var pendingInkId: String? = null
    // Most recently created annotation — the persistent undo button removes it.
    private var lastAnnotationId: String? = null

    // Transient undo pill — floats above the annotation's selection anchor.
    private var lastAnchorX = 0
    private var lastAnchorY = 0
    private var undoPillWindow: PopupWindow? = null
    private val undoDismissRunnable = Runnable { dismissUndoPill() }
    private val undoRenderer by lazy { ToolIconRenderer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContentView(buildUi())
        readerView.setNavSide(prefs.getString(KEY_NAV_SIDE, "both") ?: "both")
        Log.i(TAG, "smallestScreenWidthDp=${resources.configuration.smallestScreenWidthDp} (auto 2-col >= $AUTO_TWO_COL_MIN_DP)")
        reopenLastOrPrompt()
    }

    // --- UI ------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ReaderTheme.PAPER)
            setPadding(dp(12f), dp(6f), dp(12f), dp(10f))
        }

        pillRow = buildPill()

        pageIndicator = TextView(this).apply {
            typeface = ReaderTheme.chrome(this@ReaderActivity)
            setTextColor(ReaderTheme.INK_54)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            text = ""
        }

        toolbar.addView(pillRow)
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) // spacer
        toolbar.addView(pageIndicator, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        readerView = ReaderView(this).apply {
            onPageChanged = { page, count -> pageIndicator.text = "${page + 1} / $count" }
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
                    when (locked) {
                        AnnotationTool.comment -> startActivityForResult(
                            Intent(this@ReaderActivity, NoteActivity::class.java)
                                .putExtra(NoteActivity.EXTRA_SELECTED_TEXT, selText)
                                .putExtra(NoteActivity.EXTRA_INITIAL_TOOL, locked.name),
                            REQ_NOTE,
                        )
                        AnnotationTool.inkAnnotation -> launchInkCanvas(selText)
                        else -> commitAnnotation(locked, null)
                    }
                } else {
                annotationPopup.show(
                    anchor = this,
                    anchorX = anchorX,
                    anchorY = anchorY,
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
                        lockedTool = tool
                        updateLockSlot()
                        // Apply to current selection immediately, then stay locked.
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
                    onUnlock = { lockedTool = null; updateLockSlot() },
                )
                } // end else (no locked tool)
            }
            onHidePopup = { annotationPopup.dismiss() }
            onAnnotationTapped = { resolved, anchorX, anchorY ->
                // Faithful to Flutter's AnnotationActionToolbar: Comment | Delete.
                annotationPopup.showActions(
                    anchor = this,
                    anchorX = anchorX,
                    anchorY = anchorY,
                    onComment = { editAnnotationNote(resolved) },
                    onDelete = { deleteAnnotation(resolved) },
                )
            }
        }

        root.addView(readerView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        updatePillState()
        return root
    }

    /** The AppBarPill: annotations | undo | (lock) | more on a 6%-black rounded pill. */
    private fun buildPill(): LinearLayout {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.pill_bg)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        annotationsButton = ChromeIconButton(this, R.drawable.ic_list_alt) { launchAnnotationsPanel() }
        undoButton = ChromeIconButton(this, R.drawable.ic_undo) { undoLast() }
        moreButton = ChromeIconButton(this, R.drawable.ic_more_horiz) { showOverflowMenu() }
        pill.addView(annotationsButton)
        pill.addView(divider())
        pill.addView(undoButton)
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

    /** Dim the annotations button when empty and the undo button when nothing to undo. */
    private fun updatePillState() {
        val anns = book?.doc?.annotations
        annotationsButton.dimmed = anns.isNullOrEmpty()
        val canUndo = lastAnnotationId != null &&
            anns?.any { it.annotation.id == lastAnnotationId } == true
        undoButton.dimmed = !canUndo
    }

    /** Overflow menu: open another document, reader settings, export (soon). */
    private fun showOverflowMenu() {
        PopupMenu(this, moreButton).apply {
            menu.add(0, 1, 0, getString(R.string.open_document))
            menu.add(0, 2, 1, "Settings")
            menu.add(0, 3, 2, "Export (coming soon)").isEnabled = false
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { launchOpen(); true }
                    2 -> { launchSettings(); true }
                    else -> false
                }
            }
        }.show()
    }

    /** Remove the most recently created annotation (persistent undo). */
    private fun undoLast() {
        dismissUndoPill()
        val id = lastAnnotationId ?: return
        val opened = book ?: return
        val file = opened.file ?: return
        val newList = opened.doc.annotations.map { it.annotation }.filter { it.id != id }
        lastAnnotationId = null
        saveAnnotations(opened, file, newList)
    }

    /** Full-screen settings activity: eink_nav_side, eink_nav_reversed, ink_rule_lines. */
    private fun launchSettings() {
        startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
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
            "Grant “All files access” so Léamh can open and save annotations, then tap Open again.",
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_BROWSE -> if (resultCode == RESULT_OK) {
                val path = data?.getStringExtra(FileBrowserActivity.EXTRA_PATH) ?: return
                val file = File(path)
                if (file.canRead()) {
                    prefs.edit().putString(KEY_LAST_PATH, file.absolutePath).apply()
                    loadFromFile(file)
                } else {
                    readerView.showHint("Couldn’t read $path")
                }
            }
            REQ_NOTE -> {
                if (resultCode == RESULT_OK) {
                    val tool = AnnotationTool.fromName(data?.getStringExtra(NoteActivity.EXTRA_RESULT_TOOL))
                    val note = data?.getStringExtra(NoteActivity.EXTRA_NOTE)
                    val tag  = AnnotationTag.fromName(data?.getStringExtra(NoteActivity.EXTRA_RESULT_TAG))
                    val ink  = data?.getByteArrayExtra(NoteActivity.EXTRA_INK_PNG)
                    val inkId = data?.getStringExtra(NoteActivity.EXTRA_INK_ID)
                    commitAnnotationFromPanel(tool, note, tag, ink, inkId)
                } else {
                    readerView.cancelSelection()
                }
                readerView.post { initDrawPathLasso() }
            }
            REQ_INK -> {
                val inkId = pendingInkId
                pendingInkId = null
                if (resultCode == RESULT_OK && inkId != null) {
                    val pngBytes = data?.getByteArrayExtra(InkNoteActivity.EXTRA_INK_PNG)
                    commitInkAnnotation(inkId, pngBytes)
                } else {
                    readerView.cancelSelection()
                }
                readerView.post { initDrawPathLasso() }
            }
            REQ_RETOOL_NOTE -> {
                val ann = pendingAnnotation ?: return
                pendingAnnotation = null
                if (resultCode == RESULT_OK) {
                    val tool  = AnnotationTool.fromName(data?.getStringExtra(NoteActivity.EXTRA_RESULT_TOOL))
                    val note  = data?.getStringExtra(NoteActivity.EXTRA_NOTE)?.takeIf { it.isNotEmpty() }
                    val tag   = AnnotationTag.fromName(data?.getStringExtra(NoteActivity.EXTRA_RESULT_TAG))
                    val ink   = data?.getByteArrayExtra(NoteActivity.EXTRA_INK_PNG)
                    val inkId = data?.getStringExtra(NoteActivity.EXTRA_INK_ID)
                    val opened = book ?: return
                    val file = opened.file ?: return
                    val updated = ann.annotation.copy(
                        tool    = tool,
                        note    = note,
                        tag     = tag,
                        hasInk  = ink != null || ann.annotation.hasInk,
                    )
                    val newList = opened.doc.annotations.map { it.annotation }
                        .map { if (it.id == updated.id) updated else it }
                    val inkPng = if (ink != null && inkId != null) Pair(inkId, ink) else null
                    saveAnnotations(opened, file, newList, inkPng)
                }
                readerView.post { initDrawPathLasso() }
            }
            REQ_SETTINGS -> {
                // Re-apply nav side in case it changed in SettingsActivity.
                readerView.setNavSide(prefs.getString(KEY_NAV_SIDE, "both") ?: "both")
            }
            REQ_ANNOTATIONS -> {
                when (resultCode) {
                    RESULT_OK -> {
                        // User tapped an annotation row — jump to its position.
                        val fraction = data?.getDoubleExtra(AnnotationsPanelActivity.EXTRA_FRACTION, -1.0) ?: -1.0
                        if (fraction >= 0.0) {
                            val opened = book ?: return
                            val length = readerView.textLength()
                            val targetChar = (fraction * length).toInt().coerceIn(0, length)
                            readerView.jumpToChar(targetChar)
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

    private fun loadFromFile(file: File) {
        readerView.showHint("Loading…")
        ioExecutor.execute {
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

        val columns = resolveColumns()

        val length = opened.doc.plainText.length
        val fraction = opened.doc.position?.fraction ?: 0.0
        val startChar = (fraction * length).roundToInt().coerceIn(0, length)

        readerView.showContent(opened.doc.plainText, opened.doc.annotations, columns, startChar, opened.doc.formatSpans)
        updatePillState()
        dismissUndoPill()
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
        lastAnnotationId = annotation.id
        showUndoPill(lastAnchorX, lastAnchorY)

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

    /** Commit an ink annotation using the pre-allocated [inkId] and optional PNG bytes. */
    private fun commitInkAnnotation(inkId: String, pngBytes: ByteArray?) {
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

        readerView.cancelSelection()
        lastAnnotationId = inkId
        showUndoPill(lastAnchorX, lastAnchorY)

        val existing = opened.doc.annotations.map { it.annotation }
        val inkPng = if (pngBytes != null) Pair(inkId, pngBytes) else null
        saveAnnotations(opened, file, existing + annotation, inkPng)
    }

    /** Open the note editor for an existing annotation. Panel pre-selects its current tool. */
    private fun editAnnotationNote(resolved: ResolvedAnnotation) {
        if (book?.file == null) {
            Toast.makeText(this, "File is read-only — can't edit annotation.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingAnnotation = resolved
        startActivityForResult(
            Intent(this, NoteActivity::class.java)
                .putExtra(NoteActivity.EXTRA_NOTE, resolved.annotation.note ?: "")
                .putExtra(NoteActivity.EXTRA_SELECTED_TEXT, resolved.annotation.selectedText)
                .putExtra(NoteActivity.EXTRA_INITIAL_TOOL, resolved.annotation.tool.name),
            REQ_RETOOL_NOTE,
        )
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
        )

        readerView.cancelSelection()
        lastAnnotationId = annotation.id
        showUndoPill(lastAnchorX, lastAnchorY)

        val existing = opened.doc.annotations.map { it.annotation }
        val inkPng = if (inkBytes != null && inkId != null) Pair(inkId, inkBytes) else null
        saveAnnotations(opened, file, existing + annotation, inkPng)
    }

    private fun copyText(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Léamh", text))
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
                saveAnnotations(opened, file, newList)
            },
        )
    }

    private fun saveAnnotations(
        opened: OpenBook,
        file: File,
        newList: List<Annotation>,
        inkPng: Pair<String, ByteArray>? = null,
    ) {
        ioExecutor.execute {
            try {
                val baseBytes = if (inkPng != null)
                    DocxStore.saveInkPng(opened.bytes, inkPng.first, inkPng.second)
                else
                    opened.bytes
                val newBytes = DocxStore.write(baseBytes, newList)
                file.writeBytes(newBytes)
                val freshDoc = DocxStore.load(newBytes)
                val freshBook = OpenBook(opened.displayName, newBytes, freshDoc, file)
                main.post {
                    // Smart merge: if newer optimistic annotations were added while this
                    // save was running, keep those and only update bytes (so future saves
                    // have the right base). Otherwise do a full sync with re-resolved anchors.
                    val currentBook = book
                    if (currentBook != null) {
                        val savedIds = freshDoc.annotations.map { it.annotation.id }.toSet()
                        val currentIds = currentBook.doc.annotations.map { it.annotation.id }.toSet()
                        if (currentIds.all { it in savedIds }) {
                            book = freshBook
                            readerView.updateAnnotations(freshDoc.annotations)
                        } else {
                            book = OpenBook(freshBook.displayName, freshBook.bytes, currentBook.doc, freshBook.file)
                        }
                    }
                    updatePillState()
                }
            } catch (ex: Exception) {
                Log.e(TAG, "saveAnnotations failed", ex)
                main.post {
                    Toast.makeText(this, "Could not save changes.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- Position persistence ------------------------------------------------

    override fun onResume() {
        super.onResume()
        readerView.post { initDrawPathLasso() }
    }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    /** Write the current reading position back into the DOCX file (off-main). */
    private fun savePosition() {
        val opened = book ?: return
        val file = opened.file ?: return // read-only fallback: nothing to save
        val length = readerView.textLength()
        if (length <= 0 || savingPosition) return

        val offset = readerView.currentCharOffset()
        val fraction = offset.toDouble() / length
        val position = ReadingPosition(
            mode = ReadingMode.screenFlip, // page-at-a-time, no animation
            page = readerView.pageInfo().first,
            scrollOffset = 0.0,
            fraction = fraction,
        )

        savingPosition = true
        ioExecutor.execute {
            try {
                val newBytes = DocxStore.writePosition(opened.bytes, position)
                file.writeBytes(newBytes)
                Log.i(TAG, "saved position fraction=$fraction to ${file.name}")
            } catch (e: Exception) {
                Log.w(TAG, "could not save position", e)
            } finally {
                savingPosition = false
            }
        }
    }

    /**
     * Float a small "Undo" pill above [anchorX, anchorY] (ReaderView-relative coords)
     * for [UNDO_TIMEOUT_MS] ms. Tapping it calls [undoLast]. Dismissed by any new
     * annotation, book load, explicit undo, or delete.
     */
    private fun showUndoPill(anchorX: Int, anchorY: Int) {
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
            setOnTouchListener(PenTapListener(this@ReaderActivity) { undoLast() })
            addView(object : View(this@ReaderActivity) {
                override fun onDraw(canvas: Canvas) =
                    undoRenderer.drawVecIcon(canvas, R.drawable.ic_undo, width / 2f, height / 2f, iconExtent)
            }, LinearLayout.LayoutParams(dp(26f), dp(26f)))
            addView(TextView(this@ReaderActivity).apply {
                text = "Undo"
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
        val pw = PopupWindow(pill, WRAP_CONTENT, WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            isOutsideTouchable = false
            setOnDismissListener { undoPillWindow = null }
        }
        undoPillWindow = pw
        val loc = IntArray(2)
        readerView.getLocationInWindow(loc)
        val x = (loc[0] + anchorX - pillW / 2).coerceAtLeast(dp(8f))
        val y = (loc[1] + anchorY - pillH - dp(12f)).coerceAtLeast(dp(8f))
        pw.showAtLocation(readerView, Gravity.TOP or Gravity.START, x, y)
        main.postDelayed(undoDismissRunnable, UNDO_TIMEOUT_MS)
    }

    private fun dismissUndoPill() {
        main.removeCallbacks(undoDismissRunnable)
        undoPillWindow?.dismiss()
        undoPillWindow = null
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
        private const val REQ_SETTINGS = 1006
        private const val REQ_INK = 1007
        private const val PREFS = "leamh"
        private const val KEY_LAST_PATH = "last_path"
        private const val KEY_COLUMNS = "columns"
        private const val KEY_NAV_SIDE = "eink_nav_side"
        // The Nomad reports smallestScreenWidthDp=1024 and reads best at 1 col,
        // so the auto-2-col threshold sits above it; the larger Manta should land
        // above this and default to 2 col. Confirm the Manta's logged value and
        // tune. The toggle (persisted per device) overrides either way.
        private const val AUTO_TWO_COL_MIN_DP = 1200
        private const val UNDO_TIMEOUT_MS = 4_000L
    }
}
