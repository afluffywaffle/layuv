package com.afluffywaffle.layuv.docx.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * ISO-8601 timestamp handling that round-trips with Dart's
 * `DateTime.toIso8601String()` / `DateTime.parse()`.
 *
 * Léamh writes UTC timestamps (`…Z`), which [format] reproduces and [parse]
 * reads directly. [parse] also tolerates an explicit offset or a bare local
 * date-time (assumed UTC) so legacy/foreign records still load.
 */
object Timestamps {
    /**
     * Matches Dart's `DateTime.toUtc().toIso8601String()` exactly: always at
     * least milliseconds (3 digits), 6 digits when sub-millisecond microseconds
     * are present, `Z` suffix. (Java's `Instant.toString()` omits the fraction
     * entirely for whole seconds, which would break byte-equal comment dates.)
     */
    fun format(instant: Instant): String {
        val odt = instant.atOffset(ZoneOffset.UTC)
        val micros = instant.nano / 1000
        val frac = if (micros % 1000 == 0) "%03d".format(micros / 1000) else "%06d".format(micros)
        return "%04d-%02d-%02dT%02d:%02d:%02d.%sZ".format(
            odt.year, odt.monthValue, odt.dayOfMonth, odt.hour, odt.minute, odt.second, frac,
        )
    }

    /**
     * Short, human-readable UTC stamp (`yyyy-MM-dd HH:mm`) used to prefix
     * thread-reply paragraphs in `word/comments.xml` so Word/Pages readers see
     * when each reply was written. Display-only — Léamh re-reads thread data
     * from `leamh/annotations.json`, never by parsing this prefix back.
     */
    fun formatThreadPrefix(epochMillis: Long): String {
        val odt = Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC)
        return "%04d-%02d-%02d %02d:%02d".format(
            odt.year, odt.monthValue, odt.dayOfMonth, odt.hour, odt.minute,
        )
    }

    fun parse(s: String): Instant = try {
        Instant.parse(s)
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(s).toInstant()
        } catch (_: Exception) {
            LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)
        }
    }
}
