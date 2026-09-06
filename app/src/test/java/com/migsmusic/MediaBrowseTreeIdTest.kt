package com.migsmusic

import com.migsmusic.playback.ParsedSongId
import com.migsmusic.playback.parseSongId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaBrowseTreeIdTest {
    @Test
    fun parsesSongIdWithEachParentKind() {
        assertEquals(ParsedSongId(42L, "pl:7"), parseSongId("sg:42@pl:7"))
        assertEquals(ParsedSongId(1L, "cat:songs"), parseSongId("sg:1@cat:songs"))
        assertEquals(ParsedSongId(9L, "al:Some%20Album%2FKey"), parseSongId("sg:9@al:Some%20Album%2FKey"))
        // Only the first '@' splits song from parent; later ones belong to the parent key.
        assertEquals(ParsedSongId(3L, "ar:a@b"), parseSongId("sg:3@ar:a@b"))
    }

    @Test
    fun rejectsMalformedIds() {
        listOf("", "42", "pl:7", "sg:", "sg:42", "sg:42@", "sg:@pl:7", "sg:abc@pl:7").forEach { id ->
            assertNull("expected null for '$id'", parseSongId(id))
        }
    }
}
