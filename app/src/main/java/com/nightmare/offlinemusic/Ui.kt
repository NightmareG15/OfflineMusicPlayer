package com.nightmare.offlinemusic

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageButton

/** Cores e pequenos "ajudantes" de tela, usados pela tela principal e pelas configurações. */
object Ui {
    /**
     * [accent] é a cor dos botões preenchidos (#5A0F1A). Sobre o fundo escuro ela é escura demais para texto
     * e ícones, então [highlight] é um tom mais claro do mesmo vermelho, usado para destaques nesses casos.
     */
    class Palette(
        val dark: Boolean,
        val bg: Int,
        val surface: Int,
        val text: Int,
        val sub: Int,
        val accent: Int,
        val highlight: Int
    )

    val DARK = Palette(
        dark = true,
        bg = 0xFF121016.toInt(),
        surface = 0xFF1F1B29.toInt(),
        text = 0xFFEDEAF5.toInt(),
        sub = 0xFF9D97AB.toInt(),
        accent = 0xFF5A0F1A.toInt(),
        highlight = 0xFFE0647B.toInt()
    )

    val LIGHT = Palette(
        dark = false,
        bg = 0xFFF7F6FA.toInt(),
        surface = 0xFFECE8F5.toInt(),
        text = 0xFF211D2B.toInt(),
        sub = 0xFF77717F.toInt(),
        accent = 0xFF5A0F1A.toInt(),
        highlight = 0xFF5A0F1A.toInt()
    )

    fun palette(ctx: Context): Palette = if (Prefs.isDark(ctx)) DARK else LIGHT

    fun dp(ctx: Context, value: Int): Int = (value * ctx.resources.displayMetrics.density).toInt()

    /** Tema das caixas de diálogo (combina com o tema escolhido). */
    fun dialogTheme(p: Palette): Int =
        if (p.dark) R.style.DialogDark else R.style.DialogLight

    /** Deixa a barra de status e a de navegação combinando com o tema. */
    @Suppress("DEPRECATION")
    fun applySystemBars(activity: Activity, p: Palette) {
        val window = activity.window
        window.statusBarColor = p.bg
        window.navigationBarColor = p.bg
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (p.dark) 0 else flags, flags)
        } else {
            var v = window.decorView.systemUiVisibility
            v = if (p.dark) v and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else v or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) {
                v = if (p.dark) v and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else v or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = v
        }
    }

    /** 125 segundos viram "2:05". */
    fun formatTime(totalSeconds: Int): String =
        "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"

    /** Aviso rápido na tela. */
    fun toast(ctx: Context, text: String, long: Boolean = false) {
        Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /**
     * Android 15+ desenha o app por baixo das barras do sistema (e o teclado também entra na conta):
     * soma o tamanho delas ao espaçamento de [target] para nada ficar escondido.
     */
    @Suppress("DEPRECATION")
    fun padForSystemBars(root: View, target: View, left: Int, top: Int, right: Int, bottom: Int) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            target.setPadding(
                left + insets.systemWindowInsetLeft,
                top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight,
                bottom + insets.systemWindowInsetBottom
            )
            insets
        }
    }

    /** Botão só com ícone (sem fundo), pintado com a cor [tint]. */
    fun iconButton(ctx: Context, resId: Int, tint: Int, description: String): ImageButton {
        val button = ImageButton(ctx)
        button.setImageResource(resId)
        button.setColorFilter(tint)
        button.contentDescription = description
        button.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val pad = dp(ctx, 10)
        button.setPadding(pad, pad, pad, pad)
        val tv = TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            button.setBackgroundResource(tv.resourceId)
        } else {
            button.setBackgroundColor(Color.TRANSPARENT)
        }
        return button
    }

    /** Botão de texto com cantos arredondados, no estilo do tema. */
    fun textButton(ctx: Context, label: String, p: Palette): Button {
        val button = Button(ctx)
        button.text = label
        button.isAllCaps = false
        button.setTextColor(p.text)
        val shape = GradientDrawable()
        shape.cornerRadius = dp(ctx, 12).toFloat()
        shape.setColor(p.surface)
        button.background = shape
        return button
    }
}
