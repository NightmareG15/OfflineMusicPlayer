package com.nightmare.offlinemusic

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Barra de progresso + botões de controle (aleatório, anterior, tocar/pausar, próxima, repetir).
 * É usada pela tela principal e pela tela "Tocando agora", então o código existe uma vez só.
 */
class PlayerPanel(
    private val activity: Activity,
    private val p: Ui.Palette,
    private val service: () -> MusicService?,
    onPlayPressed: () -> Unit
) {
    val seekRow = LinearLayout(activity)
    val controls = LinearLayout(activity)

    private val timeCurrent = TextView(activity)
    private val timeTotal = TextView(activity)
    private val seek = SeekBar(activity)
    private val playButton: ImageButton
    private val shuffleButton: ImageButton
    private val repeatButton: ImageButton

    private var seeking = false
    private var lastPosSec = -1
    private var lastDurSec = -1

    init {
        fun dp(value: Int) = Ui.dp(activity, value)

        // ---- Barra de progresso
        seekRow.orientation = LinearLayout.HORIZONTAL
        seekRow.gravity = Gravity.CENTER_VERTICAL
        for (label in arrayOf(timeCurrent, timeTotal)) {
            label.text = "0:00"
            label.textSize = 12f
            label.setTextColor(p.sub)
        }
        seek.progressTintList = ColorStateList.valueOf(p.highlight)
        seek.thumbTintList = ColorStateList.valueOf(p.highlight)
        seek.progressBackgroundTintList = ColorStateList.valueOf(p.sub)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCurrent.text = Ui.formatTime(progress / 1000)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                service()?.seekTo(bar?.progress ?: 0)
                seeking = false
                lastPosSec = -1
            }
        })
        seekRow.addView(timeCurrent, LinearLayout.LayoutParams(-2, -2))
        seekRow.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(timeTotal, LinearLayout.LayoutParams(-2, -2))

        // ---- Controles
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER

        shuffleButton = Ui.iconButton(activity, R.drawable.ic_shuffle, p.sub, "Aleatório")
        shuffleButton.setOnClickListener { toggleShuffle() }
        val previousButton = Ui.iconButton(activity, R.drawable.ic_previous, p.text, "Anterior")
        previousButton.setOnClickListener { service()?.previous() }
        playButton = Ui.iconButton(activity, R.drawable.ic_play, Color.WHITE, "Tocar ou pausar")
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(p.accent)
        playButton.background = circle
        playButton.setPadding(dp(16), dp(16), dp(16), dp(16))
        playButton.setOnClickListener { onPlayPressed() }
        val nextButton = Ui.iconButton(activity, R.drawable.ic_next, p.text, "Próxima")
        nextButton.setOnClickListener { service()?.next() }
        repeatButton = Ui.iconButton(activity, R.drawable.ic_repeat, p.sub, "Repetir")
        repeatButton.setOnClickListener { cycleRepeat() }

        fun slot(size: Int) = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(4), 0, dp(4), 0) }
        controls.addView(shuffleButton, slot(48))
        controls.addView(previousButton, slot(56))
        controls.addView(playButton, slot(68))
        controls.addView(nextButton, slot(56))
        controls.addView(repeatButton, slot(48))

        refreshModeButtons()
    }

    /** Atualiza o botão tocar/pausar e a barra de progresso. */
    fun render(playing: Boolean) {
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        lastPosSec = -1
        lastDurSec = -1
        updateProgress()
    }

    fun updateProgress() {
        val s = service() ?: return
        if (seeking) return
        val duration = s.durationMs()
        val position = s.positionMs()
        val max = if (duration > 0) duration else 1
        if (seek.max != max) seek.max = max
        seek.progress = if (duration > 0) position else 0
        // Os textos só são refeitos quando o segundo muda (evita criar texto à toa).
        val posSec = position / 1000
        if (posSec != lastPosSec) { lastPosSec = posSec; timeCurrent.text = Ui.formatTime(posSec) }
        val durSec = duration / 1000
        if (durSec != lastDurSec) { lastDurSec = durSec; timeTotal.text = Ui.formatTime(durSec) }
    }

    fun refreshModeButtons() {
        shuffleButton.setColorFilter(if (Prefs.shuffle(activity)) p.highlight else p.sub)
        val mode = Prefs.repeatMode(activity)
        repeatButton.setImageResource(if (mode == MusicService.REPEAT_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        repeatButton.setColorFilter(if (mode == MusicService.REPEAT_OFF) p.sub else p.highlight)
    }

    private fun toggleShuffle() {
        val on = !Prefs.shuffle(activity)
        val s = service()
        if (s != null) s.setShuffle(on) else Prefs.setShuffle(activity, on)
        refreshModeButtons()
        Ui.toast(activity, if (on) "Aleatório: ligado" else "Aleatório: desligado")
    }

    private fun cycleRepeat() {
        val next = (Prefs.repeatMode(activity) + 1) % 3
        val s = service()
        if (s != null) s.setRepeat(next) else Prefs.setRepeatMode(activity, next)
        refreshModeButtons()
        Ui.toast(
            activity,
            when (next) {
                MusicService.REPEAT_ALL -> "Repetir: lista inteira"
                MusicService.REPEAT_ONE -> "Repetir: esta faixa"
                else -> "Repetir: desligado"
            }
        )
    }
}
