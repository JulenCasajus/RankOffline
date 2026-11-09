package org.rankoffline.app


fun RankOfflineViewModel.openRating(entry: MediaListEntry) = ratingController.open(entry)


fun RankOfflineViewModel.openGroupDetails(key: CatalogEntryKey) = ratingController.openGroup(key)


fun RankOfflineViewModel.openGroupRating(entry: MediaListEntry, fromMembers: Boolean = false) =
    ratingController.open(entry, fromMembers)


fun RankOfflineViewModel.closeGroup() = ratingController.closeGroup()


fun RankOfflineViewModel.closeRating() = ratingController.closeEditor()


fun RankOfflineViewModel.saveSimpleRating(draft: RatingDraft, qualityScore: Double, finalScore: Double) =
    ratingController.saveSimple(draft, qualityScore, finalScore)


fun RankOfflineViewModel.saveElaborateRating(draft: RatingDraft, profileId: String, finalScoreOverride: Double? = null) =
    ratingController.saveElaborate(draft, profileId, finalScoreOverride)


fun RankOfflineViewModel.saveRatingMetadata(notes: String, status: String) =
    ratingController.saveMetadata(notes, status)


fun RankOfflineViewModel.deleteRating() = ratingController.delete()

// Per-pack preferences and globals.
