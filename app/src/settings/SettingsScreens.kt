package org.rankoffline.app

import android.content.*
import android.net.Uri
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.flow.*

@Composable
internal fun SettingsScreen(
    settings: AppSettingsState,
    viewModel: RankOfflineViewModel,
    openCategories: Boolean = false,
    onCategorySettingsOpened: () -> Unit = {},
    rootRequest: Int = 0
) {
    val packs by viewModel.packStates.collectAsState()
    val selectedScope by viewModel.viewingScope.collectAsState()
    var transferScopeId by rememberSaveable { mutableStateOf(ViewingScope.ALL_ID) }
    val transferScope = ViewingScope.fromStored(transferScopeId)
    val backupPreview by viewModel.backupPreview.collectAsState()
    val transfer by viewModel.personalDataTransfer.collectAsState()
    val pendingBackupShare by viewModel.pendingPersonalDataShare.collectAsState()
    val installedManifests = remember(packs) {
        packs.values.filter { it.available }.mapNotNull { it.manifest }
    }
    val allSelected = selectedScope == ViewingScope.All
    val packId = settings.activePackId?.takeIf { id -> packs[id]?.available == true }
    val packName = installedManifests.firstOrNull { it.packId == packId }?.displayName
        ?: packId?.value?.toString().orEmpty()
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.HOME) }
    val goBack = { destination = settingsParent(destination) }
    BackHandler(destination != SettingsDestination.HOME, onBack = goBack)

    LaunchedEffect(openCategories) {
        if (openCategories) { destination = SettingsDestination.PACKS; onCategorySettingsOpened() }
    }
    LaunchedEffect(rootRequest) { if (rootRequest > 0) destination = SettingsDestination.HOME }
    LaunchedEffect(installedManifests.isEmpty(), packId, allSelected) {
        if (installedManifests.isEmpty() && destination in setOf(
            SettingsDestination.ACTIVE_PACK, SettingsDestination.DISPLAY,
            SettingsDestination.COVERS, SettingsDestination.RATING,
            SettingsDestination.RATING_CATEGORIES, SettingsDestination.RATING_PROFILES
        )) destination = SettingsDestination.HOME
        if ((packId == null || allSelected) && destination in setOf(
            SettingsDestination.COVERS, SettingsDestination.RATING,
            SettingsDestination.RATING_CATEGORIES, SettingsDestination.RATING_PROFILES
        )) destination = SettingsDestination.ACTIVE_PACK
    }

    var pendingExportScopeId by rememberSaveable { mutableStateOf(ViewingScope.ALL_ID) }
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.exportPersonalData(it, ViewingScope.fromStored(pendingExportScopeId)) } }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri -> uri?.let(viewModel::preparePersonalDataImport)
    }
    SharedSettingsUri(pendingBackupShare, "application/json", "RankOffline backup") {
        viewModel.personalDataShareHandled()
    }

    when (destination) {
        SettingsDestination.HOME -> SettingsHomeScreen(
            installedManifests, packId, allSelected,
            onSelectPack = viewModel::updateActivePack, onAll = viewModel::selectAllPacks,
            onManage = { destination = SettingsDestination.PACKS },
            onGeneral = { destination = SettingsDestination.GENERAL },
            onActivePack = { destination = SettingsDestination.ACTIVE_PACK },
            onBackup = { destination = SettingsDestination.BACKUP },
            onAbout = { destination = SettingsDestination.ABOUT },
            onDonation = { destination = SettingsDestination.DONATION }
        )
        SettingsDestination.GENERAL -> GeneralSettingsScreen(
            onCategories = { destination = SettingsDestination.PACKS },
            onTheme = { destination = SettingsDestination.THEME },
            onIcons = { destination = SettingsDestination.ICON },
            onLanguage = { destination = SettingsDestination.LANGUAGE },
            onBack = goBack
        )
        SettingsDestination.ACTIVE_PACK -> ActivePackSettingsScreen(
            if (allSelected) stringResource(R.string.media_all) else packName,
            allSelected = allSelected,
            onDisplay = { destination = SettingsDestination.DISPLAY },
            onCovers = { destination = SettingsDestination.COVERS },
            onRating = { destination = SettingsDestination.RATING },
            onBack = goBack
        )
        SettingsDestination.DISPLAY -> DisplaySettingsScreen(
            settings, allSelected,
            onStickyScoreChange = viewModel::updateStickyScore,
            onShowImagesChange = viewModel::updateShowImages,
            onBack = goBack
        )
        SettingsDestination.THEME -> ThemeSettingsScreen(
            settings.themeSelection, settings.customPalettes,
            settings.builtInPaletteOverrides, settings.hiddenBuiltInPalettes, settings.activePalette,
            onThemeSelected = viewModel::updateThemeSelection,
            onSaveCustomTheme = viewModel::saveCustomTheme,
            onDeleteCustomTheme = viewModel::deleteCustomTheme,
            onSaveBuiltInTheme = viewModel::saveBuiltInTheme,
            onDeleteBuiltInTheme = viewModel::deleteBuiltInTheme,
            onResetBuiltInThemes = viewModel::resetBuiltInThemes,
            onBack = goBack
        )
        SettingsDestination.PACKS -> PackSettingsScreen(
            packs, packId,
            onDiscover = viewModel::refreshAvailablePacks,
            onInstall = viewModel::downloadPack,
            onCancelInitialCovers = viewModel::cancelInitialCoverDownload,
            onUninstall = viewModel::uninstallPackKeepData,
            onPurge = viewModel::purgePack,
            onSelect = viewModel::updateActivePack,
            onStorage = { viewModel.refreshPackStatistics(it) },
            onRankingShare = {
                viewModel.loadRankingShare(selectedScope)
                destination = SettingsDestination.RANKING_SHARE
            }, onBack = goBack
        )
        SettingsDestination.BACKUP -> SettingsPersonalBackupScreen(
            installedManifests, transferScope, transfer.active,
            onScope = { transferScopeId = it.storedId() },
            onExport = {
                pendingExportScopeId = transferScopeId
                exportBackup.launch(if (transferScope == ViewingScope.All)
                    "RankOffline-personal-all-v2.json" else
                    "RankOffline-personal-${transferScopeId}-v2.json")
            },
            onImport = { importBackup.launch(arrayOf("application/json", "text/plain")) },
            onShare = { viewModel.sharePersonalData(transferScope) },
            onBack = goBack
        )
        SettingsDestination.RANKING_SHARE -> SettingsRankingShareScreen(viewModel, installedManifests, goBack)
        SettingsDestination.COVERS -> if (packId != null) CoversSettingsRoute(packId, packName, viewModel, goBack)
        SettingsDestination.LANGUAGE -> LanguageSettingsScreen(
            settings.language, viewModel::updateLanguage, goBack)
        SettingsDestination.RATING -> RatingSettingsScreen(
            settings.elaborateRatingEnabled, viewModel::updateElaborateRatingEnabled,
            onProfiles = { destination = SettingsDestination.RATING_PROFILES },
            onCategories = { destination = SettingsDestination.RATING_CATEGORIES }, onBack = goBack
        )
        SettingsDestination.RATING_CATEGORIES -> RatingCategoriesScreen(
            settings.ratingConfig, viewModel::updateRatingCategory,
            viewModel::createRatingCategory, viewModel::deleteRatingCategory,
            viewModel::resetRatingCategories, goBack
        )
        SettingsDestination.RATING_PROFILES -> RatingProfilesScreen(
            settings.ratingProfiles, settings.ratingConfig.categories,
            viewModel::updateRatingProfiles, viewModel::deleteRatingProfile,
            viewModel::resetRatingProfiles, goBack
        )
        SettingsDestination.ICON -> IconSettingsScreen(settings.appIcon, viewModel::updateAppIcon, goBack)
        SettingsDestination.ABOUT -> LicenceAboutScreen(installedManifests, goBack)
        SettingsDestination.DONATION -> DonationSettingsScreen(goBack)
    }
    backupPreview?.let { preview ->
        PersonalBackupPreviewDialog(preview, transfer.active, viewModel::dismissPersonalDataImport,
            { mode -> viewModel.importPersonalData(mode, transferScope) })
    }
}

