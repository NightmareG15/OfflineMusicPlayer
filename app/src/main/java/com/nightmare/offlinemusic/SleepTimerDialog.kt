package com.nightmare.offlinemusic

import android.app.Activity
import android.app.AlertDialog
import android.widget.ImageButton

/** Janela do timer para dormir e o ícone que mostra se ele está ativo (usados pela tela principal e por "Tocando agora"). */
object SleepTimerDialog {
    private val OPTIONS = intArrayOf(15, 30, 45, 60, 90)

    fun show(activity: Activity, p: Ui.Palette, service: MusicService?, onChanged: () -> Unit) {
        if (service == null || service.currentTitle() == null) {
            Ui.toast(activity, "Toque uma música primeiro para usar o timer.")
            return
        }
        val remaining = service.sleepRemainingMs()
        val labels = ArrayList<String>()
        if (remaining > 0) labels.add("Desativar timer")
        for (minutes in OPTIONS) labels.add(label(minutes))
        val title = if (remaining > 0) "Timer para dormir (faltam ${(remaining + 59_999) / 60_000} min)" else "Timer para dormir"
        AlertDialog.Builder(activity, Ui.dialogTheme(p))
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which ->
                val offset = if (remaining > 0) 1 else 0
                if (remaining > 0 && which == 0) {
                    service.cancelSleepTimer()
                    Ui.toast(activity, "Timer desativado.")
                } else {
                    val minutes = OPTIONS[which - offset]
                    service.startSleepTimer(minutes)
                    Ui.toast(activity, "A música vai parar em ${label(minutes)}.")
                }
                onChanged()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Deixa o ícone com a cor de destaque enquanto o timer está ativo. */
    fun refreshIcon(button: ImageButton, p: Ui.Palette, service: MusicService?) {
        val active = (service?.sleepRemainingMs() ?: 0L) > 0L
        if (button.tag == active) return
        button.tag = active
        button.setColorFilter(if (active) p.highlight else p.text)
    }

    private fun label(minutes: Int): String = when (minutes) {
        60 -> "1 hora"
        90 -> "1 hora e 30 min"
        else -> "$minutes minutos"
    }
}
