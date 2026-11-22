package com.nightmare.offlinemusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder

class MusicService : Service() {
    inner class LocalBinder : Binder() { fun getService(): MusicService = this@MusicService }
    private val binder = LocalBinder()
    private var player: MediaPlayer? = null
    private var uris = arrayListOf<String>()
    private var titles = arrayListOf<String>()
    private var artists = arrayListOf<String>()
    private var index = 0
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var isPlaying = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_QUEUE -> {
                uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
                titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
                artists = intent.getStringArrayListExtra(EXTRA_ARTISTS) ?: arrayListOf()
                index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (uris.size - 1).coerceAtLeast(0))
                playCurrent()
            }
            ACTION_TOGGLE -> togglePlayback()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    fun togglePlayback() {
        val current = player
        if (current == null) return
        if (current.isPlaying) {
            current.pause()
            isPlaying = false
            updateNotification()
        } else {
            if (requestAudioFocus()) {
                current.start()
                isPlaying = true
                updateNotification()
            }
        }
    }

    fun next() {
        if (uris.isEmpty()) return
        index = (index + 1) % uris.size
        playCurrent()
    }

    fun previous() {
        if (uris.isEmpty()) return
        index = if (index <= 0) uris.lastIndex else index - 1
        playCurrent()
    }

    private fun playCurrent() {
        if (uris.isEmpty()) { stopPlayback(); return }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!requestAudioFocus()) { stopPlayback(); return }
        player?.release()
        player = null
        try {
            isPlaying = false
            updateNotification()
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@MusicService, Uri.parse(uris[index]))
                setOnPreparedListener { mediaPlayer -> mediaPlayer.start(); isPlaying = true; updateNotification() }
                setOnCompletionListener { next() }
                setOnErrorListener { _, _, _ -> isPlaying = false; updateNotification(); true }
                prepareAsync()
            }
        } catch (_: Exception) {
            isPlaying = false
            stopForeground(true)
            stopSelf()
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener { change ->
                    if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) player?.pause()
                }.build()
            focusRequest = request
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        isPlaying = false
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Reprodução de música", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val previous = servicePendingIntent(ACTION_PREVIOUS, 1)
        val toggle = servicePendingIntent(ACTION_TOGGLE, 2)
        val next = servicePendingIntent(ACTION_NEXT, 3)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle(titles.getOrNull(index) ?: "Offline Music")
            .setContentText(artists.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "Reprodução local")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", previous)
            .addAction(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying) "Pausar" else "Reproduzir", toggle)
            .addAction(android.R.drawable.ic_media_next, "Próxima", next)
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_SET_QUEUE = "com.nightmare.offlinemusic.SET_QUEUE"
        const val ACTION_TOGGLE = "com.nightmare.offlinemusic.TOGGLE"
        const val ACTION_NEXT = "com.nightmare.offlinemusic.NEXT"
        const val ACTION_PREVIOUS = "com.nightmare.offlinemusic.PREVIOUS"
        const val ACTION_STOP = "com.nightmare.offlinemusic.STOP"
        const val EXTRA_URIS = "uris"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_ARTISTS = "artists"
        const val EXTRA_INDEX = "index"
        private const val CHANNEL_ID = "offline_music_playback"
        private const val NOTIFICATION_ID = 41
    }
}
