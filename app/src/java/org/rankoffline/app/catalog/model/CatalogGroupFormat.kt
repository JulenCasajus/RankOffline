package org.rankoffline.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Version of the group RECORD format, independent of PackManifest.schemaVersion. */
const val MEDIA_GROUP_CATALOG_FORMAT_VERSION = 2

/** A group member refers to an item in the SAME verified pack. */
@Serializable
data class MediaGroupCatalogMember(
    val mediaId: String,
    /** Omit for the order in which the members appear in the array. */
    val position: Int? = null
)

/**
 * One JSONL group record stored under groups/ in a validated .pack.
 *
 * Pack identity, version and payload integrity belong to PackManifest.
 * Membership is independent of the merge-view toggle. Group title and type are
 * optional descriptive metadata; neither determines whether the relation exists.
 * The optional provider fields express provenance, not identity. The representative
 * may refer to any item in this pack; it is NOT required to be a group member.
 */
@Serializable
data class MediaGroupCatalogRecord(
    val id: String,
    val members: List<MediaGroupCatalogMember>,
    val displayTitle: String? = null,
    val groupType: String? = null,
    val representativeMediaId: String? = null,
    val provider: String? = null,
    val providerGroupId: String? = null,
    val popularity: Double? = null,
    val popularityRank: Int? = null,
    val remoteCoverRef: String? = null
)

/** Insert these rows together in an importer transaction, after catalog items exist. */
internal data class MediaGroupCatalogRows(
    val group: MediaGroupEntity,
    val members: List<MediaGroupMemberEntity>,
    val representative: MediaGroupRepresentativeEntity?
)

private val groupCatalogJson = Json {
    ignoreUnknownKeys = false
    encodeDefaults = false
    isLenient = false
    allowSpecialFloatingPointValues = false
}

private val GROUP_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,191}")
private val PROVIDER_ID = Regex("[a-z][a-z0-9_]{0,63}")
private const val MAX_TITLE_LENGTH = 500
private const val MAX_REFERENCE_LENGTH = 1024
private const val MAX_MEMBERS_PER_GROUP = 10_000
/** Technical fallback, not a predefined content category or a merge strategy. */
private const val DEFAULT_GROUP_TYPE = "group"

/**
 * Decode one JSONL group using the verified containing manifest.
 * The importer is responsible for limits on line size, total groups, file hashes,
 * unique group IDs across the input and membership/representative existence.
 */
fun parseMediaGroupCatalogRecord(
    value: String,
    manifest: PackManifest
): MediaGroupCatalogRecord = groupCatalogJson.decodeFromString<MediaGroupCatalogRecord>(value).also {
    validateMediaGroupCatalogRecord(it, manifest)
}

/** For pack generation and round-trip tests. Encoding does not validate. */
internal fun encodeMediaGroupCatalogRecord(record: MediaGroupCatalogRecord): String =
    groupCatalogJson.encodeToString(record)

/** Validates one record; never assumes which kind of content the pack contains. */
internal fun validateMediaGroupCatalogRecord(
    record: MediaGroupCatalogRecord,
    manifest: PackManifest
) {
    // Relations are valid regardless of the current/default merge presentation.
    // Group records must come from files explicitly declared by the containing pack.
    require(manifest.groups.isNotEmpty()) { "Pack declares no group data" }
    require(record.id.matches(GROUP_ID)) { "Invalid group ID: ${record.id}" }
    require(record.displayTitle == null || record.displayTitle.isValidGroupText(MAX_TITLE_LENGTH)) {
        "Invalid group title"
    }
    require(record.groupType == null || record.groupType.matches(PROVIDER_ID)) {
        "Invalid group type"
    }
    require(record.provider == null || record.provider.matches(PROVIDER_ID)) {
        "Invalid group provider ID"
    }
    require(record.providerGroupId == null || record.provider != null) {
        "providerGroupId requires provider"
    }
    require(record.providerGroupId == null || record.providerGroupId.isValidGroupText(MAX_REFERENCE_LENGTH)) {
        "Invalid provider group ID"
    }
    require(record.representativeMediaId == null || record.representativeMediaId.matches(GROUP_ID)) {
        "Invalid representative item ID"
    }
    require(record.popularity == null || (record.popularity.isFinite() && record.popularity >= 0.0)) {
        "Group popularity must be finite and nonnegative"
    }
    require(record.popularityRank == null || record.popularityRank >= 0) {
        "Group popularity rank must be nonnegative"
    }
    require(record.remoteCoverRef == null || record.remoteCoverRef.isValidGroupText(MAX_REFERENCE_LENGTH)) {
        "Invalid remote cover reference"
    }

    require(record.members.isNotEmpty()) { "A group requires at least one member" }
    require(record.members.size <= MAX_MEMBERS_PER_GROUP) { "Too many group members" }
    val seenIds = HashSet<String>(record.members.size)
    val explicitPositions = record.members.count { it.position != null }
    require(explicitPositions == 0 || explicitPositions == record.members.size) {
        "Group member positions must be all explicit or all omitted"
    }
    val seenPositions = BooleanArray(record.members.size)
    record.members.forEachIndexed { index, member ->
        require(member.mediaId.matches(GROUP_ID)) { "Invalid group member ID: ${member.mediaId}" }
        require(seenIds.add(member.mediaId)) { "Duplicate group member: ${member.mediaId}" }
        val position = member.position ?: index
        require(position in seenPositions.indices) { "Group member position out of range" }
        require(!seenPositions[position]) { "Duplicate group member position" }
        seenPositions[position] = true
    }
}

/** Convert a validated group to the generic Room tables, scoped to this pack. */
internal fun MediaGroupCatalogRecord.toEntityRows(
    manifest: PackManifest
): MediaGroupCatalogRows {
    validateMediaGroupCatalogRecord(this, manifest)
    val packId = manifest.packId.value
    // Room's required columns receive neutral, stable values when metadata is absent.
    val storedGroupType = groupType ?: DEFAULT_GROUP_TYPE
    return MediaGroupCatalogRows(
        group = MediaGroupEntity(
            packId = packId,
            id = id,
            groupType = storedGroupType,
            displayTitle = displayTitle ?: id,
            provider = provider,
            providerGroupId = providerGroupId,
            popularity = popularity,
            popularityRank = popularityRank,
            remoteCoverRef = remoteCoverRef
        ),
        members = members.mapIndexed { index, member ->
            member to (member.position ?: index)
        }.sortedBy { it.second }.map { (member, position) ->
            MediaGroupMemberEntity(
                packId = packId,
                groupId = id,
                mediaId = member.mediaId,
                position = position
            )
        },
        representative = representativeMediaId?.let { mediaId ->
            MediaGroupRepresentativeEntity(
                packId = packId,
                groupId = id,
                mediaId = mediaId
            )
        }
    )
}

private fun String.isValidGroupText(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && none(Char::isISOControl)
