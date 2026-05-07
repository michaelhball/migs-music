package com.migsmusic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.migsmusic.data.OrphanAudioTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrphanAudioTrackerTest {
    private lateinit var context: Context
    private lateinit var tracker: OrphanAudioTracker

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe the prefs file before each test so add() / clear() / count() see a known state.
        context.getSharedPreferences("migs-music-orphan-audio", Context.MODE_PRIVATE)
            .edit().clear().commit()
        tracker = OrphanAudioTracker(context)
    }

    @After
    fun tearDown() {
        tracker.clear()
    }

    @Test
    fun newTrackerStartsEmpty() {
        assertEquals(0, tracker.count.value)
        assertTrue(tracker.all().isEmpty())
    }

    @Test
    fun addPopulatesCountAndAll() {
        tracker.add(listOf("content://media/external/audio/media/1", "content://media/external/audio/media/2"))
        assertEquals(2, tracker.count.value)
        val uris = tracker.all().map { it.toString() }.toSet()
        assertEquals(setOf("content://media/external/audio/media/1", "content://media/external/audio/media/2"), uris)
    }

    @Test
    fun addIsIdempotent() {
        // Backed by a Set — adding the same URI twice should not double-count.
        tracker.add(listOf("content://media/external/audio/media/1"))
        tracker.add(listOf("content://media/external/audio/media/1"))
        assertEquals(1, tracker.count.value)
    }

    @Test
    fun addAccumulatesAcrossCalls() {
        tracker.add(listOf("content://media/external/audio/media/1"))
        tracker.add(listOf("content://media/external/audio/media/2"))
        assertEquals(2, tracker.count.value)
    }

    @Test
    fun clearEmptiesEverything() {
        tracker.add(listOf("content://media/external/audio/media/1", "content://media/external/audio/media/2"))
        tracker.clear()
        assertEquals(0, tracker.count.value)
        assertTrue(tracker.all().isEmpty())
    }

    @Test
    fun countFlowReflectsLatestState() {
        // The count StateFlow drives the Settings UI badge — make sure it actually
        // emits when add/clear are called, not just when something else triggers a read.
        assertEquals(0, tracker.count.value)
        tracker.add(listOf("content://x"))
        assertEquals(1, tracker.count.value)
        tracker.add(listOf("content://y", "content://z"))
        assertEquals(3, tracker.count.value)
        tracker.clear()
        assertEquals(0, tracker.count.value)
    }

    @Test
    fun statePersistsAcrossInstances() {
        // SharedPreferences-backed: a fresh tracker instance should observe what a previous
        // instance wrote. Ensures the Settings screen sees orphans captured by the background
        // AutoImportReceiver, even though they're constructed at different times.
        tracker.add(listOf("content://media/external/audio/media/42"))
        val newTracker = OrphanAudioTracker(context)
        assertEquals(1, newTracker.count.value)
        assertEquals(
            "content://media/external/audio/media/42",
            newTracker.all().single().toString(),
        )
    }

    @Test
    fun emptyAddIsNoOp() {
        tracker.add(emptyList())
        assertEquals(0, tracker.count.value)
    }
}
