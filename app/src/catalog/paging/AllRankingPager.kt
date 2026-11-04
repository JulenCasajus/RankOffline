package org.rankoffline.app

/** The cursor is tied to an unchanged pack set, order, filters and Merge configuration. */
data class CombinedPageCursor(
    val offsets: Map<PackId, Int> = emptyMap(),
    val exhausted: Set<PackId> = emptySet(),
    val nextPopularityPack: Int = 0
)

internal data class CombinedPage<T>(
    val entries: List<T>,
    val nextCursor: CombinedPageCursor,
    val hasMore: Boolean
)

typealias AllRankingCursor = CombinedPageCursor

data class AllRankingPage(
    val entries: List<MediaListEntry>,
    val nextCursor: AllRankingCursor,
    val hasMore: Boolean
)

/**
 * One bounded incremental merge for already sorted per-pack SQL streams.
 *
 * Popularity is provider-local, so take an equitable round-robin share (about limit/N).
 * Global orders use a k-way merge: only fetch another small chunk from the pack whose
 * candidate was consumed. Unused prefetched rows are NOT added to cursor offsets:
 * they can safely be fetched again on the next UI page, without duplicates or omissions.
 * Queries never require materializing a full pack ranking in RAM.
 */
internal suspend fun <T> readCombinedPage(
    packIds: List<PackId>,
    limit: Int,
    cursor: CombinedPageCursor,
    popularity: Boolean,
    comparator: Comparator<T>?,
    fetch: suspend (PackId, Int, Int) -> List<T>
): CombinedPage<T> {
    require(limit in 1..5000)
    require(packIds.size == packIds.distinct().size)
    if (!popularity) requireNotNull(comparator)
    if (packIds.isEmpty()) return CombinedPage(emptyList(), cursor, false)
    val offsets = cursor.offsets.toMutableMap()
    val exhausted = cursor.exhausted.intersect(packIds.toSet()).toMutableSet()
    val buffers = HashMap<PackId, List<T>>()
    val positions = packIds.associateWith { 0 }.toMutableMap()
    val endAfterBuffer = hashSetOf<PackId>()
    // Only the first round-robin fetch is distributed evenly; later calls are on demand.
    val initialBatch = if (popularity) (limit + packIds.size - 1) / packIds.size
        else minOf(32, maxOf(8, limit / maxOf(1, packIds.size)))

    suspend fun candidate(packId: PackId): T? {
        if (packId in exhausted) return null
        var entries = buffers[packId]
        var position = positions.getValue(packId)
        if (entries == null || position >= entries.size) {
            if (packId in endAfterBuffer) {
                exhausted += packId
                return null
            }
            val batch = minOf(5000, maxOf(1, initialBatch))
            entries = fetch(packId, offsets[packId] ?: 0, batch)
            require(entries.size <= batch) { "Pack returned more rows than requested" }
            buffers[packId] = entries
            positions[packId] = 0
            position = 0
            if (entries.size < batch) endAfterBuffer += packId
            if (entries.isEmpty()) {
                exhausted += packId
                return null
            }
        }
        return entries[position]
    }

    val output = ArrayList<T>(limit)
    var next = cursor.nextPopularityPack.mod(packIds.size)
    if (popularity) {
        var misses = 0
        while (output.size < limit && misses < packIds.size) {
            val pack = packIds[next]
            next = (next + 1) % packIds.size
            val item = candidate(pack)
            if (item == null) misses++ else {
                output += item
                positions[pack] = positions.getValue(pack) + 1
                offsets[pack] = (offsets[pack] ?: 0).also { check(it < Int.MAX_VALUE) } + 1
                misses = 0
            }
        }
    } else {
        val order = requireNotNull(comparator)
        while (output.size < limit) {
            var best: Pair<PackId, T>? = null
            for (pack in packIds) {
                val item = candidate(pack) ?: continue
                val current = best
                if (current == null || order.compare(item, current.second) < 0) {
                    best = pack to item
                }
            }
            val selected = best ?: break
            val pack = selected.first
            output += selected.second
            positions[pack] = positions.getValue(pack) + 1
            offsets[pack] = (offsets[pack] ?: 0).also { check(it < Int.MAX_VALUE) } + 1
        }
    }
    // An exactly full last chunk may have no more rows; next page probes once.
    val hasMore = packIds.any { pack ->
        pack !in exhausted && (pack !in endAfterBuffer ||
            positions.getValue(pack) < buffers[pack].orEmpty().size)
    }
    return CombinedPage(output, CombinedPageCursor(offsets, exhausted, next), hasMore)
}

internal suspend fun readAllRankingPage(
    manifests: List<PackManifest>,
    state: RankingUiState,
    mergeByPack: Map<PackId, Boolean>,
    limit: Int,
    cursor: AllRankingCursor,
    repository: MediaRepository
): AllRankingPage {
    val ids = manifests.map { it.packId }.distinct()
    val page = readCombinedPage(ids, limit, cursor, popularity = false,
        comparator = rankingComparator(state)) { id, offset, count ->
        val manifest = manifests.first { it.packId == id }
        repository.ranking(manifest, state, mergeByPack[id] == true, count, offset)
    }
    return AllRankingPage(page.entries, page.nextCursor, page.hasMore)
}
