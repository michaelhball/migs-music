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
                    }

                MigsMusicApp(
                    appContainer = appContainer,
                    hasLibraryPermission = hasPermission,
                    onRequestPermission = {
                        permissionLauncher.launch(requiredMusicPermission())
                    },
                )
            }
        }
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
