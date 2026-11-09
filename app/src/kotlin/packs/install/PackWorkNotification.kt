package org.rankoffline.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/** Android-specific notification rendering, deliberately outside the Worker policy. */
internal class PackWorkNotification(private val context: Context) {
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    fun create(
        packId: PackId,
        action: PackWorkAction,
        displayName: String,
        progress: PackInstallProgress? = null
    ): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.pack_background_channel),
                NotificationManager.IMPORTANCE_LOW
            ))
        }

        val downloading = progress?.phase == PackInstallPhase.DOWNLOADING &&
            progress.totalDownloadBytes > 0L
        val completed = if (downloading) {
            (progress.downloadFraction * 1_000).toInt().coerceIn(0, 1_000)
        } else 0
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.pack_background_title))
            .setContentText(statusText(displayName, action, progress?.phase))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (downloading) 1_000 else 0, completed, !downloading)
            .build()

        // Stable across process restarts; not tied to a fixed MediaType ordinal.
        val id = NOTIFICATION_ID_BASE +
            (("${packId.value}:${action.name}".hashCode() and Int.MAX_VALUE) % 1_000_000)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun statusText(name: String, action: PackWorkAction, phase: PackInstallPhase?): String {
        val resource = when (phase) {
            PackInstallPhase.QUEUED -> R.string.category_phase_waiting_download
            PackInstallPhase.DOWNLOADING -> R.string.pack_background_downloading
            PackInstallPhase.VERIFYING -> R.string.category_phase_verifying
            PackInstallPhase.INSTALLING -> R.string.category_phase_installing
            PackInstallPhase.WAITING_IMPORT -> R.string.category_phase_waiting_import
            PackInstallPhase.IMPORTING -> R.string.category_phase_importing_catalog
            PackInstallPhase.FINALIZING -> R.string.category_phase_finalizing
            PackInstallPhase.DOWNLOADING_COVERS -> R.string.covers_downloading_rated
            null -> when (action) {
                PackWorkAction.INSTALL -> R.string.pack_background_downloading
                PackWorkAction.RECOVER -> R.string.category_phase_waiting_import
                PackWorkAction.INITIAL_COVERS -> R.string.covers_downloading_rated
            }
        }
        return "$name · ${context.getString(resource)}"
    }

    private companion object {
        const val CHANNEL_ID = "rankoffline_pack_operations"
        const val NOTIFICATION_ID_BASE = 4_200
    }
}
