package org.rankoffline.app

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only SAF entry point. Pack files are never bundled or copied directly into files/packs. */
class LocalPackInstallerActivity : AppCompatActivity() {
    private val scope = MainScope()
    private lateinit var status: TextView
    private val selectPack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::installSelectedPack)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).roundToInt()
        status = TextView(this).apply { setText(R.string.debug_local_pack_installer_idle) }
        val select = Button(this).apply {
            setText(R.string.debug_local_pack_installer_select)
            setOnClickListener { selectPack.launch(arrayOf("application/zip", "application/octet-stream")) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            addView(select)
            addView(status)
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun installSelectedPack(uri: Uri) {
        status.setText(R.string.debug_local_pack_installer_working)
        scope.launch {
            var staged: File? = null
            try {
                staged = stageArchive(uri)
                val manifest = withContext(Dispatchers.IO) {
                    PackArchiveReader.withVerifiedArchive(requireNotNull(staged), null) { request, _ ->
                        PackManifestParser.parse(request.manifestJson)
                    }
                }
                val services = RankOfflineServices.get(this@LocalPackInstallerActivity)
                val result = services.packManager.installLocalArchive(requireNotNull(staged), manifest)
                val ready = result == PackOperationResult.INSTALLED &&
                    services.ensurePackReady(manifest.packId)
                status.text = if (ready) getString(
                    R.string.debug_local_pack_installer_ready,
                    manifest.displayName,
                    manifest.packId.value
                ) else getString(R.string.debug_local_pack_installer_failed, result.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status.text = getString(
                    R.string.debug_local_pack_installer_failed,
                    failure.message ?: failure::class.java.simpleName
                )
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { staged?.delete() }
            }
        }
    }

    private suspend fun stageArchive(uri: Uri): File = withContext(Dispatchers.IO) {
        val directory = File(cacheDir, "local-pack-import")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create import cache" }
        check(!Files.isSymbolicLink(directory.toPath())) { "Unsafe import cache" }
        val staged = File.createTempFile("local-", ".pack", directory)
        try {
            val input = requireNotNull(contentResolver.openInputStream(uri)) { "Cannot open document" }
            input.use { it.copyBounded(staged, PackDownloadDescriptor.MAX_ARCHIVE_BYTES) }
            staged
        } catch (failure: Exception) {
            staged.delete()
            throw failure
        }
    }
}

private fun InputStream.copyBounded(destination: File, limit: Long) {
    destination.outputStream().buffered().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(read.toLong() <= limit - copied) { "Pack archive exceeds size limit" }
            output.write(buffer, 0, read)
            copied += read
        }
    }
}
