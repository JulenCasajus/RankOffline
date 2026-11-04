package org.rankoffline.app

import android.net.Uri

fun RankOfflineViewModel.exportPersonalData(uri: Uri, scope: ViewingScope = viewingScope.value) = backups.export(uri, scope)


fun RankOfflineViewModel.preparePersonalDataImport(uri: Uri) = backups.importPreview(uri)


fun RankOfflineViewModel.dismissPersonalDataImport() = backups.dismissPreview()


fun RankOfflineViewModel.importPersonalData(mode: PersonalDataImportMode, scope: ViewingScope = viewingScope.value) = backups.import(mode, scope)


fun RankOfflineViewModel.sharePersonalData(scope: ViewingScope = viewingScope.value) = backups.share(scope)


fun RankOfflineViewModel.personalDataShareHandled() = backups.shareHandled()


internal fun RankOfflineViewModel.loadRankingShare(scope: ViewingScope) = rankingShare.prepare(scope)


internal fun RankOfflineViewModel.exportRankingShare(request: RankingShareRenderRequest) = rankingShare.render(request)


internal fun RankOfflineViewModel.rankingShareHandled() = rankingShare.handled()
