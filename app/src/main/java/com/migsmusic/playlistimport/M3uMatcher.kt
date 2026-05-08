package com.migsmusic.playlistimport

import com.migsmusic.data.local.entity.SongEntity
import java.text.Normalizer

data class M3uMatchResult(
    val matched: List<MatchedSong>,
    val unmatched: List<M3uEntry>,
)

data class MatchedSong(val entry: M3uEntry, val song: SongEntity)

/**
 * Pre-computed library indices used by [matchM3uEntries]. Build once per batch import
 * (e.g. once for a Mac sync that lands several .m3u files at once) so we don't pay the
 * O(N) library indexing cost per file.
 *
 * Eagerly builds the cheap maps (lowercase artist+title, absolute path). The two
 * normalize()-based maps are slow to build — Unicode NFKD over thousands of strings
 * costs ~1s on a 2000-song library — so they're lazy and only paid for when the
 * cheap passes miss. For Mac-pushed M3Us where every entry resolves via pass 0 or
 * pass 1, the normalized maps are never built at all.
 */
class M3uMatcherIndex(private val library: List<SongEntity>) {
    // Pass 0 lookup. MediaStore stores paths as `/storage/emulated/0/...` while Mac-pushed
    // M3Us write `/sdcard/...` — same logical path on Android (sdcard is a symlink) but
    // not string-equal. We index BOTH forms so pass 0 hits in either direction without
    // having to allocate/normalize per lookup. At most one extra map entry per song.
    val byAbsolutePath: Map<String, SongEntity> =
        buildMap {
            for (s in library) {
                val p = s.absolutePath
                if (p.isEmpty()) continue
                put(p, s)
                if (p.startsWith(STORAGE_EMULATED_ROOT)) {
                    put("/sdcard/" + p.removePrefix(STORAGE_EMULATED_ROOT), s)
                } else if (p.startsWith("/sdcard/")) {
                    put(STORAGE_EMULATED_ROOT + p.removePrefix("/sdcard/"), s)
                }
            }
        }

    val byArtistTitle: Map<Pair<String, String>, SongEntity> =
        library.associateBy(
            keySelector = { it.artist.lowercase().trim() to it.title.lowercase().trim() },
            valueTransform = { it },
        )

    val byNormalizedArtistTitle: Map<Pair<String, String>, SongEntity> by lazy {
        library.associateBy(
            keySelector = { normalize(it.artist) to normalize(it.title) },
            valueTransform = { it },
        )
    }

    // Title-only normalized index, with deterministic disambiguation (alphabetical artist).
    val byNormalizedTitle: Map<String, SongEntity> by lazy {
        library
            .sortedBy { it.artist.lowercase() }
            .groupBy { normalize(it.title) }
            .mapValues { it.value.first() }
    }
}

fun matchM3uEntries(
    entries: List<M3uEntry>,
    library: List<SongEntity>,
): M3uMatchResult = matchM3uEntries(entries, M3uMatcherIndex(library))

/**
 * Maps M3U entries to library songs using a pre-built [index].
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
    index: M3uMatcherIndex,
): M3uMatchResult {
    if (entries.isEmpty()) return M3uMatchResult(emptyList(), emptyList())

    val matched = mutableListOf<MatchedSong>()
    val unmatched = mutableListOf<M3uEntry>()

    for (entry in entries) {
        val hit = matchOne(entry, index)
        if (hit != null) matched += MatchedSong(entry, hit) else unmatched += entry
    }

    return M3uMatchResult(matched, unmatched)
}

private fun matchOne(
    entry: M3uEntry,
    index: M3uMatcherIndex,
): SongEntity? {
    val artist = entry.artist?.trim()
    val title = entry.title?.trim()

    // Pass 0: exact absolutePath match. Mac-pushed M3Us already contain the on-phone
    // path of each track, so this hits ~100% of the time on a clean library and skips
    // the slower passes entirely. Cheap (single map lookup) and tried first.
    if (entry.rawPath.isNotEmpty()) {
        index.byAbsolutePath[entry.rawPath]?.let { return it }
    }

    if (!artist.isNullOrEmpty() && !title.isNullOrEmpty()) {
        // Pass 1: exact case-insensitive (artist, title). Eager map, single lookup.
        // Catches files that were moved on disk after sync (path differs but metadata
        // matches).
        index.byArtistTitle[artist.lowercase() to title.lowercase()]?.let { return it }
        // Pass 2: normalized (artist, title) — triggers building the lazy normalized
        // index on first miss in this batch. When every entry hits pass 0 or 1 (the
        // common case), the normalized index is never built and we save ~1s on a
        // 2000-song library.
        index.byNormalizedArtistTitle[normalize(artist) to normalize(title)]?.let { return it }
    }

    // Pass 3: basename fallback — useful when EXTINF is missing entirely. Derive a likely
    // title from the filename (strip extension and disambiguating prefixes like "01 -").
    val basename = basenameTitle(entry.rawPath)
    if (basename.isNotEmpty()) {
        index.byNormalizedTitle[normalize(basename)]?.let { return it }
    }

    // If we have a title but no artist (rare — old plain M3U), match title-only.
    if (!title.isNullOrEmpty()) {
        index.byNormalizedTitle[normalize(title)]?.let { return it }
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

private const val STORAGE_EMULATED_ROOT = "/storage/emulated/0/"
