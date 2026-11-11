package org.rankoffline.app


fun RankOfflineViewModel.completeWelcome(showImages: Boolean, elaborateRating: Boolean) = preferences.welcome(showImages, elaborateRating)


fun RankOfflineViewModel.completeOnboarding() = preferences.onboardingComplete()

fun RankOfflineViewModel.completeWhatsNew() = preferences.whatsNewComplete()


fun RankOfflineViewModel.updateShowImages(value: Boolean) = preferences.showImages(value, viewingScope.value == ViewingScope.All)

fun RankOfflineViewModel.updateTitlesOnPodium(value: Boolean) =
    preferences.titlesOnPodium(value, viewingScope.value == ViewingScope.All)


fun RankOfflineViewModel.updateStickyScore(value: Boolean) = preferences.stickyScore(value, viewingScope.value == ViewingScope.All)


fun RankOfflineViewModel.updateMergeEnabled(value: Boolean) = preferences.merge(value, viewingScope.value == ViewingScope.All)




fun RankOfflineViewModel.updateElaborateRatingEnabled(value: Boolean) = preferences.elaborateRating(value)

fun RankOfflineViewModel.updatePersonalTasteEnabled(value: Boolean) =
    preferences.personalTaste(value).also { job ->
    val id = settings.value.activePackId
    if (!value) job.invokeOnCompletion { failure ->
        if (failure == null) {
            if (id != null) catalog.rankingByPack.value[id]?.takeIf {
                it.sortOrder == RankingSortOrder.PERSONAL_TASTE
            }?.let { state ->
                catalog.rankingByPack.value = catalog.rankingByPack.value +
                    (id to state.copy(sortOrder = RankingSortOrder.FINAL_SCORE))
            }
            if (settings.value.mediaSettings.values.none { it.personalTasteEnabled } &&
                catalog.allRanking.value.sortOrder == RankingSortOrder.PERSONAL_TASTE) {
                catalog.allRanking.value = catalog.allRanking.value.copy(
                    sortOrder = RankingSortOrder.FINAL_SCORE)
            }
        }
    }
}
