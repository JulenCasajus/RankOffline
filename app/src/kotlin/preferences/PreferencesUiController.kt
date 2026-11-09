package org.rankoffline.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** UI commands only. SettingsRepository owns persistence, validation and local pack JSON. */
internal class PreferencesUiController(
    internal val scope: CoroutineScope,
    internal val settings: SettingsRepository,
    internal val ratings: RatingRepository,
    internal val onPackChanged: (PackId) -> Unit,
    internal val onError: suspend (Throwable) -> Unit
) {
    internal fun run(block: suspend () -> Unit) = scope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }

    internal fun changeRating(transform: (RatingSystemConfig) -> RatingSystemConfig) = run {
        val current = settings.state.value
        settings.updateRatingConfig(transform(current.ratingConfig))
        current.activePackId?.let(onPackChanged)
    }
}
