package org.rankoffline.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalDataTransferCoordinatorTest {
    @Test
    fun overlappingOperationsAreRejectedAndStateAlwaysReturnsToIdle() = runBlocking {
        val coordinator = PersonalDataTransferCoordinator()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            coordinator.run(PersonalDataTransferOperation.IMPORTING) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        assertEquals(PersonalDataTransferOperation.IMPORTING, coordinator.state.value.operation)

        assertFalse(coordinator.run(PersonalDataTransferOperation.EXPORTING) {})
        release.complete(Unit)
        assertTrue(first.await())
        assertEquals(PersonalDataTransferOperation.IDLE, coordinator.state.value.operation)
    }

    @Test
    fun cancellationReleasesTheOperationGate() = runBlocking {
        val coordinator = PersonalDataTransferCoordinator()
        var propagated = false
        try {
            coordinator.run(PersonalDataTransferOperation.IMPORTING) {
                throw CancellationException("cancelled")
            }
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertEquals(PersonalDataTransferOperation.IDLE, coordinator.state.value.operation)
        assertTrue(coordinator.run(PersonalDataTransferOperation.EXPORTING) {})
    }
}
