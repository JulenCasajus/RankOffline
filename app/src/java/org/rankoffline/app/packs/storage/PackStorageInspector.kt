package org.rankoffline.app

import java.nio.file.Files

/** Installed bytes include covers once; pending files and retained archives are residual storage. */
internal class PackStorageInspector(
    private val installer: PackInstaller,
    private val archiveStore: PackArchiveStore
) {
    fun inspect(packId: PackId): PackStorageSnapshot {
        val installed = installer.installedDirectory(packId)
        val (installedBytes, coverBytes) =
            if (installed != null && !Files.isSymbolicLink(installed.toPath())) {
                measurePackBytes(installed)
            } else {
                0L to 0L
            }
        val pendingBytes = (installer.residualBytes(packId) - installedBytes).coerceAtLeast(0L)
        return PackStorageSnapshot(
            installedBytes = installedBytes,
            coverBytes = coverBytes,
            residualBytes = Math.addExact(pendingBytes, archiveStore.bytes(packId)),
            current = true
        )
    }
}
