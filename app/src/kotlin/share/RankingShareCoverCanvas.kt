package org.rankoffline.app

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.io.File

internal fun RankingShareCardRenderer.drawPanel(
    canvas: Canvas, x: Float, y: Float, width: Float, height: Float,
    radius: Float, fill: Int, border: Int
) {
    val rect = RectF(x, y, x + width, y + height)
    canvas.drawRoundRect(rect, radius, radius, Paint().apply { color = fill; isAntiAlias = true })
    canvas.drawRoundRect(rect, radius, radius, Paint().apply {
        color = border; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
    })
}


internal fun RankingShareCardRenderer.drawRankBadge(canvas: Canvas, rank: Int, x: Float, y: Float, color: Int) {
    canvas.drawCircle(x + 28f, y + 28f, 28f, Paint().apply { this.color = color; isAntiAlias = true })
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = 0xFF171923.toInt(); textSize = 30f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    canvas.drawText(rank.toString(), x + 28f, y + 39f, paint)
}


internal fun RankingShareCardRenderer.drawCover(canvas: Canvas, path: String?, x: Float, y: Float, width: Float, height: Float) {
    val rect = RectF(x, y, x + width, y + height)
    val cover = path?.let { decodeSampled(File(it), width.toInt(), height.toInt()) }
    canvas.save()
    val clip = Path().apply { addRoundRect(rect, 18f, 18f, Path.Direction.CW) }
    canvas.clipPath(clip)
    if (cover != null) {
        val sourceRatio = cover.width.toFloat() / cover.height
        val targetRatio = width / height
        val source = if (sourceRatio > targetRatio) {
            val crop = (cover.height * targetRatio).toInt()
            android.graphics.Rect((cover.width - crop) / 2, 0, (cover.width + crop) / 2, cover.height)
        } else {
            val crop = (cover.width / targetRatio).toInt()
            android.graphics.Rect(0, (cover.height - crop) / 2, cover.width, (cover.height + crop) / 2)
        }
        canvas.drawBitmap(cover, source, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        cover.recycle()
    } else {
        canvas.drawRect(rect, Paint().apply {
            shader = LinearGradient(x, y, x + width, y + height,
                0xFF30364A.toInt(), 0xFF191D2A.toInt(), Shader.TileMode.CLAMP)
        })
    }
    canvas.restore()
}
