package com.afluffywaffle.layuv.reader

import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Process-wide serializer for EVERY `.docx` write in the app.
 *
 * Why this exists — the reader ([ReaderActivity]) and the annotations panel
 * ([AnnotationsPanelActivity]) are separate Activities. Each previously had its
 * own single-thread executor and its own copy of an atomic-write helper that
 * used the SAME `"<name>.tmp"` path. Two concurrent writers could therefore:
 *   (a) interleave on that shared temp file and rename a torn ZIP onto the live
 *       document (the original corruption class, no process kill required), and
 *   (b) each build their new bytes from a STALE in-memory snapshot, silently
 *       clobbering the other writer's just-saved change (lost update).
 *
 * This object fixes both:
 *   - ALL writes funnel through ONE shared thread, so they never interleave and
 *     FIFO ordering guarantees each write layers onto the previous commit.
 *   - Each task reads the CURRENT bytes FROM DISK (never a stale in-memory
 *     base); the [transform] maps on-disk bytes -> new bytes.
 *   - The write is atomic AND durable: a UNIQUE temp file, fsync'd before an
 *     atomic rename, with a best-effort parent-directory fsync after — and NO
 *     non-atomic in-place fallback that could truncate the live file.
 */
object DocxWriteQueue {

    private const val TAG = "DocxWriteQueue"

    // ONE thread for all docx writes across all activities.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "docx-write").apply { isDaemon = true }
    }

    /**
     * Serialize a read-modify-write against [file]. [transform] receives the
     * current on-disk bytes and returns the bytes to persist. [onSuccess] and
     * [onError] run on the shared write thread — `post` to the main thread
     * yourself before touching any View.
     */
    fun submit(
        file: File,
        transform: (ByteArray) -> ByteArray,
        onSuccess: (ByteArray) -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        executor.execute {
            try {
                val base = file.readBytes()
                val out = transform(base)
                if (out.isEmpty()) throw IOException("transform returned zero bytes for ${file.name}")
                writeAtomicDurable(file, out)
                onSuccess(out)
            } catch (e: Exception) {
                Log.e(TAG, "write failed for ${file.name}", e)
                onError(e)
            }
        }
    }

    /**
     * Serialize a read operation behind any pending writes. [block] runs on the
     * same single thread used for writes, so it sees the latest committed bytes.
     * Error handling is the caller's responsibility — wrap in try/catch inside [block].
     */
    fun enqueueRead(block: () -> Unit) {
        executor.execute(block)
    }

    /**
     * Write [bytes] to [file] atomically and durably:
     *   1. write to a UNIQUE sibling temp (`<name>.<nanos>.tmp`) so two writers
     *      can never collide on the temp path,
     *   2. fsync the temp's data to disk,
     *   3. atomic rename over the destination — throws on failure, NEVER a
     *      truncating in-place overwrite of the live file,
     *   4. best-effort parent-directory fsync so the rename survives power loss.
     * The temp is always cleaned up (rename consumes it on success; the `finally`
     * deletes it on any failure), so no orphan `.tmp` is left behind.
     */
    fun writeAtomicDurable(file: File, bytes: ByteArray) {
        val dir = file.parentFile
        val tmp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.flush()
                fos.fd.sync() // data durably on disk BEFORE the rename
            }
            // Same-directory rename is POSIX-atomic; a false return is a real
            // failure. Do NOT fall back to a truncating in-place write — that
            // reopens the torn-file window. Surface the error to the caller.
            if (!tmp.renameTo(file)) {
                throw IOException("atomic rename failed: ${tmp.name} -> ${file.name}")
            }
            fsyncDir(dir)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Best-effort fsync of [dir] so a completed rename's directory entry is durable. */
    private fun fsyncDir(dir: File?) {
        if (dir == null) return
        try {
            ParcelFileDescriptor.open(dir, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                Os.fsync(pfd.fileDescriptor)
            }
        } catch (e: Exception) {
            Log.w(TAG, "parent-dir fsync skipped: ${e.message}")
        }
    }
}
