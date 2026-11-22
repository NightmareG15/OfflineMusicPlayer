package com.nightmare.offlinemusic

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Tela de configurações simples: trocar o tema (Claro/Escuro) e abrir as configurações de som do Android. */
class SettingsActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Ui.palette(this)
        val baseLeft = Ui.dp(this, 16)
        val baseTop = Ui.dp(this, 12)
        val baseRight = Ui.dp(this, 16)
        val baseBottom = Ui.dp(this, 12)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.setPadding(baseLeft, baseTop, baseRight, baseBottom)
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                baseLeft + insets.systemWindowInsetLeft,
                baseTop + insets.systemWindowInsetTop,
                baseRight + insets.systemWindowInsetRight,
                baseBottom + insets.systemWindowInsetBottom
            )
            insets
        }

        // Topo: seta de voltar + título
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.iconButton(this, R.drawable.ic_back, p.text, "Voltar")
        back.setOnClickListener { finish() }
        val title = TextView(this)
        title.text = "Configurações"
        title.textSize = 22f
        title.setTextColor(p.text)
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        header.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = Ui.dp(this@SettingsActivity, 8) })
        root.addView(header)

        // Tema: Escuro (ligado) / Claro (desligado)
        val themeRow = LinearLayout(this)
        themeRow.orientation = LinearLayout.HORIZONTAL
        themeRow.gravity = Gravity.CENTER_VERTICAL
        val themeLabel = TextView(this)
        themeLabel.text = "Tema escuro"
        themeLabel.textSize = 16f
        themeLabel.setTextColor(p.text)
        val themeSwitch = Switch(this)
        themeSwitch.isChecked = p.dark
        themeSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setDark(this, checked)
            recreate()
        }
        themeRow.addView(themeLabel, LinearLayout.LayoutParams(0, -2, 1f))
        themeRow.addView(themeSwitch, LinearLayout.LayoutParams(-2, -2))
        root.addView(themeRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = Ui.dp(this@SettingsActivity, 24)
            bottomMargin = Ui.dp(this@SettingsActivity, 8)
        })

        // Atalho para as configurações de som do Android
        val soundButton = Ui.textButton(this, "Abrir configurações de som do Android", p)
        soundButton.setOnClickListener { openSoundSettings() }
        root.addView(soundButton, LinearLayout.LayoutParams(-1, Ui.dp(this, 52)).apply {
            topMargin = Ui.dp(this@SettingsActivity, 16)
        })

        setContentView(root)
        Ui.applySystemBars(this, p)
    }

    private fun openSoundSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "Não foi possível abrir as configurações.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
