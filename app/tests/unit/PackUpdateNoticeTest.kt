package org.rankoffline.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackUpdateNoticeTest {
    @Test fun promptWaitsForDiscoveryAndNeverAppearsDuringANewUsersOnboarding() {
        assertFalse(shouldConsiderPackUpdatePrompt(null, true, false, true))
        assertFalse(shouldConsiderPackUpdatePrompt(true, true, false, true))
        assertFalse(shouldConsiderPackUpdatePrompt(false, false, false, true))
        assertFalse(shouldConsiderPackUpdatePrompt(false, true, true, true))
        assertFalse(shouldConsiderPackUpdatePrompt(false, true, false, false))
        assertTrue(shouldConsiderPackUpdatePrompt(false, true, false, true))
    }
}
