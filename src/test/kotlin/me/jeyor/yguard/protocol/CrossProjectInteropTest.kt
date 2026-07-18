package me.jeyor.yguard.protocol

import com.google.gson.Gson
import me.jeyor.yguard.config.KeyConfig
import me.jeyor.yguard.crypto.AttestationDecryptor
import me.jeyor.yguard.crypto.ChallengeBinding
import me.jeyor.yguard.crypto.PrivateKeyRegistry
import me.jeyor.yguard.domain.AttestationResultStatus
import me.jeyor.yguard.domain.NativeStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CrossProjectInteropTest {
    @Test
    fun `client encrypts and fragments proof accepted by server`(@TempDir temporaryDirectory: Path) {
        clientClassLoader().use { client ->
            val sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111")
            val playerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
            val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() })
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
            val nativeStatusClass = client.loadClass("me.jeyor.yguard.snapshot.NativeStatus")
            val ready = nativeStatusClass.enumConstants.single { (it as Enum<*>).name == "READY" }
            val attestationClass = client.loadClass("me.jeyor.yguard.protocol.Attestation")
            val attestation = attestationClass.getConstructor(
                Int::class.javaPrimitiveType,
                UUID::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                UUID::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                List::class.java,
            ).newInstance(
                1,
                sessionId,
                2,
                nonce,
                playerUuid,
                "interop-build",
                "a".repeat(64),
                Base64.getEncoder().encodeToString("\uE000.package".toByteArray()),
                listOf(ready),
            )
            val encryptorClass = client.loadClass("me.jeyor.yguard.crypto.AttestationEncryptor")
            val encryptor = encryptorClass.getConstructor().newInstance()
            val envelope = encryptorClass.getMethod(
                "encrypt",
                attestationClass,
                String::class.java,
                java.security.PublicKey::class.java,
            ).invoke(encryptor, attestation, "test-key", keyPair.public) as ByteArray

            val fragmenter = client.loadClass("me.jeyor.yguard.protocol.Fragmenter")
            @Suppress("UNCHECKED_CAST")
            val encodedFragments = fragmenter.getMethod(
                "fragment",
                UUID::class.java,
                Int::class.javaPrimitiveType,
                ByteArray::class.java,
            ).invoke(null, sessionId, 2, envelope) as List<ByteArray>
            val assembly = FragmentAssembly()
            var reassembled: ByteArray? = null
            encodedFragments.forEach { reassembled = assembly.add(FragmentCodec.decode(it)) ?: reassembled }
            assertContentEquals(envelope, reassembled)

            val privateKeyPath = temporaryDirectory.resolve("private.pem")
            val encodedPrivateKey = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
            Files.writeString(
                privateKeyPath,
                "-----BEGIN PRIVATE KEY-----\n$encodedPrivateKey\n-----END PRIVATE KEY-----\n",
            )
            val decryptor = AttestationDecryptor(
                PrivateKeyRegistry.load(KeyConfig("test-key", mapOf("test-key" to privateKeyPath))),
            )
            val proof = decryptor.decryptAndValidate(
                requireNotNull(reassembled),
                ChallengeBinding(sessionId, 2, nonce, "test-key", playerUuid),
            )
            assertEquals("interop-build", proof.buildId)
            assertEquals(listOf("\uE000.package"), proof.loadedPackages)
            assertEquals(listOf(NativeStatus.READY), proof.nativeStatuses)
        }
    }

    @Test
    fun `challenge and result JSON interoperate`() {
        clientClassLoader().use { client ->
            val sessionId = UUID.fromString("33333333-3333-3333-3333-333333333333")
            val playerUuid = UUID.fromString("44444444-4444-4444-4444-444444444444")
            val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
            val challengeBytes = PayloadCodec.encodeChallenge(
                sessionId,
                1,
                nonce,
                "test-key",
                System.currentTimeMillis() + 5_000,
                playerUuid,
            )
            val challenge = client.loadClass("me.jeyor.yguard.protocol.ChallengeJson")
                .getMethod("parse", ByteArray::class.java)
                .invoke(null, challengeBytes)
            assertEquals(sessionId, challenge.javaClass.getMethod("sessionId").invoke(challenge))
            assertEquals(playerUuid, challenge.javaClass.getMethod("playerUuid").invoke(challenge))

            val resultBytes = PayloadCodec.encodeResult(sessionId, 1, AttestationResultStatus.ACCEPTED)
            val result = client.loadClass("me.jeyor.yguard.protocol.ResultJson")
                .getMethod("parse", ByteArray::class.java)
                .invoke(null, resultBytes)
            assertEquals(sessionId, result.javaClass.getMethod("sessionId").invoke(result))
            assertEquals("ACCEPTED", (result.javaClass.getMethod("status").invoke(result) as Enum<*>).name)
        }
    }

    private fun clientClassLoader(): URLClassLoader {
        val clientClasses = Path.of("YGuard-ClientSide-Mod", "build", "classes", "java", "main")
            .toAbsolutePath()
            .normalize()
        val gsonJar = Gson::class.java.protectionDomain.codeSource.location
        return URLClassLoader(arrayOf(clientClasses.toUri().toURL(), gsonJar), ClassLoader.getPlatformClassLoader())
    }
}
