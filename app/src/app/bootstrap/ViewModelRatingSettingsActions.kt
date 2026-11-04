package org.rankoffline.app


fun RankOfflineViewModel.updateRatingCategory(value: RatingCategory) = preferences.ratingCategory(value)


fun RankOfflineViewModel.createRatingCategory(value: RatingCategory) = preferences.addRatingCategory(value)


fun RankOfflineViewModel.deleteRatingCategory(value: RatingCategory) = preferences.removeRatingCategory(value)


fun RankOfflineViewModel.updateRatingProfiles(value: RatingProfilesConfig) = preferences.ratingProfiles(value)


fun RankOfflineViewModel.deleteRatingProfile(id: String) = preferences.removeProfile(id)


fun RankOfflineViewModel.resetRatingCategories() = preferences.resetCategories()


fun RankOfflineViewModel.resetRatingProfiles() = preferences.resetProfiles()
