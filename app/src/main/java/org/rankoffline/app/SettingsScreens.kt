package org.rankoffline.app

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

@Composable
internal fun SettingsScreen(
    settings: AppSettingsState,
    catalogCount: Int,
    categoryPackStates: Map<MediaType, CategoryPackState>,
    availableMediaTypes: Set<MediaType>,
    categoryDownloadProgress: CategoryDownloadProgress?,
    openCategories: Boolean,
    onCategorySettingsOpened: () -> Unit,
    rootRequest: Int,
    viewModel: RankOfflineViewModel
) {
    var destination by remember { mutableStateOf(SettingsDestination.HOME) }
    var confirmReplaceBackup by remember { mutableStateOf(false) }
    val preloadProgress by viewModel.coverPreloadProgress.collectAsState()
    val downloadedCoverCount by viewModel.downloadedCoverCount.collectAsState()
    val packPersonalStorage by viewModel.packPersonalStorage.collectAsState()
    val viewingScope by viewModel.viewingScope.collectAsState()
    val allAvailable by viewModel.allAvailable.collectAsState()
    val allSelected = viewingScope == ViewingScope.All
    val backupPreview by viewModel.backupPreview.collectAsState()
    val personalDataTransfer by viewModel.personalDataTransfer.collectAsState()
    val pendingPersonalDataShare by viewModel.pendingPersonalDataShare.collectAsState()
    val rankingShareData by viewModel.rankingShareData.collectAsState()
    val rankingShareExport by viewModel.rankingShareExport.collectAsState()
    val pendingRankingShare by viewModel.pendingRankingShare.collectAsState()
    val context = LocalContext.current
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportPersonalData) }
    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::preparePersonalDataImport) }
    val noInstalledPacks = availableMediaTypes.isEmpty()
    val shareChooserTitle = stringResource(R.string.personal_backup_share_chooser)
    LaunchedEffect(pendingPersonalDataShare) {
        val uri = pendingPersonalDataShare ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "RankOffline backup", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, shareChooserTitle))
        }.onSuccess {
            viewModel.personalDataShareHandled()
        }.onFailure {
            viewModel.personalDataShareFailed()
        }
    }
    val rankingShareChooserTitle = stringResource(R.string.ranking_share_chooser)
    LaunchedEffect(pendingRankingShare) {
        val uri = pendingRankingShare ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "RankOffline ranking", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, rankingShareChooserTitle))
        }.onSuccess {
            viewModel.rankingShareHandled()
        }.onFailure {
            viewModel.rankingShareFailed()
        }
    }
    LaunchedEffect(availableMediaTypes) {
        if (availableMediaTypes.isEmpty() && destination in setOf(
                SettingsDestination.ACTIVE_PACK,
                SettingsDestination.GENERAL_DISPLAY,
                SettingsDestination.GENERAL_COVERS,
                SettingsDestination.RATING,
                SettingsDestination.RATING_PROFILES,
                SettingsDestination.RATING_CATEGORIES,
                SettingsDestination.SHARE_RANKING
            )
        ) destination = SettingsDestination.HOME
    }
    LaunchedEffect(openCategories) {
        if (openCategories) {
            destination = SettingsDestination.GENERAL_CATEGORIES
            onCategorySettingsOpened()
        }
    }
    LaunchedEffect(rootRequest) {
        if (rootRequest > 0) destination = SettingsDestination.HOME
    }
    val goBack = {
        destination = when (destination) {
            SettingsDestination.GENERAL_COVERS,
            SettingsDestination.GENERAL_DISPLAY,
            SettingsDestination.RATING -> SettingsDestination.ACTIVE_PACK
            SettingsDestination.GENERAL_CATEGORIES,
            SettingsDestination.GENERAL_THEME,
            SettingsDestination.ICON,
            SettingsDestination.LANGUAGE -> SettingsDestination.GENERAL
            SettingsDestination.RATING_PROFILES,
            SettingsDestination.RATING_CATEGORIES -> SettingsDestination.RATING
            SettingsDestination.SHARE_RANKING -> SettingsDestination.ACTIVE_PACK
            else -> SettingsDestination.HOME
        }
    }

    BackHandler(enabled = destination != SettingsDestination.HOME, onBack = goBack)

    when (destination) {
        SettingsDestination.HOME -> SettingsHomeScreen(
            activeMediaType = settings.activeMediaType,
            noPacksInstalled = noInstalledPacks,
            availableMediaTypes = availableMediaTypes,
            onMediaTypeSelected = viewModel::updateActiveMediaType,
            allAvailable = allAvailable,
            allSelected = allSelected,
            onAllSelected = viewModel::selectAllMedia,
            onUnavailableMediaType = { destination = SettingsDestination.GENERAL_CATEGORIES },
            onGeneral = { destination = SettingsDestination.GENERAL },
            onActivePack = { destination = SettingsDestination.ACTIVE_PACK },
            onAbout = { destination = SettingsDestination.ABOUT },
            onDonation = { destination = SettingsDestination.DONATION }
        )
        SettingsDestination.GENERAL -> GeneralSettingsScreen(
            onCategories = { destination = SettingsDestination.GENERAL_CATEGORIES },
            onTheme = { destination = SettingsDestination.GENERAL_THEME },
            onIcons = { destination = SettingsDestination.ICON },
            onLanguage = { destination = SettingsDestination.LANGUAGE },
            onBack = goBack
        )
        SettingsDestination.ACTIVE_PACK -> ActivePackSettingsScreen(
            activeMediaType = settings.activeMediaType,
            allSelected = allSelected,
            personalDataTransfer = personalDataTransfer,
            onDisplay = { destination = SettingsDestination.GENERAL_DISPLAY },
            onCovers = { destination = SettingsDestination.GENERAL_COVERS },
            onRating = { destination = SettingsDestination.RATING },
            onShareRanking = {
                val scope = if (allSelected) ViewingScope.All
                else ViewingScope.Pack(settings.activeMediaType)
                viewModel.loadRankingShare(scope)
                destination = SettingsDestination.SHARE_RANKING
            },
            onExportPersonalData = {
                val suffix = if (allSelected) "all" else settings.activeMediaType.toPackId().value
                exportBackup.launch("RankOffline-$suffix-personal-data.json")
            },
            onImportPersonalData = {
                importBackup.launch(arrayOf("application/json", "text/json", "text/plain"))
            },
            onBack = goBack
        )
        SettingsDestination.GENERAL_DISPLAY -> DisplaySettingsScreen(
            settings = settings,
            allSelected = allSelected,
            onStickyScoreChange = viewModel::updateStickyScore,
            onShowImagesChange = viewModel::updateShowImages,
            onOneCoverPerGroupChange = viewModel::updateOneCoverPerGroup,
            onBack = goBack
        )
        SettingsDestination.GENERAL_THEME -> ThemeSettingsScreen(
            selectedTheme = settings.themeSelection,
            customPalettes = settings.customPalettes,
            builtInOverrides = settings.builtInPaletteOverrides,
            hiddenBuiltIns = settings.hiddenBuiltInPalettes,
            activePalette = settings.activePalette,
            onThemeSelected = viewModel::updateThemeSelection,
            onSaveCustomTheme = viewModel::saveCustomTheme,
            onDeleteCustomTheme = viewModel::deleteCustomTheme,
            onSaveBuiltInTheme = viewModel::saveBuiltInTheme,
            onDeleteBuiltInTheme = viewModel::deleteBuiltInTheme,
            onResetBuiltInThemes = viewModel::resetBuiltInThemes,
            onBack = goBack
        )
        SettingsDestination.GENERAL_CATEGORIES -> CategorySettingsScreen(
            categoryPackStates = categoryPackStates,
            packPersonalStorage = packPersonalStorage,
            downloadProgress = categoryDownloadProgress,
            onDownloadCategory = viewModel::downloadCategory,
            onCancelCategoryCoverDownload = viewModel::cancelInitialCoverDownload,
            onRemoveCategory = viewModel::removeCategoryCatalog,
            onPurgeCategory = { viewModel.purgePack(it.toPackId()) },
            onBack = goBack
        )
        SettingsDestination.SHARE_RANKING -> RankingShareScreen(
            data = rankingShareData,
            readyMediaTypes = availableMediaTypes,
            allAvailable = allAvailable,
            initialShowImages = { scope ->
                when (scope) {
                    ViewingScope.All -> settings.allViewSettings.showImages
                    is ViewingScope.Pack -> settings.mediaSettings.getValue(scope.mediaType).showImages
                }
            },
            exporting = rankingShareExport.rendering,
            onScopeSelected = viewModel::loadRankingShare,
            scopeSelectionEnabled = false,
            onContinue = viewModel::exportRankingShare,
            onBack = goBack
        )
        SettingsDestination.GENERAL_COVERS -> CoversSettingsScreen(
            activeMediaType = settings.activeMediaType,
            preloadProgress = preloadProgress,
            downloadedCoverCount = downloadedCoverCount,
            onDownloadTop = viewModel::downloadTopCovers,
            onCancelPreload = viewModel::cancelCoverPreload,
            onClearCovers = viewModel::clearDownloadedCovers,
            onVisibilityChanged = viewModel::setGeneralSettingsVisible,
            onBack = goBack
        )
        SettingsDestination.LANGUAGE -> LanguageSettingsScreen(
            selectedLanguage = settings.language,
            onLanguageSelected = viewModel::updateLanguage,
            onBack = goBack
        )
        SettingsDestination.RATING -> RatingSettingsScreen(
            elaborateRatingEnabled = settings.elaborateRatingEnabled,
            onElaborateRatingEnabledChange = viewModel::updateElaborateRatingEnabled,
            onProfiles = { destination = SettingsDestination.RATING_PROFILES },
            onCategories = { destination = SettingsDestination.RATING_CATEGORIES },
            onBack = goBack
        )
        SettingsDestination.RATING_CATEGORIES -> RatingCategoriesScreen(
            ratingConfig = settings.ratingConfig,
            onUpdate = viewModel::updateRatingCategory,
            onCreate = viewModel::createRatingCategory,
            onDeleteCategory = viewModel::deleteRatingCategory,
            onReset = viewModel::resetRatingCategories,
            onBack = goBack
        )
        SettingsDestination.RATING_PROFILES -> RatingProfilesScreen(
            profilesConfig = settings.ratingProfiles,
            categories = settings.ratingConfig.categories,
            onUpdate = viewModel::updateRatingProfiles,
            onDelete = viewModel::deleteRatingProfile,
            onReset = viewModel::resetRatingProfiles,
            onBack = goBack
        )
        SettingsDestination.ICON -> IconSettingsScreen(
            selectedIcon = settings.appIcon,
            onIconSelected = viewModel::updateAppIcon,
            onBack = goBack
        )
        SettingsDestination.ABOUT -> LicenceAboutScreen(catalogCount = catalogCount, onBack = goBack)
        SettingsDestination.DONATION -> DonationSettingsScreen(onBack = goBack)
    }
    backupPreview?.takeUnless { confirmReplaceBackup }?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                if (!personalDataTransfer.active) viewModel.dismissPersonalDataImport()
            },
            title = { Text(stringResource(R.string.personal_backup_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.personal_backup_preview_body,
                            preview.ratingCount,
                            preview.groupRatingCount,
                            preview.packIds.size
                        )
                    )
                    Text(
                        stringResource(R.string.personal_backup_preview_policy),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissPersonalDataImport,
                    enabled = !personalDataTransfer.active
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = !personalDataTransfer.active, onClick = {
                        viewModel.importPersonalData(PersonalDataImportMode.MERGE_NEWER)
                    }) { Text(stringResource(R.string.personal_backup_merge)) }
                    TextButton(enabled = !personalDataTransfer.active, onClick = {
                        confirmReplaceBackup = true
                    }) {
                        Text(
                            stringResource(R.string.personal_backup_replace),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )
    }
    if (confirmReplaceBackup) {
        AlertDialog(
            onDismissRequest = { if (!personalDataTransfer.active) confirmReplaceBackup = false },
            title = { Text(stringResource(R.string.personal_backup_replace_confirm_title)) },
            text = { Text(stringResource(R.string.personal_backup_replace_confirm_body)) },
            dismissButton = {
                TextButton(
                    enabled = !personalDataTransfer.active,
                    onClick = { confirmReplaceBackup = false }
                ) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    enabled = !personalDataTransfer.active,
                    onClick = {
                        confirmReplaceBackup = false
                        viewModel.importPersonalData(PersonalDataImportMode.REPLACE)
                    }
                ) {
                    Text(stringResource(R.string.personal_backup_replace), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

private enum class SettingsDestination {
    HOME,
    GENERAL,
    ACTIVE_PACK,
    GENERAL_DISPLAY,
    GENERAL_THEME,
    GENERAL_CATEGORIES,
    SHARE_RANKING,
    GENERAL_COVERS,
    LANGUAGE,
    RATING,
    RATING_PROFILES,
    RATING_CATEGORIES,
    ICON,
    ABOUT,
    DONATION
}

@Composable
private fun SettingsHomeScreen(
    activeMediaType: MediaType,
    noPacksInstalled: Boolean,
    availableMediaTypes: Set<MediaType>,
    onMediaTypeSelected: (MediaType) -> Unit,
    allAvailable: Boolean,
    allSelected: Boolean,
    onAllSelected: () -> Unit,
    onUnavailableMediaType: (MediaType) -> Unit,
    onGeneral: () -> Unit,
    onActivePack: () -> Unit,
    onAbout: () -> Unit,
    onDonation: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MediaScreenTitle(
            title = stringResource(R.string.settings_title),
            activeMediaType = activeMediaType,
            onMediaTypeSelected = onMediaTypeSelected,
            availableMediaTypes = availableMediaTypes,
            onDownloadPack = { onUnavailableMediaType(activeMediaType) },
            noPacksInstalled = noPacksInstalled,
            allAvailable = allAvailable,
            allSelected = allSelected,
            onAllSelected = onAllSelected
        )
        SettingsMenuItem(
            icon = Icons.Default.Settings,
            title = stringResource(R.string.settings_general_title),
            description = stringResource(R.string.settings_general_description),
            onClick = onGeneral
        )
        if (availableMediaTypes.isNotEmpty()) {
            SettingsMenuItem(
                icon = Icons.Default.Tune,
                title = stringResource(R.string.settings_active_pack_title),
                description = stringResource(
                    R.string.settings_active_pack_description,
                    if (allSelected) stringResource(R.string.media_all)
                    else stringResource(activeMediaType.labelResource)
                ),
                onClick = onActivePack
            )
        }
        SettingsMenuItem(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_about_title),
            description = stringResource(R.string.settings_about_description),
            onClick = onAbout
        )
        SettingsMenuItem(
            icon = Icons.Default.Favorite,
            title = stringResource(R.string.settings_donation_title),
            description = stringResource(R.string.settings_donation_description),
            onClick = onDonation
        )
    }
}

@Composable
private fun IconSettingsScreen(
    selectedIcon: AppIcon,
    onIconSelected: (AppIcon) -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_icon_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(AppIcon.selectableIcons.chunked(2)) { icons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    icons.forEach { icon ->
                        IconOptionCard(
                            icon = icon,
                            selected = icon == selectedIcon,
                            onClick = { onIconSelected(icon) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (icons.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun IconOptionCard(
    icon: AppIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayName = stringResource(icon.displayNameResource)
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { if (!selected) onClick() }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                Panel
            }
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(18.dp)
    ) {
        Box(Modifier.fillMaxSize().padding(14.dp)) {
            Image(
                painter = painterResource(icon.previewResource),
                contentDescription = stringResource(R.string.icon_content_description, displayName),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, top = 16.dp, end = 22.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(18.dp))
            )
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

@Composable
private fun SettingsActionButton(
    onClick: () -> Unit,
    label: String,
    icon: ImageVector,
    filled: Boolean,
    enabled: Boolean = true
) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            content = content
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            content = content
        )
    }
}

@Composable
private fun SettingsMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.settings_open_item, title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubscreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .dismissFocusOnUnconsumedTap(focusManager),
        containerColor = Bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            content()
        }
    }
}

@Composable
private fun LanguageSettingsScreen(
    selectedLanguage: SupportedLanguage,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.language_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(SupportedLanguage.entries) { language ->
                val selected = language == selectedLanguage
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (!selected) onLanguageSelected(language)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Panel
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(language.displayNameResource),
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        RadioButton(
                            selected = selected,
                            onClick = { if (!selected) onLanguageSelected(language) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsScreen(
    onCategories: () -> Unit,
    onTheme: () -> Unit,
    onIcons: () -> Unit,
    onLanguage: () -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_general_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                SettingsMenuItem(
                    icon = Icons.Default.Inventory2,
                    title = stringResource(R.string.categories_title),
                    description = stringResource(R.string.categories_settings_description),
                    onClick = onCategories
                )
            }
            item {
                SettingsMenuItem(
                    icon = Icons.Default.Palette,
                    title = stringResource(R.string.settings_theme_title),
                    description = stringResource(R.string.settings_theme_description),
                    onClick = onTheme
                )
            }
            item {
                SettingsMenuItem(
                    icon = Icons.Default.Image,
                    title = stringResource(R.string.settings_icon_title),
                    description = stringResource(R.string.settings_icon_description),
                    onClick = onIcons
                )
            }
            item {
                SettingsMenuItem(
                    icon = Icons.Default.Language,
                    title = stringResource(R.string.language_title),
                    description = stringResource(R.string.language_description),
                    onClick = onLanguage
                )
            }
        }
    }
}

@Composable
private fun ActivePackSettingsScreen(
    activeMediaType: MediaType,
    allSelected: Boolean,
    personalDataTransfer: PersonalDataTransferState,
    onDisplay: () -> Unit,
    onCovers: () -> Unit,
    onRating: () -> Unit,
    onShareRanking: () -> Unit,
    onExportPersonalData: () -> Unit,
    onImportPersonalData: () -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(
        stringResource(
            R.string.settings_active_pack_screen_title,
            if (allSelected) stringResource(R.string.media_all)
            else stringResource(activeMediaType.labelResource)
        ),
        onBack
    ) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                SettingsMenuItem(
                    icon = Icons.Default.Visibility,
                    title = stringResource(R.string.general_display_section),
                    description = stringResource(R.string.settings_display_description),
                    onClick = onDisplay
                )
            }
            if (!allSelected) {
                item {
                    SettingsMenuItem(
                        icon = Icons.Default.CloudDownload,
                        title = stringResource(R.string.covers_settings_title),
                        description = stringResource(R.string.covers_settings_description),
                        onClick = onCovers
                    )
                }
                item {
                    SettingsMenuItem(
                        icon = Icons.Default.Star,
                        title = stringResource(R.string.settings_rating_title),
                        description = stringResource(R.string.settings_rating_description),
                        onClick = onRating
                    )
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            stringResource(R.string.personal_backup_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            stringResource(
                                if (allSelected) R.string.personal_backup_scope_explanation_all
                                else R.string.personal_backup_scope_explanation
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (personalDataTransfer.active) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                stringResource(R.string.personal_backup_working),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TransferActionRow(
                            onClick = onShareRanking,
                            title = stringResource(R.string.ranking_share_title),
                            subtitle = stringResource(R.string.ranking_share_subtitle),
                            icon = Icons.Default.Share,
                            enabled = !personalDataTransfer.active
                        )
                        TransferActionRow(
                            onClick = onExportPersonalData,
                            title = stringResource(R.string.personal_backup_export),
                            subtitle = stringResource(R.string.personal_backup_export_subtitle),
                            icon = Icons.Default.DownloadForOffline,
                            enabled = !personalDataTransfer.active
                        )
                        TransferActionRow(
                            onClick = onImportPersonalData,
                            title = stringResource(R.string.personal_backup_import),
                            subtitle = stringResource(R.string.personal_backup_import_subtitle),
                            icon = Icons.Default.UploadFile,
                            enabled = !personalDataTransfer.active
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSettingsScreen(
    selectedTheme: ThemeSelection,
    customPalettes: List<CustomAppPalette>,
    builtInOverrides: Map<AppThemePalette, CustomAppPalette>,
    hiddenBuiltIns: Set<AppThemePalette>,
    activePalette: AppPalette,
    onThemeSelected: (ThemeSelection) -> Unit,
    onSaveCustomTheme: (CustomAppPalette, Boolean) -> Unit,
    onDeleteCustomTheme: (String) -> Unit,
    onSaveBuiltInTheme: (AppThemePalette, CustomAppPalette) -> Unit,
    onDeleteBuiltInTheme: (AppThemePalette) -> Unit,
    onResetBuiltInThemes: () -> Unit,
    onBack: () -> Unit
) {
    var editor by remember { mutableStateOf<CustomThemeEditorState?>(null) }
    BackHandler(enabled = editor != null) { editor = null }

    editor?.let { state ->
        CustomThemeEditorScreen(
            initial = state.palette,
            isNew = state.isNew,
            canDelete = AppThemePalette.entries.size - hiddenBuiltIns.size + customPalettes.size > 1,
            onSave = { value ->
                state.builtInSource?.let { onSaveBuiltInTheme(it, value) } ?: run {
                    val wasSelected = (selectedTheme as? ThemeSelection.Custom)?.id == value.id
                    onSaveCustomTheme(value, state.isNew || wasSelected)
                }
                editor = null
            },
            onDelete = { value ->
                state.builtInSource?.let(onDeleteBuiltInTheme) ?: onDeleteCustomTheme(value.id)
                editor = null
            },
            onBack = { editor = null }
        )
        return
    }

    SettingsSubscreen(stringResource(R.string.settings_theme_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(
                AppThemePalette.entries.filterNot { it in hiddenBuiltIns },
                key = { it.persistedValue }
            ) { palette ->
                val override = builtInOverrides[palette]
                val displayedName = override?.name ?: stringResource(palette.displayNameResource)
                val displayedPalette = override?.palette ?: palette.palette()
                ThemeOptionCard(
                    name = displayedName,
                    palette = displayedPalette,
                    selected = selectedTheme == ThemeSelection.BuiltIn(palette),
                    onSelect = { onThemeSelected(ThemeSelection.BuiltIn(palette)) },
                    onEdit = {
                        editor = CustomThemeEditorState(
                            palette = CustomAppPalette(
                                id = palette.persistedValue,
                                name = displayedName,
                                palette = displayedPalette
                            ),
                            isNew = false,
                            builtInSource = palette
                        )
                    }
                )
            }
            items(customPalettes, key = CustomAppPalette::id) { custom ->
                ThemeOptionCard(
                    name = custom.name,
                    palette = custom.palette,
                    selected = selectedTheme == ThemeSelection.Custom(custom.id),
                    onSelect = { onThemeSelected(ThemeSelection.Custom(custom.id)) },
                    onEdit = { editor = CustomThemeEditorState(custom, isNew = false) }
                )
            }
            item {
                SettingsActionButton(
                    onClick = {
                        editor = CustomThemeEditorState(
                            palette = CustomAppPalette(
                                id = UUID.randomUUID().toString(),
                                name = "",
                                palette = activePalette
                            ),
                            isNew = true,
                            builtInSource = null
                        )
                    },
                    label = stringResource(R.string.theme_create),
                    icon = Icons.Default.Add,
                    filled = true
                )
            }
            item {
                SettingsActionButton(
                    onClick = onResetBuiltInThemes,
                    label = stringResource(R.string.theme_reset_builtins),
                    icon = Icons.Default.Restore,
                    filled = false
                )
            }
        }
    }
}

private data class CustomThemeEditorState(
    val palette: CustomAppPalette,
    val isNew: Boolean,
    val builtInSource: AppThemePalette? = null
)

@Composable
private fun ThemeOptionCard(
    name: String,
    palette: AppPalette,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { if (!selected) onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PaletteSwatches(palette)
            Spacer(Modifier.width(16.dp))
            Text(name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            onEdit?.let { edit ->
                IconButton(onClick = edit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.theme_edit))
                }
            }
            RadioButton(selected = selected, onClick = { if (!selected) onSelect() })
        }
    }
}

@Composable
private fun PaletteSwatches(palette: AppPalette) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            palette.background,
            palette.surface,
            palette.primary,
            palette.secondary
        ).forEach { color ->
            Box(
                Modifier
                    .size(width = 16.dp, height = 36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(color)
            )
        }
    }
}

private enum class ThemeColorSlot {
    BACKGROUND,
    SURFACE,
    PRIMARY,
    SECONDARY
}

@Composable
private fun CustomThemeEditorScreen(
    initial: CustomAppPalette,
    isNew: Boolean,
    canDelete: Boolean,
    onSave: (CustomAppPalette) -> Unit,
    onDelete: (CustomAppPalette) -> Unit,
    onBack: () -> Unit
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var palette by remember(initial.id) { mutableStateOf(initial.palette) }
    var editingColor by remember(initial.id) { mutableStateOf<ThemeColorSlot?>(null) }
    var confirmDelete by remember(initial.id) { mutableStateOf(false) }
    val normalizedName = name.trim()

    SettingsSubscreen(
        stringResource(if (isNew) R.string.theme_create else R.string.theme_edit),
        onBack
    ) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= MAX_CUSTOM_THEME_NAME_LENGTH) name = it },
                    label = { Text(stringResource(R.string.theme_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { CustomThemePreview(palette) }
            items(ThemeColorSlot.entries, key = { it.name }) { slot ->
                ThemeColorRow(
                    label = stringResource(slot.labelResource()),
                    color = palette.color(slot),
                    onClick = { editingColor = slot }
                )
            }
            item {
                Button(
                    enabled = normalizedName.isNotEmpty(),
                    onClick = {
                        onSave(initial.copy(name = normalizedName, palette = palette))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
            if (!isNew && canDelete) {
                item {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.theme_delete))
                    }
                }
            }
        }
    }

    editingColor?.let { slot ->
        RankOfflineColorPickerDialog(
            initialColor = palette.color(slot).toHexRgb(),
            onDismiss = { editingColor = null },
            onApply = { hex ->
                palette = palette.withColor(slot, Color(android.graphics.Color.parseColor(hex)))
                editingColor = null
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    stringResource(
                        R.string.theme_delete_confirmation_title,
                        normalizedName.ifEmpty { initial.name }
                    )
                )
            },
            text = { Text(stringResource(R.string.theme_delete_confirmation_body)) },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(initial)
                }) {
                    Text(stringResource(R.string.theme_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun CustomThemePreview(palette: AppPalette) {
    MaterialTheme(colorScheme = palette.toColorScheme()) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.theme_preview),
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.theme_preview_card),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.secondary
                            ).forEach { color ->
                                Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(color))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeColorRow(label: String, color: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(color)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))
            )
        }
    }
}

