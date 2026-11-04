package org.rankoffline.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalBackupContractTest {
    @Test fun schemaTwoRoundTripContainsNoCatalogOrCoverPayload() {
        val backup = TestFixtures.backup()
        val encoded = encodePersonalDataBackup(backup)
        assertEquals(backup, decodePersonalDataBackup(encoded))
        assertFalse(encoded.contains("localCoverPath"))
        assertFalse(encoded.contains("catalog/"))
    }

    @Test fun schemaSourceAndUnknownFieldsAreStrict() {
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(TestFixtures.backup().copy(schemaVersion = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(TestFixtures.backup().copy(source = "other"))
        }
        val extra = encodePersonalDataBackup(TestFixtures.backup()).dropLast(1) + ",\"extra\":true}"
        assertThrows(Exception::class.java) { decodePersonalDataBackup(extra) }
    }

    @Test fun scopedBackupCannotContainAnotherPackOrGlobalSettings() {
        val scoped = TestFixtures.backup(11L)
        assertEquals(scoped, decodePersonalDataBackup(encodePersonalDataBackup(scoped)))
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(scoped.copy(ratings = scoped.ratings.map { it.copy(packId = 12L) }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(scoped.copy(global = TestFixtures.backup().global))
        }
    }

    @Test fun ratingsForUninstalledPositivePackRemainValid() {
        val detached = TestFixtures.backup(999L)
        assertEquals(999L, decodePersonalDataBackup(encodePersonalDataBackup(detached)).ratings.single().packId)
    }

    @Test fun invalidScoresDuplicateIdentitiesAndInconsistentApplicabilityAreRejected() {
        val backup = TestFixtures.backup()
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(backup.copy(ratings = backup.ratings.map { it.copy(finalScore = 10.1) }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(backup.copy(ratings = backup.ratings + backup.ratings.single()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodePersonalDataBackup(backup.copy(ratings = backup.ratings.map { rating ->
                rating.copy(categoryScores = listOf(BackupCategoryScore("writing", null, true)))
            }))
        }
    }

    @Test fun transferCoordinatorRejectsOverlapAndReturnsToIdle() = runBlocking {
        val coordinator = PersonalDataTransferCoordinator()
        val first = async {
            coordinator.run(PersonalDataTransferOperation.EXPORTING) { delay(50) }
        }
        delay(5)
        assertFalse(coordinator.run(PersonalDataTransferOperation.IMPORTING) {})
        assertTrue(first.await())
        assertFalse(coordinator.state.value.active)
    }

    @Test fun transferCoordinatorReleasesGateAfterCancellation() = runBlocking {
        val coordinator = PersonalDataTransferCoordinator()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator.run(PersonalDataTransferOperation.IMPORTING) {
                    throw CancellationException("cancel")
                }
            }
        }
        assertTrue(coordinator.run(PersonalDataTransferOperation.EXPORTING) {})
    }
}
