package org.rankoffline.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only universal catalog access. Import, covers, pack deletion and ratings live elsewhere. */
class MediaRepository(
    private val database: RankOfflineDatabase,
    val ratings: RatingRepository
) {
    private val items = database.mediaDao()
    private val groups = database.mediaGroupDao()
    private val catalogs = database.mediaCatalogDao()

    suspend fun itemCount(packId: PackId): Long = withContext(Dispatchers.IO) {
        catalogs.itemCount(packId.value)
    }

    suspend fun item(key: CatalogEntryKey): MediaItem? = withContext(Dispatchers.IO) {
        require(key.kind == CatalogEntryKind.ITEM)
        val pack = key.packId.value
        val entity = items.item(pack, key.id) ?: return@withContext null
        entity.toDomain(
            rating = items.rating(pack, key.id),
            attributes = items.itemAttributes(pack, listOf(key.id)),
            aliases = items.aliases(pack, listOf(key.id))
        ).copy(preferredCoverGroupId = groups.preferredCoverGroups(pack, listOf(key.id))
            .firstOrNull()?.groupId)
    }

    /** Merge changes only the returned projection, not memberships or persisted catalog data. */
    suspend fun search(
        manifest: PackManifest,
        state: SearchUiState,
        mergeEnabled: Boolean,
        limit: Int = 200,
        offset: Int = 0
    ): List<MediaListEntry> = withContext(Dispatchers.IO) {
        val normalized = state.forManifest(manifest)
        val rows = items.visibleEntries(CatalogQueries.catalog(manifest, normalized, mergeEnabled, limit, offset))
        materialize(manifest.packId, rows, normalized.query)
    }

    suspend fun ranking(
        manifest: PackManifest,
        state: RankingUiState,
        mergeEnabled: Boolean,
        limit: Int = 5000,
        offset: Int = 0
    ): List<MediaListEntry> = withContext(Dispatchers.IO) {
        val normalized = state.forManifest(manifest)
        val rows = items.visibleEntries(CatalogQueries.ranking(manifest, normalized, mergeEnabled, limit, offset))
        materialize(manifest.packId, rows)
    }

    /** Field/filter IDs come from the manifest, not from a category-specific enum. */
    suspend fun filterOptions(manifest: PackManifest): Map<String, List<CatalogAttributeValue>> =
        withContext(Dispatchers.IO) {
            if (!manifest.capabilities.filters) return@withContext emptyMap()
            val fields = manifest.fields.associateBy { it.id }
            manifest.filters.associate { definition ->
                val field = requireNotNull(fields[definition.field])
                val values: List<CatalogAttributeValue> = when (field.type) {
                    CatalogFieldType.TEXT, CatalogFieldType.CHOICE, CatalogFieldType.DATE ->
                        items.textFilterOptions(manifest.packId.value, field.id).map(CatalogAttributeValue::Text)
                    CatalogFieldType.INTEGER, CatalogFieldType.DECIMAL ->
                        items.numericFilterOptions(manifest.packId.value, field.id).map(CatalogAttributeValue::Number)
                    CatalogFieldType.BOOLEAN ->
                        items.booleanFilterOptions(manifest.packId.value, field.id).map(CatalogAttributeValue::Flag)
                }
                definition.id to values
            }
        }

    /** Non-colliding local paths, including independent group images. */
    suspend fun localCoverPaths(keys: Collection<CatalogEntryKey>): Map<CatalogEntryKey, String> =
        withContext(Dispatchers.IO) {
            buildMap {
                keys.distinct().groupBy { it.packId }.forEach { (packId, packKeys) ->
                    packKeys.filter { it.kind == CatalogEntryKind.ITEM }.map { it.id }
                        .chunked(400).flatMap { items.coverPaths(packId.value, it) }
                        .forEach { row -> row.localCoverPath?.let { path ->
                            put(CatalogEntryKey(packId, row.mediaId, CatalogEntryKind.ITEM), path)
                        } }
                    packKeys.filter { it.kind == CatalogEntryKind.GROUP }.map { it.id }
                        .chunked(400).flatMap { groups.groupCoverPaths(packId.value, it) }
                        .forEach { row -> row.localCoverPath?.let { path ->
                            put(CatalogEntryKey(packId, row.groupId, CatalogEntryKind.GROUP), path)
                        } }
                }
            }
        }

    /** Balanced popularity and incremental k-way title merge; only consumed rows advance cursors. */
    suspend fun searchAllPage(
        manifests: List<PackManifest>, states: Map<PackId, SearchUiState>,
        mergeByPack: Map<PackId, Boolean>, limit: Int,
        cursor: AllCatalogCursor = AllCatalogCursor()
    ): AllCatalogPage = readAllCatalogPage(manifests, states, mergeByPack, limit, cursor, this)

    suspend fun rankingAllPage(
        manifests: List<PackManifest>, state: RankingUiState,
        mergeByPack: Map<PackId, Boolean>, limit: Int,
        cursor: AllRankingCursor = AllRankingCursor()
    ): AllRankingPage = readAllRankingPage(manifests, state, mergeByPack, limit, cursor, this)

    private suspend fun materialize(
        packId: PackId, rows: List<CatalogEntryIdRow>, query: String = ""
    ): List<MediaListEntry> {
        if (rows.isEmpty()) return emptyList()
        require(rows.all { it.packId == packId.value }) { "Mixed packs in a catalog result" }
        val itemIds = rows.filter { it.entryKind == "ITEM" }.map { it.id }.distinct()
        val groupIds = rows.filter { it.entryKind == "GROUP" }.map { it.id }.distinct()
        require(itemIds.size + groupIds.size == rows.size) { "Duplicate or invalid entry identities" }
        val entities = itemIds.chunked(400).flatMap { items.itemsByIds(packId.value, it) }.associateBy { it.id }
        val itemRatings = itemIds.chunked(400).flatMap { items.ratingsByIds(packId.value, it) }.associateBy { it.mediaId }
        val attrs = itemIds.chunked(400).flatMap { items.itemAttributes(packId.value, it) }.groupBy { it.mediaId }
        val aliases = itemIds.chunked(400).flatMap { items.aliases(packId.value, it) }.groupBy { it.mediaId }
        // Already indexed membership: resolve only visible page items, never the whole catalog.
        val coverGroups = itemIds.chunked(400)
            .flatMap { groups.preferredCoverGroups(packId.value, it) }
            .associate { it.mediaId to it.groupId }
        val groupEntities = groupIds.chunked(400).flatMap { groups.groupsByIds(packId.value, it) }.associateBy { it.id }
        val declaredGroupAttrs = groupIds.chunked(400)
            .flatMap { groups.attributesForGroups(packId.value, it) }.groupBy { it.groupId }
        val memberGroupAttrs = groupIds.chunked(400)
            .flatMap { groups.memberAttributesForGroups(packId.value, it) }.groupBy { it.groupId }
        val groupAttrs = groupIds.associateWith { id ->
            val declared = declaredGroupAttrs[id].orEmpty()
            val members = memberGroupAttrs[id].orEmpty().map { row ->
                MediaGroupAttributeEntity(packId.value, id, row.fieldId, 0,
                    row.valueText, row.valueNumber, row.valueBoolean)
            }
            (declared + members).groupBy { it.fieldId }.flatMap { (field, values) ->
                values.distinctBy { Triple(it.valueText, it.valueNumber, it.valueBoolean) }
                    .mapIndexed { position, row -> row.copy(fieldId = field, position = position) }
            }
        }
        val representatives = groupIds.chunked(400).flatMap { groups.representativesByIds(packId.value, it) }
            .associateBy { it.groupId }
        check(entities.size == itemIds.size && groupEntities.size == groupIds.size) {
            "Catalog changed while materializing a page"
        }
        return rows.map { row ->
            when (row.entryKind) {
                "ITEM" -> {
                    val entity = checkNotNull(entities[row.id])
                    val result = entity.toDomain(
                        rating = itemRatings[row.id],
                        categoryScore = row.categoryScore,
                        attributes = attrs[row.id].orEmpty(), aliases = aliases[row.id].orEmpty()
                    )
                    val preferred = bestMatchingAliasTitle(result.title, result.aliases.map { it.title }, query)
                    MediaListEntry.Item(result.copy(
                        preferredCoverGroupId = coverGroups[row.id], searchDisplayTitle = preferred
                    ))
                }
                "GROUP" -> {
                    val group = checkNotNull(groupEntities[row.id]).toDomain(
                        representatives[row.id], groupAttrs[row.id].orEmpty())
                    MediaListEntry.Group(
                        value = group,
                        finalScore = row.finalScore,
                        qualityScore = row.qualityScore,
                        additiveScore = row.additiveScore,
                        categoryScore = row.categoryScore,
                        memberCount = row.memberCount,
                        ratedCount = row.ratedCount,
                        derived = !row.hasExplicitScore
                    )
                }
                else -> error("Unexpected entry kind: ${row.entryKind}")
            }
        }
    }
}

internal fun rankingComparator(state: RankingUiState): Comparator<MediaListEntry> = Comparator { a, b ->
    fun score(entry: MediaListEntry): Double? = when (state.sortOrder) {
        RankingSortOrder.FINAL_SCORE -> entry.finalScore
        RankingSortOrder.QUALITY_SCORE -> entry.qualityScore
        RankingSortOrder.PERSONAL_TASTE -> entry.additiveScore
        RankingSortOrder.CATEGORY_SCORE -> entry.categoryScore
    }
    val left = score(a)
    val right = score(b)
    val metric = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        state.descending -> right.compareTo(left)
        else -> left.compareTo(right)
    }
    if (metric != 0) metric
    else sqliteNoCaseCompare(a.title, b.title).takeIf { it != 0 }
        ?: compareValuesBy(a, b, { it.packId.value }, { it.kind.name }, { it.id })
}
