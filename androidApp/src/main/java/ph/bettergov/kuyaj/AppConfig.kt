package ph.bettergov.kuyaj

/** Deployment settings come from gradle.properties (kuyaj.*), so a release needs no code change. */
object AppConfig {
    const val SYNC_INTERVAL_HOURS = 6L
    const val MAX_BUNDLE_BYTES = 50L * 1024 * 1024

    val syncBaseUrl: String? = BuildConfig.SYNC_BASE_URL.trimEnd('/').ifBlank { null }

    /** `keyId:base64Key` pairs, comma separated. No pinned key means no sync. */
    val pinnedKeys: Map<String, String> = BuildConfig.SYNC_PUBLIC_KEYS.split(',')
        .mapNotNull { pair -> pair.trim().split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap()

    /** Debug builds may sync from a local server on http://10.0.2.2. Release builds need HTTPS. */
    val allowHttpSync: Boolean = BuildConfig.DEBUG
}
