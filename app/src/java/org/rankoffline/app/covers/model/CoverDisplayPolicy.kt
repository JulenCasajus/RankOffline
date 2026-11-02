package org.rankoffline.app

/**
 * Only the IMAGE identity changes. Search, ranking, rating, notes and group
 * membership continue to use [MediaListEntry.key]. Never manufacture groups.
 */
internal fun MediaListEntry.displayCoverKey(settings: AppSettingsState): CatalogEntryKey {
    val entry = this as? MediaListEntry.Item ?: return key
    if (settings.mediaSettings[packId]?.useGroupCoversForItems != true) return key
    val groupId = entry.value.preferredCoverGroupId ?: return key
    return CatalogEntryKey(packId, groupId, CatalogEntryKind.GROUP)
}

/** For a details screen holding one item, not a list entry. */
internal fun MediaItem.displayCoverKey(settings: AppSettingsState): CatalogEntryKey {
    if (settings.mediaSettings[packId]?.useGroupCoversForItems != true) return key
    val groupId = preferredCoverGroupId ?: return key
    return CatalogEntryKey(packId, groupId, CatalogEntryKind.GROUP)
}
