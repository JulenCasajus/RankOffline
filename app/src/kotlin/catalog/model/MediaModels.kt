package org.rankoffline.app

import java.text.Normalizer
import java.util.Locale

/** Alias metadata is catalog data, not an alternative item identity. */
data class MediaItemAlias(
    val title: String,
    val language: String? = null,
    val kind: String? = null
)

/** One catalog item, identified only by (packId, id). Fields are declared by its pack. */
data class MediaItem(
    val packId: PackId,
    val id: String,
    val title: String,
    val attributes: Map<String, List<CatalogAttributeValue>> = emptyMap(),
    val aliases: List<MediaItemAlias> = emptyList(),
    val provider: String? = null,
    val providerId: String? = null,
    val popularity: Double? = null,
    val popularityRank: Int? = null,
    val finalScore: Double? = null,
    val qualityScore: Double? = null,
    val additiveScore: Double? = null,
    val categoryScore: Double? = null,
    val remoteCoverRef: String? = null,
    val localCoverPath: String? = null,
    val coverResolutionState: CoverResolutionState = CoverResolutionState.UNKNOWN,
    /** Presentation-only: optional cover borrowed from an item in the same pack. */
    val sharedCoverMediaId: String? = null,
    /** Search-only title; the canonical catalog title is never replaced. */
    val searchDisplayTitle: String? = null,
    /** Presentation-only group relationship, never an item identity replacement. */
    val preferredCoverGroupId: String? = null
) {
    val key: CatalogEntryKey get() = CatalogEntryKey(packId, id, CatalogEntryKind.ITEM)
}

/** A pack-defined relation between items, with optional catalog metadata. */
data class MediaGroup(
    val packId: PackId,
    val id: String,
    /** Room stores the group ID as a fallback when the pack supplies no title. */
    val displayTitle: String,
    /** A pack-defined label; "group" is the importer's neutral fallback. */
    val groupType: String,
    val attributes: Map<String, List<CatalogAttributeValue>> = emptyMap(),
    val provider: String? = null,
    val providerGroupId: String? = null,
    val popularity: Double? = null,
    val popularityRank: Int? = null,
    /** Loaded separately from MediaGroupRepresentativeEntity; may not be a member. */
    val representativeMediaId: String? = null,
    val remoteCoverRef: String? = null,
    val localCoverPath: String? = null,
    val coverResolutionState: CoverResolutionState = CoverResolutionState.UNKNOWN
) {
    val key: CatalogEntryKey get() = CatalogEntryKey(packId, id, CatalogEntryKind.GROUP)
}

data class MediaGroupDetail(
    val group: MediaGroup,
    /** Membership is a relationship, not an attribute of each individual item. */
    val members: List<MediaItem>,
    val explicitRating: MediaGroupRatingEntity?,
    val derivedRating: DerivedGroupRating
) {
    init {
        require(members.all { it.packId == group.packId }) {
            "Group members must belong to the same pack"
        }
        require(explicitRating == null ||
            (explicitRating.packId == group.packId.value && explicitRating.groupId == group.id)) {
            "Group rating belongs to a different group"
        }
    }
}

/** Merge switches list presentation between items and relations; it changes neither. */
sealed interface MediaListEntry {
    val id: String
    val packId: PackId
    val kind: CatalogEntryKind
    val title: String
    val finalScore: Double?
    val qualityScore: Double?
    val additiveScore: Double?
    val categoryScore: Double?

    val key: CatalogEntryKey get() = CatalogEntryKey(packId, id, kind)

    data class Item(val value: MediaItem) : MediaListEntry {
        override val id: String get() = value.id
        override val packId: PackId get() = value.packId
        override val kind: CatalogEntryKind get() = CatalogEntryKind.ITEM
        override val title: String get() = value.searchDisplayTitle ?: value.title
        override val finalScore: Double? get() = value.finalScore
        override val qualityScore: Double? get() = value.qualityScore
        override val additiveScore: Double? get() = value.additiveScore
        override val categoryScore: Double? get() = value.categoryScore
    }

    data class Group(
        val value: MediaGroup,
        override val finalScore: Double?,
        override val qualityScore: Double?,
        override val additiveScore: Double?,
        override val categoryScore: Double?,
        val memberCount: Int,
        val ratedCount: Int,
        /** Presentation-only: cover fallback, never an additional group membership. */
        val sharedCoverMediaId: String? = null,
        /** True when scores come from members rather than an explicit group rating. */
        val derived: Boolean
    ) : MediaListEntry {
        init {
            require(memberCount >= 0 && ratedCount in 0..memberCount) {
                "Invalid group rating counts"
            }
        }

        override val id: String get() = value.id
        override val packId: PackId get() = value.packId
        override val kind: CatalogEntryKind get() = CatalogEntryKind.GROUP
        override val title: String get() = value.displayTitle
        val popularity: Double? get() = value.popularity
        val popularityRank: Int? get() = value.popularityRank
        val representativeMediaId: String? get() = value.representativeMediaId
    }
}

