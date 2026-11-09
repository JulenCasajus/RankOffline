package org.rankoffline.app

/** UI Storage screen only; no eager count or filesystem traversal during startup. */
internal suspend fun RankOfflineServices.refreshPackStatistics(
    packId: PackId,
    fields: Set<PackSummaryField> = PackSummaryField.entries.toSet()
): LocalPackSummary? = packManager.refreshSummary(packId, fields)
