package org.rankoffline.app

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupCatalogRecoveryTest {
    @Test
    fun verifiedCatalogPublishesReadyWithoutRedundantRecovery() {
        assertEquals(
            StartupCatalogAction.PUBLISH_READY,
            startupCatalogAction(catalogOperationActive = false, verifiedCurrent = true)
        )
    }

    @Test
    fun inconsistentCatalogRecoversAndActiveWorkIsNotDuplicated() {
        assertEquals(
            StartupCatalogAction.RECOVER,
            startupCatalogAction(catalogOperationActive = false, verifiedCurrent = false)
        )
        assertEquals(
            StartupCatalogAction.WAIT_FOR_ACTIVE_OPERATION,
            startupCatalogAction(catalogOperationActive = true, verifiedCurrent = false)
        )
        assertEquals(
            StartupCatalogAction.PUBLISH_READY,
            startupCatalogAction(catalogOperationActive = true, verifiedCurrent = true)
        )
    }
}
