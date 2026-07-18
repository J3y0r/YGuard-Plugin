package me.jeyor.yguard.crypto

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import me.jeyor.yguard.config.KeyConfig
import me.jeyor.yguard.protocol.ProtocolException
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttestationDecryptorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val playerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `decrypts protocol compatible envelope`() {
        val decryptor = decryptor()
        val binding = binding()

        val proof = decryptor.decryptAndValidate(envelope(validProof()), binding)

        assertEquals(sessionId, proof.sessionId)
        assertEquals(playerUuid, proof.playerUuid)
        assertEquals("allowed-build", proof.buildId)
    }

    @Test
    fun `rejects GCM tampering and nonce replay`() {
        val decryptor = decryptor()
        val encrypted = envelope(validProof())
        val tamperedText = encrypted.toString(StandardCharsets.UTF_8)
        val ciphertext = Regex("\"ciphertext\":\"([^\"]+)\"").find(tamperedText)!!.groupValues[1]
        val ciphertextBytes = Base64.getDecoder().decode(ciphertext).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val tampered = tamperedText.replace(ciphertext, Base64.getEncoder().encodeToString(ciphertextBytes)).toByteArray()

        assertFailsWith<ProtocolException> { decryptor.decryptAndValidate(tampered, binding()) }
        assertFailsWith<ProtocolException> {
            decryptor.decryptAndValidate(envelope(validProof()), binding().copy(nonce = "A".repeat(43)))
        }
    }

    @Test
    fun `rejects proof expanding over one MiB`() {
        val decryptor = decryptor()
        val oversized = ByteArray(1_048_577) { 'A'.code.toByte() }

        assertFailsWith<ProtocolException> { decryptor.decryptAndValidate(envelope(oversized), binding()) }
    }

    private fun decryptor(): AttestationDecryptor {
        val path = temporaryDirectory.resolve("private.pem")
        val pem = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n$pem\n-----END PRIVATE KEY-----\n")
        return AttestationDecryptor(PrivateKeyRegistry.load(KeyConfig("test-key", mapOf("test-key" to path))))
    }

    private fun binding() = ChallengeBinding(sessionId, 1, nonce, "test-key", playerUuid)

    private fun validProof(): ByteArray = JsonObject().apply {
        addProperty("protocolVersion", 1)
        addProperty("sessionId", sessionId.toString())
        addProperty("attempt", 1)
        addProperty("nonce", nonce)
        addProperty("playerUuid", playerUuid.toString())
        addProperty("buildId", "allowed-build")
        addProperty("hwidSha256", "a".repeat(64))
        addProperty("loadedPackagesBase64", "")
        add("nativeStatuses", JsonArray().apply { add("READY") })
    }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun envelope(proof: ByteArray): ByteArray {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(12) { (it + 5).toByte() }
        val encryptedProof = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, iv))
            doFinal(gzip(proof))
        }
        val encryptedKey = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(
                Cipher.ENCRYPT_MODE,
                keyPair.public,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
            )
            doFinal(aes.encoded)
        }
        return JsonObject().apply {
            addProperty("protocolVersion", 1)
            addProperty("sessionId", sessionId.toString())
            addProperty("attempt", 1)
            addProperty("keyId", "test-key")
            addProperty("encryptedKey", Base64.getEncoder().encodeToString(encryptedKey))
            addProperty("iv", Base64.getEncoder().encodeToString(iv))
            addProperty("ciphertext", Base64.getEncoder().encodeToString(encryptedProof))
        }.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }

    companion object {
        private lateinit var keyPair: KeyPair

        @JvmStatic
        @BeforeAll
        fun generateKeyPair() {
            keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        }
    }
}
