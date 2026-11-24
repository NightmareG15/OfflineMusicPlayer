package com.nightmare.offlinemusic

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Configurações: tema, pausa ao desconectar o fone, atalho de som, redefinir e informações do app. */
class SettingsActivity : Activity() {
    private lateinit var p: Ui.Palette

    private fun dp(value: Int): Int = Ui.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Ui.palette(this)
        val baseLeft = dp(16)
        val baseTop = dp(12)
        val baseRight = dp(16)
        val baseBottom = dp(12)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.setPadding(baseLeft, baseTop, baseRight, baseBottom)
        Ui.padForSystemBars(root, root, baseLeft, baseTop, baseRight, baseBottom)

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
        header.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(header)

        // O conteúdo rola, para caber em telas pequenas.
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(sectionTitle("Aparência"))
        body.addView(switchRow("Tema escuro", "Desligue para usar o tema claro", p.dark) { checked ->
            Prefs.setDark(this, checked)
            recreate()
        })

        body.addView(sectionTitle("Reprodução"))
        body.addView(
            switchRow(
                "Pausar ao desconectar o fone",
                "Evita que o som saia no alto-falante sem querer",
                Prefs.pauseOnUnplug(this)
            ) { checked -> Prefs.setPauseOnUnplug(this, checked) }
        )

        body.addView(sectionTitle("Som"))
        val soundButton = Ui.textButton(this, "Abrir configurações de som do Android", p)
        soundButton.setOnClickListener { openSoundSettings() }
        body.addView(soundButton, LinearLayout.LayoutParams(-1, dp(52)))

        body.addView(sectionTitle("Redefinir"))
        val resetButton = Ui.textButton(this, "Redefinir tudo para o padrão", p)
        resetButton.setOnClickListener { confirmReset() }
        body.addView(resetButton, LinearLayout.LayoutParams(-1, dp(52)))

        body.addView(sectionTitle("Sobre o app"))
        body.addView(aboutCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        setContentView(root)
        Ui.applySystemBars(this, p)
    }

    private fun sectionTitle(text: String): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = 13f
        view.setTextColor(p.highlight)
        view.setTypeface(null, android.graphics.Typeface.BOLD)
        view.setPadding(0, dp(24), 0, dp(8))
        return view
    }

    /** Linha com texto à esquerda e um botão liga/desliga à direita. */
    private fun switchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val labelView = TextView(this)
        labelView.text = label
        labelView.textSize = 16f
        labelView.setTextColor(p.text)
        val hintView = TextView(this)
        hintView.text = hint
        hintView.textSize = 12f
        hintView.setTextColor(p.sub)
        texts.addView(labelView)
        texts.addView(hintView)
        val toggle = Switch(this)
        // Ligado: trilho vinho (#5A0F1A) com botão branco. Desligado: tons neutros.
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        toggle.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, p.sub))
        toggle.trackTintList = ColorStateList(states, intArrayOf(p.accent, p.surface))
        toggle.isChecked = checked
        toggle.setOnCheckedChangeListener { _, isOn -> onChange(isOn) }   // definido depois do estado inicial
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(toggle, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun confirmReset() {
        AlertDialog.Builder(this, Ui.dialogTheme(p))
            .setTitle("Redefinir configurações?")
            .setMessage(
                "Volta o tema (escuro), a pausa ao desconectar o fone e a ordem da lista ao padrão.\n\n" +
                    "Suas playlists e os nomes personalizados das músicas NÃO são apagados."
            )
            .setPositiveButton("Redefinir") { _, _ ->
                Prefs.resetSettings(this)
                Ui.toast(this, "Configurações redefinidas.")
                recreate()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Informações do app: versão, commit do GitHub que gerou o APK, tipo (release ou debug) e créditos. */
    private fun aboutCard(): View {
        val type = if (BuildConfig.DEBUG) "Debug (versão de testes)" else "Release"
        val card = TextView(this)
        card.textSize = 14f
        card.setTextColor(p.text)
        card.setLineSpacing(0f, 1.2f)
        card.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(p.surface)
        }
        card.text = getString(R.string.app_name) + "\n" +
            "Versão: ${BuildConfig.VERSION_NAME}\n" +
            "Commit: ${BuildConfig.COMMIT_ID}\n" +
            "Tipo: $type\n\n" +
            CREDITS
        return card
    }

    private fun openSoundSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                Ui.toast(this, "Não foi possível abrir as configurações.", long = true)
            }
        }
    }

    companion object {
        // Texto dos créditos: edite aqui se quiser mudar.
        private const val CREDITS = "Criado por NightmareG15.\nCódigo feito com ajuda de IA (ChatGPT e Claude)."
    }
}
