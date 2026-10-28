package org.rankoffline.app

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RankingShareRendererTest {
    private val items = (1..10).map { index ->
        RankingShareItem(
            packId = OfficialPackIds.Anime,
            canonicalId = "anime-$index",
            title = if (index == 2) "進撃の巨人 — A deliberately long translated title" else "Title $index",
            rating = "${10 - index / 10.0}",
            coverPath = null
        )
    }

    @Test
    fun allFourVariantsRenderAtStoryResolution() = runBlocking {
        RankingShareTemplate.entries.forEach { template ->
            listOf(false, true).forEach { showImages ->
                val bitmap = RankingShareCardRenderer.render(
                    RankingShareRenderRequest(
                        heading = "My Top ${template.limit} Anime",
                        items = items,
                        template = template,
                        showImages = showImages,
                        accentArgb = 0xFF19D5E5.toInt(),
                        backgroundPath = null
                    )
                )
                try {
                    assertEquals(1080, bitmap.width)
                    assertEquals(1920, bitmap.height)
                    assertTrue(bitmap.getPixel(540, 960) != 0)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun exporterWritesAReadablePng() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exporter = RankingShareExporter(context) { null }
        val file = exporter.export(
            RankingShareRenderRequest(
                heading = "My Top 7 Anime",
                items = items.take(7),
                template = RankingShareTemplate.TOP_SEVEN,
                showImages = false,
                accentArgb = 0xFF19D5E5.toInt(),
                backgroundPath = null
            )
        )
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            assertEquals(1080, bounds.outWidth)
            assertEquals(1920, bounds.outHeight)
            assertTrue(file.length() > 0L)
        } finally {
            file.delete()
        }
    }

    @Test
    fun corruptBackgroundFallsBackWithoutBreakingRender() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corruptBackground = File(context.cacheDir, "corrupt-ranking-background.webp")
        corruptBackground.writeText("not an image")
        try {
            val bitmap = RankingShareCardRenderer.render(
                RankingShareRenderRequest(
                    heading = "My Top 7 Anime",
                    items = items.take(7),
                    template = RankingShareTemplate.TOP_SEVEN,
                    showImages = true,
                    accentArgb = 0xFF19D5E5.toInt(),
                    backgroundPath = corruptBackground.absolutePath
                )
            )
            try {
                assertEquals(1080, bitmap.width)
                assertEquals(1920, bitmap.height)
                assertTrue(bitmap.getPixel(540, 960) != 0)
            } finally {
                bitmap.recycle()
            }
        } finally {
            corruptBackground.delete()
        }
    }
}
