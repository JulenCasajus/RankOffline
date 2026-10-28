package org.rankoffline.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.ColorUtils
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class RankingShareTemplate(val limit: Int) {
    TOP_SEVEN(7),
    TOP_TEN(10)
}

internal data class RankingShareItem(
    val packId: PackId,
    val canonicalId: String,
    val title: String,
    val rating: String,
    val coverPath: String?
)

internal data class RankingShareData(
    val scope: ViewingScope,
    val items: List<RankingShareItem> = emptyList(),
    val backgroundPath: String? = null,
    val loading: Boolean = true,
    val failed: Boolean = false
)

internal data class RankingShareRenderRequest(
    val heading: String,
    val items: List<RankingShareItem>,
    val template: RankingShareTemplate,
    val showImages: Boolean,
    val accentArgb: Int,
    val backgroundPath: String?
)

internal data class RankingShareExportState(
    val rendering: Boolean = false,
    val uriReady: Boolean = false
)

/**
 * One renderer is used by both preview and exported PNG, preventing visual drift. All dimensions
 * below use the 1080x1920 logical canvas and are scaled uniformly for previews.
 */
internal object RankingShareCardRenderer {
    const val EXPORT_WIDTH = 1080
    const val EXPORT_HEIGHT = 1920
    const val PREVIEW_WIDTH = 360
    const val PREVIEW_HEIGHT = 640
    const val MAX_BACKGROUND_BYTES = 8L * 1024L * 1024L