/** Prefer an alias only when the canonical title does not already match the query. */
internal fun bestMatchingAliasTitle(
    canonicalTitle: String,
    aliases: List<String>,
    rawQuery: String
): String? {
    val query = rawQuery.trim()
    if (query.codePointCount(0, query.length) < 3) return null
    val normalizedQuery = query.normalizedForAliasMatch()
    if (canonicalTitle.normalizedForAliasMatch().contains(normalizedQuery)) return null

    return aliases.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { alias -> alias to alias.normalizedForAliasMatch() }
        .filter { (_, normalized) -> normalized.contains(normalizedQuery) }
        .minWithOrNull(
            compareBy<Pair<String, String>>(
                { (_, normalized) ->
                    when {
                        normalized == normalizedQuery -> 0
                        normalized.startsWith(normalizedQuery) -> 1
                        else -> 2
                    }
                },
                { it.second },
                { it.first }
            )
        )?.first
}

private fun String.normalizedForAliasMatch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

/** Room attributes have no field semantics; their types are already validated at import. */
private data class StoredAttribute(
    val fieldId: String,
    val position: Int,
    val value: CatalogAttributeValue
)

private fun storedAttribute(
    fieldId: String,
    position: Int,
    text: String?,
    number: Double?,
    boolean: Boolean?
): StoredAttribute {
    require(position >= 0) { "Negative attribute position for $fieldId" }
    require(listOf(text, number, boolean).count { it != null } == 1) {
        "Stored attribute $fieldId must have exactly one value"
    }
    val value = when {
        text != null -> CatalogAttributeValue.Text(text)
        number != null -> CatalogAttributeValue.Number(number)
        else -> CatalogAttributeValue.Flag(requireNotNull(boolean))
    }
    return StoredAttribute(fieldId, position, value)
}

private fun List<StoredAttribute>.toDomainAttributes(): Map<String, List<CatalogAttributeValue>> =
    groupBy(StoredAttribute::fieldId).mapValues { (fieldId, rows) ->
        val sorted = rows.sortedBy(StoredAttribute::position)
        require(sorted.zipWithNext().none { (a, b) -> a.position == b.position }) {
            "Duplicate attribute position for $fieldId"
        }
        sorted.map(StoredAttribute::value)
    }

/** Adapt one persisted item. Load attributes and aliases separately when needed. */
fun MediaItemEntity.toDomain(
    rating: MediaRatingEntity? = null,
    categoryScore: Double? = null,
    attributes: List<MediaItemAttributeEntity> = emptyList(),
    aliases: List<MediaAliasEntity> = emptyList()
): MediaItem {
    require(rating == null || (rating.packId == packId && rating.mediaId == id)) {
        "Rating belongs to a different catalog item"
    }
    require(attributes.all { it.packId == packId && it.mediaId == id }) {
        "Attributes belong to a different catalog item"
    }
    require(aliases.all { it.packId == packId && it.mediaId == id }) {
        "Aliases belong to a different catalog item"
    }
    return MediaItem(
        packId = PackId(packId),
        id = id,
        title = title,
        attributes = attributes.map { row ->
            storedAttribute(row.fieldId, row.position, row.valueText, row.valueNumber, row.valueBoolean)
        }.toDomainAttributes(),
        aliases = aliases.map { MediaItemAlias(it.title, it.language, it.kind) },
        provider = provider,
        providerId = providerId,
        popularity = popularity,
        popularityRank = popularityRank,
        finalScore = rating?.finalScore,
        qualityScore = rating?.qualityScore,
        additiveScore = rating?.additiveScore,
        categoryScore = categoryScore,
        remoteCoverRef = remoteCoverRef,
        localCoverPath = localCoverPath,
        coverResolutionState = coverResolutionState.toCoverResolutionState()
    )
}

/** The optional representative is stored in its own Room relation. */
fun MediaGroupEntity.toDomain(
    representative: MediaGroupRepresentativeEntity? = null,
    attributes: List<MediaGroupAttributeEntity> = emptyList()
): MediaGroup {
    require(representative == null ||
        (representative.packId == packId && representative.groupId == id)) {
        "Representative belongs to a different group"
    }
    require(attributes.all { it.packId == packId && it.groupId == id }) {
        "Attributes belong to a different group"
    }
    return MediaGroup(
        packId = PackId(packId),
        id = id,
        displayTitle = displayTitle,
        groupType = groupType,
        attributes = attributes.map { row ->
            storedAttribute(row.fieldId, row.position, row.valueText, row.valueNumber, row.valueBoolean)
        }.toDomainAttributes(),
        provider = provider,
        providerGroupId = providerGroupId,
        popularity = popularity,
        popularityRank = popularityRank,
        representativeMediaId = representative?.mediaId,
        remoteCoverRef = remoteCoverRef,
        localCoverPath = localCoverPath,
        coverResolutionState = coverResolutionState.toCoverResolutionState()
    )
}

private fun String.toCoverResolutionState(): CoverResolutionState =
    CoverResolutionState.entries.firstOrNull { it.name == this }
        ?: CoverResolutionState.UNKNOWN
