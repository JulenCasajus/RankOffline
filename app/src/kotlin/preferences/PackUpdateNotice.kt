package org.rankoffline.app

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.sync.withLock

internal fun packUpdateNoticeKey(id: PackId, version: String): String = "${id.value}:$version"

internal fun shouldConsiderPackUpdatePrompt(
    startedAsNewUser: Boolean?, checkFinished: Boolean,
    whatsNewVisible: Boolean, onboardingComplete: Boolean
): Boolean = startedAsNewUser == false && checkFinished && !whatsNewVisible && onboardingComplete

/** Dismissing one revision never suppresses a later revision of the same pack. */
suspend fun SettingsRepository.postponePackUpdates(targets: Collection<Pair<PackId, String>>) =
    lock.withLock {
        val additions = targets.mapTo(hashSetOf()) { (id, version) -> packUpdateNoticeKey(id, version) }
        if (additions.isEmpty()) return@withLock
        val next = postponedUpdates.value + additions
        context.dataStore.edit { it[postponedUpdatesKey] = next }
        postponedUpdates.value = next
    }
