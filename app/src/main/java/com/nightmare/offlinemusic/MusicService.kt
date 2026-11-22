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
import android.os.PowerManager

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

    // OBS: o nome NÃO pode ser "isPlaying". Dentro de blocos do MediaPlayer isso
    // aponta para MediaPlayer.isPlaying (somente leitura) e quebra a compilação.
    private var playing = false
    private var pausedByFocusLoss = false
    private var errorStreak = 0

    /** A tela (MainActivity) se registra aqui para ser avisada de mudanças. */
    var onStateChanged: ((title: String?, playing: Boolean) -> Unit)? = null

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pauseForFocus(resumeLater = false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pauseForFocus(resumeLater = true)
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocusLoss) {
                pausedByFocusLoss = false
                resumeIfPossible()
            }
        }
    }

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
                errorStreak = 0
                playCurrent()
            }
            ACTION_TOGGLE -> togglePlayback()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    // ---- Funções usadas pela tela e pela notificação ----

    fun currentTitle(): String? = titles.getOrNull(index)?.takeIf { player != null }

    fun isPlayingNow(): Boolean = playing

    fun togglePlayback() {
        val current = player ?: return
        try {
            if (playing) {
                current.pause()
                playing = false
                pausedByFocusLoss = false
            } else if (requestAudioFocus()) {
                current.start()
                playing = true
            }
        } catch (e: IllegalStateException) {
            return
        }
        updateNotification()
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

    // ---- Lógica interna ----

    private fun playCurrent() {
        if (uris.isEmpty()) { stopPlayback(); return }
        playing = false
        pausedByFocusLoss = false
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!requestAudioFocus()) { stopPlayback(); return }
        releasePlayer()

        val mp = MediaPlayer()
        try {
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setAudioAttributes(audioAttrs)
            mp.setDataSource(this, Uri.parse(uris[index]))
            mp.setOnPreparedListener { prepared ->
                errorStreak = 0
                prepared.start()
                playing = true
                updateNotification()
            }
            mp.setOnCompletionListener { next() }
            mp.setOnErrorListener { _, _, _ ->
                // Arquivo com problema: pula para o próximo (e para se todos falharem).
                errorStreak++
                if (errorStreak >= uris.size) stopPlayback() else next()
                true
            }
            player = mp
            mp.prepareAsync()
            updateNotification()
        } catch (e: Exception) {
            mp.release()
            player = null
            errorStreak++
            if (errorStreak >= uris.size) stopPlayback() else next()
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    private fun pauseForFocus(resumeLater: Boolean) {
        val current = player ?: return
        if (!playing) return
        try { current.pause() } catch (e: IllegalStateException) { return }
        playing = false
        pausedByFocusLoss = resumeLater
        updateNotification()
    }

    private fun resumeIfPossible() {
        val current = player ?: return
        try { current.start() } catch (e: IllegalStateException) { return }
        playing = true
        updateNotification()
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= 26) {
            // O pedido de foco é criado uma única vez e reaproveitado.
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { focusRequest = it }
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            manager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            manager.abandonAudioFocus(focusListener)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopPlayback() {
        releasePlayer()
        playing = false
        pausedByFocusLoss = false
        abandonAudioFocus()
        stopForeground(true)
        stopSelf()
        onStateChanged?.invoke(null, false)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Reprodução de música", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val previous = servicePendingIntent(ACTION_PREVIOUS, 1)
        val toggle = servicePendingIntent(ACTION_TOGGLE, 2)
        val next = servicePendingIntent(ACTION_NEXT, 3)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle(titles.getOrNull(index) ?: "Offline Music")
            .setContentText(artists.getOrNull(index)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Reprodução local")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", previous)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pausar" else "Reproduzir",
                toggle
            )
            .addAction(android.R.drawable.ic_media_next, "Próxima", next)
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification())
        onStateChanged?.invoke(titles.getOrNull(index), playing)
    }

    override fun onDestroy() {
        releasePlayer()
        abandonAudioFocus()
        onStateChanged = null
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