private fun ThemeColorSlot.labelResource(): Int = when (this) {
    ThemeColorSlot.BACKGROUND -> R.string.theme_color_background
    ThemeColorSlot.SURFACE -> R.string.theme_color_surface
    ThemeColorSlot.PRIMARY -> R.string.theme_color_primary
    ThemeColorSlot.SECONDARY -> R.string.theme_color_secondary
}

private fun AppPalette.color(slot: ThemeColorSlot): Color = when (slot) {
    ThemeColorSlot.BACKGROUND -> background
    ThemeColorSlot.SURFACE -> surface
    ThemeColorSlot.PRIMARY -> primary
    ThemeColorSlot.SECONDARY -> secondary
}

private fun AppPalette.withColor(slot: ThemeColorSlot, color: Color): AppPalette = when (slot) {
    ThemeColorSlot.BACKGROUND -> copy(background = color)
    ThemeColorSlot.SURFACE -> copy(surface = color)
    ThemeColorSlot.PRIMARY -> copy(primary = color)
    ThemeColorSlot.SECONDARY -> copy(secondary = color)
}

@Composable
private fun DisplaySettingsScreen(
    settings: AppSettingsState,
    allSelected: Boolean,
    onStickyScoreChange: (Boolean) -> Unit,
    onShowImagesChange: (Boolean) -> Unit,
    onOneCoverPerGroupChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.general_display_section), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.general_sticky_score), fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.general_sticky_score_description), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = if (allSelected) settings.allViewSettings.stickyScore else settings.stickyScore,
                                onCheckedChange = onStickyScoreChange
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.general_show_images), fontWeight = FontWeight.SemiBold)
                                Text(stringResource(R.string.general_show_images_description), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = if (allSelected) settings.allViewSettings.showImages else settings.showImages,
                                onCheckedChange = onShowImagesChange
                            )
                        }
                        if (!allSelected) Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.covers_one_per_group),
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    stringResource(R.string.covers_one_per_group_description),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.oneCoverPerGroup,
                                onCheckedChange = onOneCoverPerGroupChange
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySettingsScreen(
    categoryPackStates: Map<MediaType, CategoryPackState>,
    packPersonalStorage: Map<MediaType, PackPersonalStorage>,
    downloadProgress: CategoryDownloadProgress?,
    onDownloadCategory: (MediaType) -> Unit,
    onCancelCategoryCoverDownload: (MediaType) -> Unit,
    onRemoveCategory: (MediaType) -> Unit,
    onPurgeCategory: (MediaType) -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.categories_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Column {
                    CategoryManagementContent(
                        categoryPacks = categoryPackStates,
                        packPersonalStorage = packPersonalStorage,
                        downloadProgress = downloadProgress,
                        onDownloadCategory = onDownloadCategory,
                        onCancelCategoryCoverDownload = onCancelCategoryCoverDownload,
                        onRemoveCategory = onRemoveCategory,
                        onPurgeCategory = onPurgeCategory
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferActionRow(
    onClick: () -> Unit,
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.settings_open_item, title))
        }
    }
}

