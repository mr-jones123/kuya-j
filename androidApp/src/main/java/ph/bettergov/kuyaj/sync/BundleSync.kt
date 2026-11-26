package ph.bettergov.kuyaj.sync

import android.content.Context
import ph.bettergov.kuyaj.AppConfig
import ph.bettergov.kuyaj.kb.KnowledgeStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Downloads, verifies and installs signed knowledge bundles.
 * Order follows Airgap: partial file, checks, current -> previous, partial -> current,
 * rebuild index, then save version. A failure before the swap leaves the old bundle.
 */
/**
 * [builtInVersion] is the version of the records shipped in the app (assets/kb/VERSION). A downloaded bundle is only
 * used if it's newer, so an app update with fresher built-in records isn't overridden by an older download.
 */
class BundleSync(context: Context, private val store: KnowledgeStore, private val builtInVersion: String? = null) {
    private val dir = File(context.filesDir, "kb").apply { mkdirs() }
    private val current = File(dir, "bundle-current.json")
    private val previous = File(dir, "bundle-previous.json")
    private val prefs = context.getSharedPreferences("kb-sync", Context.MODE_PRIVATE)

    sealed interface Result {
        data class Updated(val version: String) : Result
        data object UpToDate : Result
        data class Failed(val reason: String) : Result
    }

    val installedVersion: String? get() = prefs.getString("version", null)
    val lastSyncAt: Long? get() = prefs.getLong("lastSyncAt", 0).takeIf { it > 0 }

    /** Called once at startup: load the last installed bundle, or stay on built-in records. */
    fun loadInstalled() {
        val version = installedVersion ?: return
        if (!current.exists() || !newerThanBuiltIn(version)) return
        runCatching { store.replaceFromBundle(current.readText(), version) }
            .onFailure { store.revertToBuiltIn() }
    }

    fun sync(): Result {
        val base = AppConfig.syncBaseUrl ?: return Result.Failed("sync_not_configured")
        return try {
            val manifest = Manifest.parse(String(httpGet("$base/api/v1/sync/kb", 64 * 1024)))
            BundleVerifier.validateManifest(manifest, AppConfig.allowHttpSync)
            if (URL(manifest.url).let { "${it.protocol}://${it.authority}" } != URL(base).let { "${it.protocol}://${it.authority}" }) {
                throw BundleRejected("bundle_url_origin_invalid")
            }
            val installed = installedVersion
            if (!newerThanBuiltIn(manifest.version) ||
                installed != null && (manifest.version == installed || BundleVerifier.isOlderVersion(manifest.version, installed))
            ) {
                markSynced()
                return Result.UpToDate
            }
            if (manifest.byteLength > AppConfig.MAX_BUNDLE_BYTES) throw BundleRejected("bundle_too_large")

            val partial = File(dir, "bundle-${manifest.version.replace(Regex("[^A-Za-z0-9]"), "_")}.partial")
            partial.delete()
            val bytes = httpGet(manifest.url, manifest.byteLength.toInt())
            val text = try {
                BundleVerifier.verify(bytes, manifest, AppConfig.pinnedKeys, AppConfig.allowHttpSync)
            } catch (e: BundleRejected) {
                partial.delete()
                throw e
            }
            partial.writeBytes(bytes)
            install(partial, text, manifest.version)
            Result.Updated(manifest.version)
        } catch (e: BundleRejected) {
            Result.Failed(e.reason)
        } catch (e: Exception) {
            Result.Failed("network: ${e.message}")
        }
    }

    private fun install(partial: File, text: String, version: String) {
        previous.delete()
        if (current.exists() && !current.renameTo(previous)) throw IllegalStateException("could not back up bundle")
        try {
            if (!partial.renameTo(current)) throw IllegalStateException("could not move bundle")
            store.replaceFromBundle(text, version)
        } catch (e: Exception) {
            current.delete()
            if (previous.exists()) previous.renameTo(current)
            loadInstalled()
            throw e
        }
        prefs.edit().putString("version", version).apply()
        markSynced()
    }

    private fun newerThanBuiltIn(version: String) =
        builtInVersion == null || (version != builtInVersion && !BundleVerifier.isOlderVersion(version, builtInVersion))

    private fun markSynced() = prefs.edit().putLong("lastSyncAt", System.currentTimeMillis()).apply()

    /** Reads at most [maxBytes]; a larger body is refused instead of filling memory. */
    private fun httpGet(url: String, maxBytes: Int): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
        conn.readTimeout = TimeUnit.SECONDS.toMillis(60).toInt()
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode} for $url")
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw BundleRejected("bundle_length_invalid")
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }
}
