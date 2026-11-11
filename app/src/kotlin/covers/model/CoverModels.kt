package org.rankoffline.app

/** Explicit storage ownership; two packs may use the same item ID or group ID. */
data class CoverStorageIdentity(
    val packId: PackId,
    val id: String,
    val kind: CatalogEntryKind
) {
    init { require(id.isNotBlank()) { "Cover owner ID must not be blank" } }

    /** Unambiguous even if item IDs contain ':' or other separators. */
    fun cacheIdentity(): String = "${packId.value.toString().length}:${packId.value}:${kind.name}:${id.length}:$id"
}

enum class CoverResolutionState { UNKNOWN, AVAILABLE, NOT_FOUND }
enum class CoverDownloadState { IDLE, DOWNLOADING, NOT_FOUND }

sealed interface CoverDownloadResult {
    data object Downloaded : CoverDownloadResult
    data object AlreadyAvailable : CoverDownloadResult
    data object AlreadyInProgress : CoverDownloadResult
    data object NotFound : CoverDownloadResult
    data object NetworkError : CoverDownloadResult
    data object TemporaryError : CoverDownloadResult
}

enum class CoverDownloadStatus {
    IDLE, WAITING_FOR_COVERS, PREPARING_COVER_LIST, DOWNLOADING_COVERS,
    RETRYING_COVERS, WAITING_TO_SAVE, COMPLETED, CANCELLED, FAILED
}

val CoverDownloadStatus.isTerminal: Boolean
    get() = this == CoverDownloadStatus.COMPLETED ||
        this == CoverDownloadStatus.CANCELLED || this == CoverDownloadStatus.FAILED

data class CoverDownloadProgress(
    val packId: PackId? = null,
    val status: CoverDownloadStatus = CoverDownloadStatus.IDLE,
    val target: Int = 0,
    val processed: Int = 0,
    val existing: Int = 0,
    val downloaded: Int = 0,
    val unavailable: Int = 0,
    val errors: Int = 0,
    val message: UserMessage? = null
) {
    val isActive: Boolean get() = status in setOf(
        CoverDownloadStatus.WAITING_FOR_COVERS,
        CoverDownloadStatus.PREPARING_COVER_LIST,
        CoverDownloadStatus.DOWNLOADING_COVERS,
        CoverDownloadStatus.RETRYING_COVERS,
        CoverDownloadStatus.WAITING_TO_SAVE
    )

    /** Counts only verified local files, not attempts or failed downloads. */
    val available: Int get() = (existing.toLong() + downloaded).coerceIn(0L, target.coerceAtLeast(0).toLong()).toInt()
    val fraction: Float get() = if (target <= 0) 0f else (processed.toFloat() / target).coerceIn(0f, 1f)
}
