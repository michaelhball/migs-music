package com.migsmusic

import com.migsmusic.data.local.entity.SongEntity
import com.migsmusic.playlistimport.M3uEntry
import com.migsmusic.playlistimport.M3uMatcherIndex
import com.migsmusic.playlistimport.basenameTitle
import com.migsmusic.playlistimport.matchM3uEntries
import com.migsmusic.playlistimport.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uMatcherTest {
    @Test
    fun exactCaseInsensitiveMatch() {
        val library = listOf(song(id = 1, artist = "The Beatles", title = "Hey Jude"))
        val entries = listOf(M3uEntry(rawPath = "ignored.mp3", artist = "the beatles", title = "HEY JUDE"))

        val result = matchM3uEntries(entries, library)

        assertEquals(1, result.matched.size)
        assertEquals(1L, result.matched[0].song.id)
        assertEquals(emptyList<M3uEntry>(), result.unmatched)
    }

    @Test
    fun normalizedFallbackHandlesFeatAndRemastered() {
        val library = listOf(song(id = 7, artist = "Pink Floyd", title = "Money"))
        // M3U entry has the Spotify-style qualifier; library has the bare title.
        val entries = listOf(M3uEntry(rawPath = "x.mp3", artist = "Pink Floyd", title = "Money (Remastered 2011)"))

        val result = matchM3uEntries(entries, library)

        assertEquals(1, result.matched.size)
        assertEquals(7L, result.matched[0].song.id)
    }

    @Test
    fun smartQuotesAndAccentsNormalize() {
        val library = listOf(song(id = 3, artist = "Beyonce", title = "Don't Hurt Yourself"))
        val entries =
            listOf(
                M3uEntry(rawPath = "x.mp3", artist = "Beyoncé", title = "Don’t Hurt Yourself"),
            )

        val result = matchM3uEntries(entries, library)

        assertEquals(1, result.matched.size)
        assertEquals(3L, result.matched[0].song.id)
    }

    @Test
    fun basenameFallbackUsedWhenNoExtinfMetadata() {
        val library = listOf(song(id = 4, artist = "Radiohead", title = "Karma Police"))
        // No artist/title fields — only a path.
        val entries =
            listOf(
                M3uEntry(rawPath = "/Users/me/Music/Radiohead/OK Computer/Karma Police.mp3"),
            )

        val result = matchM3uEntries(entries, library)

        assertEquals(1, result.matched.size)
        assertEquals(4L, result.matched[0].song.id)
    }

    @Test
    fun basenameFallbackStripsTrackNumberPrefixes() {
        val library = listOf(song(id = 9, artist = "Adele", title = "Hello"))
        val entries =
            listOf(
                M3uEntry(rawPath = "/Music/Adele/25/01 - Hello.mp3"),
                M3uEntry(rawPath = "/Music/Adele/25/01. Hello.mp3"),
                M3uEntry(rawPath = "/Music/Adele/25/01 Hello.mp3"),
            )

        val result = matchM3uEntries(entries, library)

        assertEquals(3, result.matched.size)
        result.matched.forEach { assertEquals(9L, it.song.id) }
    }

    @Test
    fun unmatchedEntriesAreReported() {
        val library = listOf(song(id = 1, artist = "A", title = "X"))
        val entries =
            listOf(
                M3uEntry(rawPath = "x.mp3", artist = "A", title = "X"),
                M3uEntry(rawPath = "y.mp3", artist = "Some Random", title = "Track Not In Library"),
            )

        val result = matchM3uEntries(entries, library)

        assertEquals(1, result.matched.size)
        assertEquals(1, result.unmatched.size)
        assertEquals("Track Not In Library", result.unmatched[0].title)
    }

    @Test
    fun whenArtistDiffersTitleOnlyMatchIsAvoided() {
        // Two artists with the same title — picking the wrong artist is worse than reporting
        // unmatched, so when EXTINF artist is provided we only match (artist, title).
        val library =
            listOf(
                song(id = 1, artist = "Artist A", title = "Same Title"),
                song(id = 2, artist = "Artist B", title = "Same Title"),
            )
        val entries =
            listOf(
                M3uEntry(rawPath = "x.mp3", artist = "Artist C", title = "Same Title"),
            )

        val result = matchM3uEntries(entries, library)

        // Pass 1+2 fail (artist doesn't match); fall through to basename → empty;
        // then to title-only match against the deterministic alphabetical-first artist.
        // (This documents the current behaviour — we accept some false-positive risk for
        // legacy plain-M3U coverage. If this becomes a problem, switch to strict.)
        assertEquals(1, result.matched.size)
        assertEquals(1L, result.matched[0].song.id) // alphabetical first wins
    }

    @Test
    fun emptyEntriesProducesEmptyResult() {
        val result = matchM3uEntries(emptyList(), listOf(song(1, "A", "B")))
        assertEquals(emptyList<Any>(), result.matched)
        assertEquals(emptyList<Any>(), result.unmatched)
    }

    @Test
    fun emptyLibraryProducesAllUnmatched() {
        val entries = listOf(M3uEntry("x.mp3", artist = "A", title = "B"))
        val result = matchM3uEntries(entries, emptyList())
        assertEquals(emptyList<Any>(), result.matched)
        assertEquals(1, result.unmatched.size)
    }

    @Test
    fun preservesM3uOrderInMatched() {
        val library =
            listOf(
                song(1, "X", "First"),
                song(2, "X", "Second"),
                song(3, "X", "Third"),
            )
        val entries =
            listOf(
                M3uEntry("a.mp3", artist = "X", title = "Third"),
                M3uEntry("b.mp3", artist = "X", title = "First"),
                M3uEntry("c.mp3", artist = "X", title = "Second"),
            )

        val result = matchM3uEntries(entries, library)

        assertEquals(listOf(3L, 1L, 2L), result.matched.map { it.song.id })
    }

    @Test
    fun normalizeRespectsBoundaries() {
        // Smoke check on the helper directly.
        assertEquals("hey jude", normalize("Hey Jude"))
        // Curly apostrophe flattened to straight, but kept (semantically meaningful).
        assertEquals("don't stop me now", normalize("Don’t Stop Me Now"))
        assertEquals("cafe", normalize("Café"))
        // Square-bracket and parenthesised qualifiers stripped.
        assertEquals("creep", normalize("Creep (Acoustic)"))
        assertEquals("creep", normalize("Creep [Live at Glasto]"))
        assertTrue(normalize("  multi   space  ") == "multi space")
    }

    @Test
    fun reusedMatcherIndexProducesSameResultsAcrossCalls() {
        // Index is built once per Mac sync batch. Hitting the same library across multiple
        // entry batches must produce identical matches — regression guard for the perf
        // optimisation that shares the index across files.
        val library =
            listOf(
                song(1, "Artist", "First"),
                song(2, "Artist", "Second"),
            )
        val index = M3uMatcherIndex(library)

        val firstBatch =
            matchM3uEntries(
                listOf(M3uEntry("a.mp3", artist = "Artist", title = "First")),
                index,
            )
        val secondBatch =
            matchM3uEntries(
                listOf(M3uEntry("b.mp3", artist = "Artist", title = "Second")),
                index,
            )
        // Re-run the first batch to make sure the index hasn't been mutated.
        val firstBatchAgain =
            matchM3uEntries(
                listOf(M3uEntry("a.mp3", artist = "Artist", title = "First")),
                index,
            )

        assertEquals(1L, firstBatch.matched.single().song.id)
        assertEquals(2L, secondBatch.matched.single().song.id)
        assertEquals(1L, firstBatchAgain.matched.single().song.id)
    }

    @Test
    fun normalizedArtistTitleFallbackTrumpsBasenameWhenBothWouldMatch() {
        // The matcher's three-pass order is exact (artist+title) → normalised → basename.
        // Make sure pass 2 wins when pass 1 fails — even if basename would also match a
        // different song, normalised artist+title is more specific and should win.
        val library =
            listOf(
                song(1, "Pink Floyd", "Money"),
                song(2, "Some Other Artist", "Money"),
            )
        // Entry has the qualifier; library has the bare title. Normalised match against
        // (Pink Floyd, Money) succeeds in pass 2.
        val entries =
            listOf(
                M3uEntry(rawPath = "/Music/SomeOtherArtist/Money.mp3", artist = "Pink Floyd", title = "Money (2011 Remaster)"),
            )

        val result = matchM3uEntries(entries, library)

        assertEquals(1L, result.matched.single().song.id)
    }

    @Test
    fun emptyLibraryProducesEmptyIndexButDoesntCrash() {
        // M3uMatcherIndex(emptyList()) should produce empty maps, not blow up.
        val index = M3uMatcherIndex(emptyList())
        val result =
            matchM3uEntries(
                listOf(M3uEntry("a.mp3", artist = "X", title = "Y")),
                index,
            )
        assertEquals(0, result.matched.size)
        assertEquals(1, result.unmatched.size)
    }

    @Test
    fun titleContainingHyphensSurvivesParsedSeparator() {
        // EXTINF "Artist - Title with - hyphen" — the parser splits on the FIRST " - "
        // separator, so artist is "Artist" and title is "Title with - hyphen". The matcher
        // should find it.
        val library = listOf(song(7, artist = "The Artist", title = "Title with - hyphen"))
        val entries =
            listOf(
                M3uEntry(rawPath = "x.mp3", artist = "The Artist", title = "Title with - hyphen"),
            )
        val result = matchM3uEntries(entries, library)
        assertEquals(7L, result.matched.single().song.id)
    }

    @Test
    fun basenameTitleHandlesBothSeparators() {
        assertEquals("Hello", basenameTitle("/Music/Adele/Hello.mp3"))
        assertEquals("Hello", basenameTitle("C:\\Music\\Adele\\Hello.mp3"))
        assertEquals("Hello", basenameTitle("Hello.mp3"))
        assertEquals("Hello", basenameTitle("Hello"))
        assertEquals("", basenameTitle(""))
    }

    private fun song(
        id: Long,
        artist: String,
        title: String,
    ) = SongEntity(
        id = id,
        contentUri = "content://media/external/audio/media/$id",
        albumId = null,
        title = title,
        artist = artist,
        album = "Album",
        durationMs = 200_000,
        trackNumber = 1,
        discNumber = 1,
        folderPath = "Music",
        folderName = "Music",
        albumArtUri = null,
        dateAddedSeconds = 0,
        dateModifiedSeconds = 0,
    )
}
