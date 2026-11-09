package org.rankoffline.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class RankingShareTemplate(val limit: Int, val width: Int, val height: Int) {
    TOP_SEVEN(7, 1080, 1920),
    TOP_TEN(10, 1920, 1080)
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
    val topSevenBackground: String? = null,
    val topTenBackground: String? = null,
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

/** Both PNG and preview use the same canvas with two explicit 9:16 and 16:9 templates. */
internal object RankingShareCardRenderer {
    const val MAX_BACKGROUND_BYTES = 8L * 1024L * 1024L

    suspend fun render(
        request: RankingShareRenderRequest,
        width: Int = request.template.width,
        height: Int = request.template.height
    ): Bitmap = withContext(Dispatchers.Default) {
        require(width > 0 && height > 0 &&
            width.toLong() * request.template.height == height.toLong() * request.template.width)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / request.template.width.toFloat(), height / request.template.height.toFloat())
        drawBackground(canvas, request)
        when (request.template) {
            RankingShareTemplate.TOP_SEVEN -> drawTopSeven(canvas, request)
            RankingShareTemplate.TOP_TEN -> drawTopTen(canvas, request)
        }
        bitmap
    }

    internal fun drawTopSeven(canvas: Canvas, request: RankingShareRenderRequest) {
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
        items.drop(3).forEachIndexed { index, item ->
            drawRankingRow(canvas, item, index + 4, 72f, 900f + index * 205f,
                936f, 174f, request.showImages)
        }
        drawFooter(canvas, request.template)
    }

    internal fun drawTopTen(canvas: Canvas, request: RankingShareRenderRequest) {
        drawHeader(canvas, request)
        request.items.take(10).forEachIndexed { index, item ->
            // Exactly: [1,2], [3,4], [5,6], [7,8], [9,10].
            val column = index % 2
            val row = index / 2
            drawRankingRow(canvas, item, index + 1,
                if (column == 0) 65f else 985f,
                235f + row * 161f, 870f, 139f, request.showImages, compact = true)
        }
        drawFooter(canvas, request.template)
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
            val coverHeight = slot.height * 0.47f
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

    internal fun drawRankingRow(
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
        val rankWidth = if (compact) 78f else 78f
        drawText(canvas, "#$rank", x + 18f, y + 26f, rankWidth.toInt(), if (compact) 32f else 39f,
            0xFFE8EAF2.toInt(), true, 1)
        var textX = x + rankWidth + 16f
        if (showImages) {
            val coverWidth = if (compact) 72f else 90f
            drawCover(canvas, item.coverPath, textX, y + 18f, coverWidth, height - 36f)
            textX += coverWidth + 18f
        }
        if (compact) {
            // Landscape Top 10: title and score use separate columns, never overlapping.
            val rightInset = 18f
            val scoreWidth = 100f
            val titleWidth = (x + width - textX - scoreWidth - rightInset - 14f).toInt()
            drawText(canvas, item.title, textX, y + 48f, titleWidth,
                35f, Color.WHITE, true, 2)
            drawText(canvas, item.rating, x + width - scoreWidth - rightInset, y + 48f,
                scoreWidth.toInt(), 30f, 0xFF8FE5F0.toInt(), true, 1)
        } else {
            val textWidth = (x + width - textX - 18f).toInt()
            drawText(canvas, item.title, textX, y + 28f, textWidth,
                34f, Color.WHITE, true, 2)
            drawText(canvas, item.rating, textX, y + height - 58f, textWidth,
                30f, 0xFF8FE5F0.toInt(), true, 1)
        }
    }

}

internal class RankingShareExporter(
    context: Context,
    internal val backgroundProvider: (ViewingScope, RankingShareTemplate) -> File?
) {
    internal val appContext = context.applicationContext
    internal val directory = File(appContext.cacheDir, SHARED_RANKING_DIRECTORY)

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

    fun backgroundFor(scope: ViewingScope, template: RankingShareTemplate): String? = backgroundProvider(scope, template)
        ?.takeIf { it.isFile && it.length() in 1..RankingShareCardRenderer.MAX_BACKGROUND_BYTES }
        ?.absolutePath

    internal fun cleanupOldFiles() {
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
