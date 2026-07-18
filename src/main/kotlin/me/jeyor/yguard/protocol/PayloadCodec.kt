package me.jeyor.yguard.protocol

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import me.jeyor.yguard.domain.AttestationProof
import me.jeyor.yguard.domain.AttestationResultStatus
import me.jeyor.yguard.domain.NativeStatus
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

data class ChallengePayload(
    val protocolVersion: Int,
    val sessionId: String,
    val attempt: Int,
    val nonce: String,
    val keyId: String,
    val expiresAtEpochMs: Long,
    val playerUuid: String,
)

data class ResultPayload(
    val protocolVersion: Int,
    val sessionId: String,
    val attempt: Int,
    val status: String,
)

data class AttestationEnvelope(
    val protocolVersion: Int,
    val sessionId: UUID,
    val attempt: Int,
    val keyId: String,
    val encryptedKey: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

object PayloadCodec {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val keyIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val integerPattern = Regex("0|[1-9][0-9]*")
    private val hwidPattern = Regex("[0-9a-f]{64}")
    private val statusOrder = listOf(
        NativeStatus.NATIVE_UNAVAILABLE,
        NativeStatus.HWID_UNAVAILABLE,
        NativeStatus.PACKAGES_UNAVAILABLE,
        NativeStatus.HOOK_UNAVAILABLE,
    )

    fun encodeChallenge(
        sessionId: UUID,
        attempt: Int,
        nonce: String,
        keyId: String,
        expiresAtEpochMs: Long,
        playerUuid: UUID,
    ): ByteArray = gson.toJson(
        ChallengePayload(
            protocolVersion = ProtocolConstants.VERSION,
            sessionId = sessionId.toString(),
            attempt = attempt,
            nonce = nonce,
            keyId = keyId,
            expiresAtEpochMs = expiresAtEpochMs,
            playerUuid = playerUuid.toString(),
        ),
    ).toByteArray(StandardCharsets.UTF_8)

    fun encodeResult(sessionId: UUID, attempt: Int, status: AttestationResultStatus): ByteArray =
        gson.toJson(
            ResultPayload(
                protocolVersion = ProtocolConstants.VERSION,
                sessionId = sessionId.toString(),
                attempt = attempt,
                status = status.name,
            ),
        ).toByteArray(StandardCharsets.UTF_8)

    fun decodeEnvelope(bytes: ByteArray): AttestationEnvelope {
        if (bytes.isEmpty() || bytes.size > ProtocolConstants.MAX_ENVELOPE_BYTES) {
            throw ProtocolException("Invalid envelope size")
        }
        val values = readObject(decodeUtf8(bytes)) { name, reader ->
            when (name) {
                "protocolVersion", "attempt" -> readInteger(reader)
                "sessionId", "keyId", "encryptedKey", "iv", "ciphertext" -> readString(reader)
                else -> throw ProtocolException("Unknown envelope field")
            }
        }
        requireFields(values, setOf("protocolVersion", "sessionId", "attempt", "keyId", "encryptedKey", "iv", "ciphertext"))
        val protocolVersion = values.int("protocolVersion")
        val attempt = values.int("attempt")
        val keyId = values.string("keyId")
        if (protocolVersion != ProtocolConstants.VERSION || attempt !in 1..3 || !keyIdPattern.matches(keyId)) {
            throw ProtocolException("Invalid envelope metadata")
        }
        val encryptedKey = decodeStandardBase64(values.string("encryptedKey"), allowEmpty = false)
        val iv = decodeStandardBase64(values.string("iv"), allowEmpty = false)
        val ciphertext = decodeStandardBase64(values.string("ciphertext"), allowEmpty = false)
        if (encryptedKey.size != 384 || iv.size != 12 || ciphertext.size < 16) {
            throw ProtocolException("Invalid envelope cryptographic field size")
        }
        return AttestationEnvelope(
            protocolVersion = protocolVersion,
            sessionId = parseUuid(values.string("sessionId")),
            attempt = attempt,
            keyId = keyId,
            encryptedKey = encryptedKey,
            iv = iv,
            ciphertext = ciphertext,
        )
    }

    fun decodeProof(bytes: ByteArray): AttestationProof {
        if (bytes.isEmpty() || bytes.size > ProtocolConstants.MAX_PROOF_BYTES) {
            throw ProtocolException("Invalid proof size")
        }
        val values = readObject(decodeUtf8(bytes)) { name, reader ->
            when (name) {
                "protocolVersion", "attempt" -> readInteger(reader)
                "sessionId", "nonce", "playerUuid", "buildId" -> readString(reader)
                "hwidSha256", "loadedPackagesBase64" -> readNullableString(reader)
                "nativeStatuses" -> readStatuses(reader)
                else -> throw ProtocolException("Unknown proof field")
            }
        }
        val fields = setOf(
            "protocolVersion",
            "sessionId",
            "attempt",
            "nonce",
            "playerUuid",
            "buildId",
            "hwidSha256",
            "loadedPackagesBase64",
            "nativeStatuses",
        )
        requireFields(values, fields)
        val protocolVersion = values.int("protocolVersion")
        val attempt = values.int("attempt")
        if (protocolVersion != ProtocolConstants.VERSION || attempt !in 1..3) {
            throw ProtocolException("Invalid proof metadata")
        }
        val nonce = values.string("nonce")
        validateNonce(nonce)
        val buildId = values.string("buildId")
        if (buildId.isBlank() || buildId.length > 256) {
            throw ProtocolException("Invalid buildId")
        }
        val hwid = values.nullableString("hwidSha256")
        if (hwid != null && !hwidPattern.matches(hwid)) {
            throw ProtocolException("Invalid HWID digest")
        }
        val packageValue = values.nullableString("loadedPackagesBase64")
        val packages = packageValue?.let(::decodePackages)
        @Suppress("UNCHECKED_CAST")
        val statuses = values["nativeStatuses"] as List<NativeStatus>
        validateStatusBindings(statuses, hwid, packages)
        return AttestationProof(
            protocolVersion = protocolVersion,
            sessionId = parseUuid(values.string("sessionId")),
            attempt = attempt,
            nonce = nonce,
            playerUuid = parseUuid(values.string("playerUuid")),
            buildId = buildId,
            hwidSha256 = hwid,
            loadedPackages = packages,
            nativeStatuses = statuses,
        )
    }

    fun validateNonce(nonce: String) {
        if (nonce.contains('=')) {
            throw ProtocolException("Invalid nonce encoding")
        }
        val decoded = try {
            Base64.getUrlDecoder().decode(nonce)
        } catch (exception: IllegalArgumentException) {
            throw ProtocolException("Invalid nonce encoding", exception)
        }
        if (decoded.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != nonce) {
            throw ProtocolException("Invalid nonce encoding")
        }
    }

    private fun validateStatusBindings(statuses: List<NativeStatus>, hwid: String?, packages: List<String>?) {
        if (statuses.isEmpty()) {
            throw ProtocolException("Native status list is empty")
        }
        if (statuses == listOf(NativeStatus.READY)) {
            if (hwid == null || packages == null) {
                throw ProtocolException("READY requires complete native data")
            }
            return
        }
        if (NativeStatus.READY in statuses || statuses.distinct().size != statuses.size) {
            throw ProtocolException("Invalid native status combination")
        }
        val canonical = statusOrder.filter { it in statuses }
        if (canonical != statuses || statuses.any { it !in statusOrder }) {
            throw ProtocolException("Native statuses are not in canonical order")
        }
        if (NativeStatus.NATIVE_UNAVAILABLE in statuses) {
            if (statuses.size != 1 || hwid != null || packages != null) {
                throw ProtocolException("Invalid NATIVE_UNAVAILABLE fields")
            }
            return
        }
        if ((NativeStatus.HWID_UNAVAILABLE in statuses) != (hwid == null)) {
            throw ProtocolException("HWID availability does not match status")
        }
        if ((NativeStatus.PACKAGES_UNAVAILABLE in statuses) != (packages == null)) {
            throw ProtocolException("Package availability does not match status")
        }
    }

    private fun decodePackages(encoded: String): List<String> {
        val bytes = decodeStandardBase64(encoded, allowEmpty = true)
        val text = decodeUtf8(bytes)
        if (text.isEmpty()) {
            return emptyList()
        }
        val packages = text.split("\n", ignoreCase = false, limit = Int.MAX_VALUE)
        if (packages.any(::invalidPackageName)) {
            throw ProtocolException("Invalid package name")
        }
        val sorted = packages.sortedWith(UnicodeCodePointComparator)
        if (packages != sorted || packages.distinct().size != packages.size) {
            throw ProtocolException("Package list is not canonical")
        }
        return packages
    }

    private fun invalidPackageName(value: String): Boolean {
        if (value.isEmpty() || value.startsWith('.') || value.endsWith('.') || ".." in value) {
            return true
        }
        return value.codePoints().anyMatch { codePoint ->
            Character.isISOControl(codePoint) || codePoint == '/'.code || codePoint == ';'.code || codePoint == '['.code
        }
    }

    private fun decodeStandardBase64(value: String, allowEmpty: Boolean): ByteArray {
        if (!allowEmpty && value.isEmpty()) {
            throw ProtocolException("Empty Base64 value")
        }
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (exception: IllegalArgumentException) {
            throw ProtocolException("Invalid Base64 value", exception)
        }
        if (Base64.getEncoder().encodeToString(decoded) != value) {
            throw ProtocolException("Non-canonical Base64 value")
        }
        return decoded
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            throw ProtocolException("UTF-8 BOM is not allowed")
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (exception: CharacterCodingException) {
            throw ProtocolException("Invalid UTF-8", exception)
        }
    }

    private fun readObject(text: String, fieldReader: (String, JsonReader) -> Any?): Map<String, Any?> {
        val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
        val values = LinkedHashMap<String, Any?>()
        try {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw ProtocolException("JSON payload must be an object")
            }
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (values.containsKey(name)) {
                    throw ProtocolException("Duplicate JSON field")
                }
                values[name] = fieldReader(name, reader)
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw ProtocolException("Trailing JSON content")
            }
            return values
        } catch (exception: ProtocolException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw ProtocolException("Malformed JSON payload", exception)
        }
    }

    private fun readInteger(reader: JsonReader): Int {
        if (reader.peek() != JsonToken.NUMBER) {
            throw ProtocolException("Expected JSON integer")
        }
        val raw = reader.nextString()
        if (!integerPattern.matches(raw)) {
            throw ProtocolException("Expected JSON integer")
        }
        return raw.toIntOrNull() ?: throw ProtocolException("JSON integer out of range")
    }

    private fun readString(reader: JsonReader): String {
        if (reader.peek() != JsonToken.STRING) {
            throw ProtocolException("Expected JSON string")
        }
        return reader.nextString()
    }

    private fun readNullableString(reader: JsonReader): String? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return readString(reader)
    }

    private fun readStatuses(reader: JsonReader): List<NativeStatus> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            throw ProtocolException("Expected native status array")
        }
        val statuses = ArrayList<NativeStatus>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (statuses.size == statusOrder.size) {
                throw ProtocolException("Too many native statuses")
            }
            val value = readString(reader)
            statuses += try {
                NativeStatus.valueOf(value)
            } catch (_: IllegalArgumentException) {
                throw ProtocolException("Unknown native status")
            }
        }
        reader.endArray()
        return statuses
    }

    private fun requireFields(values: Map<String, Any?>, expected: Set<String>) {
        if (values.keys != expected) {
            throw ProtocolException("Missing JSON fields")
        }
    }

    private fun parseUuid(value: String): UUID {
        val parsed = try {
            UUID.fromString(value)
        } catch (exception: IllegalArgumentException) {
            throw ProtocolException("Invalid UUID", exception)
        }
        if (parsed.toString() != value) {
            throw ProtocolException("UUID must use canonical lowercase form")
        }
        return parsed
    }

    private fun Map<String, Any?>.int(name: String): Int = this[name] as? Int
        ?: throw ProtocolException("Invalid integer field")

    private fun Map<String, Any?>.string(name: String): String = this[name] as? String
        ?: throw ProtocolException("Invalid string field")

    private fun Map<String, Any?>.nullableString(name: String): String? {
        val value = this[name]
        if (value != null && value !is String) {
            throw ProtocolException("Invalid nullable string field")
        }
        return value
    }

    private object UnicodeCodePointComparator : Comparator<String> {
        override fun compare(left: String, right: String): Int {
            var leftIndex = 0
            var rightIndex = 0
            while (leftIndex < left.length && rightIndex < right.length) {
                val leftCodePoint = left.codePointAt(leftIndex)
                val rightCodePoint = right.codePointAt(rightIndex)
                if (leftCodePoint != rightCodePoint) {
                    return leftCodePoint.compareTo(rightCodePoint)
                }
                leftIndex += Character.charCount(leftCodePoint)
                rightIndex += Character.charCount(rightCodePoint)
            }
            return (left.length - leftIndex).compareTo(right.length - rightIndex)
        }
    }
}
