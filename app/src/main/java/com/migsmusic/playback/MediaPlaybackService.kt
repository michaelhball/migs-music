package com.migsmusic.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.migsmusic.MainActivity
import com.migsmusic.MigsMusicApplication
import com.migsmusic.R

/**
 * Hosts the Media3 session for system-level integration (lockscreen, notification, BT).
 *
 * Eagerly calls startForeground in onStartCommand to satisfy Android's 5s FGS contract.
 * Media3's DefaultMediaNotificationProvider replaces this notification once playback
 * begins; we use the same notification ID so the swap is in-place.
 */
class MediaPlaybackService : MediaSessionService() {
    private val playbackManager: PlaybackManager
        get() = (application as MigsMusicApplication).appContainer.playbackManager

    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        mediaSession = playbackManager.getOrCreateMediaSession(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The OS gives us 5 seconds after startForegroundService to call startForeground.
        // Media3 will only call it once isPlaying flips true, so do it pre-emptively here
        // with a placeholder. Media3 will replace this notification when the session is active.
        promoteToForeground()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away from Recents. Stop playback so audio doesn't keep
        // running in the background, then tear down the foreground service.
        playbackManager.stopForTaskRemoval()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        playbackManager.clearMediaSession()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notification = buildBootstrapNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                BOOTSTRAP_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(BOOTSTRAP_NOTIF_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Now-playing controls"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildBootstrapNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Preparing playback…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "migs_music_playback"
        // Match Media3 DefaultMediaNotificationProvider.NOTIFICATION_ID so its notification
        // replaces our bootstrap one in place rather than stacking.
        private const val BOOTSTRAP_NOTIF_ID = 1001
    }
}
