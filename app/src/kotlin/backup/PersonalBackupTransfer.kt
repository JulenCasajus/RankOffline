package org.rankoffline.app

data class PersonalBackupPreview(
    val backup: PersonalDataBackup,
    val ratingCount: Int,
    val groupRatingCount: Int,
    val packIds: Set<Long>,
    val missingPackIds: Set<Long>
)

enum class PersonalDataTransferOperation { IDLE, EXPORTING, VALIDATING_IMPORT, IMPORTING, SHARING }
data class PersonalDataTransferState(
    val operation: PersonalDataTransferOperation = PersonalDataTransferOperation.IDLE
) { val active: Boolean get() = operation != PersonalDataTransferOperation.IDLE }

internal class PersonalDataTransferCoordinator {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(PersonalDataTransferState())
    val state: kotlinx.coroutines.flow.StateFlow<PersonalDataTransferState> = _state

    suspend fun run(operation: PersonalDataTransferOperation, block: suspend () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        _state.value = PersonalDataTransferState(operation)
        return try { block(); true } finally {
            _state.value = PersonalDataTransferState()
            mutex.unlock()
        }
    }
}

