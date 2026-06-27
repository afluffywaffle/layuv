package com.afluffywaffle.layuv.docx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * In-memory view of a DOCX (a ZIP archive). Reads every entry into a
 * name -> bytes map, preserving insertion order so a later full-rewrite can keep
 * entries in their original order. Pure JVM (`java.util.zip`): works on desktop
 * and Android, no Play Services. Equivalent to the Dart `archive` package usage
 * in docx_store.dart.
 */
class DocxArchive private constructor(
    private val entries: LinkedHashMap<String, ByteArray>,
    private val methods: Map<String, Int>,
) {
    fun bytes(name: String): ByteArray? = entries[name]
    fun text(name: String): String? = entries[name]?.toString(Charsets.UTF_8)
    fun contains(name: String): Boolean = entries.containsKey(name)
    val names: Set<String> get() = entries.keys

    /** A mutable, order-preserving copy of the entries for a full rewrite. */
    fun toMutableEntries(): LinkedHashMap<String, ByteArray> = LinkedHashMap(entries)

    /**
     * Original compression method (STORED=0, DEFLATED=8) for each entry.
     * Pass to [write] so unchanged entries keep their original method instead
     * of being re-deflated.
     */
    fun entryMethods(): Map<String, Int> = methods

    companion object {
        fun read(docx: ByteArray): DocxArchive {
            val map = LinkedHashMap<String, ByteArray>()
            val methods = HashMap<String, Int>()
            ZipInputStream(ByteArrayInputStream(docx)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        map[entry.name] = zin.readBytes()
                        methods[entry.name] = entry.method
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            return DocxArchive(map, methods)
        }

        /**
         * Writes entries to a ZIP, preserving order. [sourceMethods] should be
         * the result of [entryMethods] from the source archive; entries whose
         * original method was STORED are written uncompressed (avoids wasting CPU
         * re-deflating already-compressed content like PNGs). New or modified
         * entries not present in [sourceMethods] default to DEFLATED.
         */
        fun write(entries: Map<String, ByteArray>, sourceMethods: Map<String, Int> = emptyMap()): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                // A full rewrite happens on EVERY annotation/position save and re-deflates
                // the whole body (document.xml). On the Supernote's low-power CPU that
                // compression — not file size — is the felt cost, and these devices have
                // ample storage, so trade a little size for a faster save. BEST_SPEED is
                // purely encoder-side: any reader (Word/Pages/GDocs) inflates it identically,
                // and the golden tests compare extracted text, not compressed bytes.
                zos.setLevel(Deflater.BEST_SPEED)
                for ((name, data) in entries) {
                    val ze = ZipEntry(name)
                    if (sourceMethods[name] == ZipEntry.STORED) {
                        // STORED entries require size, compressedSize, and CRC set
                        // before putNextEntry; ZipOutputStream won't compute them.
                        ze.method = ZipEntry.STORED
                        ze.size = data.size.toLong()
                        ze.compressedSize = data.size.toLong()
                        val crc = CRC32().also { it.update(data) }
                        ze.crc = crc.value
                    }
                    zos.putNextEntry(ze)
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            return bos.toByteArray()
        }
    }
}
