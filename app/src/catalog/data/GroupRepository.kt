package org.rankoffline.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pack-defined group relationships only. Rating CRUD is shared in [RatingRepository]. */
class GroupRepository(
    private val database: RankOfflineDatabase,
    val ratings: RatingRepository
) {
    private val groups = database.mediaGroupDao()
    private val items = database.mediaDao()

    /** Group details and its independently rated members, in a consistent Room snapshot. */
    suspend fun detail(key: CatalogEntryKey): MediaGroupDetail? = withContext(Dispatchers.IO) {
        require(key.kind == CatalogEntryKind.GROUP) { "A group key is required" }
        val pack = key.packId.value
        val group = groups.group(pack, key.id) ?: return@withContext null
        val relations = groups.members(pack, key.id)
        val ids = relations.map { it.mediaId }
        check(ids.size == ids.distinct().size) { "Duplicate member in ${key.id}" }
        val catalogItems = ids.chunked(400)
            .flatMap { items.itemsByIds(pack, it) }.associateBy { it.id }
        check(catalogItems.size == ids.size) { "Group ${key.id} references missing items" }
        val attributes = ids.chunked(400).flatMap { items.itemAttributes(pack, it) }.groupBy { it.mediaId }
        val aliases = ids.chunked(400).flatMap { items.aliases(pack, it) }.groupBy { it.mediaId }
        val memberRatings = groups.ratedMemberRatings(pack, key.id)
        val memberScores = groups.ratedMemberScores(pack, key.id)
        val byId = memberRatings.associateBy { it.mediaId }
        MediaGroupDetail(
            group = group.toDomain(groups.representative(pack, key.id), groups.attributes(pack, key.id)),
            members = relations.map { relation ->
                checkNotNull(catalogItems[relation.mediaId]).toDomain(
                    rating = byId[relation.mediaId],
                    attributes = attributes[relation.mediaId].orEmpty(),
                    aliases = aliases[relation.mediaId].orEmpty()
                )
            },
            explicitRating = groups.rating(pack, key.id),
            derivedRating = deriveMediaGroupRating(memberRatings, memberScores)
        )
    }

    /** Derived score is only a suggestion; never persists a group rating. */
    suspend fun derivedRating(key: CatalogEntryKey): DerivedGroupRating? = withContext(Dispatchers.IO) {
        require(key.kind == CatalogEntryKind.GROUP)
        val pack = key.packId.value
        if (groups.group(pack, key.id) == null) null
        else deriveMediaGroupRating(
            groups.ratedMemberRatings(pack, key.id),
            groups.ratedMemberScores(pack, key.id)
        )
    }

}
