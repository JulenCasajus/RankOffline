package org.rankoffline.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import java.io.File

internal fun RankingShareCardRenderer.drawBackground(canvas: Canvas, request: RankingShareRenderRequest) {
    val width = request.template.width
    val height = request.template.height
    val background = request.backgroundPath?.let(::decodeBackground)
    if (background != null) {
        try {
            val sourceRatio = background.width.toFloat() / background.height
            val targetRatio = width.toFloat() / height
            val source = if (sourceRatio > targetRatio) {
                val crop = (background.height * targetRatio).toInt()
                android.graphics.Rect((background.width - crop) / 2, 0,
                    (background.width + crop) / 2, background.height)
            } else {
                val crop = (background.width / targetRatio).toInt()
                android.graphics.Rect(0, (background.height - crop) / 2,
                    background.width, (background.height + crop) / 2)
            }
            canvas.drawBitmap(background, source, android.graphics.Rect(0, 0, width, height), null)
        } finally { background.recycle() }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
                intArrayOf(0x7A050812, 0xB80A0D18.toInt(), 0xF20A0D18.toInt()),
                null, Shader.TileMode.CLAMP)
        })
    } else {
        // Procedural fallback if an installed placeholder cannot be read.
        val deepAccent = ColorUtils.blendARGB(request.accentArgb, Color.BLACK, 0.78f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(deepAccent, 0xFF090C17.toInt(), 0xFF161022.toInt()),
                null, Shader.TileMode.CLAMP)
        })
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(request.accentArgb, 45)
        }
        canvas.drawCircle(width * 0.82f, height * 0.14f, width * 0.35f, glow)
        canvas.drawCircle(width * 0.12f, height * 0.84f, width * 0.40f, glow)
    }
}


internal fun RankingShareCardRenderer.decodeBackground(path: String): Bitmap? {
    val file = File(path)
    if (!file.isFile || file.length() !in 1..RankingShareCardRenderer.MAX_BACKGROUND_BYTES) return null
    return decodeSampled(file, 1920, 1920)
}


internal fun RankingShareCardRenderer.decodeSampled(file: File, requestedWidth: Int, requestedHeight: Int): Bitmap? {
    if (!file.isFile || file.length() <= 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= requestedWidth &&
        bounds.outHeight / (sample * 2) >= requestedHeight
    ) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}