/** Covers are the sole Settings destination that activates cover progress/count subscriptions. */
@Composable
private fun CoversSettingsRoute(id: PackId, name: String, viewModel: RankOfflineViewModel, onBack: () -> Unit) {
    val progressByPack by viewModel.coverProgressByPack.collectAsState()
    val packs by viewModel.packStates.collectAsState()
    val downloadedCount = packs[id]?.availableCoverCount
    LaunchedEffect(id, downloadedCount) {
        if (downloadedCount == null) {
            viewModel.refreshPackStatistics(id, setOf(PackSummaryField.AVAILABLE_COVER_COUNT))
        }
    }
    CoversSettingsScreen(id, name, progressByPack[id] ?: CoverDownloadProgress(), downloadedCount,
        onDownloadTop = { viewModel.downloadTopCovers(id, it) },
        onCancel = { viewModel.cancelCoverDownloads(id) },
        onClear = { viewModel.clearDownloadedCovers(id) }, onBack = onBack)
}

@Composable
internal fun SharedSettingsUri(uri: Uri?, mime: String, title: String, onHandled: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(uri) {
        uri ?: return@LaunchedEffect
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title))
        } catch (_: Exception) {
            // The UI may report failure separately, but the URI must not be shared again on recomposition.
        } finally { onHandled() }
    }
}
