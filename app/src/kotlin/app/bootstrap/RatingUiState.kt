package org.rankoffline.app


/** The editor operates on an item or a group without changing its catalog identity. */
data class RatingEditorState(
    val entry: MediaListEntry,
    val draft: RatingDraft? = null
) {
    val key: CatalogEntryKey get() = entry.key
    val isGroup: Boolean get() = entry.kind == CatalogEntryKind.GROUP
}

internal fun RatingEditorState?.matches(entry: MediaListEntry): Boolean =
    this?.key == entry.key


/** Null values explicitly clear pending/optimistic display scores. */
internal data class RatingDisplayOverride(
    val finalScore: Double?,
    val qualityScore: Double?,
    val additiveScore: Double?
)

/** An item's ID alone is never a safe override key, especially in RankOfflineAll. */
internal fun ratingOverrideKey(
    packId: PackId,
    id: String,
    kind: CatalogEntryKind = CatalogEntryKind.ITEM
): CatalogEntryKey = CatalogEntryKey(packId, id, kind)

/** Apply provisional scores without mutating domain objects or conflating items and groups. */
internal fun applyRatingOverrides(
    items: List<MediaListEntry>,
    overrides: Map<CatalogEntryKey, RatingDisplayOverride>
): List<MediaListEntry> = items.map { entry ->
    val score = overrides[entry.key] ?: return@map entry
    when (entry) {
        is MediaListEntry.Item -> entry.copy(value = entry.value.copy(
            finalScore = score.finalScore,
            qualityScore = score.qualityScore,
            additiveScore = score.additiveScore
        ))
        is MediaListEntry.Group -> entry.copy(
            finalScore = score.finalScore,
            qualityScore = score.qualityScore,
            additiveScore = score.additiveScore
        )
    }
}

