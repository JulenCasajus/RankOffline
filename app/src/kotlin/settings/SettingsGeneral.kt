package org.rankoffline.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*

@Composable
internal fun IconSettingsScreen(
    selectedIcon: AppIcon,
    onIconSelected: (AppIcon) -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_icon_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)
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
internal fun LanguageSettingsScreen(
    selectedLanguage: SupportedLanguage,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.language_title), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)
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
internal fun GeneralSettingsScreen(
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
            contentPadding = floatingNavigationContentPadding(bottom = 24.dp)
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
internal fun SettingsHomeScreen(
    installedManifests: List<PackManifest>, activePackId: PackId?, allSelected: Boolean,
    onSelectPack: (PackId) -> Unit, onAll: () -> Unit, onManage: () -> Unit,
    onGeneral: () -> Unit, onActivePack: () -> Unit, onBackup: () -> Unit,
    onAbout: () -> Unit, onDonation: () -> Unit
) {
    val bottomClearance = LocalFloatingNavigationClearance.current
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PackScreenTitle(
            title = stringResource(R.string.settings_title),
            installedManifests = installedManifests, activePackId = activePackId,
            onPackSelected = onSelectPack, onManagePacks = onManage,
            allSelected = allSelected, allowAll = installedManifests.size >= 2,
            onAllSelected = onAll
        )
        SettingsMenuItem(Icons.Default.Settings, stringResource(R.string.settings_general_title),
            stringResource(R.string.settings_general_description), onGeneral)
        if (installedManifests.isNotEmpty()) {
            val label = if (allSelected) stringResource(R.string.media_all)
                else installedManifests.firstOrNull { it.packId == activePackId }?.displayName.orEmpty()
            SettingsMenuItem(Icons.Default.Tune, stringResource(R.string.settings_active_pack_title),
                stringResource(R.string.settings_active_pack_description, label), onActivePack)
        }
        SettingsMenuItem(Icons.Default.Save, stringResource(R.string.settings_data_title),
            stringResource(R.string.settings_data_description), onBackup)
        SettingsMenuItem(Icons.Default.Info, stringResource(R.string.settings_about_title),
            stringResource(R.string.settings_about_description), onAbout)
        SettingsMenuItem(Icons.Default.Favorite, stringResource(R.string.settings_donation_title),
            stringResource(R.string.settings_donation_description), onDonation)
    }
}

@Composable
internal fun ActivePackSettingsScreen(
    packName: String, allSelected: Boolean, onDisplay: () -> Unit,
    onCovers: () -> Unit, onRating: () -> Unit, onBack: () -> Unit
) {
    SettingsSubscreen(stringResource(R.string.settings_active_pack_screen_title, packName), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 16.dp)
        ) {
            item { SettingsMenuItem(Icons.Default.Visibility, stringResource(R.string.general_display_section),
                stringResource(R.string.settings_display_description), onDisplay) }
            if (!allSelected) {
                item { SettingsMenuItem(Icons.Default.CloudDownload, stringResource(R.string.covers_settings_title),
                    stringResource(R.string.covers_settings_description), onCovers) }
                item { SettingsMenuItem(Icons.Default.Star, stringResource(R.string.settings_rating_title),
                    stringResource(R.string.settings_rating_description), onRating) }
            }
        }
    }
}

@Composable
internal fun DisplaySettingsScreen(
    settings: AppSettingsState, allSelected: Boolean,
    onStickyScoreChange: (Boolean) -> Unit, onShowImagesChange: (Boolean) -> Unit,
    onTitlesOnPodiumChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val showImages = if (allSelected) settings.allViewSettings.showImages else settings.showImages
    SettingsSubscreen(stringResource(R.string.general_display_section), onBack) {
        LazyColumn(
            Modifier.fillMaxSize().background(Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = floatingNavigationContentPadding(bottom = 16.dp)
        ) {
            item {
                SettingsSwitchRow(stringResource(R.string.general_sticky_score),
                    stringResource(R.string.general_sticky_score_description),
                    if (allSelected) settings.allViewSettings.stickyScore else settings.stickyScore,
                    onStickyScoreChange)
            }
            item {
                SettingsSwitchRow(stringResource(R.string.general_show_images),
                    stringResource(R.string.general_show_images_description),
                    showImages,
                    onShowImagesChange)
            }
            item {
                SettingsSwitchRow(stringResource(R.string.general_titles_on_podium),
                    stringResource(R.string.general_titles_on_podium_description),
                    if (allSelected) settings.allViewSettings.titlesOnPodium
                    else settings.activeMediaSettings?.titlesOnPodium == true,
                    onTitlesOnPodiumChange, enabled = showImages)
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, detail: String, checked: Boolean,
                              onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}
