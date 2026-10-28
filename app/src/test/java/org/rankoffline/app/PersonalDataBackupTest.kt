package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonalDataBackupTest {
    @Test
    fun personalDataBackupRoundTripsWithoutCatalogOrCoverPayloads() {
        val backup = fixture()

        val encoded = encodePersonalDataBackup(backup)
        val decoded = decodePersonalDataBackup(encoded)

        assertEquals(backup, decoded)
        assert(!encoded.contains("localCoverPath"))
        assert(!encoded.contains("catalog/"))
    }

    @Test
    fun sourceIsExplicitAndBackupsCreatedBeforeSourceFieldRemainReadable() {
        val encoded = encodePersonalDataBackup(fixture())
        assert(encoded.contains("\"source\":\"rankoffline\""))
        val legacy = encoded.replace("\"source\":\"rankoffline\",", "")
        assertEquals(PERSONAL_BACKUP_SOURCE, decodePersonalDataBackup(legacy).source)
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(fixture().copy(source = "unknown"))
        }
    }

    @Test
    fun invalidPackIdentityAndUnknownFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(
                fixture().copy(ratings = fixture().ratings.map { it.copy(packId = "../escape") })
            )
        }
        val encoded = encodePersonalDataBackup(fixture()).dropLast(1) + ",\"unexpected\":true}"
        assertThrows(Exception::class.java) { decodePersonalDataBackup(encoded) }
    }

    @Test
    fun exportAndImportShareTheExactSameSizeBoundary() {
        requireValidBackupSize(MAX_BACKUP_BYTES - 1)
        requireValidBackupSize(MAX_BACKUP_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            requireValidBackupSize(MAX_BACKUP_BYTES + 1)
        }
    }

    @Test
    fun invalidScoresWeightsAndDuplicateThemesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(
                fixture().copy(
                    ratings = fixture().ratings.map { it.copy(finalScore = 10.1) }
                )
            )
        }
        val invalidSettings = DefaultSettings.mediaSettings(MediaType.ANIME).let { settings ->
            settings.copy(
                ratingProfiles = settings.ratingProfiles.copy(
                    profiles = settings.ratingProfiles.profiles.mapIndexed { index, profile ->
                        if (index == 0) profile.copy(weights = profile.weights + ("unknown" to 10.0))
                        else profile
                    }
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(
                fixture().copy(mediaSettings = mapOf(OfficialPackIds.Anime.value to invalidSettings))
            )
        }
        val duplicate = BackupPalette(
            "duplicate",
            "Duplicate",
            0xFF000000,
            0xFF111111,
            0xFFFFFFFF,
            0xFF000000
        )
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(
                fixture().copy(themes = BackupThemeWorkspace(custom = listOf(duplicate, duplicate)))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(
                fixture().copy(
                    ratings = fixture().ratings.map { rating ->
                        rating.copy(
                            categoryScores = listOf(
                                BackupCategoryScore("writing", score = null, applicable = true)
                            )
                        )
                    }
                )
            )
        }
    }

    @Test
    fun scopedBackupRequiresEveryValueToBelongToItsDeclaredPack() {
        val scoped = fixture().copy(
            scopePackIds = setOf(OfficialPackIds.Anime.value),
            globalSettingsIncluded = false
        )
        assertEquals(scoped, decodePersonalDataBackup(encodePersonalDataBackup(scoped)))

        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(
                scoped.copy(
                    ratings = scoped.ratings + scoped.ratings.single().copy(
                        packId = OfficialPackIds.Books.value,
                        canonicalId = "openlibrary:OL1W"
                    )
                )
            )
        }
    }

    private fun fixture() = PersonalDataBackup(
        exportedAt = 123L,
        mediaSettings = mapOf(OfficialPackIds.Anime.value to DefaultSettings.mediaSettings(MediaType.ANIME)),
        initializedPackIds = setOf(OfficialPackIds.Anime.value),
        catalogSortOrders = mapOf(OfficialPackIds.Anime.value to CatalogSortOrder.TITLE_ASC.name),
        allViewSettings = AllViewSettings(catalogSortOrder = CatalogSortOrder.TITLE_DESC),
        themeSelection = AppThemePalette.MIDNIGHT_NEON.persistedValue,
        themes = BackupThemeWorkspace(
            custom = listOf(
                BackupPalette("custom_1", "Custom", 0xFF000000, 0xFF111111, 0xFF00FFFF, 0xFFFF00FF)
            )
        ),
        ratings = listOf(
            BackupRating(
                packId = OfficialPackIds.Anime.value,
                canonicalId = "mal:1",
                additiveScore = 0.5,
                qualityScore = 8.0,
                finalScore = 8.5,
                notes = "note",
                status = "completed",
                updatedAt = 100L,
                profileId = "general",
                categoryScores = listOf(BackupCategoryScore("writing", 8.0, true))
            )
        ),
        groupRatings = emptyList()
    )
}
