package org.rankoffline.app

import android.os.SystemClock
import android.util.Log

/** Small debug-only startup trace. Release builds pay only an inlined boolean check. */
internal class StartupTiming(private val startedAt: Long = SystemClock.elapsedRealtime()) {
    private var previousAt: Long = startedAt

    fun mark(stage: String) {
        if (BuildConfig.DEBUG) {
            val now = SystemClock.elapsedRealtime()
            Log.i(TAG, "$stage: +${now - previousAt} ms (${now - startedAt} ms total)")
            previousAt = now
        }
    }

    companion object {
        private const val TAG = "RankOfflineStartup"
    }
}
