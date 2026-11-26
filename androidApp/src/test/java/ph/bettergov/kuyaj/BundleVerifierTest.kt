package ph.bettergov.kuyaj

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ph.bettergov.kuyaj.sync.BundleRejected
import ph.bettergov.kuyaj.sync.BundleVerifier
import ph.bettergov.kuyaj.sync.Manifest
import java.security.SecureRandom
import java.util.Base64

class BundleVerifierTest {
    private val keys = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    private val publicKey = (keys.public as Ed25519PublicKeyParameters).encoded
    private val keyId = BundleVerifier.keyIdFor(publicKey)
    private val pinned = mapOf(keyId to Base64.getEncoder().encodeToString(publicKey))

    private val records = """[{"id":"stock-RICE-25KG","category":"stock","title":"Rice","content":"Bodega 2 has 42 sacks.","keywords":["rice"]}]"""
    private val bundle = """{"files":{"stock.json":${org.json.JSONObject.quote(records)}},"generatedAt":"2026-11-16T01:00:00Z"}""".toByteArray()

    private fun sign(bytes: ByteArray) = Ed25519Signer().run {
        init(true, keys.private as Ed25519PrivateKeyParameters)
        update(bytes, 0, bytes.size)
        Base64.getEncoder().encodeToString(generateSignature())
    }

    private fun manifest(bytes: ByteArray = bundle, signature: String = sign(bundle), id: String = keyId) = Manifest(
        algorithm = "Ed25519", signatureEncoding = "base64", byteLength = bytes.size.toLong(),
        sha256 = BundleVerifier.sha256Hex(bytes), version = "2026-11-16T01:00:00Z", keyId = id,
        url = "https://sync.example.ph/api/v1/sync/kb/download", publishedAt = "2026-11-16T01:00:00Z", signature = signature,
    )

    private fun rejected(expected: String, block: () -> Unit) {
        try {
            block(); fail("expected $expected")
        } catch (e: BundleRejected) {
            assertEquals(expected, e.reason)
        }
    }

    @Test fun acceptsGenuineBundle() {
        val text = BundleVerifier.verify(bundle, manifest(), pinned)
        assertTrue(text.contains("stock.json"))
    }

    @Test fun rejectsChangedByte() {
        val tampered = String(bundle).replace("42", "420").toByteArray()
        rejected("bundle_length_invalid") { BundleVerifier.verify(tampered, manifest(), pinned) }
        val sameLength = String(bundle).replace("42", "99").toByteArray()
        rejected("bundle_sha256_invalid") { BundleVerifier.verify(sameLength, manifest(), pinned) }
    }

    @Test fun rejectsAttackerWithMatchingHash() {
        // The attacker recomputes the hash for their bytes, but cannot sign with the pinned key.
        val evil = String(bundle).replace("42", "99").toByteArray()
        rejected("bundle_signature_invalid") { BundleVerifier.verify(evil, manifest(evil, signature = sign(bundle)), pinned) }
    }

    @Test fun rejectsUnpinnedKey() {
        rejected("bundle_key_invalid") { BundleVerifier.verify(bundle, manifest(id = "0000000000000000"), pinned) }
    }

    @Test fun rejectsHttpInRelease() {
        rejected("bundle_manifest_invalid") {
            BundleVerifier.verify(bundle, manifest().copy(url = "http://sync.example.ph/x"), pinned)
        }
    }

    @Test fun ordersVersions() {
        assertTrue(BundleVerifier.isOlderVersion("2026-11-15T00:00:00Z", "2026-11-16T00:00:00Z"))
        assertFalse(BundleVerifier.isOlderVersion("10", "9"))
        assertFalse(BundleVerifier.isOlderVersion("latest", "2026"))
    }
}
