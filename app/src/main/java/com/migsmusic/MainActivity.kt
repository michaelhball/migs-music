package com.migsmusic

import android.Manifest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.migsmusic.ui.MigsMusicApp
import com.migsmusic.ui.theme.MigsMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Volume rocker controls music stream while the app is in foreground, even when
        // nothing is currently playing. Standard Android music-app behavior.
        volumeControlStream = AudioManager.STREAM_MUSIC

        val appContainer = (application as MigsMusicApplication).appContainer

        setContent {
            MigsMusicTheme {
                var hasPermission by remember { mutableStateOf(checkMusicPermission()) }
                val permissionLauncher =
                    rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        hasPermission = granted
                        // Once the user has granted media access, also ask for notifications
                        // (Android 13+). Without it our playback notification posts silently
                        // and never reaches the lockscreen / shade — first-launch UX bug on
                        // every modern device. We chain rather than ask both up-front so the
                        // user only sees the second prompt after committing to using the app.
                        if (granted) maybeRequestPostNotifications()
                    }

                MigsMusicApp(
                    appContainer = appContainer,
                    hasLibraryPermission = hasPermission,
                    onRequestPermission = {
                        permissionLauncher.launch(requiredMusicPermission())
                    },
                )

                // Already-granted users on a fresh install: ask for POST_NOTIFICATIONS once
                // up-front. Idempotent (the system remembers the answer); no-op pre-T.
                LaunchedEffect(hasPermission) {
                    if (hasPermission) maybeRequestPostNotifications()
                }
            }
        }
    }

    private val postNotificationsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result is informational */ }

    private fun maybeRequestPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, perm) == PermissionChecker.PERMISSION_GRANTED) return
        postNotificationsLauncher.launch(perm)
    }

    private fun checkMusicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            requiredMusicPermission(),
        ) == PermissionChecker.PERMISSION_GRANTED

    private fun requiredMusicPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
}
