package com.migsmusic.playlistimport

/**
 * One track entry parsed from an M3U file.
 *
 * @param rawPath The path token as written in the M3U (likely a Mac filesystem path that
 *                won't resolve on Android — used only for basename fallback during matching).
 * @param artist From `#EXTINF` if the file is extended M3U, else null.
 * @param title  From `#EXTINF` if present, else null.
 * @param durationSec From `#EXTINF` if present (-1 in M3U means unknown).
 */
data class M3uEntry(
    val rawPath: String,
    val artist: String? = null,
    val title: String? = null,
    val durationSec: Int? = null,
)

/**
 * Parses M3U / Extended M3U content into [M3uEntry] rows in source order.
 *
 * Format (per https://en.wikipedia.org/wiki/M3U):
 * - Plain M3U: one path per line. Lines starting with `#` are comments.
 * - Extended M3U: starts with `#EXTM3U`. Each track is preceded by an `#EXTINF:duration,Artist - Title`
 *   line; the next non-comment line is the path.
 *
 * Tolerant: any malformed `#EXTINF` line is treated as if metadata were absent. Comments other
 * than `#EXTINF:` are ignored. Mixed line endings (`\r\n`, `\n`, `\r`) all work. A leading
 * UTF-8 BOM is stripped.
 */
fun parseM3u(content: String): List<M3uEntry> {
    if (content.isBlank()) return emptyList()

    // Strip UTF-8 BOM if present — Music.app's exports sometimes include it.
    val cleaned = content.trimStart('﻿')

    val out = mutableListOf<M3uEntry>()
    var pendingArtist: String? = null
    var pendingTitle: String? = null
    var pendingDuration: Int? = null

    for (rawLine in cleaned.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue

        if (line.startsWith("#")) {
            if (line.startsWith("#EXTINF:")) {
                val (dur, artist, title) = parseExtInf(line.removePrefix("#EXTINF:"))
                pendingDuration = dur
                pendingArtist = artist
                pendingTitle = title
            }
            // All other comments (including #EXTM3U) are ignored.
            continue
        }

        out +=
            M3uEntry(
                rawPath = line,
                artist = pendingArtist,
                title = pendingTitle,
                durationSec = pendingDuration,
            )
        pendingArtist = null
        pendingTitle = null
        pendingDuration = null
    }

    return out
}

/**
 * Parses the body of an `#EXTINF:` line. Format: `<duration>,<artist> - <title>`.
 * Returns (duration, artist, title); any field that can't be parsed comes back null.
 */
private fun parseExtInf(body: String): Triple<Int?, String?, String?> {
    val commaIndex = body.indexOf(',')
    if (commaIndex < 0) {
        // No comma — entire body is treated as the title; duration unknown.
        val title = body.trim().ifEmpty { null }
        return Triple(null, null, title)
    }

    val durationToken = body.substring(0, commaIndex).trim()
    val duration = durationToken.toIntOrNull()
    val rest = body.substring(commaIndex + 1).trim()
    if (rest.isEmpty()) return Triple(duration, null, null)

    // Split artist/title on the first " - " (with surrounding spaces). If absent, treat the
    // whole rest as the title.
    val sepIndex = rest.indexOf(" - ")
    return if (sepIndex < 0) {
        Triple(duration, null, rest)
    } else {
        val artist = rest.substring(0, sepIndex).trim().ifEmpty { null }
        val title = rest.substring(sepIndex + 3).trim().ifEmpty { null }
        Triple(duration, artist, title)
    }
}
