package com.migsmusic

import com.migsmusic.playlistimport.parseM3u
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    @Test
    fun emptyContentReturnsEmptyList() {
        assertEquals(emptyList<Any>(), parseM3u(""))
        assertEquals(emptyList<Any>(), parseM3u("   \n\n  "))
    }

    @Test
    fun plainM3uReturnsPathsOnlyWithNoMetadata() {
        val content =
            """
            /Users/me/Music/Beatles/Hey Jude.mp3
            /Users/me/Music/Beatles/Let It Be.mp3
            """.trimIndent()

        val entries = parseM3u(content)
        assertEquals(2, entries.size)
        assertEquals("/Users/me/Music/Beatles/Hey Jude.mp3", entries[0].rawPath)
        assertNull(entries[0].artist)
        assertNull(entries[0].title)
        assertNull(entries[0].durationSec)
    }

    @Test
    fun extendedM3uPullsArtistTitleAndDuration() {
        val content =
            """
            #EXTM3U
            #EXTINF:241,The Beatles - Hey Jude
            /Users/me/Music/Beatles/Hey Jude.mp3
            #EXTINF:243,The Beatles - Let It Be
            /Users/me/Music/Beatles/Let It Be.mp3
            """.trimIndent()

        val entries = parseM3u(content)
        assertEquals(2, entries.size)
        assertEquals("The Beatles", entries[0].artist)
        assertEquals("Hey Jude", entries[0].title)
        assertEquals(241, entries[0].durationSec)
        assertEquals("Let It Be", entries[1].title)
    }

    @Test
    fun missingExtinfBetweenTwoTracksLeavesSecondMetadataNull() {
        val content =
            """
            #EXTM3U
            #EXTINF:241,The Beatles - Hey Jude
            /Users/me/Music/Beatles/Hey Jude.mp3
            /Users/me/Music/Beatles/Let It Be.mp3
            """.trimIndent()

        val entries = parseM3u(content)
        assertEquals(2, entries.size)
        // Pending metadata is consumed by the first path; the second entry has no metadata.
        assertEquals("Hey Jude", entries[0].title)
        assertNull(entries[1].artist)
        assertNull(entries[1].title)
        assertNull(entries[1].durationSec)
    }

    @Test
    fun extinfWithoutArtistTitleSeparatorTreatsWholeTailAsTitle() {
        val content =
            """
            #EXTM3U
            #EXTINF:184,Some Loose Track Title
            /Users/me/Music/Misc/Track.mp3
            """.trimIndent()

        val entry = parseM3u(content).single()
        assertEquals("Some Loose Track Title", entry.title)
        assertNull(entry.artist)
        assertEquals(184, entry.durationSec)
    }

    @Test
    fun extinfWithUnknownDurationStillParses() {
        // -1 is the M3U convention for "unknown duration". We preserve it as-is — callers
        // can choose to ignore negative durations.
        val content =
            """
            #EXTM3U
            #EXTINF:-1,Pink Floyd - Time
            /Users/me/Music/Pink Floyd/Time.mp3
            """.trimIndent()

        val entry = parseM3u(content).single()
        assertEquals(-1, entry.durationSec)
        assertEquals("Pink Floyd", entry.artist)
        assertEquals("Time", entry.title)
    }

    @Test
    fun bomIsStrippedFromStartOfFile() {
        val content = "﻿#EXTM3U\n/path/to.mp3"
        val entries = parseM3u(content)
        assertEquals(1, entries.size)
        assertEquals("/path/to.mp3", entries[0].rawPath)
    }

    @Test
    fun mixedCrlfAndLfLineEndingsBothWork() {
        val content = "#EXTM3U\r\n#EXTINF:120,Artist - Track\r\n/path/track.mp3\n"
        val entry = parseM3u(content).single()
        assertEquals("Artist", entry.artist)
        assertEquals("Track", entry.title)
    }

    @Test
    fun unrecognizedCommentsAreIgnoredButPathsAfterThemAreEntries() {
        val content =
            """
            #EXTM3U
            # this is a freeform comment
            #PLAYLIST:My Playlist
            #EXTINF:60,Artist - Title
            /path/song.mp3
            """.trimIndent()

        val entry = parseM3u(content).single()
        assertEquals("Artist", entry.artist)
        assertEquals("Title", entry.title)
    }

    @Test
    fun whitespaceAroundExtinfFieldsIsTrimmed() {
        val content = "#EXTINF: 200 ,  The Artist  -  The Title  \n/path/x.mp3"
        val entry = parseM3u(content).single()
        assertEquals(200, entry.durationSec)
        assertEquals("The Artist", entry.artist)
        assertEquals("The Title", entry.title)
    }

    @Test
    fun consecutiveExtinfLinesLastOneWins() {
        // Two EXTINF lines back-to-back — only the most recent applies to the path that
        // follows. (The first one's metadata is dropped on the floor; we don't surface it
        // anywhere.) Documents the actual behavior; not a feature, just want a regression
        // check if the parser's metadata-buffer logic ever changes.
        val content =
            """
            #EXTM3U
            #EXTINF:100,Wrong Artist - Wrong Title
            #EXTINF:200,Right Artist - Right Title
            /path/song.mp3
            """.trimIndent()

        val entry = parseM3u(content).single()
        assertEquals("Right Artist", entry.artist)
        assertEquals("Right Title", entry.title)
        assertEquals(200, entry.durationSec)
    }

    @Test
    fun pathOnlyM3uWithNoExtm3uMarkerStillParses() {
        // Plain M3U (no #EXTM3U header) — a list of paths, no metadata. Common for old-
        // style or hand-written playlists.
        val content =
            """
            /Music/A/track1.mp3
            /Music/A/track2.mp3
            /Music/A/track3.mp3
            """.trimIndent()
        val entries = parseM3u(content)
        assertEquals(3, entries.size)
        entries.forEach {
            assertNull(it.artist)
            assertNull(it.title)
        }
    }

    @Test
    fun unicodePathsAndTitlesRoundTrip() {
        // Unicode (CJK, accented, emoji) shouldn't trip the parser's UTF-8 handling.
        val content = "#EXTINF:120,Sigur Rós - Hoppípolla\n/Music/Sigur Rós/Hoppípolla.mp3"
        val entry = parseM3u(content).single()
        assertEquals("Sigur Rós", entry.artist)
        assertEquals("Hoppípolla", entry.title)
        assertEquals("/Music/Sigur Rós/Hoppípolla.mp3", entry.rawPath)
    }

    @Test
    fun titleWithExtraHyphensPreservesAfterFirstSeparator() {
        // EXTINF "Artist - Title - With - More - Hyphens": the parser splits on the FIRST
        // " - " sequence so the artist is "Artist" and the title carries the rest. Worth
        // a regression test because it'd be tempting to "fix" with a smarter heuristic.
        val content = "#EXTINF:120,Artist - Title - With - Hyphens\n/path.mp3"
        val entry = parseM3u(content).single()
        assertEquals("Artist", entry.artist)
        assertEquals("Title - With - Hyphens", entry.title)
    }

    @Test
    fun blankLinesAreSkipped() {
        val content =
            """

            #EXTM3U

            #EXTINF:60,Artist - One
            /path/one.mp3


            #EXTINF:60,Artist - Two
            /path/two.mp3

            """.trimIndent()

        val entries = parseM3u(content)
        assertEquals(2, entries.size)
        assertEquals("One", entries[0].title)
        assertEquals("Two", entries[1].title)
    }
}
