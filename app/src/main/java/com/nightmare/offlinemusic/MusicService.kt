package com.nightmare.offlinemusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class MusicService : Service() {
    inner class LocalBinder : Binder() { fun getService(): MusicService = this@MusicService }

    private val binder = LocalBinder()
    private var player: MediaPlayer? = null
    private var prepared = false

    // Fila de músicas (na ordem em que aparecem na tela)
    private var uris: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var artists: List<String> = emptyList()

    // Ordem de reprodução (muda quando o modo aleatório está ligado) e posição nela
    private var order = ArrayList<Int>()
    private var pos = 0
    private val index: Int get() = order.getOrElse(pos) { 0 }

    private var shuffle = false
    private var repeatMode = REPEAT_OFF

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var session: MediaSession? = null
    private var noisyRegistered = false

    // OBS: o nome NÃO pode ser "isPlaying". Dentro de blocos do MediaPlayer isso
    // aponta para MediaPlayer.isPlaying (somente leitura) e quebra a compilação.
    private var playing = false
    private var pausedByFocusLoss = false
    private var errorStreak = 0

    /** A tela se registra aqui para ser avisada quando a música ou o estado mudam. */
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

    // Fone desconectado (cabo ou Bluetooth): pausa para o som não sair no alto-falante.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        shuffle = Prefs.shuffle(this)
        repeatMode = Prefs.repeatMode(this)
        createNotificationChannel()
    }

    /** Registra o aviso de "fone desconectado" (só enquanto há reprodução). */
    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        try { unregisterReceiver(noisyReceiver) } catch (e: IllegalArgumentException) { }
        noisyRegistered = false
    }

    private fun releaseSession() {
        session?.isActive = false
        session?.release()
        session = null
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayback()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    // ---- Funções usadas pela tela, pela notificação e pelos botões do fone ----

    /** Define a fila e começa a tocar a música [startIndex]. A fila vem direto da tela (sem limite de tamanho). */
    fun setQueue(newUris: List<String>, newTitles: List<String>, newArtists: List<String>, startIndex: Int) {
        if (newUris.isEmpty()) return
        uris = newUris          // a tela entrega listas novas e não as altera depois
        titles = newTitles
        artists = newArtists
        errorStreak = 0
        buildOrder(startIndex.coerceIn(0, uris.size - 1))
        // Garante que o serviço continue vivo (em primeiro plano) mesmo com a tela fechada.
        val keepAlive = Intent(this, MusicService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(keepAlive) else startService(keepAlive)
        playCurrent()
    }

    fun currentTitle(): String? = if (player != null) titles.getOrNull(index) else null

    fun currentUri(): String? = if (player != null) uris.getOrNull(index) else null

    fun isPlayingNow(): Boolean = playing

    fun isShuffleOn(): Boolean = shuffle

    fun getRepeatMode(): Int = repeatMode

    fun positionMs(): Int {
        val current = player ?: return 0
        if (!prepared) return 0
        return try { current.currentPosition } catch (e: IllegalStateException) { 0 }
    }

    fun durationMs(): Int {
        val current = player ?: return 0
        if (!prepared) return 0
        return try { current.duration } catch (e: IllegalStateException) { 0 }
    }

    fun seekTo(ms: Int) {
        val current = player ?: return
        if (!prepared) return
        try { current.seekTo(ms.coerceAtLeast(0)) } catch (e: IllegalStateException) { return }
        updateSession()
    }

    fun togglePlayback() {
        if (playing) pause() else resumeIfPossible(requestFocus = true)
    }

    fun pause() {
        val current = player ?: return
        if (!playing) return
        try { current.pause() } catch (e: IllegalStateException) { return }
        playing = false
        pausedByFocusLoss = false
        updateNotification()
    }

    fun next() = advance(auto = false)

    fun previous() {
        if (uris.isEmpty()) return
        // Padrão dos players: se já passou de 3 segundos, "anterior" volta ao início da música.
        if (positionMs() > 3000) { seekTo(0); return }
        pos = if (pos <= 0) order.lastIndex else pos - 1
        playCurrent()
    }

    fun setShuffle(on: Boolean) {
        shuffle = on
        Prefs.setShuffle(this, on)
        if (uris.isNotEmpty()) buildOrder(index)
    }

    fun setRepeat(mode: Int) {
        repeatMode = mode
        Prefs.setRepeatMode(this, mode)
        try { player?.isLooping = (mode == REPEAT_ONE) } catch (e: IllegalStateException) { }
    }

    // ---- Lógica interna ----

    /** Monta a ordem de reprodução. Com aleatório ligado, a música [start] vem primeiro. */
    private fun buildOrder(start: Int) {
        val rest = ArrayList<Int>()
        for (i in uris.indices) if (i != start) rest.add(i)
        if (shuffle) {
            rest.shuffle()
            order = ArrayList()
            order.add(start)
            order.addAll(rest)
            pos = 0
        } else {
            order = ArrayList()
            for (i in uris.indices) order.add(i)
            pos = start
        }
    }

    private fun advance(auto: Boolean) {
        if (uris.isEmpty()) return
        if (pos + 1 < order.size) {
            pos++
        } else {
            // Chegou ao fim da lista: com "repetir" desligado, a reprodução termina sozinha.
            if (auto && repeatMode == REPEAT_OFF) { stopPlayback(); return }
            if (shuffle) {
                val last = index
                val again = ArrayList<Int>()
                for (i in uris.indices) again.add(i)
                again.shuffle()
                if (again.size > 1 && again[0] == last) {
                    val tmp = again[0]
                    again[0] = again[again.size - 1]
                    again[again.size - 1] = tmp
                }
                order = again
            }
            pos = 0
        }
        playCurrent()
    }

    private fun playCurrent() {
        if (uris.isEmpty()) { stopPlayback(); return }
        playing = false
        prepared = false
        pausedByFocusLoss = false
        if (session == null) createMediaSession()
        registerNoisyReceiver()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!requestAudioFocus()) { stopPlayback(); return }
        releasePlayer()

        val mp = MediaPlayer()
        try {
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setAudioAttributes(audioAttrs)
            mp.setDataSource(this, Uri.parse(uris[index]))
            mp.isLooping = (repeatMode == REPEAT_ONE)
            mp.setOnPreparedListener { ready ->
                prepared = true
                errorStreak = 0
                ready.start()
                playing = true
                updateMetadata()
                updateNotification()
            }
            mp.setOnCompletionListener { advance(auto = true) }
            mp.setOnErrorListener { _, _, _ ->
                // Arquivo com problema: pula para o próximo (e para se todos falharem).
                errorStreak++
                if (errorStreak >= uris.size) stopPlayback() else advance(auto = false)
                true
            }
            player = mp
            mp.prepareAsync()
            updateMetadata()
            updateNotification()
        } catch (e: Exception) {
            mp.release()
            player = null
            errorStreak++
            if (errorStreak >= uris.size) stopPlayback() else advance(auto = false)
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        prepared = false
    }

    private fun pauseForFocus(resumeLater: Boolean) {
        val current = player ?: return
        if (!playing) return
        try { current.pause() } catch (e: IllegalStateException) { return }
        playing = false
        pausedByFocusLoss = resumeLater
        updateNotification()
    }

    private fun resumeIfPossible(requestFocus: Boolean = false) {
        val current = player ?: return
        if (!prepared) return
        if (requestFocus && !requestAudioFocus()) return
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
        unregisterNoisyReceiver()
        releaseSession()
        stopForeground(true)
        stopSelf()
        onStateChanged?.invoke(null, false)
    }

    // ---- Sessão de mídia: é ela que recebe os botões do fone (play/pause, próxima, anterior) ----

    @Suppress("DEPRECATION")
    private fun createMediaSession() {
        val s = MediaSession(this, "OfflineMusic")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { if (!playing) togglePlayback() }
            override fun onPause() { pause() }
            override fun onSkipToNext() { next() }
            override fun onSkipToPrevious() { previous() }
            override fun onSeekTo(position: Long) { seekTo(position.toInt()) }
            override fun onStop() { stopPlayback() }
        })
        s.isActive = true
        session = s
    }

    private fun updateSession() {
        val s = session ?: return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, positionMs().toLong(), if (playing) 1f else 0f)
                .build()
        )
    }

    private fun updateMetadata() {
        val s = session ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titles.getOrNull(index) ?: "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artists.getOrNull(index) ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs().toLong())
                .build()
        )
    }

    // ---- Notificação ----

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
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        session?.let { style.setMediaSession(it.sessionToken) }
        return builder
            .setContentTitle(titles.getOrNull(index) ?: "Offline Music")
            .setContentText(artists.getOrNull(index)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Reprodução local")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setStyle(style)
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
        updateSession()
        onStateChanged?.invoke(titles.getOrNull(index), playing)
    }

    override fun onDestroy() {
        unregisterNoisyReceiver()
        releasePlayer()
        abandonAudioFocus()
        releaseSession()
        onStateChanged = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_TOGGLE = "com.nightmare.offlinemusic.TOGGLE"
        const val ACTION_NEXT = "com.nightmare.offlinemusic.NEXT"
        const val ACTION_PREVIOUS = "com.nightmare.offlinemusic.PREVIOUS"
        const val ACTION_STOP = "com.nightmare.offlinemusic.STOP"
        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2
        private const val CHANNEL_ID = "offline_music_playback"
        private const val NOTIFICATION_ID = 41
    }
}
