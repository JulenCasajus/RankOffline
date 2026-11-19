package org.rankoffline.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * First-run presentation only. No filesystem, Room, WorkManager or network calls from Compose.
 * The host owns actions, state collection and trusted-source discovery.
 */
@Composable
fun FirstRunOnboarding(
    settings: AppSettingsState,
    packStates: Map<PackId, PackState>,
    isDiscovering: Boolean,
    onWelcomeComplete: (elaborateRating: Boolean) -> Unit,
    onLanguageSelected: (SupportedLanguage) -> Unit,
    onThemeSelected: (AppThemePalette) -> Unit,
    onDiscoverPacks: () -> Unit,
    onInstallPack: (PackId) -> Unit,
    onCancelInitialCovers: (PackId) -> Unit,
    onUninstallPack: (PackId) -> Unit,
    onPurgePack: (PackId) -> Unit,
    onPackSelected: (PackId) -> Unit,
    onRequestStorageDetails: (PackId) -> Unit,
    onOnboardingComplete: () -> Unit
) {
    val latestPacks by rememberUpdatedState(packStates)
    val latestDiscovering by rememberUpdatedState(isDiscovering)
    // Prefetch on step one; retry once on entering step two if the first fetch failed.
    LaunchedEffect(settings.settingsLoaded, settings.hasCompletedWelcome,
        settings.hasCompletedOnboarding) {
        if (!settings.settingsLoaded || settings.hasCompletedOnboarding) return@LaunchedEffect
        if (!settings.hasCompletedWelcome) {
            onDiscoverPacks()
        } else if (latestPacks.values.none { it.sourceAvailable }) {
            if (latestDiscovering) snapshotFlow { latestDiscovering }.first { !it }
            if (latestPacks.values.none { it.sourceAvailable }) onDiscoverPacks()
        }
    }
    when {
        !settings.settingsLoaded -> Unit
        !settings.hasCompletedWelcome -> WelcomeStyleDialog(
            settings = settings,
            onLanguageSelected = onLanguageSelected,
            onThemeSelected = onThemeSelected,
            onComplete = onWelcomeComplete
        )
        !settings.hasCompletedOnboarding -> PackOnboardingDialog(
            packStates = packStates,
            isDiscovering = isDiscovering,
            languageTag = settings.language.languageTag,
            activePackId = onboardingReadyPackId(settings.activePackId, packStates),
            onDiscoverPacks = onDiscoverPacks,
            onInstallPack = onInstallPack,
            onCancelInitialCovers = onCancelInitialCovers,
            onUninstallPack = onUninstallPack,
            onPurgePack = onPurgePack,
            onPackSelected = onPackSelected,
            onRequestStorageDetails = onRequestStorageDetails,
            onComplete = onOnboardingComplete
        )
    }
}

/** A remembered pack is selectable only if its catalog is READY, not merely installed. */
internal fun onboardingReadyPackId(
    activePackId: PackId?,
    packs: Map<PackId, PackState>
): PackId? = activePackId?.takeIf { packs[it]?.available == true }
