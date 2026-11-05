package org.rankoffline.app

import androidx.work.Data
import androidx.work.WorkInfo
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackOperationSchedulerTest {
    @Test fun archiveTurnIgnoresEarlierImportEvenForLegacyWork() {
        val importing = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING,
            setOf("rankoffline-pack-action:INSTALL"),
            progress = Data.Builder().putString("phase", PackInstallPhase.IMPORTING.name).build())
        val downloading = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING,
            setOf("rankoffline-pack-action:INSTALL"),
            progress = Data.Builder().putString("phase", PackInstallPhase.DOWNLOADING.name).build())
        val later = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING,
            setOf("rankoffline-pack-action:INSTALL", packInstallSequenceTag(5)),
            progress = Data.Builder().putString("phase", PackInstallPhase.DOWNLOADING.name).build())

        assertFalse(importing.blocksArchiveSequence(4))
        assertTrue(downloading.blocksArchiveSequence(4))
        assertFalse(later.blocksArchiveSequence(4))
    }

    @Test fun archiveTransfersAreFifoAndExclusive() = runBlocking {
        val scheduler = PackOperationScheduler()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        val first = launch {
            scheduler.withArchiveTransfer {
                order += 1
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch { scheduler.withArchiveTransfer { order += 2 } }

        assertEquals(listOf(1), order)
        releaseFirst.complete(Unit)
        withTimeout(2_000) { first.join(); second.join() }
        assertEquals(listOf(1, 2), order)
    }

    @Test fun queuedArchivePreventsAnotherCoverFromStarting() = runBlocking {
        val scheduler = PackOperationScheduler(maxConcurrentCovers = 1)
        val coverEntered = CompletableDeferred<Unit>()
        val releaseCover = CompletableDeferred<Unit>()
        val archiveEntered = CompletableDeferred<Unit>()
        val releaseArchive = CompletableDeferred<Unit>()
        val secondCoverEntered = CompletableDeferred<Unit>()
        val first = launch { scheduler.withCoverTransfer {
            coverEntered.complete(Unit); releaseCover.await()
        } }
        coverEntered.await()
        val archive = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.withArchiveTransfer {
                archiveEntered.complete(Unit)
                releaseArchive.await()
            }
        }
        val second = launch { scheduler.withCoverTransfer { secondCoverEntered.complete(Unit) } }

        releaseCover.complete(Unit)
        withTimeout(2_000) { archiveEntered.await() }
        assertFalse(secondCoverEntered.isCompleted)
        releaseArchive.complete(Unit)
        withTimeout(2_000) { first.join(); archive.join(); second.join() }
        assertTrue(secondCoverEntered.isCompleted)
    }

    @Test fun logicalCoverBatchesDoNotInterleave() = runBlocking {
        val scheduler = PackOperationScheduler()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async { scheduler.withCoverBatch { releaseFirst.await() } }
        val second = async { scheduler.withCoverBatch { secondEntered.complete(Unit) } }

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        withTimeout(2_000) { first.await(); second.await() }
        assertTrue(secondEntered.isCompleted)
    }
}
