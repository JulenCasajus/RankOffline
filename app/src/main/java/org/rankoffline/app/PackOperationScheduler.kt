package org.rankoffline.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/** Gives archive transfers network priority without coupling them to local Room imports. */
class PackOperationScheduler {
    private val lock = Any()
    private val activeArchiveTransfers = MutableStateFlow(0)

    fun archiveTransferStarted() = synchronized(lock) {
        activeArchiveTransfers.value = activeArchiveTransfers.value + 1
    }

    fun archiveTransferFinished() = synchronized(lock) {
        activeArchiveTransfers.value = (activeArchiveTransfers.value - 1).coerceAtLeast(0)
    }

    suspend fun awaitCoverNetworkPermit() {
        activeArchiveTransfers.filter { it == 0 }.first()
    }
}
