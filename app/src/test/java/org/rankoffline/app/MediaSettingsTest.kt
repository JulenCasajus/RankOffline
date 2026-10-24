package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSettingsTest {
    @Test
    fun destructiveCategoryResetChangesOnlyTheSelectedMediaSettings() {
        val original = DefaultSettings.allMediaSettings().mapValues { (mediaType, settings) ->
            settings.copy(elaborateRatingEnabled = true, mergeEnabled = mediaType.supportsMerge())
        }

        val reset = resetMediaSettingsForCategory(original, MediaType.MOVIE)

        assertEquals(DefaultSettings.mediaSettings(MediaType.MOVIE), reset.getValue(MediaType.MOVIE))
        MediaType.entries.filterNot { it == MediaType.MOVIE }.forEach { mediaType ->
            assertEquals(original.getValue(mediaType), reset.getValue(mediaType))
        }
    }
    @Test
    fun freshSettingsContainAllFiveIndependentMediaDefaults() {
        val state = AppSettingsState()

        assertEquals(MediaType.entries.toSet(), state.mediaSettings.keys)
        assertEquals(MediaType.ANIME, state.activeMediaType)
        assertEquals(DefaultSettings.defaultRatingConfig, state.ratingConfig)
        assertEquals(DefaultSettings.defaultProfiles(), state.ratingProfiles)
        assertFalse(state.elaborateRatingEnabled)
        assertFalse(state.mergeEnabled)
    }

    @Test
    fun animeDefaultsRemainExactlyThePublishedDefaults() {
        val anime = DefaultSettings.mediaSettings(MediaType.ANIME)

        assertEquals(DefaultSettings.defaultRatingConfig, anime.ratingConfig)
        assertEquals(DefaultSettings.defaultProfiles(), anime.ratingProfiles)
        assertEquals(
            listOf("writing", "characters", "visuals", "worldbuilding", "audio"),
            anime.ratingConfig.categories.map { it.id }
        )
        assertEquals(
            listOf(35.0, 20.0, 20.0, 15.0, 10.0),
            anime.ratingConfig.categories.map { it.weight }
        )
    }

    @Test
    fun legacyMigrationPreservesCustomAnimeConfigurationAndFlags() {
        val customCategory = RatingCategory(
            id = "custom",
            name = "My category",
            description = "My description",
            weight = 37.5,
            color = "#123456",
            order = 8
        )
        val customProfile = RatingProfile("mine", "My profile", mapOf("custom" to 73.0))

        val migrated = migrateLegacyAnimeSettings(
            LegacyAnimeSettings(
                elaborateRatingEnabled = true,
                ratingConfig = RatingSystemConfig(
                    categories = listOf(customCategory),
                    additiveBonus = AdditiveBonus("Mine", "Custom bonus", 0.7, "#654321")
                ),
                ratingProfiles = RatingProfilesConfig(listOf(customProfile)),
                mergeEnabled = true
            )
        ).settings

        assertEquals(listOf(customCategory), migrated.ratingConfig.categories)
        assertEquals("Mine", migrated.ratingConfig.additiveBonus?.name)
        assertEquals(listOf(customProfile), migrated.ratingProfiles.profiles)
        assertEquals(73.0, migrated.ratingProfiles.profiles.single().weights.getValue("custom"), 0.0)
        assertTrue(migrated.elaborateRatingEnabled)
        assertTrue(migrated.mergeEnabled)
    }

    @Test
    fun legacyMigrationRetainsInactiveCategoryCleanupOnlyForAnime() {
        val active = category("active")
        val inactive = category("removed").copy(active = false)

        val migrated = migrateLegacyAnimeSettings(
            LegacyAnimeSettings(ratingConfig = RatingSystemConfig(listOf(active, inactive), null))
        )

        assertEquals(listOf(active), migrated.settings.ratingConfig.categories)
        assertEquals(listOf("removed"), migrated.removedCategoryIds)
        assertTrue(DefaultSettings.mediaSettings(MediaType.MANGA).ratingConfig.categories.all { it.active })
    }

    @Test
    fun fillingNewMediaDefaultsNeverOverwritesMigratedAnime() {
        val customAnime = migrateLegacyAnimeSettings(
            LegacyAnimeSettings(ratingConfig = RatingSystemConfig(listOf(category("custom")), null))
        ).settings

        val normalized = normalizeMediaSettingsSnapshot(null, customAnime)

        assertEquals(customAnime, normalized.getValue(MediaType.ANIME))
        MediaType.entries.filterNot { it == MediaType.ANIME }.forEach { mediaType ->
            assertEquals(DefaultSettings.mediaSettings(mediaType), normalized.getValue(mediaType))
        }
    }

    @Test
    fun mediaUpdatesAndResetsAreIsolated() {
        val original = DefaultSettings.allMediaSettings()
        val mangaCustom = category("manga_custom")
        val withMangaEdit = updateMediaSettings(original, MediaType.MANGA) { manga ->
            manga.copy(ratingConfig = manga.ratingConfig.copy(categories = listOf(mangaCustom)))
        }
        val bookProfile = RatingProfile("reader", "Reader", mapOf("writing" to 100.0))
        val withBookEdit = updateMediaSettings(withMangaEdit, MediaType.BOOK) { book ->
            book.copy(ratingProfiles = RatingProfilesConfig(listOf(bookProfile)))
        }

        assertEquals(original.getValue(MediaType.ANIME), withBookEdit.getValue(MediaType.ANIME))
        assertEquals(original.getValue(MediaType.MOVIE), withBookEdit.getValue(MediaType.MOVIE))

        val mangaReset = updateMediaSettings(withBookEdit, MediaType.MANGA) {
            it.withDefaultCategories(MediaType.MANGA)
        }
        assertEquals(
            DefaultSettings.ratingConfig(MediaType.MANGA).categories,
            mangaReset.getValue(MediaType.MANGA).ratingConfig.categories
        )
        assertEquals(withBookEdit.getValue(MediaType.ANIME), mangaReset.getValue(MediaType.ANIME))
        assertEquals(withBookEdit.getValue(MediaType.BOOK), mangaReset.getValue(MediaType.BOOK))

        val customSeries = updateMediaSettings(mangaReset, MediaType.SERIES) { series ->
            series.copy(ratingProfiles = RatingProfilesConfig(listOf(bookProfile)))
        }
        val seriesReset = updateMediaSettings(customSeries, MediaType.SERIES) {
            it.withDefaultProfiles(MediaType.SERIES)
        }
        assertEquals(
            DefaultSettings.profiles(MediaType.SERIES),
            seriesReset.getValue(MediaType.SERIES).ratingProfiles
        )
        assertEquals(customSeries.getValue(MediaType.MOVIE), seriesReset.getValue(MediaType.MOVIE))
    }

    @Test
    fun activeMediaChangesComputedSettingsImmediately() {
        val settings = DefaultSettings.allMediaSettings() +
            (MediaType.BOOK to DefaultSettings.mediaSettings(MediaType.BOOK).copy(
                showImages = true,
                stickyScore = false,
                oneCoverPerGroup = true
            ))
        val state = AppSettingsState(mediaSettings = settings).withActiveMediaType(MediaType.BOOK)

        assertEquals(MediaType.BOOK, state.activeMediaType)
        assertEquals(DefaultSettings.ratingConfig(MediaType.BOOK), state.ratingConfig)
        assertEquals(DefaultSettings.profiles(MediaType.BOOK), state.ratingProfiles)
        assertTrue(state.showImages)
        assertFalse(state.stickyScore)
        assertTrue(state.oneCoverPerGroup)
    }

    @Test
    fun displaySettingsArePersistedIndependentlyPerPack() {
        val settings = updateMediaSettings(
            updateMediaSettings(DefaultSettings.allMediaSettings(), MediaType.MANGA) {
                it.copy(showImages = true, stickyScore = false, oneCoverPerGroup = true)
            },
            MediaType.BOOK
        ) { it.copy(showImages = false, stickyScore = true, oneCoverPerGroup = false) }

        val restored = normalizeMediaSettingsSnapshot(
            decodeMediaSettingsSnapshot(encodeMediaSettingsSnapshot(settings)),
            settings.getValue(MediaType.ANIME)
        )

        assertTrue(restored.getValue(MediaType.MANGA).showImages)
        assertFalse(restored.getValue(MediaType.MANGA).stickyScore)
        assertTrue(restored.getValue(MediaType.MANGA).oneCoverPerGroup)
        assertFalse(restored.getValue(MediaType.BOOK).showImages)
        assertTrue(restored.getValue(MediaType.BOOK).stickyScore)
        assertFalse(restored.getValue(MediaType.BOOK).oneCoverPerGroup)
    }

    @Test
    fun showImagesChoicesRoundTripIndependentlyForEveryOfficialPack() {
        val expected = MediaType.entries.associateWith { mediaType ->
            DefaultSettings.mediaSettings(mediaType).copy(
                showImages = mediaType in setOf(MediaType.ANIME, MediaType.MOVIE, MediaType.BOOK)
            )
        }

        val restored = normalizeMediaSettingsSnapshot(
            decodeMediaSettingsSnapshot(encodeMediaSettingsSnapshot(expected)),
            expected.getValue(MediaType.ANIME)
        )

        MediaType.entries.forEach { mediaType ->
            assertEquals(expected.getValue(mediaType).showImages, restored.getValue(mediaType).showImages)
        }
        assertTrue(DefaultSettings.mediaSettings(MediaType.SERIES).oneCoverPerGroup)
    }

    @Test
    fun oldSnapshotUsesLegacyGlobalDisplayPreferences() {
        val oldJson = mediaJson(DefaultSettings.mediaSettings(MediaType.MANGA))
            .replace(Regex(",?\\\"showImages\\\":(true|false)"), "")
            .replace(Regex(",?\\\"stickyScore\\\":(true|false)"), "")
        val decoded = decodeMediaSettingsSnapshot(
            """{"media":{"mangas":$oldJson}}""",
            legacyShowImages = true,
            legacyStickyScore = false
        )
        val restored = normalizeMediaSettingsSnapshot(
            decoded,
            DefaultSettings.mediaSettings(MediaType.ANIME),
            legacyShowImages = true,
            legacyStickyScore = false
        )

        MediaType.entries.forEach { mediaType ->
            assertTrue(restored.getValue(mediaType).showImages)
            assertFalse(restored.getValue(mediaType).stickyScore)
        }
    }

    @Test
    fun partialSnapshotFillsMissingMediaAndIgnoresUnknownKeys() {
        val manga = DefaultSettings.mediaSettings(MediaType.MANGA).copy(mergeEnabled = true)
        val encoded = """{"media":{"manga":${mediaJson(manga)},"future":${mediaJson(manga)}}}"""

        val decoded = decodeMediaSettingsSnapshot(encoded)
        val normalized = normalizeMediaSettingsSnapshot(
            decoded,
            DefaultSettings.mediaSettings(MediaType.ANIME)
        )

        assertTrue(normalized.getValue(MediaType.MANGA).mergeEnabled)
        assertEquals(MediaType.entries.toSet(), normalized.keys)
        assertEquals(DefaultSettings.mediaSettings(MediaType.BOOK), normalized.getValue(MediaType.BOOK))
    }

    @Test
    fun malformedSnapshotAndMalformedEntryRecoverSafely() {
        val legacyAnime = DefaultSettings.mediaSettings(MediaType.ANIME).copy(mergeEnabled = true)
        assertEquals(
            legacyAnime,
            normalizeMediaSettingsSnapshot(decodeMediaSettingsSnapshot("not json"), legacyAnime)
                .getValue(MediaType.ANIME)
        )

        val validBook = DefaultSettings.mediaSettings(MediaType.BOOK).copy(elaborateRatingEnabled = true)
        val partiallyMalformed = """{"media":{"anime":{"broken":true},"book":${mediaJson(validBook)}}}"""
        val normalized = normalizeMediaSettingsSnapshot(
            decodeMediaSettingsSnapshot(partiallyMalformed),
            legacyAnime
        )
        assertEquals(legacyAnime, normalized.getValue(MediaType.ANIME))
        assertTrue(normalized.getValue(MediaType.BOOK).elaborateRatingEnabled)
    }

    @Test
    fun snapshotUsesStablePersistedMediaKeysAndRoundTrips() {
        val settings = DefaultSettings.allMediaSettings() + (
            MediaType.MOVIE to DefaultSettings.mediaSettings(MediaType.MOVIE)
                .copy(oneCoverPerGroup = true)
        )
        val encoded = encodeMediaSettingsSnapshot(settings)

        MediaType.entries.forEach { assertTrue(encoded.contains("\"${it.toPackId().value}\"")) }
        val normalized = normalizeMediaSettingsSnapshot(
            decodeMediaSettingsSnapshot(encoded),
            settings.getValue(MediaType.ANIME)
        )
        assertEquals(settings, normalized)
        assertTrue(normalized.getValue(MediaType.MOVIE).oneCoverPerGroup)
        assertFalse(normalized.getValue(MediaType.ANIME).oneCoverPerGroup)
    }

    @Test
    fun allDefaultsHaveValidIndependentCategoriesAndProfiles() {
        MediaType.entries.forEach { mediaType ->
            val first = DefaultSettings.mediaSettings(mediaType)
            val second = DefaultSettings.mediaSettings(mediaType)
            val categoryIds = first.ratingConfig.categories.map { it.id }

            assertEquals(categoryIds.size, categoryIds.toSet().size)
            assertEquals(6, first.ratingProfiles.profiles.size)
            first.ratingProfiles.profiles.forEach { profile ->
                assertEquals(categoryIds.toSet(), profile.weights.keys)
                assertTrue(profile.weights.values.all { it.isFinite() && it in 0.0..100.0 })
                assertTrue(profile.weights.values.any { it > 0.0 })
                assertEquals(100.0, profile.weights.values.sum(), 0.0001)
            }
            assertEquals(first, second)
            assertNotSame(first.ratingConfig.categories, second.ratingConfig.categories)
        }
    }

    @Test
    fun unknownJsonFieldsAreIgnored() {
        val animeJson = mediaJson(DefaultSettings.mediaSettings(MediaType.ANIME))
        val withUnknownField = animeJson.dropLast(1) + ",\"futureField\":42}"
        val decoded = decodeMediaSettingsSnapshot("""{"media":{"anime":$withUnknownField}}""")

        assertEquals(
            DefaultSettings.mediaSettings(MediaType.ANIME),
            normalizeMediaSettingsSnapshot(
                decoded,
                DefaultSettings.mediaSettings(MediaType.ANIME)
            ).getValue(MediaType.ANIME)
        )
    }

    @Test
    fun categoryDefaultsHaveTheRequestedStableIdsAndWeights() {
        val expected = mapOf(
            MediaType.ANIME to linkedMapOf(
                "writing" to 35.0, "characters" to 20.0, "visuals" to 20.0,
                "worldbuilding" to 15.0, "audio" to 10.0
            ),
            MediaType.MANGA to linkedMapOf(
                "writing" to 30.0, "characters" to 20.0, "art" to 25.0,
                "paneling" to 10.0, "worldbuilding" to 15.0
            ),
            MediaType.SERIES to linkedMapOf(
                "writing" to 25.0, "characters" to 20.0, "direction" to 15.0,
                "visuals" to 15.0, "worldbuilding" to 15.0, "audio" to 10.0
            ),
            MediaType.MOVIE to linkedMapOf(
                "writing" to 20.0, "characters" to 15.0, "direction" to 20.0,
                "visuals" to 20.0, "editing" to 15.0, "audio" to 10.0
            ),
            MediaType.BOOK to linkedMapOf(
                "writing" to 30.0, "characters" to 20.0, "prose" to 25.0,
                "worldbuilding" to 15.0, "structure" to 10.0
            )
        )

        expected.forEach { (mediaType, categories) ->
            val actual = DefaultSettings.ratingConfig(mediaType).categories
            assertEquals(categories.keys.toList(), actual.map { it.id })
            assertEquals(categories.values.toList(), actual.map { it.weight })
            assertEquals(categories.keys.indices.toList(), actual.map { it.order })
        }
    }

    @Test
    fun everyPredefinedProfileHasTheRequestedExactWeights() {
        val expected = mapOf(
            MediaType.MANGA to listOf(
                profile("general", 30, 20, 25, 10, 15),
                profile("narrative", 45, 25, 10, 10, 10),
                profile("character_driven", 25, 40, 15, 10, 10),
                profile("art_focused", 15, 15, 40, 20, 10),
                profile("world_adventure", 20, 20, 15, 10, 35),
                profile("atmospheric", 25, 15, 30, 15, 15)
            ),
            MediaType.BOOK to listOf(
                profile("general", 30, 20, 25, 15, 10),
                profile("narrative", 45, 20, 15, 10, 10),
                profile("character_driven", 25, 40, 15, 10, 10),
                profile("prose_style", 20, 10, 45, 10, 15),
                profile("world_adventure", 20, 15, 15, 40, 10),
                profile("atmospheric", 20, 15, 35, 20, 10)
            ),
            MediaType.SERIES to listOf(
                profile("general", 25, 20, 15, 15, 15, 10),
                profile("narrative", 40, 25, 10, 5, 10, 10),
                profile("character_driven", 20, 40, 10, 10, 10, 10),
                profile("visual_direction", 10, 10, 30, 30, 10, 10),
                profile("world_adventure", 20, 15, 10, 10, 35, 10),
                profile("atmospheric", 15, 15, 20, 20, 10, 20)
            ),
            MediaType.MOVIE to listOf(
                profile("general", 20, 15, 20, 20, 15, 10),
                profile("narrative", 40, 25, 15, 5, 10, 5),
                profile("character_driven", 25, 40, 15, 5, 10, 5),
                profile("visual_direction", 10, 10, 30, 30, 10, 10),
                profile("technical_craft", 10, 10, 20, 20, 25, 15),
                profile("atmospheric", 15, 10, 20, 20, 10, 25)
            )
        )

        expected.forEach { (mediaType, profiles) ->
            val actual = DefaultSettings.profiles(mediaType).profiles
            assertEquals(profiles.map { it.first }, actual.map { it.id })
            profiles.zip(actual).forEach { (expectedProfile, actualProfile) ->
                assertEquals(expectedProfile.second, actualProfile.weights.values.toList())
            }
        }
    }

    @Test
    fun legacyMirrorContainsExactlyTheCurrentAnimeSettings() {
        val anime = DefaultSettings.mediaSettings(MediaType.ANIME).copy(
            elaborateRatingEnabled = true,
            mergeEnabled = true
        )
        val mirror = anime.toLegacyAnimeSettings()

        assertEquals(anime.elaborateRatingEnabled, mirror.elaborateRatingEnabled)
        assertEquals(anime.ratingConfig, mirror.ratingConfig)
        assertEquals(anime.ratingProfiles, mirror.ratingProfiles)
        assertEquals(anime.mergeEnabled, mirror.mergeEnabled)
    }

    private fun category(id: String) = RatingCategory(id, id, "Description", 100.0, "#123456", 0)

    private fun profile(id: String, vararg weights: Int) =
        id to weights.map(Int::toDouble)

    private fun mediaJson(settings: MediaSettings): String = encodeMediaSettingsSnapshot(
        mapOf(MediaType.ANIME to settings)
    ).substringAfter("\"${MediaType.ANIME.toPackId().value}\":").dropLast(2)
}
