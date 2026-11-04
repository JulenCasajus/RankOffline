package org.rankoffline.app


fun RankOfflineViewModel.completeWelcome(showImages: Boolean, elaborateRating: Boolean) = preferences.welcome(showImages, elaborateRating)


fun RankOfflineViewModel.completeOnboarding() = preferences.onboardingComplete()


fun RankOfflineViewModel.updateShowImages(value: Boolean) = preferences.showImages(value, viewingScope.value == ViewingScope.All)


fun RankOfflineViewModel.updateStickyScore(value: Boolean) = preferences.stickyScore(value, viewingScope.value == ViewingScope.All)


fun RankOfflineViewModel.updateMergeEnabled(value: Boolean) = preferences.merge(value, viewingScope.value == ViewingScope.All)


fun RankOfflineViewModel.updateUseGroupCoversForItems(value: Boolean) = preferences.useGroupCoversForItems(value)


fun RankOfflineViewModel.updateElaborateRatingEnabled(value: Boolean) = preferences.elaborateRating(value)