@Composable
private fun CoversSettingsScreen(
    activeMediaType: MediaType,
    preloadProgress: CoverPreloadProgress,
    downloadedCoverCount: Int,
    onDownloadTop: (Int) -> Unit,
    onCancelPreload: () -> Unit,
    onClearCovers: () -> Unit,
    onVisibilityChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var showClearConfirmation by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onVisibilityChanged(true)
        onDispose { onVisibilityChanged(false) }
    }
    SettingsSubscreen(stringResource(R.string.covers_settings_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.covers_settings_explanation),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        Text(
                            stringResource(R.string.covers_downloaded_count, downloadedCoverCount),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            stringResource(activeMediaType.labelResource),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        listOf(250, 500, 1000, 5000).forEach { limit ->
                            Button(
                                onClick = { onDownloadTop(limit) },
                                enabled = !preloadProgress.isActive,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(
                                        if (preloadProgress.status in setOf(
                                                CoverPreloadStatus.CANCELLED,
                                                CoverPreloadStatus.FAILED
                                            ) && preloadProgress.target == limit
                                        ) R.string.covers_continue_top
                                        else R.string.covers_download_top,
                                        limit
                                    )
                                )
                            }
                        }
                        CoverPreloadProgressView(preloadProgress)
                        if (preloadProgress.isActive) {
                            TextButton(
                                onClick = onCancelPreload,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.action_cancel)) }
                        }
                        OutlinedButton(
                            onClick = { showClearConfirmation = true },
                            enabled = !preloadProgress.isActive,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.covers_clear))
                        }
                    }
                }
            }
        }
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.covers_clear_title)) },
            text = { Text(stringResource(R.string.covers_clear_explanation)) },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirmation = false
                    onClearCovers()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

