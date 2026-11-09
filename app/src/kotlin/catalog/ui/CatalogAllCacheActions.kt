package org.rankoffline.app

/** Read a valid aggregate page in the same frame as a return to RankOffline All. */
internal fun CatalogUiController.cachedAllSearchPage(): SearchPageState? {
    val states = packs.states.value.values
    val manifests = states.filter { it.available }.mapNotNull { it.manifest }.sortedBy { it.packId.value }
    val settings = preferences.state.value
    val merges = manifests.associate { it.packId to
        (settings.allViewSettings.mergeEnabled && it.capabilities.merge) }
    val revision = manifests.fold(0L) { token, manifest ->
        token * 31L + (revisions.catalog.value[manifest.packId] ?: 0L)
    }
    val key = CatalogSessionCache.AllSearchKey(manifests, allSearch.value, merges, revision)
    return session.findAllSearch(key)?.page
}

internal fun CatalogUiController.cachedAllRankingPage(): CatalogUiController.RankingPage? {
    val states = packs.states.value.values
    val manifests = states.filter { it.available }.mapNotNull { it.manifest }.sortedBy { it.packId.value }
    val settings = preferences.state.value
    val merges = manifests.associate { it.packId to
        (settings.allViewSettings.mergeEnabled && it.capabilities.merge) }
    val categoryIds = compatibleAllRatingCategories(manifests.map { it.packId }, settings.mediaSettings)
        .mapTo(hashSetOf()) { it.id }
    val revision = manifests.fold(0L) { token, manifest ->
        val id = manifest.packId
        token * 31L + (revisions.catalog.value[id] ?: 0L) * 13L + (revisions.ratings.value[id] ?: 0L)
    }
    val key = CatalogSessionCache.AllRankingKey(manifests,
        allRanking.value.withValidRatingCategory(categoryIds), merges, revision)
    return session.findAllRanking(key)?.page
}
