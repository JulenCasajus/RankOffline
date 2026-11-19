package org.rankoffline.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun PackUiController.update(id: PackId) = scope.launch {
    if (states.value[id]?.canUpdate != true) return@launch
    try {
        withContext(Dispatchers.IO) {
            PackWorkScheduler.enqueue(context, id, PackWorkAction.UPDATE)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { onError(failure) }
}