@Composable
private fun CoverPreloadProgressView(preloadProgress: CoverPreloadProgress) {
    if (preloadProgress.status == CoverPreloadStatus.IDLE) return
    Spacer(Modifier.height(12.dp))
    Text(
        when (preloadProgress.status) {
            CoverPreloadStatus.WAITING_FOR_COVERS ->
                stringResource(R.string.category_phase_waiting_covers)
            CoverPreloadStatus.PREPARING_COVER_LIST ->
                stringResource(R.string.covers_preparing_list)
            CoverPreloadStatus.RETRYING_COVERS ->
                stringResource(R.string.covers_downloading_rated)
            CoverPreloadStatus.COMPLETED -> stringResource(R.string.covers_completed)
            CoverPreloadStatus.CANCELLED -> stringResource(R.string.covers_download_interrupted)
            CoverPreloadStatus.FAILED -> stringResource(R.string.covers_download_interrupted)
            else -> stringResource(R.string.covers_downloading_rated)
        },
        fontWeight = FontWeight.SemiBold
    )
    if (preloadProgress.status == CoverPreloadStatus.PREPARING_COVER_LIST ||
        preloadProgress.status == CoverPreloadStatus.WAITING_FOR_COVERS
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    } else {
        Text(
            stringResource(R.string.covers_progress, preloadProgress.available, preloadProgress.target),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
        LinearProgressIndicator(
            progress = { preloadProgress.fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
    Text(
        stringResource(
            R.string.covers_progress_details_explicit,
            preloadProgress.available,
            preloadProgress.unavailable,
            preloadProgress.errors
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp)
    )
    when {
        preloadProgress.status == CoverPreloadStatus.COMPLETED -> Text(
            stringResource(R.string.covers_completed),
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        preloadProgress.status == CoverPreloadStatus.FAILED ||
            preloadProgress.status == CoverPreloadStatus.CANCELLED -> Text(
            stringResource(R.string.covers_download_interrupted),
            color = MaterialTheme.colorScheme.error,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun RatingSettingsScreen(
    elaborateRatingEnabled: Boolean,
    onElaborateRatingEnabledChange: (Boolean) -> Unit,
    onProfiles: () -> Unit,
    onCategories: () -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_rating_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            stringResource(R.string.rating_mode),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        listOf(
                            false to stringResource(R.string.rating_simple),
                            true to stringResource(R.string.rating_elaborate)
                        ).forEach { (isElaborate, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onElaborateRatingEnabledChange(isElaborate)
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = elaborateRatingEnabled == isElaborate,
                                    onClick = { onElaborateRatingEnabledChange(isElaborate) }
                                )
                                Text(label)
                            }
                        }
                    }
                }
            }
            item {
                SettingsMenuItem(
                    icon = Icons.Default.Tune,
                    title = stringResource(R.string.rating_profiles),
                    description = stringResource(R.string.rating_profiles_description),
                    onClick = onProfiles
                )
            }
            item {
                SettingsMenuItem(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(R.string.rating_categories),
                    description = stringResource(R.string.rating_categories_description),
                    onClick = onCategories
                )
            }
        }
    }
}

@Composable
private fun RatingCategoriesScreen(
    ratingConfig: RatingSystemConfig,
    onUpdate: (RatingCategory) -> Unit,
    onCreate: (RatingCategory) -> Unit,
    onDeleteCategory: (RatingCategory) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    val newCategoryName = stringResource(R.string.rating_new_category_name)
    val newCategoryDescription = stringResource(R.string.rating_new_category_description)
    SettingsSubscreen(stringResource(R.string.rating_categories), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(ratingConfig.categories.sortedBy { it.order }, key = { it.id }) { category ->
                RatingCategoryEditor(category, onUpdate, onDelete = { onDeleteCategory(category) })
            }
            item {
                SettingsActionButton(
                    onClick = {
                        onCreate(
                            RatingCategory(
                                id = "custom_${UUID.randomUUID()}",
                                name = newCategoryName,
                                description = newCategoryDescription,
                                weight = 0.0,
                                color = "#7EC7FF",
                                order = (ratingConfig.categories.maxOfOrNull { it.order } ?: -1) + 1
                            )
                        )
                    },
                    label = stringResource(R.string.rating_add_category),
                    icon = Icons.Default.Add,
                    filled = true
                )
            }
            item {
                SettingsActionButton(
                    onClick = { confirmReset = true },
                    label = stringResource(R.string.rating_reset_categories),
                    icon = Icons.Default.Restore,
                    filled = false
                )
            }
        }
    }
    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(R.string.rating_reset_categories),
            text = stringResource(R.string.rating_reset_categories_confirmation),
            onDismiss = { confirmReset = false },
            onConfirm = { confirmReset = false; onReset() }
        )
    }
}

