package ph.bettergov.kuyaj.sync

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import ph.bettergov.kuyaj.kb.KnowledgeStore
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64

/** The signed manifest the sync server returns. Same fields as Airgap's. */
data class Manifest(
    val algorithm: String,
    val signatureEncoding: String,
    val byteLength: Long,
    val sha256: String,
    val version: String,
    val keyId: String,
    val url: String,
    val publishedAt: String,
    val signature: String,
) {
    companion object {
        fun parse(json: String): Manifest = JSONObject(json).run {
            Manifest(
                algorithm = optString("algorithm"),
                signatureEncoding = optString("signatureEncoding"),
                byteLength = optLong("byteLength", -1),
                sha256 = optString("sha256"),
                version = optString("version"),
                keyId = optString("keyId"),
                url = optString("url"),
                publishedAt = optString("publishedAt"),
                signature = optString("signature"),
            )
        }
    }
}

class BundleRejected(val reason: String) : Exception(reason)

/**
 * Every check runs before the app trusts a byte: manifest shape, length,
 * SHA-256, pinned key, Ed25519 signature, strict UTF-8, record schema.
 */
object BundleVerifier {
    private val HEX64 = Regex("^[a-f0-9]{64}$")

    fun validateManifest(m: Manifest, allowHttp: Boolean = false) {
        val schemeOk = m.url.startsWith("https://") || (allowHttp && m.url.startsWith("http://"))
        val dateOk = runCatching { OffsetDateTime.parse(m.publishedAt) }.isSuccess
        if (m.algorithm != "Ed25519" || m.signatureEncoding != "base64" || m.byteLength < 1 ||
            !HEX64.matches(m.sha256) || m.version.isBlank() || m.keyId.isBlank() || !schemeOk || !dateOk
        ) throw BundleRejected("bundle_manifest_invalid")
    }

    /** Returns the bundle text once every check passes. */
    fun verify(bytes: ByteArray, m: Manifest, pinnedKeys: Map<String, String>, allowHttp: Boolean = false): String {
        validateManifest(m, allowHttp)
        if (bytes.size.toLong() != m.byteLength) throw BundleRejected("bundle_length_invalid")
        if (sha256Hex(bytes) != m.sha256) throw BundleRejected("bundle_sha256_invalid")

        val key = pinnedKeys[m.keyId]?.let(::decodeBase64)
        if (key == null || key.size != 32) throw BundleRejected("bundle_key_invalid")
        val sig = decodeBase64(m.signature)
        if (sig == null || sig.size != 64 || !ed25519Verify(key, bytes, sig)) {
            throw BundleRejected("bundle_signature_invalid")
        }

        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            throw BundleRejected("bundle_schema_invalid")
        }
        val generatedAt = runCatching { JSONObject(text).getString("generatedAt") }.getOrNull()
        if (generatedAt == null || runCatching { OffsetDateTime.parse(generatedAt) }.isFailure) {
            throw BundleRejected("bundle_schema_invalid")
        }
        runCatching { KnowledgeStore.parseBundle(text) }.onFailure { throw BundleRejected("bundle_schema_invalid") }
        return text
    }

    /** Versions are numbers or ISO times; digit groups compare in order. No digits means never older. */
    fun isOlderVersion(offered: String, installed: String): Boolean {
        val a = Regex("\\d+").findAll(offered).map { it.value.toBigInteger() }.toList()
        val b = Regex("\\d+").findAll(installed).map { it.value.toBigInteger() }.toList()
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrNull(i) ?: java.math.BigInteger.ZERO).compareTo(b.getOrNull(i) ?: java.math.BigInteger.ZERO)
            if (c != 0) return c < 0
        }
        return false
    }

    /** First 16 hex characters of SHA-256 over the raw public key. */
    fun keyIdFor(publicKey: ByteArray): String = sha256Hex(publicKey).take(16)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun decodeBase64(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s) }.getOrNull()

    private fun ed25519Verify(key: ByteArray, message: ByteArray, sig: ByteArray): Boolean =
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(key, 0))
            update(message, 0, message.size)
            verifySignature(sig)
        }
}
