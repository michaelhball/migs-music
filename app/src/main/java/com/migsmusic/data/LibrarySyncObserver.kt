package com.migsmusic.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.migsmusic.data.repository.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches MediaStore for audio file additions/removals and re-indexes the local library
 * when changes settle. Means the user never has to tap a Rescan button — drop music onto
 * the phone (via OpenMTP / adb push / anywhere else) and it shows up.
 *
 * Lives in [com.migsmusic.MigsMusicApplication]; one instance per process.
 */
class LibrarySyncObserver(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null

    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scheduleScan()
            }

            override fun onChange(
                selfChange: Boolean,
                uri: Uri?,
            ) {
                scheduleScan()
            }
        }

    fun start() {
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                // notifyForDescendants =
                true,
                observer,
            )
        }.onFailure { Log.w(TAG, "Failed to register MediaStore observer: ${it.message}") }
    }

    fun stop() {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        debounceJob?.cancel()
    }

    /**
     * Coalesces bursty MediaStore notifications (a single OpenMTP transfer can fire dozens
     * back-to-back) into a single scan. Re-arms on every event; runs the scan once activity
     * has been quiet for [DEBOUNCE_MS].
     */
    private fun scheduleScan() {
        debounceJob?.cancel()
        debounceJob =
            scope.launch {
                delay(DEBOUNCE_MS)
                runCatching { libraryRepository.scanDevice() }
                    .onFailure { Log.w(TAG, "Auto-rescan failed: ${it.message}") }
            }
    }

    private companion object {
        const val TAG = "LibrarySyncObserver"
        const val DEBOUNCE_MS = 2_000L
    }
}
