package com.afluffywaffle.layuv.docx

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipEntry

/**
 * In-memory view of a DOCX (a ZIP archive). Reads every entry into a
 * name -> bytes map, preserving insertion order so a later full-rewrite can keep
 * entries in their original order. Pure JVM: works on desktop and Android, no
 * Play Services. Equivalent to the Dart `archive` package usage in docx_store.dart.
 *
 * Both [read] and [write] parse/emit the ZIP format directly (no
 * `ZipInputStream`/`ZipOutputStream`) so [read] can retain each entry's
 * ORIGINAL compressed bytes verbatim alongside its decompressed content,
 * method (STORED/DEFLATED) and CRC32, and [write] can raw-copy the original
 * compressed bytes for any entry whose decompressed content is unchanged —
 * `ZipOutputStream` has no public API to inject pre-compressed bytes for a
 * DEFLATED entry (it always re-deflates whatever is passed to `write()`), so
 * that raw-copy fast path requires building local/central-directory headers
 * by hand.
 */
class DocxArchive private constructor(
    private val entries: LinkedHashMap<String, ByteArray>,
    private val methods: Map<String, Int>,
    private val originalCompressed: Map<String, ByteArray>,
    private val crcs: Map<String, Long>,
) {
    fun bytes(name: String): ByteArray? = entries[name]
    fun text(name: String): String? = entries[name]?.toString(Charsets.UTF_8)
    fun contains(name: String): Boolean = entries.containsKey(name)
    val names: Set<String> get() = entries.keys

    /**
     * A mutable, order-preserving copy of the entries for a full rewrite.
     *
     * This IS a defensive copy of the top-level map (new LinkedHashMap), but the
     * ByteArray values themselves are NOT deep-copied — callers only ever replace
     * values wholesale (put a new/changed ByteArray) rather than mutating an
     * existing array in place, so aliasing the original decompressed bytes here is
     * safe and avoids doubling memory for every embedded media entry on every
     * write. [write]'s unchanged-entry detection compares these bytes against
     * [originalDecompressed] by content, so do not change this to a fully
     * independent deep copy without re-checking that path.
     */
    fun toMutableEntries(): LinkedHashMap<String, ByteArray> = LinkedHashMap(entries)

    /** The original decompressed bytes as read from the source archive, keyed by entry name. */
    fun originalDecompressed(): Map<String, ByteArray> = entries

    /**
     * Original compression method (STORED=0, DEFLATED=8) for each entry.
     * Pass to [write] so unchanged entries keep their original method instead
     * of being re-deflated.
     */
    fun entryMethods(): Map<String, Int> = methods

    /** Original CRC32 for each entry, as read from the source archive's central directory. */
    fun entryCrcs(): Map<String, Long> = crcs

    /** Original compressed bytes for each entry, exactly as stored in the source ZIP. */
    fun entryCompressedBytes(): Map<String, ByteArray> = originalCompressed

    companion object {
        private const val LOCAL_HEADER_SIG = 0x04034b50
        private const val CENTRAL_DIR_SIG = 0x02014b50
        private const val EOCD_SIG = 0x06054b50

        fun read(docx: ByteArray): DocxArchive {
            val entries = LinkedHashMap<String, ByteArray>()
            val methods = HashMap<String, Int>()
            val compressed = HashMap<String, ByteArray>()
            val crcs = HashMap<String, Long>()

            val eocdOffset = findEocd(docx)
            val centralDirOffset = readU32(docx, eocdOffset + 16).toInt()
            val entryCount = readU16(docx, eocdOffset + 10)

            var pos = centralDirOffset
            repeat(entryCount) {
                require(readU32(docx, pos).toInt() == CENTRAL_DIR_SIG) {
                    "Malformed central directory entry at offset $pos"
                }
                val method = readU16(docx, pos + 10)
                val crc = readU32(docx, pos + 16)
                val compressedSize = readU32(docx, pos + 20).toInt()
                val uncompressedSize = readU32(docx, pos + 24).toInt()
                val nameLen = readU16(docx, pos + 28)
                val extraLen = readU16(docx, pos + 30)
                val commentLen = readU16(docx, pos + 32)
                val localHeaderOffset = readU32(docx, pos + 42).toInt()
                val name = String(docx, pos + 46, nameLen, Charsets.UTF_8)
                val isDirectory = name.endsWith("/")

                if (!isDirectory) {
                    val dataStart = localFileDataOffset(docx, localHeaderOffset)
                    val rawBytes = docx.copyOfRange(dataStart, dataStart + compressedSize)
                    val decompressed = when (method) {
                        ZipEntry.STORED -> rawBytes
                        ZipEntry.DEFLATED -> inflate(rawBytes, uncompressedSize)
                        else -> error("Unsupported ZIP compression method $method for entry $name")
                    }
                    entries[name] = decompressed
                    methods[name] = method
                    compressed[name] = rawBytes
                    crcs[name] = crc
                }

                pos += 46 + nameLen + extraLen + commentLen
            }

            return DocxArchive(entries, methods, compressed, crcs)
        }

        /** Finds the "end of central directory" record, scanning backward for its signature. */
        private fun findEocd(docx: ByteArray): Int {
            val minEocdSize = 22
            var i = docx.size - minEocdSize
            while (i >= 0) {
                if (readU32(docx, i).toInt() == EOCD_SIG) return i
                i--
            }
            error("Not a valid ZIP/DOCX archive: no end-of-central-directory record found")
        }

        /** Local file header layout differs from the central directory's only in which fixed
         * offsets hold the name/extra lengths and the lack of a comment field. */
        private fun localFileDataOffset(docx: ByteArray, localHeaderOffset: Int): Int {
            require(readU32(docx, localHeaderOffset).toInt() == LOCAL_HEADER_SIG) {
                "Malformed local file header at offset $localHeaderOffset"
            }
            val nameLen = readU16(docx, localHeaderOffset + 26)
            val extraLen = readU16(docx, localHeaderOffset + 28)
            return localHeaderOffset + 30 + nameLen + extraLen
        }

        private fun inflate(raw: ByteArray, uncompressedSize: Int): ByteArray {
            val inflater = Inflater(true) // ZIP DEFLATE entries have no zlib header
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(uncompressedSize.coerceAtLeast(64))
            val buf = ByteArray(8192)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                }
            } finally {
                inflater.end()
            }
            return out.toByteArray()
        }

        private fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_SPEED, true) // raw DEFLATE, no zlib header
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size)
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            deflater.end()
            return out.toByteArray()
        }

        private fun readU16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun readU32(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)

        private fun writeU16(out: ByteArrayOutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        private fun writeU32(out: ByteArrayOutputStream, v: Long) {
            out.write((v and 0xFF).toInt())
            out.write(((v ushr 8) and 0xFF).toInt())
            out.write(((v ushr 16) and 0xFF).toInt())
            out.write(((v ushr 24) and 0xFF).toInt())
        }

        /**
         * Writes entries to a ZIP, preserving order. [source], if provided, is the
         * [DocxArchive] the entries were read from; any entry whose decompressed bytes
         * are bit-identical to what [source] read is raw-copied using its original
         * compressed bytes/CRC/method — skipping re-deflation entirely. Entries not
         * present in [source] (new or with changed content) are (re)compressed here.
         *
         * [sourceMethods] is kept as a narrower, back-compat entry point for callers
         * that only want STORED-preservation (no source archive at hand for the
         * raw-copy fast path, e.g. [DocxFromText]'s initial-write case).
         */
        fun write(
            entries: Map<String, ByteArray>,
            sourceMethods: Map<String, Int> = emptyMap(),
            source: DocxArchive? = null,
        ): ByteArray {
            val originalDecompressed = source?.originalDecompressed() ?: emptyMap()
            val originalCompressed = source?.entryCompressedBytes() ?: emptyMap()
            val originalCrcs = source?.entryCrcs() ?: emptyMap()
            val originalMethods = source?.entryMethods() ?: sourceMethods

            data class Prepared(
                val name: String,
                val method: Int,
                val crc: Long,
                val compressedSize: Int,
                val uncompressedSize: Int,
                val compressedBytes: ByteArray,
                val localHeaderOffset: Int,
            )

            val body = ByteArrayOutputStream()
            val prepared = ArrayList<Prepared>(entries.size)

            for ((name, data) in entries) {
                val unchanged = originalCompressed.containsKey(name) &&
                    originalDecompressed[name]?.contentEquals(data) == true

                val method: Int
                val crc: Long
                val compressedBytes: ByteArray
                if (unchanged) {
                    // Bit-identical to source: raw-copy the original compressed bytes,
                    // skipping decompression/recompression entirely.
                    method = originalMethods.getValue(name)
                    crc = originalCrcs.getValue(name)
                    compressedBytes = originalCompressed.getValue(name)
                } else if (originalMethods[name] == ZipEntry.STORED) {
                    // Preserve STORED for already-uncompressed media even when changed —
                    // avoids wasting CPU deflating incompressible content like PNGs.
                    method = ZipEntry.STORED
                    crc = CRC32().also { it.update(data) }.value
                    compressedBytes = data
                } else {
                    method = ZipEntry.DEFLATED
                    crc = CRC32().also { it.update(data) }.value
                    compressedBytes = deflate(data)
                }

                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val localHeaderOffset = body.size()

                writeU32(body, LOCAL_HEADER_SIG.toLong())
                writeU16(body, 20) // version needed
                writeU16(body, 0) // flags
                writeU16(body, method)
                writeU16(body, 0) // mod time
                writeU16(body, 0x21) // mod date (arbitrary fixed date, matches prior behavior of not caring about mtimes)
                writeU32(body, crc)
                writeU32(body, compressedBytes.size.toLong())
                writeU32(body, data.size.toLong())
                writeU16(body, nameBytes.size)
                writeU16(body, 0) // extra length
                body.write(nameBytes)
                body.write(compressedBytes)

                prepared += Prepared(name, method, crc, compressedBytes.size, data.size, compressedBytes, localHeaderOffset)
            }

            val centralDirStart = body.size()
            for (p in prepared) {
                val nameBytes = p.name.toByteArray(Charsets.UTF_8)
                writeU32(body, CENTRAL_DIR_SIG.toLong())
                writeU16(body, 20) // version made by
                writeU16(body, 20) // version needed
                writeU16(body, 0) // flags
                writeU16(body, p.method)
                writeU16(body, 0) // mod time
                writeU16(body, 0x21) // mod date
                writeU32(body, p.crc)
                writeU32(body, p.compressedSize.toLong())
                writeU32(body, p.uncompressedSize.toLong())
                writeU16(body, nameBytes.size)
                writeU16(body, 0) // extra length
                writeU16(body, 0) // comment length
                writeU16(body, 0) // disk number start
                writeU16(body, 0) // internal attrs
                writeU32(body, 0) // external attrs
                writeU32(body, p.localHeaderOffset.toLong())
                body.write(nameBytes)
            }
            val centralDirSize = body.size() - centralDirStart

            writeU32(body, EOCD_SIG.toLong())
            writeU16(body, 0) // disk number
            writeU16(body, 0) // disk with central dir
            writeU16(body, prepared.size) // entries on this disk
            writeU16(body, prepared.size) // total entries
            writeU32(body, centralDirSize.toLong())
            writeU32(body, centralDirStart.toLong())
            writeU16(body, 0) // comment length

            return body.toByteArray()
        }
    }
}
