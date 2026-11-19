package org.rankoffline.app

import java.io.File

fun PackInstaller.residualBytes(packId: PackId): Long =
    (listOf(packDirectory(packId), pendingFile(packId), deletionFile(packId),
        updateFile(packId), previousDirectory(packId)) +
        stagingDirectories(packId)).sumOf(::ownedTreeBytes)


internal fun PackInstaller.stagingDirectories(packId: PackId) = root.listFiles().orEmpty().filter {
    it.name.startsWith(".${packId.directoryName()}-") && it.name.endsWith(".part")
}


internal fun PackInstaller.pendingFile(packId: PackId) = File(root, ".${packId.directoryName()}.pending")


internal fun PackInstaller.deletionFile(packId: PackId) = File(root, ".${packId.directoryName()}.deleting")

internal fun PackInstaller.updateFile(packId: PackId) = File(root, ".${packId.directoryName()}.updating")

internal fun PackInstaller.previousDirectory(packId: PackId) = File(root, ".${packId.directoryName()}.previous")
