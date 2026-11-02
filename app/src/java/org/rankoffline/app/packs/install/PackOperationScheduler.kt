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
        val waitingArchives: Int = 0,
        val archiveActive: Boolean = false,
        val activeCovers: Int = 0
    )

    private val monitor = Any()
    private val state = MutableStateFlow(State())

    /**
     * Reserves network priority for an archive for the full duration of [transfer].
     * Cancellation, success and failure always release the reservation.
     */
    suspend fun <T> withArchiveTransfer(transfer: suspend () -> T): T {
        update { it.copy(waitingArchives = it.waitingArchives + 1) }
        var acquired = false
        try {
            while (!acquired) {
                synchronized(monitor) {
                    val current = state.value
                    if (!current.archiveActive && current.activeCovers == 0) {
                        state.value = current.copy(
                            waitingArchives = current.waitingArchives - 1,
                            archiveActive = true
                        )
                        acquired = true
                    }
                }
                if (!acquired) {
                    state.first { !it.archiveActive && it.activeCovers == 0 }
                }
            }
            return transfer()
        } finally {
            update { current ->
                if (acquired) {
                    check(current.archiveActive)
                    current.copy(archiveActive = false)
                } else {
                    check(current.waitingArchives > 0)
                    current.copy(waitingArchives = current.waitingArchives - 1)
                }
            }
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
                        current.waitingArchives == 0 &&
                        current.activeCovers < maxConcurrentCovers
                    ) {
                        state.value = current.copy(activeCovers = current.activeCovers + 1)
                        acquired = true
                    }
                }
                if (!acquired) {
                    state.first {
                        !it.archiveActive &&
                            it.waitingArchives == 0 &&
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
}
