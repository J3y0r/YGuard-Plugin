package me.jeyor.yguard.crypto

import me.jeyor.yguard.domain.AttestationProof
import me.jeyor.yguard.protocol.AttestationEnvelope
import me.jeyor.yguard.protocol.PayloadCodec
import me.jeyor.yguard.protocol.ProtocolConstants
import me.jeyor.yguard.protocol.ProtocolException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.spec.MGF1ParameterSpec
import java.util.Arrays
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

data class ChallengeBinding(
    val sessionId: UUID,
    val attempt: Int,
    val nonce: String,
    val keyId: String,
    val playerUuid: UUID,
)

class AttestationDecryptor(private val keyRegistry: PrivateKeyRegistry) {
    fun decryptAndValidate(envelopeBytes: ByteArray, binding: ChallengeBinding): AttestationProof {
        val envelope = PayloadCodec.decodeEnvelope(envelopeBytes)
        validateEnvelopeBinding(envelope, binding)
        val aesKey = unwrapKey(envelope)
        try {
            val compressed = decryptCiphertext(envelope, aesKey)
            val proof = PayloadCodec.decodeProof(decompress(compressed))
            validateProofBinding(proof, binding)
            return proof
        } catch (exception: ProtocolException) {
            throw exception
        } catch (exception: Exception) {
            throw ProtocolException("Attestation decryption failed", exception)
        } finally {
            Arrays.fill(aesKey, 0.toByte())
        }
    }

    private fun validateEnvelopeBinding(envelope: AttestationEnvelope, binding: ChallengeBinding) {
        if (
            envelope.protocolVersion != ProtocolConstants.VERSION ||
            envelope.sessionId != binding.sessionId ||
            envelope.attempt != binding.attempt ||
            envelope.keyId != binding.keyId
        ) {
            throw ProtocolException("Envelope does not match challenge")
        }
    }

    private fun unwrapKey(envelope: AttestationEnvelope): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val parameters = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT,
        )
        return try {
            cipher.init(Cipher.DECRYPT_MODE, keyRegistry.get(envelope.keyId), parameters)
            cipher.doFinal(envelope.encryptedKey).also {
                if (it.size != 32) {
                    Arrays.fill(it, 0.toByte())
                    throw ProtocolException("Invalid AES key size")
                }
            }
        } catch (exception: ProtocolException) {
            throw exception
        } catch (exception: Exception) {
            throw ProtocolException("Attestation key unwrap failed", exception)
        }
    }

    private fun decryptCiphertext(envelope: AttestationEnvelope, aesKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, envelope.iv))
        return cipher.doFinal(envelope.ciphertext)
    }

    private fun decompress(compressed: ByteArray): ByteArray {
        try {
            ByteArrayInputStream(compressed).use { input ->
                GZIPInputStream(input).use { gzip ->
                    val output = ByteArrayOutputStream(minOf(compressed.size * 2, ProtocolConstants.MAX_PROOF_BYTES))
                    val buffer = ByteArray(8_192)
                    var total = 0
                    while (true) {
                        val read = gzip.read(buffer)
                        if (read == -1) {
                            break
                        }
                        if (total + read > ProtocolConstants.MAX_PROOF_BYTES) {
                            throw ProtocolException("Decompressed proof exceeds maximum size")
                        }
                        output.write(buffer, 0, read)
                        total += read
                    }
                    return output.toByteArray()
                }
            }
        } catch (exception: ProtocolException) {
            throw exception
        } catch (exception: Exception) {
            throw ProtocolException("Invalid GZIP proof", exception)
        }
    }

    private fun validateProofBinding(proof: AttestationProof, binding: ChallengeBinding) {
        if (
            proof.protocolVersion != ProtocolConstants.VERSION ||
            proof.sessionId != binding.sessionId ||
            proof.attempt != binding.attempt ||
            proof.nonce != binding.nonce ||
            proof.playerUuid != binding.playerUuid
        ) {
            throw ProtocolException("Proof does not match challenge")
        }
    }
}
