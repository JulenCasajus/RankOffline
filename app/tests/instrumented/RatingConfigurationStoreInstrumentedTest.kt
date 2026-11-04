package org.rankoffline.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RatingConfigurationStoreInstrumentedTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<Context>() }
    private val packId = PackId(987654321L)

    @After fun cleanUp() {
        context.filesDir.resolve("local/${packId.value}").deleteRecursively()
    }

    @Test fun localEditsSurviveReloadAndExplicitResetRestoresManifestDefaults() {
        val original = PackRatingDefinition(
            RatingSystemConfig(listOf(RatingCategory("writing", "Writing", "Original", 100.0,
                "#FFAA55", 0))),
            RatingProfilesConfig(listOf(RatingProfile("general", "General", mapOf("writing" to 100.0))))
        )
        val store = LocalRatingConfigurationStore(context)
        assertEquals(original, store.readOrCreate(packId, original))

        val added = RatingCategory("custom_1234567890abcdef", "Added", "Personal", 20.0,
            "#7EC7FF", 1)
        val edited = PackRatingDefinition(
            RatingSystemConfig(listOf(original.config.categories.single().copy(weight = 80.0), added)),
            RatingProfilesConfig(listOf(
                RatingProfile("general", "General", mapOf("writing" to 80.0, added.id to 20.0)),
                RatingProfile("custom_abcdef1234567890", "Custom", mapOf("writing" to 50.0, added.id to 50.0))
            ))
        )
        store.write(packId, edited)
        assertEquals(edited, LocalRatingConfigurationStore(context).readOrCreate(packId, original))

        store.write(packId, original)
        assertEquals(original, LocalRatingConfigurationStore(context).readOrCreate(packId, original))
        assertFalse(store.path(packId).parentFile!!.resolve("rating.json.bak").exists())
    }
}
