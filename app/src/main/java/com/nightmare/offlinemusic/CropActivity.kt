package com.nightmare.offlinemusic

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * Tela para ajustar a imagem de fundo: arraste com um dedo e use o gesto de pinça (dois dedos) para dar zoom.
 * O que aparece aqui ocupa a mesma área da tela principal, então o resultado é igual ao que você vê.
 */
class CropActivity : Activity() {
    private lateinit var cropView: CropView
    private lateinit var hint: TextView
    private lateinit var useButton: Button
    private var bitmap: Bitmap? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val p = Ui.DARK

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        cropView = CropView(this)
        root.addView(cropView, FrameLayout.LayoutParams(-1, -1))

        hint = TextView(this)
        hint.text = "Carregando imagem..."
        hint.textSize = 14f
        hint.setTextColor(Color.WHITE)
        hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8))
        val hintShape = GradientDrawable()
        hintShape.cornerRadius = Ui.dp(this, 20).toFloat()
        hintShape.setColor(0x99000000.toInt())
        hint.background = hintShape
        val hintParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        hintParams.topMargin = Ui.dp(this, 12)
        root.addView(hint, hintParams)

        val cancelButton = Ui.textButton(this, "Cancelar", p)
        cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        useButton = Ui.textButton(this, "Usar imagem", p)
        val accentShape = GradientDrawable()
        accentShape.cornerRadius = Ui.dp(this, 12).toFloat()
        accentShape.setColor(p.accent)
        useButton.background = accentShape
        useButton.setTextColor(Color.WHITE)
        useButton.isEnabled = false
        useButton.setOnClickListener { saveCrop() }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(cancelButton, LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f).apply { rightMargin = Ui.dp(this@CropActivity, 8) })
        bar.addView(useButton, LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f))
        val barParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        barParams.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 16))
        root.addView(bar, barParams)

        // Mantém os botões fora das barras do sistema (Android 15+ desenha por baixo delas).
        root.setOnApplyWindowInsetsListener { _, insets ->
            hintParams.topMargin = Ui.dp(this, 12) + insets.systemWindowInsetTop
            barParams.setMargins(
                Ui.dp(this, 16) + insets.systemWindowInsetLeft, 0,
                Ui.dp(this, 16) + insets.systemWindowInsetRight, Ui.dp(this, 16) + insets.systemWindowInsetBottom
            )
            hint.requestLayout()
            bar.requestLayout()
            insets
        }
        setContentView(root)
        Ui.applySystemBars(this, p)

        // Lê a imagem fora da tela principal para não travar.
        Thread {
            val loaded = decode(uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    loaded?.recycle()
                } else if (loaded == null) {
                    Ui.toast(this, "Não foi possível abrir essa imagem.", long = true)
                    setResult(RESULT_CANCELED)
                    finish()
                } else {
                    bitmap = loaded
                    cropView.setImage(loaded)
                    hint.text = "Arraste e use dois dedos para ajustar"
                    useButton.isEnabled = true
                }
            }
        }.start()
    }

    /** Abre a imagem já reduzida (no máximo ~3,5 milhões de pixels) para gastar pouca memória. */
    private fun decode(uri: Uri): Bitmap? {
        try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while ((w / sample).toLong() * (h / sample) > MAX_PIXELS) sample *= 2
            val options = BitmapFactory.Options()
            options.inSampleSize = sample
            options.inPreferredConfig = Bitmap.Config.RGB_565
            var result = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            val rotation = readRotation(uri)
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
                if (rotated !== result) result.recycle()
                result = rotated
            }
            return result
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    /** Fotos de câmera trazem a "rotação" gravada no arquivo; sem isso elas apareceriam deitadas. */
    private fun readRotation(uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun saveCrop() {
        val cropped = cropView.renderCrop()
        if (cropped == null) {
            Ui.toast(this, "Não foi possível salvar a imagem.", long = true)
            return
        }
        useButton.isEnabled = false
        val target = Prefs.wallpaperFile(this)
        Thread {
            var ok = false
            try {
                // Grava num arquivo temporário e só depois troca, para nunca ficar com um arquivo pela metade.
                val temp = File(target.parentFile, "wallpaper.tmp")
                FileOutputStream(temp).use { cropped.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                ok = temp.renameTo(target)
            } catch (e: Exception) {
                ok = false
            }
            cropped.recycle()
            runOnUiThread {
                if (ok) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    useButton.isEnabled = true
                    Ui.toast(this, "Não foi possível salvar a imagem.", long = true)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        cropView.clear()
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }

    companion object {
        private const val MAX_PIXELS = 3_500_000L
    }
}

/** Mostra a imagem e deixa arrastar / dar zoom, sem nunca deixar sobrar borda vazia. */
class CropView(context: Context) : View(context) {
    private var image: Bitmap? = null
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var minScale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (image == null) return false
            val current = currentScale()
            val target = (current * detector.scaleFactor).coerceIn(minScale, minScale * MAX_ZOOM)
            val factor = target / current
            matrix.postScale(factor, factor, detector.focusX, detector.focusY)
            clamp()
            invalidate()
            return true
        }
    })

    fun setImage(bitmap: Bitmap) {
        image = bitmap
        reset()
        invalidate()
    }

    fun clear() {
        image = null
    }

    /** Começa com a imagem preenchendo a tela inteira e centralizada. */
    private fun reset() {
        val b = image ?: return
        if (width == 0 || height == 0) return
        minScale = maxOf(width / b.width.toFloat(), height / b.height.toFloat())
        matrix.setScale(minScale, minScale)
        matrix.postTranslate((width - b.width * minScale) / 2f, (height - b.height * minScale) / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    private fun currentScale(): Float {
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** Impede que a imagem saia da tela deixando espaço vazio. */
    private fun clamp() {
        val b = image ?: return
        matrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        val imgW = b.width * scale
        val imgH = b.height * scale
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]
        tx = if (imgW <= width) (width - imgW) / 2f else tx.coerceIn(width - imgW, 0f)
        ty = if (imgH <= height) (height - imgH) / 2f else ty.coerceIn(height - imgH, 0f)
        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        matrix.setValues(values)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return false
        scaleDetector.onTouchEvent(event)
        // Arrastar usa o ponto médio dos dedos que estão na tela.
        val skip = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count == 0) return true
        val cx = sumX / count
        val cy = sumY / count
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastX = cx
                lastY = cy
            }
            MotionEvent.ACTION_MOVE -> {
                matrix.postTranslate(cx - lastX, cy - lastY)
                lastX = cx
                lastY = cy
                clamp()
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = image ?: return
        canvas.drawBitmap(b, matrix, paint)
    }

    /** Gera a imagem final: exatamente o que está sendo mostrado na tela. */
    fun renderCrop(): Bitmap? {
        val b = image ?: return null
        if (width <= 0 || height <= 0) return null
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(b, matrix, paint)
        return out
    }

    companion object {
        private const val MAX_ZOOM = 4f
    }
}
