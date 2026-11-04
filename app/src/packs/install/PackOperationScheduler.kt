package org.rankoffline.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * In-process network coordinator for pack archives and cover images.
 *
 * Archives have priority over *new* cover transfers. When an archive is queued,
 * no new cover transfer starts; the archive begins after running covers finish.
 * Calls must wrap the actual network operation, not just a preflight check.
 *
 * This is not a persistent download queue. WorkManager owns durable scheduling,
 * and callers must share this instance (e.g. via the application service graph).
 */
internal class PackOperationScheduler(
    private val maxConcurrentCovers: Int = 2
) {
    init {
        require(maxConcurrentCovers > 0) { "maxConcurrentCovers must be positive" }
    }

    private data class State(
        val archiveQueue: List<Long> = emptyList(),
        val archiveActive: Boolean = false,
        val activeCovers: Int = 0,
        val coverBatchQueue: List<Long> = emptyList(),
        val coverBatchActive: Boolean = false
    )

    private val monitor = Any()
    private val state = MutableStateFlow(State())
    private var nextTicket = 0L

    /**
     * Reserves network priority for an archive for the full duration of [transfer].
     * Cancellation, success and failure always release the reservation.
     */
    suspend fun <T> withArchiveTransfer(transfer: suspend () -> T): T {
        val ticket = enqueueArchive()
        var acquired = false
        try {
            while (!acquired) {
                synchronized(monitor) {
                    val current = state.value
                    if (!current.archiveActive && current.activeCovers == 0 &&
                        current.archiveQueue.firstOrNull() == ticket) {
                        state.value = current.copy(
                            archiveQueue = current.archiveQueue.drop(1),
                            archiveActive = true
                        )
                        acquired = true
                    }
                }
                if (!acquired) {
                    state.first { !it.archiveActive && it.activeCovers == 0 &&
                        it.archiveQueue.firstOrNull() == ticket }
                }
            }
            return transfer()
        } finally {
            update { current ->
                if (acquired) {
                    check(current.archiveActive)
                    current.copy(archiveActive = false)
                } else {
                    current.copy(archiveQueue = current.archiveQueue - ticket)
                }
            }
        }
    }

    /** Only one package owns a logical cover batch; archive transfers may pause it between images. */
    suspend fun <T> withCoverBatch(transfer: suspend () -> T): T {
        val ticket = enqueueCoverBatch()
        var acquired = false
        try {
            while (!acquired) {
                synchronized(monitor) {
                    val current = state.value
                    if (!current.coverBatchActive && current.coverBatchQueue.firstOrNull() == ticket) {
                        state.value = current.copy(
                            coverBatchQueue = current.coverBatchQueue.drop(1), coverBatchActive = true)
                        acquired = true
                    }
                }
                if (!acquired) state.first {
                    !it.coverBatchActive && it.coverBatchQueue.firstOrNull() == ticket
                }
            }
            return transfer()
        } finally {
            update { current -> if (acquired) current.copy(coverBatchActive = false)
                else current.copy(coverBatchQueue = current.coverBatchQueue - ticket) }
        }
    }

    /**
     * Limits simultaneous cover requests, and prevents new cover requests while
     * an archive is running or queued. Wrap each individual network request.
     */
    suspend fun <T> withCoverTransfer(transfer: suspend () -> T): T {
        var acquired = false
        try {
            while (!acquired) {
                synchronized(monitor) {
                    val current = state.value
                    if (!current.archiveActive &&
                        current.archiveQueue.isEmpty() &&
                        current.activeCovers < maxConcurrentCovers
                    ) {
                        state.value = current.copy(activeCovers = current.activeCovers + 1)
                        acquired = true
                    }
                }
                if (!acquired) {
                    state.first {
                        !it.archiveActive &&
                            it.archiveQueue.isEmpty() &&
                            it.activeCovers < maxConcurrentCovers
                    }
                }
            }
            return transfer()
        } finally {
            if (acquired) {
                update { current ->
                    check(current.activeCovers > 0)
                    current.copy(activeCovers = current.activeCovers - 1)
                }
            }
        }
    }

    private inline fun update(transform: (State) -> State) {
        synchronized(monitor) {
            state.value = transform(state.value)
        }
    }

    private fun enqueueArchive(): Long = synchronized(monitor) {
        val ticket = nextTicket++
        state.value = state.value.copy(archiveQueue = state.value.archiveQueue + ticket)
        ticket
    }

    private fun enqueueCoverBatch(): Long = synchronized(monitor) {
        val ticket = nextTicket++
        state.value = state.value.copy(coverBatchQueue = state.value.coverBatchQueue + ticket)
        ticket
    }
}
