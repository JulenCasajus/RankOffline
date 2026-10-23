package org.rankoffline.app

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** App-private disposable storage so instrumentation never mutates a developer's debug data. */
internal class IsolatedTestContext(base: Context, label: String) : ContextWrapper(base) {
    private val root = File(
        base.cacheDir,
        "instrumentation-storage/$label-${System.nanoTime()}"
    ).apply { mkdirs() }
    private val isolatedFiles = File(root, "files").apply { mkdirs() }
    private val isolatedCache = File(root, "cache").apply { mkdirs() }

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = isolatedFiles
    override fun getCacheDir(): File = isolatedCache

    fun delete() {
        root.deleteRecursively()
    }
}
