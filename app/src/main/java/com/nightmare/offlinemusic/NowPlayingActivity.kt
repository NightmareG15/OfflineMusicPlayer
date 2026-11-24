package com.nightmare.offlinemusic

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tela "Tocando agora": nome, artista, capa, progresso, controles e timer.
 * A capa (em baixa qualidade) só fica na memória enquanto esta tela está aberta e visível.
 */
class NowPlayingActivity : Activity() {
    private lateinit var p: Ui.Palette
    private lateinit var panel: PlayerPanel
    private lateinit var coverView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var timerButton: ImageButton
    private var coverSizePx = 0

    private var musicService: MusicService? = null
    private var bound = false
    private var visible = false
    private var coverId = -2L      // música cuja capa foi pedida (-2 = nenhuma ainda)
    private var coverToken = 0     // descarta respostas antigas quando a música muda rápido
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (musicService?.isPlayingNow() == true) panel.updateProgress()
            SleepTimerDialog.refreshIcon(timerButton, p, musicService)
            handler.postDelayed(this, 1000)
        }
    }

    private val stateListener: (String?, Boolean) -> Unit = { title, playing ->
        runOnUiThread { render(title, playing) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as MusicService.LocalBinder).getService()
            musicService = service
            bound = true
            service.onStateChanged = stateListener
            render(service.currentTitle(), service.isPlayingNow())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            bound = false
        }
    }

    private fun dp(value: Int): Int = Ui.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Ui.palette(this)
        val baseLeft = dp(20)
        val baseTop = dp(12)
        val baseRight = dp(20)
        val baseBottom = dp(16)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.setPadding(baseLeft, baseTop, baseRight, baseBottom)
        Ui.padForSystemBars(root, root, baseLeft, baseTop, baseRight, baseBottom)

        // Topo: voltar, título da tela e timer
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.iconButton(this, R.drawable.ic_back, p.text, "Voltar")
        back.setOnClickListener { finish() }
        val heading = TextView(this)
        heading.text = "Tocando agora"
        heading.textSize = 16f
        heading.setTextColor(p.text)
        heading.setTypeface(null, Typeface.BOLD)
        heading.gravity = Gravity.CENTER
        timerButton = Ui.iconButton(this, R.drawable.ic_timer, p.text, "Timer para dormir")
        timerButton.setOnClickListener {
            SleepTimerDialog.show(this, p, musicService) { SleepTimerDialog.refreshIcon(timerButton, p, musicService) }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(timerButton, LinearLayout.LayoutParams(dp(44), dp(44)))

        // Capa (cantos arredondados; sem capa, mostra uma nota musical)
        coverSizePx = minOf(resources.displayMetrics.widthPixels - dp(80), dp(300))
        coverView = ImageView(this)
        val coverShape = GradientDrawable()
        coverShape.cornerRadius = dp(16).toFloat()
        coverShape.setColor(p.surface)
        coverView.background = coverShape
        coverView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
        coverView.clipToOutline = true
        setCover(null)
        val coverHolder = FrameLayout(this)
        coverHolder.addView(coverView, FrameLayout.LayoutParams(coverSizePx, coverSizePx, Gravity.CENTER))

        titleView = TextView(this)
        titleView.textSize = 22f
        titleView.setTextColor(p.text)
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.maxLines = 2
        titleView.ellipsize = TextUtils.TruncateAt.END
        artistView = TextView(this)
        artistView.textSize = 15f
        artistView.setTextColor(p.sub)
        artistView.maxLines = 1
        artistView.ellipsize = TextUtils.TruncateAt.END

        panel = PlayerPanel(this, p, { musicService }, { musicService?.togglePlayback() })

        root.addView(top)
        root.addView(coverHolder, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(artistView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(12) })
        root.addView(panel.seekRow)
        root.addView(panel.controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        setContentView(root)
        Ui.applySystemBars(this, p)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        panel.refreshModeButtons()
        Intent(this, MusicService::class.java).also { bindService(it, connection, Context.BIND_AUTO_CREATE) }
        handler.post(ticker)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(ticker)
        // A capa sai da memória assim que a tela deixa de estar visível.
        coverToken++
        coverId = -2L
        setCover(null)
        if (bound) {
            musicService?.clearListener(stateListener)
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun render(title: String?, playing: Boolean) {
        if (title == null) {
            finish()   // a reprodução terminou (por exemplo, pelo botão Fechar da notificação)
            return
        }
        val service = musicService
        titleView.text = title
        val artist = service?.currentArtist()
        artistView.text = if (artist.isNullOrBlank() || artist == "<unknown>") "Artista desconhecido" else artist
        panel.render(playing)
        SleepTimerDialog.refreshIcon(timerButton, p, service)
        val id = service?.currentId() ?: -1L
        if (id != coverId) {
            coverId = id
            loadCover(id)
        }
    }

    // ------------------------------------------------------------------ capa

    private fun setCover(bitmap: Bitmap?) {
        if (bitmap != null) {
            coverView.scaleType = ImageView.ScaleType.CENTER_CROP
            coverView.setPadding(0, 0, 0, 0)
            coverView.clearColorFilter()
            coverView.setImageBitmap(bitmap)
        } else {
            val pad = coverSizePx / 4
            coverView.scaleType = ImageView.ScaleType.FIT_CENTER
            coverView.setPadding(pad, pad, pad, pad)
            coverView.setImageResource(R.drawable.ic_notification)
            coverView.setColorFilter(p.sub)
        }
    }

    private fun loadCover(id: Long) {
        val token = ++coverToken
        setCover(null)
        if (id < 0) return
        Thread {
            val bitmap = decodeCover(id)
            runOnUiThread {
                if (bitmap != null) {
                    if (token == coverToken && visible && !isDestroyed) setCover(bitmap) else bitmap.recycle()
                }
            }
        }.start()
    }

    /** Procura a capa gravada dentro do arquivo da música e a abre em tamanho pequeno (baixa qualidade). */
    private fun decodeCover(id: Long): Bitmap? {
        val uri = Media.uriFor(id)
        var bytes: ByteArray? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            bytes = retriever.embeddedPicture
        } catch (e: Exception) {
            bytes = null
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }
        if (bytes != null) {
            try {
                return decodeSmall(bytes)
            } catch (e: OutOfMemoryError) {
                return null
            }
        }
        // Android 10+: tenta também a miniatura que o próprio sistema guarda para a música.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return contentResolver.loadThumbnail(uri, Size(COVER_PX, COVER_PX), null)
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    /** Abre a imagem reduzida: o lado menor fica entre 256 e 511 pixels, em cores de 16 bits (metade da memória). */
    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_PX && bounds.outHeight / (sample * 2) >= COVER_PX) sample *= 2
        val options = BitmapFactory.Options()
        options.inSampleSize = sample
        options.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        private const val COVER_PX = 256
    }
}
