package me.jeyor.yguard.crypto

import me.jeyor.yguard.config.KeyConfig
import me.jeyor.yguard.protocol.ProtocolException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class PrivateKeyRegistry private constructor(
    val activeKeyId: String,
    private val keys: Map<String, RSAPrivateKey>,
) {
    fun get(keyId: String): RSAPrivateKey = keys[keyId] ?: throw ProtocolException("Unknown keyId")

    companion object {
        private const val MAX_KEY_FILE_BYTES = 16_384L
        private const val BEGIN = "-----BEGIN PRIVATE KEY-----"
        private const val END = "-----END PRIVATE KEY-----"

        fun load(config: KeyConfig): PrivateKeyRegistry {
            val keyFactory = KeyFactory.getInstance("RSA")
            val keys = config.privateKeys.mapValues { (keyId, path) ->
                if (!Files.isRegularFile(path) || Files.size(path) !in 1..MAX_KEY_FILE_BYTES) {
                    throw IllegalStateException("Private key file for $keyId is missing or invalid: $path")
                }
                val pem = Files.readString(path, StandardCharsets.US_ASCII).trim()
                if (!pem.startsWith(BEGIN) || !pem.endsWith(END)) {
                    throw IllegalStateException("Private key $keyId must be PKCS#8 PEM")
                }
                val body = pem.removePrefix(BEGIN).removeSuffix(END).replace(Regex("\\s"), "")
                val encoded = try {
                    Base64.getDecoder().decode(body)
                } catch (exception: IllegalArgumentException) {
                    throw IllegalStateException("Private key $keyId has invalid PEM encoding", exception)
                }
                val key = try {
                    keyFactory.generatePrivate(PKCS8EncodedKeySpec(encoded)) as? RSAPrivateKey
                } catch (exception: Exception) {
                    throw IllegalStateException("Private key $keyId is not a valid RSA PKCS#8 key", exception)
                } ?: throw IllegalStateException("Private key $keyId is not RSA")
                if (key.modulus.bitLength() != 3072) {
                    throw IllegalStateException("Private key $keyId must be RSA-3072")
                }
                key
            }
            return PrivateKeyRegistry(config.activeKeyId, keys)
        }
    }
}
