package org.rankoffline.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils

internal fun RankingShareCardRenderer.drawHeader(canvas: Canvas, request: RankingShareRenderRequest) {
    val horizontal = request.template == RankingShareTemplate.TOP_TEN
    val left = if (horizontal) 110f else 72f
    val width = request.template.width - (2 * left).toInt()
    drawText(canvas, request.heading, left, if (horizontal) 48f else 72f,
        width, if (horizontal) 68f else 72f, Color.WHITE, true, 2)
    drawText(canvas, "RankOffline", left, if (horizontal) 130f else 180f,
        width, 32f, 0xFFD5D8E5.toInt(), false, 1)
    val dividerY = if (horizontal) 197f else 232f
    canvas.drawRoundRect(RectF(left, dividerY, request.template.width - left, dividerY + 6f),
        3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(request.accentArgb, 210)
        })
}


internal fun RankingShareCardRenderer.drawText(
    canvas: Canvas,
    value: String,
    x: Float,
    y: Float,
    width: Int,
    size: Float,
    color: Int,
    bold: Boolean,
    maxLines: Int
) {
    if (width <= 0 || value.isBlank()) return
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }
    val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(0f, 0.95f)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
    canvas.save()
    canvas.translate(x, y)
    layout.draw(canvas)
    canvas.restore()
}


internal fun RankingShareCardRenderer.drawFooter(canvas: Canvas, template: RankingShareTemplate) {
    val left = if (template == RankingShareTemplate.TOP_TEN) 110f else 72f
    drawText(canvas, "RankOffline", left, template.height - 69f,
        template.width - (2 * left).toInt(), 28f, 0xFFB9BECD.toInt(), false, 1)
}