    suspend fun render(
        request: RankingShareRenderRequest,
        width: Int = EXPORT_WIDTH,
        height: Int = EXPORT_HEIGHT
    ): Bitmap = withContext(Dispatchers.Default) {
        require(width > 0 && height > 0 && width * EXPORT_HEIGHT == height * EXPORT_WIDTH)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / EXPORT_WIDTH.toFloat(), height / EXPORT_HEIGHT.toFloat())
        drawBackground(canvas, request)
        when (request.template) {
            RankingShareTemplate.TOP_SEVEN -> drawTopSeven(canvas, request)
            RankingShareTemplate.TOP_TEN -> drawTopTen(canvas, request)
        }
        bitmap
    }

    private fun drawBackground(canvas: Canvas, request: RankingShareRenderRequest) {
        val background = request.backgroundPath?.let(::decodeBackground)
        if (background != null) {
            val sourceRatio = background.width.toFloat() / background.height
            val targetRatio = EXPORT_WIDTH.toFloat() / EXPORT_HEIGHT
            val source = if (sourceRatio > targetRatio) {
                val cropWidth = (background.height * targetRatio).toInt()
                android.graphics.Rect((background.width - cropWidth) / 2, 0, (background.width + cropWidth) / 2, background.height)
            } else {
                val cropHeight = (background.width / targetRatio).toInt()
                android.graphics.Rect(0, (background.height - cropHeight) / 2, background.width, (background.height + cropHeight) / 2)
            }
            canvas.drawBitmap(background, source, android.graphics.Rect(0, 0, EXPORT_WIDTH, EXPORT_HEIGHT), null)
            background.recycle()
            canvas.drawRect(
                0f, 0f, EXPORT_WIDTH.toFloat(), EXPORT_HEIGHT.toFloat(),
                Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, EXPORT_HEIGHT.toFloat(),
                        intArrayOf(0x7A050812, 0xB80A0D18.toInt(), 0xF20A0D18.toInt()), null, Shader.TileMode.CLAMP)
                }
            )
        } else {
            val deepAccent = ColorUtils.blendARGB(request.accentArgb, Color.BLACK, 0.78f)
            canvas.drawRect(
                0f, 0f, EXPORT_WIDTH.toFloat(), EXPORT_HEIGHT.toFloat(),
                Paint().apply {
                    shader = LinearGradient(0f, 0f, EXPORT_WIDTH.toFloat(), EXPORT_HEIGHT.toFloat(),
                        intArrayOf(deepAccent, 0xFF090C17.toInt(), 0xFF161022.toInt()), null, Shader.TileMode.CLAMP)
                }
            )
            val glow = Paint().apply {
                color = ColorUtils.setAlphaComponent(request.accentArgb, 45)
                isAntiAlias = true
            }
            canvas.drawCircle(880f, 260f, 360f, glow)
            canvas.drawCircle(100f, 1650f, 430f, glow)
        }
        canvas.drawRect(0f, 0f, EXPORT_WIDTH.toFloat(), EXPORT_HEIGHT.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, EXPORT_HEIGHT.toFloat(),
                intArrayOf(Color.TRANSPARENT, 0x55000000, 0xAA000000.toInt()), null, Shader.TileMode.CLAMP)
        })
    }

    private fun drawHeader(canvas: Canvas, request: RankingShareRenderRequest) {
        drawText(canvas, request.heading, 72f, 72f, 936, 72f, Color.WHITE, true, 2)
        drawText(canvas, "RankOffline", 72f, 180f, 936, 34f, 0xFFD5D8E5.toInt(), false, 1)
        canvas.drawRoundRect(RectF(72f, 232f, 1008f, 238f), 3f, 3f, Paint().apply {
            color = ColorUtils.setAlphaComponent(request.accentArgb, 210)
            isAntiAlias = true
        })
    }

    private fun drawTopSeven(canvas: Canvas, request: RankingShareRenderRequest) {
        drawHeader(canvas, request)
        val items = request.items.take(7)
        val podium = listOf(
            PodiumSlot(1, 390f, 290f, 300f, 445f, 0xFFFFD66B.toInt()),
            PodiumSlot(2, 72f, 405f, 250f, 385f, 0xFFD4DAE5.toInt()),
            PodiumSlot(3, 758f, 435f, 250f, 355f, 0xFFC88B62.toInt())
        )
        podium.forEach { slot ->
            items.getOrNull(slot.rank - 1)?.let { drawPodiumItem(canvas, it, slot, request.showImages) }
        }
        val rowTop = 900f
        items.drop(3).forEachIndexed { index, item ->
            drawRankingRow(canvas, item, index + 4, 72f, rowTop + index * 205f, 936f, 174f, request.showImages)
        }
        drawFooter(canvas)
    }

    private fun drawTopTen(canvas: Canvas, request: RankingShareRenderRequest) {
        drawHeader(canvas, request)
        val items = request.items.take(10)
        items.forEachIndexed { index, item ->
            val column = index / 5
            val row = index % 5
            val x = if (column == 0) 54f else 552f
            drawRankingRow(
                canvas, item, index + 1, x, 300f + row * 292f, 474f, 258f, request.showImages,
                compact = true
            )
        }
        drawFooter(canvas)
    }

    private data class PodiumSlot(
        val rank: Int,
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val medal: Int
    )

    private fun drawPodiumItem(canvas: Canvas, item: RankingShareItem, slot: PodiumSlot, showImages: Boolean) {
        drawPanel(canvas, slot.x, slot.y, slot.width, slot.height, 34f, 0xA51A1E2B.toInt(), slot.medal)
        drawRankBadge(canvas, slot.rank, slot.x + 18f, slot.y + 18f, slot.medal)
        val titleTop: Float
        if (showImages) {
            // Reserve a stable two-line title area plus the rating below every podium cover.
            val coverHeight = slot.height * 0.42f
            drawCover(canvas, item.coverPath, slot.x + 22f, slot.y + 64f, slot.width - 44f, coverHeight)
            titleTop = slot.y + 78f + coverHeight
        } else {
            titleTop = slot.y + 105f
        }
        drawText(canvas, item.title, slot.x + 20f, titleTop, (slot.width - 40f).toInt(),
            if (slot.rank == 1) 37f else 32f, Color.WHITE, true, if (showImages) 2 else 4)
        drawText(canvas, item.rating, slot.x + 20f, slot.y + slot.height - 58f,
            (slot.width - 40f).toInt(), 31f, slot.medal, true, 1)
    }

    private fun drawRankingRow(
        canvas: Canvas,
        item: RankingShareItem,
        rank: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        showImages: Boolean,
        compact: Boolean = false
    ) {
        drawPanel(canvas, x, y, width, height, 28f, 0xA51A1E2B.toInt(), 0x443D4660)
        val rankWidth = if (compact) 64f else 78f
        drawText(canvas, "#$rank", x + 18f, y + 26f, rankWidth.toInt(), if (compact) 34f else 39f,
            0xFFE8EAF2.toInt(), true, 1)
        var textX = x + rankWidth + 16f
        if (showImages) {
            val coverWidth = if (compact) 105f else 90f
            drawCover(canvas, item.coverPath, textX, y + 18f, coverWidth, height - 36f)
            textX += coverWidth + 18f
        }
        val textWidth = (x + width - textX - 18f).toInt()
        drawText(canvas, item.title, textX, y + 28f, textWidth,
            if (compact) 30f else 34f, Color.WHITE, true, if (compact) 3 else 2)
        drawText(canvas, item.rating, textX, y + height - 58f, textWidth,
            if (compact) 27f else 30f, 0xFF8FE5F0.toInt(), true, 1)
    }

    private fun drawPanel(
        canvas: Canvas, x: Float, y: Float, width: Float, height: Float,
        radius: Float, fill: Int, border: Int
    ) {
        val rect = RectF(x, y, x + width, y + height)
        canvas.drawRoundRect(rect, radius, radius, Paint().apply { color = fill; isAntiAlias = true })
        canvas.drawRoundRect(rect, radius, radius, Paint().apply {
            color = border; style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true
        })
    }

    private fun drawRankBadge(canvas: Canvas, rank: Int, x: Float, y: Float, color: Int) {
        canvas.drawCircle(x + 28f, y + 28f, 28f, Paint().apply { this.color = color; isAntiAlias = true })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFF171923.toInt(); textSize = 30f; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        canvas.drawText(rank.toString(), x + 28f, y + 39f, paint)
    }

    private fun drawCover(canvas: Canvas, path: String?, x: Float, y: Float, width: Float, height: Float) {
        val rect = RectF(x, y, x + width, y + height)
        val cover = path?.let {
            runCatching { decodeSampled(File(it), width.toInt(), height.toInt()) }.getOrNull()
        }
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

    private fun drawText(
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

    private fun drawFooter(canvas: Canvas) {
        drawText(canvas, "RankOffline", 72f, 1835f, 936, 28f, 0xFFB9BECD.toInt(), false, 1)
    }

    private fun decodeBackground(path: String): Bitmap? {
        val file = File(path)
        if (!file.isFile || file.length() !in 1..MAX_BACKGROUND_BYTES) return null
        return runCatching { decodeSampled(file, EXPORT_WIDTH, EXPORT_HEIGHT) }.getOrNull()
    }

    private fun decodeSampled(file: File, requestedWidth: Int, requestedHeight: Int): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth > MAX_IMAGE_DIMENSION || bounds.outHeight > MAX_IMAGE_DIMENSION
        ) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= requestedWidth &&
            bounds.outHeight / (sample * 2) >= requestedHeight
        ) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private const val MAX_IMAGE_DIMENSION = 20_000
}

