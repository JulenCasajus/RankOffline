package org.rankoffline.app

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Remove one image, regardless of origin. Shared physical files affect all referring entries. */
suspend fun CoverRepository.removeLocalCover(key: CatalogEntryKey): Boolean = withContext(Dispatchers.IO) {
    if (key.kind == CatalogEntryKind.GROUP) {
        val own = records.load(key)
        val ownFile = own?.localPath?.takeIf { validLocal(key, own.remoteRef, it) }
            ?: own?.remoteRef?.let { declaredCover(key.packId, it) }
                ?.takeIf(CoverImageFiles::isValid)?.absolutePath
        if (ownFile == null) {
            // The user is viewing a borrowed ITEM file. Delete that actual file,
            // not a fictitious GROUP copy. A GROUP with its own file is unaffected.
            val fallback = fallbackKeys(key).firstOrNull()
                ?: return@withContext false
            return@withContext removeLocalCover(fallback)
        }
    }
    PackCoverWriteLocks.withPackLock(key.packId) {
        if (!CoverRepository.inProgress.add(key)) return@withPackLock false
        try {
            val record = records.load(key) ?: return@withPackLock false
            val file = record.localPath?.let(::File)
                ?.takeIf { validOwnedPath(key, record.remoteRef, it) }
                ?: declaredCover(key.packId, record.remoteRef)
                ?: return@withPackLock false
            if (!storage.owns(key.packId, file)) return@withPackLock false
            dirty(key.packId, PackSummaryField.INSTALLED_BYTES, PackSummaryField.COVER_BYTES,
                PackSummaryField.AVAILABLE_COVER_COUNT, PackSummaryField.MISSING_COVER_COUNT)
            if (file.exists() && !file.delete()) return@withPackLock false
            val references = records.localRecords(key.packId).filter { it.localPath == file.absolutePath }
            for (ref in references) {
                // A failed CAS leaves a missing-image path, not another entry's image.
                records.updateIfCurrent(ref, null, CoverResolutionState.UNKNOWN)
            }
            if (references.none { it.key == key }) {
                records.updateIfCurrent(record, null, CoverResolutionState.UNKNOWN)
            }
            true
        } finally {
            CoverRepository.inProgress.remove(key)
        }
    }
}


/** Removes every cover file, leaving the pack's catalog and personal records alone. */
suspend fun CoverRepository.clearLocalCovers(packId: PackId): Boolean = withContext(Dispatchers.IO) {
    PackCoverWriteLocks.withPackLock(packId) {
            val root = storage.coversRoot(packId)
            dirty(packId, PackSummaryField.INSTALLED_BYTES, PackSummaryField.COVER_BYTES,
                PackSummaryField.AVAILABLE_COVER_COUNT, PackSummaryField.MISSING_COVER_COUNT)
            // In-flight downloads started before this clear must not repopulate covers/.
            generation(packId).incrementAndGet()
            if (!deleteOwnedTree(root)) return@withPackLock false
            var cleared = true
            for (record in records.localRecords(packId)) {
                if (record.localPath?.let { storage.owns(packId, File(it)) } == true) {
                    if (!records.updateIfCurrent(record, null, CoverResolutionState.UNKNOWN)) cleared = false
                }
            }
            cleared
    }
}


internal fun CoverRepository.dirty(packId: PackId, vararg fields: PackSummaryField) =
    beforeCoverMutation(packId, fields.toSet())


internal fun CoverRepository.validOwnedPath(key: CatalogEntryKey, remoteRef: String?, file: File): Boolean =
    storage.owns(key, file) ||
        (declaredCover(key.packId, remoteRef)?.absolutePath == file.absolutePath &&
            storage.owns(key.packId, file))


internal fun CoverRepository.validLocal(key: CatalogEntryKey, ref: String?, path: String?): Boolean {
    val file = path?.takeIf(String::isNotBlank)?.let(::File) ?: return false
    return validOwnedPath(key, ref, file) && CoverImageFiles.isValid(file)
}
