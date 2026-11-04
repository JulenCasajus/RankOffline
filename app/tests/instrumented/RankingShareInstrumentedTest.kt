package org.rankoffline.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RankingShareInstrumentedTest {
    @Test fun templateLimitsAndDimensionsMatchThePublicShareContract() {
        assertEquals(Triple(7, 1080, 1920), RankingShareTemplate.TOP_SEVEN.run {
            Triple(limit, width, height)
        })
        assertEquals(Triple(10, 1920, 1080), RankingShareTemplate.TOP_TEN.run {
            Triple(limit, width, height)
        })
    }

    @Test fun bothTemplatesRenderAtTheirExactAspectRatio() = runBlocking {
        RankingShareTemplate.entries.forEach { template ->
            val bitmap = RankingShareCardRenderer.render(request(template),
                width = template.width / 4, height = template.height / 4)
            try {
                assertEquals(template.width / 4, bitmap.width)
                assertEquals(template.height / 4, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun rendererRejectsDistortedOutputDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { RankingShareCardRenderer.render(request(RankingShareTemplate.TOP_TEN), 100, 100) }
        }
    }

    private fun request(template: RankingShareTemplate) = RankingShareRenderRequest(
        heading = "Ranking",
        items = (1..template.limit).map { rank ->
            RankingShareItem(PackId(1), "item:$rank", "Item $rank", "$rank.0", null)
        },
        template = template,
        showImages = false,
        accentArgb = 0xFF336699.toInt(),
        backgroundPath = null
    )
}
