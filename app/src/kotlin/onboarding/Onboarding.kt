package org.rankoffline.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * First-run presentation only. No filesystem, Room, WorkManager or network calls from Compose.
 * The host owns actions, state collection and the one-time trusted-source discovery.
 */
@Composable
fun FirstRunOnboarding(
    settings: AppSettingsState,
    packStates: Map<PackId, PackState>,
    isDiscovering: Boolean,
    onWelcomeComplete: (showImages: Boolean, elaborateRating: Boolean) -> Unit,
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
    // Begin metadata-only discovery as soon as the first onboarding screen opens.
    LaunchedEffect(settings.settingsLoaded, settings.hasCompletedOnboarding) {
        if (settings.settingsLoaded && !settings.hasCompletedOnboarding) onDiscoverPacks()
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
