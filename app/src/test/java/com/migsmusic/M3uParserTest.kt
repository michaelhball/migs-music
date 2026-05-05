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