internal class RankingShareExporter(
    context: Context,
    private val backgroundProvider: (ViewingScope) -> File?
) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.cacheDir, SHARED_RANKING_DIRECTORY)

    suspend fun export(request: RankingShareRenderRequest): File = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create ranking share directory" }
        cleanupOldFiles()
        val bitmap = RankingShareCardRenderer.render(request)
        val output = File(directory, "RankOffline-ranking-${System.currentTimeMillis()}.png")
        val partial = File(directory, "${output.name}.part")
        try {
            FileOutputStream(partial).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Unable to encode ranking image" }
                stream.fd.sync()
            }
            check(partial.renameTo(output)) { "Unable to finalize ranking image" }
            output
        } finally {
            bitmap.recycle()
            if (partial.exists()) partial.delete()
        }
    }

    fun backgroundFor(scope: ViewingScope): String? = backgroundProvider(scope)
        ?.takeIf { it.isFile && it.length() in 1..RankingShareCardRenderer.MAX_BACKGROUND_BYTES }
        ?.absolutePath

    private fun cleanupOldFiles() {
        val cutoff = System.currentTimeMillis() - SHARE_FILE_MAX_AGE_MS
        directory.listFiles().orEmpty().forEach { file ->
            if (file.lastModified() < cutoff || file.name.endsWith(".part")) file.delete()
        }
    }

    private companion object {
        const val SHARE_FILE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}

internal const val SHARED_RANKING_DIRECTORY = "shared-ranking-cards"
