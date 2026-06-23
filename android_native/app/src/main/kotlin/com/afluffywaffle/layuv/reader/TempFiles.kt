package com.afluffywaffle.layuv.reader

import android.content.Context
import java.io.File

/**
 * Shared cache-dir handoff for cross-Activity data passing (the "no static
 * `@Volatile`" pattern). The reader, note, and ink activities exchange PNG/JSON
 * payloads by writing them to [Context.cacheDir] and reading-then-deleting on the
 * far side. These four helpers were previously duplicated byte-for-byte in
 * ReaderActivity, NoteActivity, and InkNoteActivity.
 *
 * All operations swallow exceptions (best-effort handoff): a read returns null on
 * any failure, a write is a no-op on failure, and a null payload deletes the file.
 */
object TempFiles {
    fun readBytes(context: Context, name: String): ByteArray? = try {
        val f = File(context.cacheDir, name)
        if (!f.exists()) null else f.readBytes().also { f.delete() }
    } catch (_: Exception) { null }

    fun readText(context: Context, name: String): String? = try {
        val f = File(context.cacheDir, name)
        if (!f.exists()) null else f.readText().also { f.delete() }
    } catch (_: Exception) { null }

    fun writeBytes(context: Context, name: String, bytes: ByteArray?) = try {
        val f = File(context.cacheDir, name)
        if (bytes != null) f.writeBytes(bytes) else f.delete()
    } catch (_: Exception) {}

    fun writeText(context: Context, name: String, text: String?) = try {
        val f = File(context.cacheDir, name)
        if (text != null) f.writeText(text) else f.delete()
    } catch (_: Exception) {}
}
