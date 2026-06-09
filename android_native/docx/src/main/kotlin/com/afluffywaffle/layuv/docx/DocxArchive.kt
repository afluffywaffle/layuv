package com.afluffywaffle.layuv.docx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
) {
    fun bytes(name: String): ByteArray? = entries[name]
    fun text(name: String): String? = entries[name]?.toString(Charsets.UTF_8)
    fun contains(name: String): Boolean = entries.containsKey(name)
    val names: Set<String> get() = entries.keys

    /** A mutable, order-preserving copy of the entries for a full rewrite. */
    fun toMutableEntries(): LinkedHashMap<String, ByteArray> = LinkedHashMap(entries)

    companion object {
        fun read(docx: ByteArray): DocxArchive {
            val map = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(docx)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        map[entry.name] = zin.readBytes()
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            return DocxArchive(map)
        }

        /** Writes entries to a ZIP, preserving order. */
        fun write(entries: Map<String, ByteArray>): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                for ((name, data) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            return bos.toByteArray()
        }
    }
}