@Composable
private fun RatingProfilesScreen(
    profilesConfig: RatingProfilesConfig,
    categories: List<RatingCategory>,
    onUpdate: (RatingProfilesConfig) -> Unit,
    onDelete: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var confirmReset by remember { mutableStateOf(false) }
    val newProfileName = stringResource(R.string.rating_new_profile_name)
    SettingsSubscreen(stringResource(R.string.rating_profiles), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(profilesConfig.profiles, key = { it.id }) { profile ->
                RatingProfileEditor(
                    profile = profile,
                    categories = categories.sortedBy { it.order },
                    canDelete = profilesConfig.profiles.size > 1,
                    onUpdate = { changed ->
                        onUpdate(profilesConfig.copy(profiles = profilesConfig.profiles.map {
                            if (it.id == changed.id) changed else it
                        }))
                    },
                    onDelete = { onDelete(profile.id) }
                )
            }
            item {
                SettingsActionButton(
                    onClick = {
                        val profile = RatingProfile(
                            id = "custom_${UUID.randomUUID()}",
                            name = newProfileName,
                            weights = categories.associate { it.id to 5.0 }
                        )
                        onUpdate(profilesConfig.copy(profiles = profilesConfig.profiles + profile))
                    },
                    label = stringResource(R.string.rating_create_profile),
                    icon = Icons.Default.Add,
                    filled = true
                )
            }
            item {
                SettingsActionButton(
                    onClick = { confirmReset = true },
                    label = stringResource(R.string.rating_reset_profiles),
                    icon = Icons.Default.Restore,
                    filled = false
                )
            }
        }
    }
    if (confirmReset) {
        ConfirmationDialog(
            title = stringResource(R.string.rating_reset_profiles),
            text = stringResource(R.string.rating_reset_profiles_confirmation),
            onDismiss = { confirmReset = false },
            onConfirm = { confirmReset = false; onReset() }
        )
    }
}

