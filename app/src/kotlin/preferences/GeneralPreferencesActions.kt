package org.rankoffline.app


internal fun PreferencesUiController.lastTab(value: AppTab) = run { settings.updateLastTab(value) }


internal fun PreferencesUiController.welcome(showImages: Boolean, elaborate: Boolean) = run {
    settings.completeWelcome(showImages, elaborate)
}


internal fun PreferencesUiController.onboardingComplete() = run { settings.completeOnboarding() }

internal fun PreferencesUiController.whatsNewComplete() = run { settings.acknowledgeWhatsNew() }


internal fun PreferencesUiController.language(value: SupportedLanguage) = run { settings.updateLanguage(value) }
