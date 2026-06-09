package com.afluffywaffle.layuv.reader

import android.content.Context
import android.net.Uri
import android.util.Log
import com.afluffywaffle.layuv.docx.DocxStore
import com.afluffywaffle.layuv.docx.LoadedDocument
import java.io.File

/**
 * A DOCX opened for reading. [file] is non-null when it was opened by real path
 * (direct file I/O, so position + annotations can be written back); null means a
 * read-only fallback opened via a content stream (the Supernote's SAF providers
 * return read-only URIs, so direct-path is the primary route — see
 * [[native-android-port]]).
 */
class OpenBook(
    val displayName: String,
    val bytes: ByteArray,
    val doc: LoadedDocument,
    val file: File?,
) {
    val writable: Boolean get() = file != null
}

/**
 * Reads a DOCX and runs it through the pure-JVM [DocxStore]. Call OFF the main
 * thread. Logs whole-book load time, plain-text length, annotation count, heap
 * delta and writability — the device spike numbers to read off
 * `adb logcat -s LeamhLoader`.
 */
object BookLoader {
    private const val TAG = "LeamhLoader"

    /** Primary path: direct file I/O (requires All-files access). Writable. */
    fun loadFromFile(file: File): OpenBook = finish(file.name, file.readBytes(), file)

    /** Fallback: read-only stream from a content [uri] (no write-back). */
    fun loadReadOnly(context: Context, uri: Uri, displayName: String): OpenBook {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("could not open input stream for $uri")
        return finish(displayName, bytes, null)
    }

    private fun finish(name: String, bytes: ByteArray, file: File?): OpenBook {
        val rt = Runtime.getRuntime()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val doc = DocxStore.load(bytes)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val heapAfter = rt.totalMemory() - rt.freeMemory()

        Log.i(
            TAG,
            "loaded ${bytes.size / 1024}KB '$name' in ${ms}ms: " +
                "plainChars=${doc.plainText.length} " +
                "annotations=${doc.annotations.size} " +
                "position=${doc.position?.fraction} " +
                "writable=${file != null} " +
                "heapDelta=${(heapAfter - heapBefore) / 1024}KB",
        )
        return OpenBook(name, bytes, doc, file)
    }
}
