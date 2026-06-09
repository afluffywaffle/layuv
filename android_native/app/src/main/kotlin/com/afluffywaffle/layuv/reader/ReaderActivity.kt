package com.afluffywaffle.layuv.reader

import android.app.Activity
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.afluffywaffle.layuv.R
import com.afluffywaffle.layuv.docx.DocxStore
import com.afluffywaffle.layuv.docx.model.Annotation
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
    private lateinit var columnsButton: Button
    private lateinit var prefs: SharedPreferences

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var book: OpenBook? = null
    @Volatile private var savingPosition = false

    private val annotationPopup by lazy { AnnotationPopup(this) }
    private var pendingSelStart = -1
    private var pendingSelEnd = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContentView(buildUi())
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
            val pad = dp(8f)
            setPadding(pad, pad, pad, pad)
        }

        val openButton = chromeButton(getString(R.string.open_document)) { launchOpen() }
        columnsButton = chromeButton(columnsLabel(resolveColumns())) { toggleColumns() }
        val annotationsButton = chromeButton(getString(R.string.annotations)) { launchAnnotationsPanel() }

        pageIndicator = TextView(this).apply {
            typeface = ReaderTheme.body(this@ReaderActivity)
            setTextColor(ReaderTheme.INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            text = ""
        }

        toolbar.addView(openButton)
        toolbar.addView(columnsButton)
        toolbar.addView(annotationsButton)
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) // spacer
        toolbar.addView(pageIndicator, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply {
            marginEnd = dp(8f)
        })

        readerView = ReaderView(this).apply {
            onPageChanged = { page, count -> pageIndicator.text = "${page + 1} / $count" }
            onSelectionReady = { start, end, anchorX, anchorY ->
                pendingSelStart = start
                pendingSelEnd = end
                annotationPopup.show(
                    this, anchorX, anchorY,
                    onDismiss = { readerView.cancelSelection() },
                ) { tool ->
                    if (tool == AnnotationTool.comment) {
                        startActivityForResult(
                            Intent(this@ReaderActivity, NoteActivity::class.java),
                            REQ_NOTE,
                        )
                    } else {
                        commitAnnotation(tool, null)
                    }
                }
            }
        }

        root.addView(readerView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    /** A large (>= 56dp) greyscale-safe chrome button — e-ink tap target. */
    private fun chromeButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        typeface = ReaderTheme.body(this@ReaderActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ReaderTheme.INK)
        minHeight = dp(56f)
        minimumHeight = dp(56f)
        minWidth = dp(64f)
        setOnClickListener { onClick() }
    }

    private fun columnsLabel(columns: Int): String = if (columns >= 2) "2 col" else "1 col"

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
                    val note = data?.getStringExtra(NoteActivity.EXTRA_NOTE)
                    commitAnnotation(AnnotationTool.comment, note)
                } else {
                    readerView.cancelSelection()
                }
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
        columnsButton.text = columnsLabel(columns)

        val length = opened.doc.plainText.length
        val fraction = opened.doc.position?.fraction ?: 0.0
        val startChar = (fraction * length).roundToInt().coerceIn(0, length)

        readerView.showContent(opened.doc.plainText, opened.doc.annotations, columns, startChar)
    }

    // --- Columns -------------------------------------------------------------

    /** Explicit user choice from prefs, else auto by screen width. */
    private fun resolveColumns(): Int {
        val stored = prefs.getInt(KEY_COLUMNS, 0)
        if (stored == 1 || stored == 2) return stored
        return if (resources.configuration.smallestScreenWidthDp >= AUTO_TWO_COL_MIN_DP) 2 else 1
    }

    private fun toggleColumns() {
        val next = if (readerView.columns() >= 2) 1 else 2
        prefs.edit().putInt(KEY_COLUMNS, next).apply()
        columnsButton.text = columnsLabel(next)
        readerView.setColumns(next)
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

        val existing = opened.doc.annotations.map { it.annotation }
        val updated = existing + annotation

        ioExecutor.execute {
            try {
                val newBytes = DocxStore.write(opened.bytes, updated)
                file.writeBytes(newBytes)
                val freshDoc = DocxStore.load(newBytes)
                val freshBook = OpenBook(opened.displayName, newBytes, freshDoc, file)
                Log.i(TAG, "annotation saved: ${annotation.tool} '${annotation.selectedText.take(30)}'")
                main.post {
                    book = freshBook
                    readerView.updateAnnotations(freshDoc.annotations)
                    readerView.fullClear()
                }
            } catch (ex: Exception) {
                Log.e(TAG, "commitAnnotation failed", ex)
                main.post {
                    Toast.makeText(this, "Could not save annotation.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- Position persistence ------------------------------------------------

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

    private fun dp(value: Float): Int = ReaderTheme.dp(this, value).roundToInt()

    companion object {
        private const val TAG = "LeamhActivity"
        private const val REQ_BROWSE = 1002
        private const val REQ_NOTE = 1003
        private const val REQ_ANNOTATIONS = 1004
        private const val PREFS = "leamh"
        private const val KEY_LAST_PATH = "last_path"
        private const val KEY_COLUMNS = "columns"
        // The Nomad reports smallestScreenWidthDp=1024 and reads best at 1 col,
        // so the auto-2-col threshold sits above it; the larger Manta should land
        // above this and default to 2 col. Confirm the Manta's logged value and
        // tune. The toggle (persisted per device) overrides either way.
        private const val AUTO_TWO_COL_MIN_DP = 1200
    }
}