@Composable
private fun RatingProfileEditor(
    profile: RatingProfile,
    categories: List<RatingCategory>,
    canDelete: Boolean,
    onUpdate: (RatingProfile) -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirmation by remember(profile.id) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = localizedProfileName(profile),
                    onValueChange = { onUpdate(profile.copy(name = it)) },
                    label = { Text(stringResource(R.string.rating_profile_name)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(enabled = canDelete, onClick = { showDeleteConfirmation = true }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rating_delete_profile), tint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            categories.forEach { category ->
                val weight = profile.weights[category.id] ?: 0.0
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(localizedCategoryName(category), modifier = Modifier.width(100.dp), maxLines = 1)
                    DiscreteSlider(
                        value = weight,
                        onValueChange = { value ->
                            snapWeight(value)?.let { onUpdate(profile.copy(weights = profile.weights + (category.id to it))) }
                        },
                        min = WEIGHT_MIN,
                        max = WEIGHT_MAX,
                        step = WEIGHT_STEP,
                        accessibilityLabel = stringResource(R.string.category_weight_slider_accessibility),
                        valueDescription = stringResource(R.string.category_weight_percent, weight.toInt()),
                        modifier = Modifier.weight(1f)
                    )
                    Text(stringResource(R.string.category_weight_percent, weight.toInt()), modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            if (!canDelete) {
                Text(stringResource(R.string.rating_last_profile_required), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
    if (showDeleteConfirmation) {
        ConfirmationDialog(
            title = stringResource(R.string.rating_delete_profile),
            text = stringResource(R.string.rating_delete_profile_confirmation, localizedProfileName(profile)),
            onDismiss = { showDeleteConfirmation = false },
            onConfirm = { showDeleteConfirmation = false; onDelete() }
        )
    }
}

@Composable
private fun ConfirmationDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_apply)) } }
    )
}

@Composable
private fun LicenceAboutScreen(catalogCount: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentLocale = Locale.getDefault()
    val versionName = remember(context.packageName) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val formattedCatalogCount = remember(catalogCount, currentLocale) {
        NumberFormat.getIntegerInstance(currentLocale).format(catalogCount)
    }
    SettingsSubscreen(stringResource(R.string.settings_about_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                AboutCard("RankOffline", MaterialTheme.colorScheme.onSurface) {
                    Text(
                        stringResource(R.string.about_version, versionName),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        stringResource(R.string.catalog_local_count, formattedCatalogCount),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    AboutBody(stringResource(R.string.about_summary_multimedia))
                    AboutBody(stringResource(R.string.about_rankoffline_licence))
                    AttributionLink(
                        stringResource(R.string.about_source_code),
                        AttributionRegistry.SOURCE_CODE_URL
                    )
                }
            }
            item {
                AboutCard(stringResource(R.string.about_data_sources), MaterialTheme.colorScheme.onSurface) {
                    AboutProviderSection(
                        stringResource(R.string.nav_anime),
                        stringResource(R.string.about_anime_sources_description),
                        AttributionRegistry.providers.take(4)
                    )
                    AttributionLink(
                        "manami-project/anime-offline-database",
                        AttributionRegistry.ANIME_OFFLINE_DATABASE_ORIGINAL_URL
                    )
                    AboutProviderSection(
                        stringResource(R.string.media_manga),
                        stringResource(R.string.about_manga_source_description),
                        listOf(AttributionRegistry.providers[4])
                    )
                    Text(
                        stringResource(R.string.about_movies_series),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Image(
                        painter = painterResource(R.drawable.tmdb_primary_short_blue),
                        contentDescription = stringResource(R.string.about_tmdb_logo_description),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .width(114.dp)
                            .height(49.dp)
                            .clickable { openTrustedUrl(context, AttributionRegistry.TMDB_URL) }
                    )
                    AboutBody(stringResource(R.string.about_tmdb_source_description), topPadding = 4.dp)
                    AboutBody(stringResource(R.string.about_tmdb_notice), topPadding = 6.dp)
                    AttributionLink("TMDB", AttributionRegistry.TMDB_URL)
                    AboutProviderSection(
                        stringResource(R.string.media_books),
                        stringResource(R.string.about_open_library_source_description),
                        listOf(AttributionRegistry.providers[6])
                    )
                }
            }
            item {
                AboutCard(stringResource(R.string.about_data_licences), MaterialTheme.colorScheme.onSurface) {
                    AboutBody(stringResource(R.string.about_anime_database_licences))
                    AboutBody(stringResource(R.string.about_alias_database_licences))
                    AboutBody(stringResource(R.string.about_cc0_scope))
                    AboutBody(stringResource(R.string.about_open_library_licence_caveat))
                    AttributionLink("ODbL 1.0", AttributionRegistry.ODBL_URL)
                    AttributionLink("DbCL 1.0", AttributionRegistry.DBCL_URL)
                    AttributionLink("CC0 1.0", AttributionRegistry.CC0_URL)
                }
            }
            item {
                AboutCard(stringResource(R.string.about_open_source_software), MaterialTheme.colorScheme.onSurface) {
                    AboutBody(stringResource(R.string.about_software_licences))
                    AboutBody(stringResource(R.string.about_packaged_notices))
                    AttributionLink("GPL-3.0", AttributionRegistry.GPL_URL)
                    AttributionLink("Apache-2.0", AttributionRegistry.APACHE_URL)
                }
            }
            item {
                AboutCard(stringResource(R.string.about_legal_trademarks), MaterialTheme.colorScheme.onSurface) {
                    AboutBody(stringResource(R.string.about_non_affiliation))
                    AboutBody(stringResource(R.string.about_rankoffline_trademarks))
                    AttributionLink(
                        stringResource(R.string.about_trademark_policy),
                        AttributionRegistry.TRADEMARKS_URL
                    )
                }
            }
        }
    }
}

@Composable
private fun DonationSettingsScreen(onBack: () -> Unit) {
    SettingsSubscreen(stringResource(R.string.settings_donation_title), onBack) {
        Column(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AboutCard(stringResource(R.string.settings_donation_title), MaterialTheme.colorScheme.primary) {
                AboutBody(stringResource(R.string.settings_donation_body))
            }
        }
    }
}

@Composable
private fun AboutCard(
    title: String,
    titleColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = titleColor)
            content()
        }
    }
}

