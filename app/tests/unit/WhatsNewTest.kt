package org.rankoffline.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test fun shownOnlyAfterOnboardingForTheUnacknowledgedVersion() {
        val ready = AppSettingsState(settingsLoaded = true, hasCompletedOnboarding = true)
        assertTrue(shouldShowWhatsNew(ready, "0.2.3"))
        assertTrue(shouldShowWhatsNew(ready, "0.2.3-debug.1"))
        assertFalse(shouldShowWhatsNew(ready, "0.2.0"))
        assertFalse(shouldShowWhatsNew(ready, "0.2.2"))
        assertFalse(shouldShowWhatsNew(ready.copy(settingsLoaded = false), "0.2.3"))
        assertFalse(shouldShowWhatsNew(ready.copy(hasCompletedOnboarding = false), "0.2.3"))
        assertFalse(shouldShowWhatsNew(ready.copy(lastSeenWhatsNewVersion = "0.2.3"), "0.2.3"))
    }
}
