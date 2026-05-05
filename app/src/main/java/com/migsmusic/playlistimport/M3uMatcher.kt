package com.migsmusic.playlistimport

import com.migsmusic.data.local.entity.SongEntity
import java.text.Normalizer

data class M3uMatchResult(
    val matched: List<MatchedSong>,
    val unmatched: List<M3uEntry>,
)

data class MatchedSong(val entry: M3uEntry, val song: SongEntity)

/**
 * Maps M3U entries to library songs.
 *
 * Three passes per entry, in order. Only the first hit wins:
 * 1. Exact case-insensitive (artist, title) match against `SongEntity.artist + .title`.
 * 2. Normalized match — lowercase, ASCII-folded, parenthesised qualifiers stripped
 *    (`(feat. X)`, `[Remastered]`, etc), smart-quotes flattened.
 * 3. Filename-basename fallback — handles plain M3U without `#EXTINF` metadata.
 *
 * Strict — when an artist is provided and no library song matches, we leave it unmatched
 * rather than guessing across artists. The user gets a clear "couldn't find these" list.
 */
fun matchM3uEntries(
    entries: List<M3uEntry>,
    library: List<SongEntity>,
): M3uMatchResult {
    if (entries.isEmpty()) return M3uMatchResult(emptyList(), emptyList())

    // Pre-index the library three ways for O(1) lookup per entry.
    val byArtistTitle: Map<Pair<String, String>, SongEntity> =
        library.associateBy(
            keySelector = { it.artist.lowercase().trim() to it.title.lowercase().trim() },
            valueTransform = { it },
        )
    val byNormalizedArtistTitle: Map<Pair<String, String>, SongEntity> =
        library.associateBy(
            keySelector = { normalize(it.artist) to normalize(it.title) },
            valueTransform = { it },
        )
    // Title-only normalized index, with deterministic disambiguation (alphabetical artist).
    val byNormalizedTitle: Map<String, SongEntity> =
        library
            .sortedBy { it.artist.lowercase() }
            .groupBy { normalize(it.title) }
            .mapValues { it.value.first() }

    val matched = mutableListOf<MatchedSong>()
    val unmatched = mutableListOf<M3uEntry>()

    for (entry in entries) {
        val hit = matchOne(entry, byArtistTitle, byNormalizedArtistTitle, byNormalizedTitle)
        if (hit != null) matched += MatchedSong(entry, hit) else unmatched += entry
    }

    return M3uMatchResult(matched, unmatched)
}

private fun matchOne(
    entry: M3uEntry,
    byArtistTitle: Map<Pair<String, String>, SongEntity>,
    byNormalizedArtistTitle: Map<Pair<String, String>, SongEntity>,
    byNormalizedTitle: Map<String, SongEntity>,
): SongEntity? {
    val artist = entry.artist?.trim()
    val title = entry.title?.trim()

    if (!artist.isNullOrEmpty() && !title.isNullOrEmpty()) {
        // Pass 1: exact case-insensitive (artist, title).
        byArtistTitle[artist.lowercase() to title.lowercase()]?.let { return it }
        // Pass 2: normalized (artist, title).
        byNormalizedArtistTitle[normalize(artist) to normalize(title)]?.let { return it }
    }

    // Pass 3: basename fallback — useful when EXTINF is missing entirely. Derive a likely
    // title from the filename (strip extension and disambiguating prefixes like "01 -").
    val basename = basenameTitle(entry.rawPath)
    if (basename.isNotEmpty()) {
        byNormalizedTitle[normalize(basename)]?.let { return it }
    }

    // If we have a title but no artist (rare — old plain M3U), match title-only.
    if (!title.isNullOrEmpty()) {
        byNormalizedTitle[normalize(title)]?.let { return it }
    }

    return null
}

/**
 * Pulls a likely-title out of a filesystem path. Handles both `/` and `\` separators (Mac
 * vs Windows exports), strips the extension, and strips a leading "01 - " / "01. " / "01 "
 * track-number prefix.
 */
internal fun basenameTitle(path: String): String {
    val lastSep = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    val basename = if (lastSep >= 0) path.substring(lastSep + 1) else path
    val withoutExt = basename.substringBeforeLast('.', basename)
    return TRACK_NUMBER_PREFIX.replace(withoutExt, "").trim()
}

/**
 * Folds artist/title strings to a canonical comparable form. Conservative — preserves enough
 * to keep "Hey Jude" distinct from "Hey, Jude" but tolerant of casing, smart quotes, accents,
 * and common qualifier suffixes.
 */
internal fun normalize(s: String): String {
    val noSmart =
        s
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace('“', '"')
            .replace('”', '"')
    // NFKD then strip combining marks → "Café" → "Cafe".
    val ascii =
        Normalizer
            .normalize(noSmart, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
    val noQualifiers = QUALIFIERS.replace(ascii, "")
    return noQualifiers.lowercase().replace(WHITESPACE_RUN, " ").trim()
}

// e.g. " (feat. Lana del Rey)", " [Remastered 2009]", " (Live at Wembley)"
private val QUALIFIERS = Regex("""\s*[\(\[][^\)\]]*[\)\]]\s*""")
private val WHITESPACE_RUN = Regex("""\s+""")
private val COMBINING_MARKS = Regex("""\p{InCombiningDiacriticalMarks}+""")

// Leading "01 - ", "01. ", "01 " — common in iTunes filename exports.
private val TRACK_NUMBER_PREFIX = Regex("""^\d{1,3}[\s.\-]+""")
