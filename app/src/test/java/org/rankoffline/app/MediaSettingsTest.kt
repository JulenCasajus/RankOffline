package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSettingsTest {
    @Test
    fun newOfficialDefaultsEnableStickyScoresAndSeriesMergeOnly() {
        MediaType.entries.forEach { mediaType ->
            assertTrue(DefaultSettings.mediaSettings(mediaType).stickyScore)
        }
        assertTrue(DefaultSettings.mediaSettings(MediaType.SERIES).oneCoverPerGroup)
        assertTrue(DefaultSettings.mediaSettings(MediaType.SERIES).mergeEnabled)
        MediaType.entries.filterNot { it == MediaType.SERIES }.forEach { mediaType ->
            assertFalse(DefaultSettings.mediaSettings(mediaType).mergeEnabled)
        }
    }

    @Test
    fun welcomeImageChoiceKeepsBooksConservativeDefault() {
        assertFalse(onboardingShowImagesDefault(MediaType.BOOK, requested = true))
        MediaType.entries.filterNot { it == MediaType.BOOK }.forEach { mediaType ->
            assertTrue(onboardingShowImagesDefault(mediaType, requested = true))
        }
        assertTrue(MediaType.entries.none { onboardingShowImagesDefault(it, requested = false) })
    }
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
        val expectedProfileCounts = mapOf(
            MediaType.ANIME to 6,
            MediaType.MANGA to 5,
            MediaType.MOVIE to 5,
            MediaType.SERIES to 6,
            MediaType.BOOK to 6
        )
        MediaType.entries.forEach { mediaType ->
            val first = DefaultSettings.mediaSettings(mediaType)
            val second = DefaultSettings.mediaSettings(mediaType)
            val categoryIds = first.ratingConfig.categories.map { it.id }

            assertEquals(categoryIds.size, categoryIds.toSet().size)
            assertEquals(expectedProfileCounts.getValue(mediaType), first.ratingProfiles.profiles.size)
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
                "writing" to 35.0, "characters" to 25.0, "art" to 25.0,
                "worldbuilding" to 15.0
            ),
            MediaType.SERIES to linkedMapOf(
                "writing" to 35.0, "characters" to 25.0, "visuals" to 10.0,
                "worldbuilding" to 20.0, "audio" to 10.0
            ),
            MediaType.MOVIE to linkedMapOf(
                "writing" to 35.0, "characters" to 25.0, "visuals" to 20.0,
                "staging_immersion" to 10.0, "audio" to 10.0
            ),
            MediaType.BOOK to linkedMapOf(
                "writing" to 35.0, "characters" to 25.0, "prose" to 15.0,
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
                profile("general", 35, 25, 25, 15),
                profile("narrative", 50, 25, 15, 10),
                profile("character_driven", 25, 50, 15, 10),
                profile("art_focused", 20, 20, 50, 10),
                profile("world_adventure", 25, 20, 15, 40)
            ),
            MediaType.BOOK to listOf(
                profile("general", 35, 25, 15, 15, 10),
                profile("narrative", 45, 20, 15, 10, 10),
                profile("character_driven", 25, 40, 15, 10, 10),
                profile("prose_style", 20, 10, 45, 10, 15),
                profile("world_adventure", 20, 15, 15, 40, 10),
                profile("atmospheric", 20, 20, 20, 20, 20)
            ),
            MediaType.SERIES to listOf(
                profile("general", 35, 25, 10, 20, 10),
                profile("narrative", 50, 15, 10, 15, 10),
                profile("character_driven", 20, 50, 10, 10, 10),
                profile("visual_direction", 20, 15, 40, 10, 15),
                profile("world_adventure", 20, 20, 10, 40, 10),
                profile("atmospheric", 20, 20, 25, 10, 25)
            ),
            MediaType.MOVIE to listOf(
                profile("general", 35, 25, 20, 10, 10),
                profile("narrative", 40, 20, 10, 20, 10),
                profile("character_driven", 25, 40, 10, 15, 10),
                profile("visual_direction", 15, 15, 40, 15, 15),
                profile("atmospheric", 15, 15, 25, 20, 25)
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
    fun categoryColorsDescriptionsAndProfileNamesMatchTheNewPresets() {
        fun category(mediaType: MediaType, id: String) =
            DefaultSettings.ratingConfig(mediaType).categories.first { it.id == id }

        assertEquals("#E04BCF", category(MediaType.MANGA, "art").color)
        assertEquals(
            "Drawing quality, art style, character design, backgrounds, panel composition, flow, visual clarity and storytelling.",
            category(MediaType.MANGA, "art").description
        )
        assertEquals("#E04BCF", category(MediaType.BOOK, "prose").color)
        assertEquals("#EF5350", category(MediaType.BOOK, "structure").color)
        assertEquals("#81C784", category(MediaType.MOVIE, "staging_immersion").color)
        assertEquals(
            "Casting choices, performances, staging, direction, emotional impact and the film's ability to immerse and engage the viewer.",
            category(MediaType.MOVIE, "staging_immersion").description
        )
        assertEquals(
            "Character writing, development, dynamics and charm.",
            category(MediaType.MOVIE, "characters").description
        )
        assertEquals(
            "Soundtrack, sound effects and audiovisual integration.",
            category(MediaType.MOVIE, "audio").description
        )
        val expectedColors = mapOf(
            MediaType.ANIME to listOf("#FFAA55", "#4FC3F7", "#E04BCF", "#81C784", "#EF5350"),
            MediaType.MANGA to listOf("#FFAA55", "#4FC3F7", "#E04BCF", "#81C784"),
            MediaType.MOVIE to listOf("#FFAA55", "#4FC3F7", "#E04BCF", "#81C784", "#EF5350"),
            MediaType.SERIES to listOf("#FFAA55", "#4FC3F7", "#E04BCF", "#81C784", "#EF5350"),
            MediaType.BOOK to listOf("#FFAA55", "#4FC3F7", "#E04BCF", "#81C784", "#EF5350")
        )
        expectedColors.forEach { (mediaType, colors) ->
            assertEquals(colors, DefaultSettings.ratingConfig(mediaType).categories.map { it.color })
        }
        val expectedDescriptions = mapOf(
            MediaType.ANIME to listOf(
                "Plot, themes, pacing, highlights, beginning, ending",
                "Main characters, supporting characters, antagonist, development, depth, charisma, dynamics",
                "Animation, art style, cinematography, backgrounds, effects, character design",
                "Setting, lore, power system",
                "OST, openings/endings, sound effects"
            ),
            MediaType.MANGA to listOf(
                "Plot, themes, pacing, structure, dialogue, beginning and ending.",
                "Main and supporting characters, development, depth, charisma and dynamics.",
                "Drawing quality, art style, character design, backgrounds, panel composition, flow, visual clarity and storytelling.",
                "Setting, lore, internal rules, systems and sense of place."
            ),
            MediaType.MOVIE to listOf(
                "Plot, themes, dialogue, narrative construction and payoff.",
                "Character writing, development, dynamics and charm.",
                "Cinematography, production design, effects, lighting and visual identity.",
                "Casting choices, performances, staging, direction, emotional impact and the film's ability to immerse and engage the viewer.",
                "Soundtrack, sound effects and audiovisual integration."
            ),
            MediaType.SERIES to listOf(
                "Plot, themes, dialogue, pacing, season structure and narrative payoff.",
                "Main and supporting characters, development, depth, charisma and dynamics.",
                "Cinematography, production design, effects, lighting and visual execution.",
                "Setting, lore, internal rules and continuity where applicable.",
                "Music, sound design, mixing and use of audio."
            ),
            MediaType.BOOK to listOf(
                "Plot, themes, ideas, dialogue, narrative construction and ending.",
                "Main and supporting characters, development, depth and relationships.",
                "Language, voice, readability, imagery and stylistic execution.",
                "Setting, lore, internal coherence and sense of place where applicable.",
                "Narrative rhythm, chapter/section structure, progression and organization."
            )
        )
        expectedDescriptions.forEach { (mediaType, descriptions) ->
            assertEquals(descriptions, DefaultSettings.ratingConfig(mediaType).categories.map { it.description })
        }
        val expectedProfiles = mapOf(
            MediaType.ANIME to listOf(
                "general" to "General", "narrative" to "Narrative",
                "character_driven" to "Character-driven", "visual_action" to "Visual / Action",
                "world_adventure" to "World / Adventure", "atmospheric" to "Atmospheric"
            ),
            MediaType.MANGA to listOf(
                "general" to "General", "narrative" to "Narrative",
                "character_driven" to "Character-driven", "art_focused" to "Art-focused",
                "world_adventure" to "World / Adventure"
            ),
            MediaType.MOVIE to listOf(
                "general" to "General", "narrative" to "Narrative",
                "character_driven" to "Character-driven", "visual_direction" to "Visual",
                "atmospheric" to "Atmospheric"
            ),
            MediaType.SERIES to listOf(
                "general" to "General", "narrative" to "Narrative",
                "character_driven" to "Character-driven", "visual_direction" to "Visual",
                "world_adventure" to "World / Adventure", "atmospheric" to "Atmospheric"
            ),
            MediaType.BOOK to listOf(
                "general" to "General", "narrative" to "Narrative",
                "character_driven" to "Character-driven", "prose_style" to "Prose / Style",
                "world_adventure" to "World / Adventure", "atmospheric" to "Atmospheric"
            )
        )
        expectedProfiles.forEach { (mediaType, expected) ->
            assertEquals(
                expected,
                DefaultSettings.profiles(mediaType).profiles.map { it.id to it.name }
            )
        }
    }

    @Test
    fun everyGeneralProfileMatchesItsCategoryDefaultWeights() {
        MediaType.entries.forEach { mediaType ->
            val settings = DefaultSettings.mediaSettings(mediaType)
            val categoryWeights = settings.ratingConfig.categories.associate { it.id to it.weight }
            val general = settings.ratingProfiles.resolveProfile(DefaultSettings.GENERAL_PROFILE_ID)

            assertEquals(categoryWeights, general.weights)
        }
    }

    @Test
    fun untouchedPreviousFactoryPresetsMigrateExactlyOnceWithoutChangingOtherSettings() {
        MediaType.entries.filterNot { it == MediaType.ANIME }.forEach { mediaType ->
            val previous = DefaultSettings.mediaSettings(mediaType).copy(
                elaborateRatingEnabled = true,
                ratingConfig = requireNotNull(PreviousRatingDefaults.config(mediaType)),
                ratingProfiles = requireNotNull(PreviousRatingDefaults.profiles(mediaType)),
                showImages = true,
                stickyScore = false
            )

            val migrated = migrateUntouchedPreviousRatingDefaults(mediaType, previous)

            assertEquals(DefaultSettings.ratingConfig(mediaType), migrated.ratingConfig)
            assertEquals(DefaultSettings.profiles(mediaType), migrated.ratingProfiles)
            assertTrue(migrated.elaborateRatingEnabled)
            assertTrue(migrated.showImages)
            assertFalse(migrated.stickyScore)
            assertEquals(migrated, migrateUntouchedPreviousRatingDefaults(mediaType, migrated))
        }
    }

    @Test
    fun persistedPreviousFactorySnapshotMigratesPerPackThroughTheRealDecoder() {
        val previous = MediaType.entries.associateWith { mediaType ->
            if (mediaType == MediaType.ANIME) {
                DefaultSettings.mediaSettings(mediaType)
            } else {
                DefaultSettings.mediaSettings(mediaType).copy(
                    ratingConfig = requireNotNull(PreviousRatingDefaults.config(mediaType)),
                    ratingProfiles = requireNotNull(PreviousRatingDefaults.profiles(mediaType))
                )
            }
        }

        val restored = normalizeMediaSettingsSnapshot(
            decodeMediaSettingsSnapshot(encodeMediaSettingsSnapshot(previous)),
            previous.getValue(MediaType.ANIME)
        )

        assertEquals(DefaultSettings.allMediaSettings(), restored)
    }

    @Test
    fun anyCategoryOrProfileCustomizationPreventsAutomaticPresetMigration() {
        val mediaType = MediaType.MOVIE
        val previous = DefaultSettings.mediaSettings(mediaType).copy(
            ratingConfig = requireNotNull(PreviousRatingDefaults.config(mediaType)),
            ratingProfiles = requireNotNull(PreviousRatingDefaults.profiles(mediaType))
        )
        val customCategory = previous.copy(
            ratingConfig = previous.ratingConfig.copy(
                categories = previous.ratingConfig.categories.map {
                    if (it.id == "visuals") it.copy(color = "#123456") else it
                }
            )
        )
        val customProfile = previous.copy(
            ratingProfiles = previous.ratingProfiles.copy(
                profiles = previous.ratingProfiles.profiles.map {
                    if (it.id == "general") it.copy(weights = it.weights + ("writing" to 21.0)) else it
                }
            )
        )

        assertEquals(customCategory, migrateUntouchedPreviousRatingDefaults(mediaType, customCategory))
        assertEquals(customProfile, migrateUntouchedPreviousRatingDefaults(mediaType, customProfile))
    }

    @Test
    fun removedFactoryProfileIdsFallBackWithoutChangingAnime() {
        assertEquals(
            DefaultSettings.mediaSettings(MediaType.ANIME),
            migrateUntouchedPreviousRatingDefaults(
                MediaType.ANIME,
                DefaultSettings.mediaSettings(MediaType.ANIME)
            )
        )
        assertEquals(
            DefaultSettings.GENERAL_PROFILE_ID,
            DefaultSettings.profiles(MediaType.MOVIE).resolveProfile("technical_craft").id
        )
        assertEquals(
            DefaultSettings.GENERAL_PROFILE_ID,
            DefaultSettings.profiles(MediaType.MANGA).resolveProfile("atmospheric").id
        )
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
