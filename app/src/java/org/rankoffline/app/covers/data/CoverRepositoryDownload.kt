package org.rankoffline.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Resolves an existing image or saves a remotely obtained image in this pack's covers/. */
suspend fun CoverRepository.downloadCover(key: CatalogEntryKey): CoverDownloadResult = withContext(Dispatchers.IO) {
    if (!CoverRepository.inProgress.add(key)) return@withContext CoverDownloadResult.AlreadyInProgress
    val state = states.getOrPut(key) { MutableStateFlow(CoverDownloadState.IDLE) }
    state.value = CoverDownloadState.DOWNLOADING
    val generation = generation(key.packId).get()
    var temporary: File? = null
    try {
        val record = records.load(key) ?: return@withContext CoverDownloadResult.NotFound
        if (validLocal(key, record.remoteRef, record.localPath)) {
            if (record.state == CoverResolutionState.NOT_FOUND.name) {
                return@withContext PackCoverWriteLocks.withPackLock(key.packId) {
                    val latest = records.load(key) ?: return@withPackLock CoverDownloadResult.NotFound
                    if (!sameVersion(record, latest) || generation != generation(key.packId).get())
                        return@withPackLock CoverDownloadResult.TemporaryError
                    dirty(key.packId, PackSummaryField.MISSING_COVER_COUNT)
                    if (records.updateIfCurrent(latest, latest.localPath, CoverResolutionState.AVAILABLE))
                        CoverDownloadResult.AlreadyAvailable else CoverDownloadResult.TemporaryError
                }
            }
            return@withContext CoverDownloadResult.AlreadyAvailable
        }

        // Dedicated GROUP sources are attempted before member images, even if a member is
        // already on disk. The member is only a visual fallback; no files are copied.
        // If this GROUP has no physical dedicated cover, request the ITEM image
        // under its own identity: never copy/download the same bytes as GROUP.
        if (key.kind == CatalogEntryKind.GROUP &&
            (record.remoteRef.isNullOrBlank() ||
                (record.remoteRef.startsWith("covers/") &&
                    declaredCover(key.packId, record.remoteRef)
                        ?.let(CoverImageFiles::isValid) != true) ||
                record.state == CoverResolutionState.NOT_FOUND.name)) {
            return@withContext downloadGroupFallback(key, generation, record)
        }

        // Pack-declared image references are local files. A user-deleted image stays deleted.
        if (record.remoteRef?.startsWith("covers/") == true) {
            return@withContext PackCoverWriteLocks.withPackLock(key.packId) {
                val latest = records.load(key) ?: return@withPackLock CoverDownloadResult.NotFound
                if (!sameVersion(record, latest) || generation != generation(key.packId).get())
                    return@withPackLock CoverDownloadResult.TemporaryError
                val file = declaredCover(key.packId, latest.remoteRef)
                val stateToSet = if (file != null && CoverImageFiles.isValid(file))
                    CoverResolutionState.AVAILABLE else CoverResolutionState.NOT_FOUND
                // Persist dirty bits before modifying the cover state in Room.
                dirty(key.packId, PackSummaryField.MISSING_COVER_COUNT,
                    PackSummaryField.AVAILABLE_COVER_COUNT)
                if (records.updateIfCurrent(latest, file?.takeIf(CoverImageFiles::isValid)?.absolutePath,
                        stateToSet)) {
                    if (stateToSet == CoverResolutionState.AVAILABLE)
                        CoverDownloadResult.AlreadyAvailable else CoverDownloadResult.NotFound
                } else CoverDownloadResult.TemporaryError
            }
        }

        if (record.candidates.isEmpty() && record.metadataCandidates.isEmpty()) {
            val missing = notFoundIfCurrent(record, generation)
            return@withContext if (key.kind == CatalogEntryKind.GROUP &&
                missing == CoverDownloadResult.NotFound)
                downloadGroupFallback(key, generation, record) else missing
        }
        if (!(stagingRoot.isDirectory || stagingRoot.mkdirs()) ||
            Files.isSymbolicLink(stagingRoot.toPath())) return@withContext CoverDownloadResult.TemporaryError
        val staging = File.createTempFile("cover-", ".part", stagingRoot)
        temporary = staging
        val result = progressiveCoverDownload(
            directCandidates = record.candidates,
            metadataCandidates = record.metadataCandidates,
            resolveMetadata = DeclarativeCoverMetadataResolver::resolve
        ) { uri ->
            staging.delete()
            when (downloader.download(uri, staging)) {
                CoverTransferResult.DOWNLOADED -> {
                    if (!CoverImageFiles.isValid(staging)) {
                        CoverDownloadResult.TemporaryError
                    } else PackCoverWriteLocks.withPackLock(key.packId) {
                        val latest = records.load(key) ?: return@withPackLock CoverDownloadResult.NotFound
                        if (!sameVersion(record, latest) ||
                            generation != generation(key.packId).get() || !packInstalled(key.packId)) {
                            return@withPackLock CoverDownloadResult.TemporaryError
                        }
                        val destination = storage.coverFile(key)
                        val relative = "covers/${if (key.kind == CatalogEntryKind.ITEM) "items" else "groups"}/${destination.name}"
                        // Never overwrite a file whose bytes are part of the pack manifest.
                        if (manifestProvider(key.packId)?.files?.any { it.path == relative } == true) {
                            return@withPackLock CoverDownloadResult.TemporaryError
                        }
                        val parent = destination.parentFile ?: return@withPackLock CoverDownloadResult.TemporaryError
                        if (!storage.owns(key, destination)) return@withPackLock CoverDownloadResult.TemporaryError
                        dirty(key.packId, PackSummaryField.INSTALLED_BYTES, PackSummaryField.COVER_BYTES,
                            PackSummaryField.AVAILABLE_COVER_COUNT, PackSummaryField.MISSING_COVER_COUNT)
                        if (!(parent.isDirectory || parent.mkdirs())) {
                            return@withPackLock CoverDownloadResult.TemporaryError
                        }
                        CoverImageFiles.promote(staging, destination)
                        if (records.updateIfCurrent(latest, destination.absolutePath,
                                CoverResolutionState.AVAILABLE)) CoverDownloadResult.Downloaded
                        else {
                            destination.delete()
                            CoverDownloadResult.TemporaryError
                        }
                    }
                }
                CoverTransferResult.NOT_FOUND -> CoverDownloadResult.NotFound
                CoverTransferResult.NETWORK_ERROR -> CoverDownloadResult.NetworkError
                CoverTransferResult.TEMPORARY_ERROR -> CoverDownloadResult.TemporaryError
            }
        }
        if (result != CoverDownloadResult.NotFound) return@withContext result
        val missing = notFoundIfCurrent(record, generation)
        if (key.kind == CatalogEntryKind.GROUP && missing == CoverDownloadResult.NotFound) {
            downloadGroupFallback(key, generation, record)
        } else {
            missing
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        CoverDownloadResult.TemporaryError
    } catch (_: Exception) {
        CoverDownloadResult.TemporaryError
    } finally {
        temporary?.delete()
        state.value = CoverDownloadState.IDLE
        CoverRepository.inProgress.remove(key)
    }
}

/** Resolves each metadata source only after every candidate from the previous source failed. */
internal suspend fun <T> progressiveCoverDownload(
    directCandidates: List<URI>,
    metadataCandidates: List<T>,
    resolveMetadata: suspend (T) -> CoverMetadataResolution,
    downloadCandidate: suspend (URI) -> CoverDownloadResult
): CoverDownloadResult {
    val seen = linkedSetOf<URI>()
    var networkFailure = false
    var temporaryFailure = false
    for (index in -1 until metadataCandidates.size) {
        val resolution = if (index < 0) CoverMetadataResolution(directCandidates)
            else resolveMetadata(metadataCandidates[index])
        when (resolution.failure) {
            CoverDownloadResult.NetworkError -> networkFailure = true
            CoverDownloadResult.TemporaryError,
            CoverDownloadResult.AlreadyInProgress -> temporaryFailure = true
            else -> Unit
        }
        for (uri in resolution.candidates) {
            if (!seen.add(uri)) continue
            when (val result = downloadCandidate(uri)) {
                CoverDownloadResult.Downloaded,
                CoverDownloadResult.AlreadyAvailable -> return result
                CoverDownloadResult.NetworkError -> networkFailure = true
                CoverDownloadResult.TemporaryError,
                CoverDownloadResult.AlreadyInProgress -> temporaryFailure = true
                CoverDownloadResult.NotFound -> Unit
            }
        }
    }
    return when {
        networkFailure -> CoverDownloadResult.NetworkError
        temporaryFailure -> CoverDownloadResult.TemporaryError
        else -> CoverDownloadResult.NotFound
    }
}


internal suspend fun CoverRepository.downloadGroupFallback(
    groupKey: CatalogEntryKey,
    expectedGeneration: Long,
    original: CoverRecord
): CoverDownloadResult {
    if (expectedGeneration != generation(groupKey.packId).get())
        return CoverDownloadResult.TemporaryError
    val keys = fallbackKeys(groupKey)
    if (keys.isEmpty()) return notFoundIfCurrent(original, expectedGeneration)
    for (itemKey in keys) {
        if (expectedGeneration != generation(groupKey.packId).get())
            return CoverDownloadResult.TemporaryError
        when (val outcome = downloadCover(itemKey)) {
            CoverDownloadResult.NotFound -> continue // Try next valid member.
            else -> return outcome
        }
    }
    return notFoundIfCurrent(original, expectedGeneration)
}


internal fun CoverRepository.generation(packId: PackId): AtomicLong =
    CoverRepository.generations.computeIfAbsent(packId) { AtomicLong() }


/** NOT_FOUND is attached to the exact item/group in Room, never just a batch counter. */
internal suspend fun CoverRepository.notFoundIfCurrent(record: CoverRecord, expectedGeneration: Long): CoverDownloadResult =
    PackCoverWriteLocks.withPackLock(record.key.packId) {
        val latest = records.load(record.key) ?: return@withPackLock CoverDownloadResult.NotFound
        if (!sameVersion(record, latest) ||
            expectedGeneration != generation(record.key.packId).get()) {
            return@withPackLock CoverDownloadResult.TemporaryError
        }
        if (latest.state == CoverResolutionState.NOT_FOUND.name && latest.localPath == null) {
            return@withPackLock CoverDownloadResult.NotFound
        }
        dirty(record.key.packId, PackSummaryField.MISSING_COVER_COUNT,
            PackSummaryField.AVAILABLE_COVER_COUNT)
        if (records.updateIfCurrent(latest, null, CoverResolutionState.NOT_FOUND))
            CoverDownloadResult.NotFound else CoverDownloadResult.TemporaryError
    }


internal fun CoverRepository.sameVersion(a: CoverRecord, b: CoverRecord): Boolean =
    a.remoteRef == b.remoteRef && a.localPath == b.localPath


internal fun CoverRepository.packInstalled(packId: PackId): Boolean =
    storage.coversRoot(packId).parentFile?.let { readInstalledManifest(it)?.packId == packId } == true
