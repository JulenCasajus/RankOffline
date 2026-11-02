package org.rankoffline.app

import java.io.InputStream

const val BONUS_MAX: Double = 2.0

enum class CatalogSortOrder { POPULARITY, POPULARITY_REVERSE, TITLE_ASC, TITLE_DESC }

data class MediaItemEntity(
    val packId: Long, val id: String, val title: String, val provider: String? = null,
    val providerId: String? = null, val popularity: Double? = null,
    val popularityRank: Int? = null, val remoteCoverRef: String? = null,
    val localCoverPath: String? = null, val coverResolutionState: String = "UNKNOWN"
)

data class MediaItemAttributeEntity(
    val packId: Long, val mediaId: String, val fieldId: String, val position: Int = 0,
    val valueText: String? = null, val valueNumber: Double? = null,
    val valueBoolean: Boolean? = null
)

data class MediaAliasEntity(
    val packId: Long, val mediaId: String, val title: String,
    val language: String? = null, val kind: String? = null
)

data class MediaGroupEntity(
    val packId: Long, val id: String, val groupType: String, val displayTitle: String,
    val provider: String? = null, val providerGroupId: String? = null,
    val popularity: Double? = null, val popularityRank: Int? = null,
    val remoteCoverRef: String? = null, val localCoverPath: String? = null,
    val coverResolutionState: String = "UNKNOWN"
)

data class MediaGroupMemberEntity(
    val packId: Long, val groupId: String, val mediaId: String, val position: Int
)

data class MediaGroupRepresentativeEntity(
    val packId: Long, val groupId: String, val mediaId: String
)

data class PackInstallRequest(val manifestJson: String, val sourceRevision: String? = null)