@Composable
private fun AboutBody(text: String, topPadding: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = topPadding)
    )
}

@Composable
private fun AboutProviderSection(
    heading: String,
    description: String,
    providers: List<ProviderAttribution>
) {
    Text(
        heading,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 12.dp)
    )
    AboutBody(description, topPadding = 4.dp)
    providers.forEach { provider -> AttributionLink(provider.name, provider.url) }
}

@Composable
private fun AttributionLink(label: String, url: String) {
    val context = LocalContext.current
    TextButton(
        onClick = { openTrustedUrl(context, url) },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp).size(14.dp)
        )
    }
}

private fun openTrustedUrl(context: Context, url: String) {
    if (url !in AttributionRegistry.trustedUrls) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun RatingCategoryEditor(category: RatingCategory, onUpdate: (RatingCategory) -> Unit, onDelete: () -> Unit) {
    val themePrimary = MaterialTheme.colorScheme.primary
    val themeSecondary = MaterialTheme.colorScheme.secondary
    val swatch = try {
        Color(android.graphics.Color.parseColor(category.color))
    } catch (_: Exception) {
        MaterialTheme.colorScheme.surface
    }
    var showColorPicker by remember(category.id) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(category.id) { mutableStateOf(false) }
    val colorContentDescription = stringResource(R.string.category_color_content_description)

    Card(
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = localizedCategoryName(category),
                    onValueChange = { onUpdate(category.copy(name = it)) },
                    label = { Text(stringResource(R.string.category_name)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(onClick = { showDeleteConfirmation = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            OutlinedTextField(
                value = localizedCategoryDescription(category),
                onValueChange = { onUpdate(category.copy(description = it)) },
                label = { Text(stringResource(R.string.category_description)) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.category_color),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp)
                )
                ThemeColorShortcut(
                    label = stringResource(R.string.category_color_primary),
                    color = themePrimary,
                    onClick = { onUpdate(category.withThemeColor(themePrimary)) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                ThemeColorShortcut(
                    label = stringResource(R.string.category_color_secondary),
                    color = themeSecondary,
                    onClick = { onUpdate(category.withThemeColor(themeSecondary)) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(swatch)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { showColorPicker = true }
                        .semantics {
                            contentDescription = colorContentDescription
                        }
                )
            }
        }
    }
    if (showColorPicker) {
        RankOfflineColorPickerDialog(
            initialColor = category.color,
            onDismiss = { showColorPicker = false },
            onApply = { color ->
                category.withHexColor(color)?.let(onUpdate)
                showColorPicker = false
            }
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.category_delete_title)) },
            text = {
                Text(stringResource(R.string.category_delete_explanation, localizedCategoryName(category)))
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmation = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun ThemeColorShortcut(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clickable(role = Role.Button, onClick = onClick),
        color = color,
        contentColor = bestContentColor(color),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(10.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
internal fun RankOfflineColorPickerDialog(
    initialColor: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val initialHsv = remember(initialColor) {
        HsvColor.fromHex(initialColor) ?: HsvColor.fromHex("#19D5E5")!!
    }
    var hsv by remember(initialColor) { mutableStateOf(initialHsv) }
    var hexInput by remember(initialColor) { mutableStateOf(initialHsv.toHex()) }
    val validColor = normalizeHexColor(hexInput)
    val previewColor = Color(hsv.toArgb())
    val saturationValueDescription = stringResource(R.string.color_picker_saturation_value)
    val hueDescription = stringResource(R.string.color_picker_hue)

    fun updateFromHsv(value: HsvColor) {
        hsv = value.normalized()
        hexInput = hsv.toHex()
    }

    AlertDialog(
        modifier = Modifier.dismissFocusOnUnconsumedTap(focusManager),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_picker_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(previewColor, RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                )
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .testTag("color_picker_saturation_value")
                        .aspectRatio(1.55f)
                        .clip(RoundedCornerShape(12.dp))
                        .semantics { contentDescription = saturationValueDescription }
                        .pointerInput(hsv.hue) {
                            fun select(position: Offset) {
                                updateFromHsv(
                                    hsv.copy(
                                        saturation = (position.x / size.width).coerceIn(0f, 1f),
                                        value = (1f - position.y / size.height).coerceIn(0f, 1f)
                                    )
                                )
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                select(down.position)
                                drag(down.id) { change ->
                                    select(change.position)
                                    change.consume()
                                }
                            }
                        }
                ) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color.White, Color.hsv(hsv.hue, 1f, 1f))
                        )
                    )
                    drawRect(
                        brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                    )
                    val marker = Offset(hsv.saturation * size.width, (1f - hsv.value) * size.height)
                    drawCircle(bestContentColor(previewColor), radius = 10.dp.toPx(), center = marker)
                    drawCircle(previewColor, radius = 7.dp.toPx(), center = marker)
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .testTag("color_picker_hue")
                        .height(34.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .semantics { contentDescription = hueDescription }
                        .pointerInput(Unit) {
                            fun select(position: Offset) {
                                updateFromHsv(hsv.copy(hue = position.x / size.width * 360f))
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                select(down.position)
                                drag(down.id) { change ->
                                    select(change.position)
                                    change.consume()
                                }
                            }
                        }
                ) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(
                                Color.Red,
                                Color.Yellow,
                                Color.Green,
                                Color.Cyan,
                                Color.Blue,
                                Color.Magenta,
                                Color.Red
                            )
                        )
                    )
                    val marker = Offset(hsv.hue / 360f * size.width, size.height / 2f)
                    drawCircle(bestContentColor(Color.hsv(hsv.hue, 1f, 1f)), 9.dp.toPx(), marker)
                    drawCircle(Color.hsv(hsv.hue, 1f, 1f), 6.dp.toPx(), marker)
                }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { value ->
                        hexInput = value
                        HsvColor.fromHex(value)?.let { hsv = it }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("color_picker_hex"),
                    label = { Text(stringResource(R.string.color_hex_label)) },
                    supportingText = {
                        if (hexInput.isNotBlank() && validColor == null) {
                            Text(stringResource(R.string.color_invalid))
                        }
                    },
                    isError = hexInput.isNotBlank() && validColor == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { focusManager.clearFocus(force = true) }
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(
                enabled = validColor != null,
                onClick = { validColor?.let { onApply(hsv.toHex()) } }
            ) { Text(stringResource(R.string.action_apply)) }
        }
    )
}

@Composable
internal fun CatalogSortOrder.criterionLabel(): String = when (this) {
    CatalogSortOrder.POPULARITY,
    CatalogSortOrder.POPULARITY_REVERSE -> stringResource(R.string.sort_popularity)
    CatalogSortOrder.TITLE_ASC,
    CatalogSortOrder.TITLE_DESC -> stringResource(R.string.sort_title_ascending)
}

@Composable
internal fun RankingSortOrder.localizedLabel(): String = stringResource(
    when (this) {
        RankingSortOrder.FINAL_SCORE -> R.string.sort_final_score
        RankingSortOrder.QUALITY_SCORE -> R.string.sort_quality_score
        RankingSortOrder.PERSONAL_TASTE -> R.string.sort_personal_taste
        RankingSortOrder.CATEGORY_SCORE -> R.string.sort_category_score
    }
)

@Composable
internal fun rankingSortLabel(
    sortOrder: RankingSortOrder,
    categoryId: String?,
    categories: List<RatingCategory>
): String {
    if (sortOrder != RankingSortOrder.CATEGORY_SCORE) return sortOrder.localizedLabel()
    val category = categories.firstOrNull { it.id == categoryId }
    return category?.let { localizedCategoryName(it) } ?: stringResource(R.string.sort_category_score)
}

@Composable
internal fun scoreLabel(v: Double): String = stringResource(when {
    v >= 9.75 -> R.string.score_flawless
    v >= 8.75 -> R.string.score_amazing
    v >= 7.75 -> R.string.score_great
    v >= 6.75 -> R.string.score_good
    v >= 5.75 -> R.string.score_decent
    v >= 4.75 -> R.string.score_average
    v >= 3.75 -> R.string.score_subpar
    v >= 2.75 -> R.string.score_poor
    v >= 1.75 -> R.string.score_bad
    else -> R.string.score_unwatchable
})

@Composable
internal fun localizedCategoryName(category: RatingCategory): String {
    val canonical = MediaType.entries.firstNotNullOfOrNull { mediaType ->
        DefaultSettings.canonicalCategory(mediaType, category.id)
            ?.takeIf { it.name == category.name && it.description == category.description }
            ?.let { mediaType to it }
    }
    val resource = canonical?.let { (mediaType, _) -> categoryNameResource(mediaType, category.id) }
    return resource?.let { stringResource(it) } ?: category.name
}

@Composable
internal fun localizedCategoryDescription(category: RatingCategory): String {
    val canonicalMedia = MediaType.entries.firstOrNull { mediaType ->
        DefaultSettings.canonicalCategory(mediaType, category.id)?.description == category.description
    }
    val resource = canonicalMedia?.let { categoryDescriptionResource(it, category.id) }
    return resource?.let { stringResource(it) } ?: category.description
}

@Composable
internal fun localizedProfileName(profile: RatingProfile): String {
    val isCanonical = MediaType.entries.any { mediaType ->
        DefaultSettings.canonicalProfile(mediaType, profile.id)?.name == profile.name
    }
    val resource = if (isCanonical) profileNameResource(profile.id) else null
    return resource?.let { stringResource(it) } ?: profile.name
}

private fun profileNameResource(id: String): Int? = when (id) {
    DefaultSettings.GENERAL_PROFILE_ID -> R.string.profile_general
    "narrative" -> R.string.profile_narrative
    "character_driven" -> R.string.profile_character_driven
    "visual_action" -> R.string.profile_visual_action
    "art_focused" -> R.string.profile_art_focused
    "prose_style" -> R.string.profile_prose_style
    "visual_direction" -> R.string.profile_visual_direction
    "technical_craft" -> R.string.profile_technical_craft
    "world_adventure" -> R.string.profile_world_adventure
    "atmospheric" -> R.string.profile_atmospheric
    else -> null
}

@Composable
internal fun localizedBonusName(bonus: AdditiveBonus): String =
    if (bonus.name == DefaultSettings.defaultAdditiveBonus.name) {
        stringResource(R.string.bonus_personal_taste)
    } else {
        bonus.name
    }

private fun categoryNameResource(mediaType: MediaType, id: String): Int? = when (id) {
    "writing" -> when (mediaType) {
        MediaType.ANIME -> R.string.category_writing
        MediaType.MANGA -> R.string.category_manga_writing
        MediaType.BOOK -> R.string.category_book_writing
        MediaType.SERIES -> R.string.category_series_writing
        MediaType.MOVIE -> R.string.category_movie_writing
    }
    "characters" -> R.string.category_characters
    "engagement" -> R.string.category_engagement
    "visuals" -> R.string.category_visuals
    "worldbuilding" -> R.string.category_worldbuilding
    "audio" -> R.string.category_audio
    "art" -> R.string.category_art
    "paneling" -> R.string.category_paneling
    "prose" -> R.string.category_prose
    "structure" -> R.string.category_structure
    "direction" -> R.string.category_direction
    "editing" -> R.string.category_editing
    "staging_immersion" -> R.string.category_staging_immersion
    else -> null
}

private fun categoryDescriptionResource(mediaType: MediaType, id: String): Int? = when (mediaType) {
    MediaType.ANIME -> when (id) {
        "writing" -> R.string.category_writing_description
        "characters" -> R.string.category_characters_description
        "engagement" -> R.string.category_engagement_description
        "visuals" -> R.string.category_visuals_description
        "worldbuilding" -> R.string.category_worldbuilding_description
        "audio" -> R.string.category_audio_description
        else -> null
    }
    MediaType.MANGA -> when (id) {
        "writing" -> R.string.category_manga_writing_description
        "characters" -> R.string.category_manga_characters_description
        "art" -> R.string.category_art_description
        "paneling" -> R.string.category_paneling_description
        "worldbuilding" -> R.string.category_manga_worldbuilding_description
        else -> null
    }
    MediaType.BOOK -> when (id) {
        "writing" -> R.string.category_book_writing_description
        "characters" -> R.string.category_book_characters_description
        "prose" -> R.string.category_prose_description
        "worldbuilding" -> R.string.category_book_worldbuilding_description
        "structure" -> R.string.category_structure_description
        else -> null
    }
    MediaType.SERIES -> when (id) {
        "writing" -> R.string.category_series_writing_description
        "characters" -> R.string.category_series_characters_description
        "direction" -> R.string.category_series_direction_description
        "visuals" -> R.string.category_series_visuals_description
        "worldbuilding" -> R.string.category_series_worldbuilding_description
        "audio" -> R.string.category_series_audio_description
        else -> null
    }
    MediaType.MOVIE -> when (id) {
        "writing" -> R.string.category_movie_writing_description
        "characters" -> R.string.category_movie_characters_description
        "direction" -> R.string.category_movie_direction_description
        "visuals" -> R.string.category_movie_visuals_description
        "staging_immersion" -> R.string.category_staging_immersion_description
        "editing" -> R.string.category_editing_description
        "audio" -> R.string.category_movie_audio_description
        else -> null
    }
}

internal fun scoreColor(v: Double): Color = when {
    v >= 9 -> Cyan
    v >= 8 -> Mint
    v >= 7 -> Color(0xFFA4CD78)
    v >= 5 -> Orange
    v >= 3 -> Coral
    else -> Color(0xFFF32B60)
}
