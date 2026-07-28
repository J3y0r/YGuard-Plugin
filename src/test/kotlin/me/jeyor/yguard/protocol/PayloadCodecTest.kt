package me.jeyor.yguard.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import me.jeyor.yguard.domain.NativeStatus
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PayloadCodecTest {
    private val sessionId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val playerUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun `encodes fragment channel registration as NUL terminated UTF-8`() {
        assertContentEquals(
            "yguard:attestation_fragment\u0000".toByteArray(StandardCharsets.UTF_8),
            ProtocolConstants.fragmentChannelRegistration(),
        )
    }

    @Test
    fun `accepts native unavailable with null native values`() {
        val proof = PayloadCodec.decodeProof(
            proofJson(
                hwid = null,
                packages = null,
                statuses = listOf("NATIVE_UNAVAILABLE"),
            ),
        )

        assertNull(proof.hwidSha256)
        assertNull(proof.loadedPackages)
        assertEquals(listOf(NativeStatus.NATIVE_UNAVAILABLE), proof.nativeStatuses)
    }

    @Test
    fun `accepts empty package collection for ready native`() {
        val proof = PayloadCodec.decodeProof(
            proofJson(
                hwid = "a".repeat(64),
                packages = "",
                statuses = listOf("READY"),
            ),
        )

        assertEquals(emptyList(), proof.loadedPackages)
    }

    @Test
    fun `uses Unicode code point order for package list`() {
        val packageText = "\uF900\n\uD800\uDC00"
        val encoded = Base64.getEncoder().encodeToString(packageText.toByteArray(StandardCharsets.UTF_8))

        val proof = PayloadCodec.decodeProof(
            proofJson("b".repeat(64), encoded, listOf("READY")),
        )

        assertEquals(listOf("\uF900", "\uD800\uDC00"), proof.loadedPackages)
    }

    @Test
    fun `accepts JVM package segments that are not Java source identifiers`() {
        val encoded = Base64.getEncoder().encodeToString("\uE000.package".toByteArray(StandardCharsets.UTF_8))

        val proof = PayloadCodec.decodeProof(proofJson("f".repeat(64), encoded, listOf("READY")))

        assertEquals(listOf("\uE000.package"), proof.loadedPackages)
    }

    @Test
    fun `rejects status order mismatch and inconsistent nulls`() {
        val wrongOrder = proofJson(
            hwid = null,
            packages = null,
            statuses = listOf("PACKAGES_UNAVAILABLE", "HWID_UNAVAILABLE"),
        )
        val inconsistent = proofJson(
            hwid = "c".repeat(64),
            packages = "",
            statuses = listOf("HWID_UNAVAILABLE"),
        )

        assertFailsWith<ProtocolException> { PayloadCodec.decodeProof(wrongOrder) }
        assertFailsWith<ProtocolException> { PayloadCodec.decodeProof(inconsistent) }
    }

    @Test
    fun `rejects duplicate and unknown JSON fields`() {
        val valid = proofJson("d".repeat(64), "", listOf("READY")).toString(StandardCharsets.UTF_8)
        val duplicate = valid.replace("\"attempt\":1", "\"attempt\":1,\"attempt\":1")
        val unknown = valid.dropLast(1) + ",\"extra\":true}"

        assertFailsWith<ProtocolException> { PayloadCodec.decodeProof(duplicate.toByteArray()) }
        assertFailsWith<ProtocolException> { PayloadCodec.decodeProof(unknown.toByteArray()) }
    }

    @Test
    fun `rejects non canonical package Base64 and UUID`() {
        val missingPaddingText = Base64.getEncoder().encodeToString("pk".toByteArray()).trimEnd('=')
        val badBase64 = proofJson("e".repeat(64), missingPaddingText, listOf("READY"))
        val uppercaseUuid = proofJson("e".repeat(64), "", listOf("READY")).toString(StandardCharsets.UTF_8)
            .replace(sessionId.toString(), "ABCDEFAB-1111-1111-1111-111111111111")

        assertFailsWith<ProtocolException> { PayloadCodec.decodeProof(badBase64) }
        assertFailsWith<ProtocolException> { PayloadCodec.decodeProof(uppercaseUuid.toByteArray()) }
    }

    private fun proofJson(hwid: String?, packages: String?, statuses: List<String>): ByteArray {
        val json = JsonObject().apply {
            addProperty("protocolVersion", 1)
            addProperty("sessionId", sessionId.toString())
            addProperty("attempt", 1)
            addProperty("nonce", nonce)
            addProperty("playerUuid", playerUuid.toString())
            addProperty("buildId", "build-one")
            if (hwid == null) add("hwidSha256", JsonNull.INSTANCE) else addProperty("hwidSha256", hwid)
            if (packages == null) add("loadedPackagesBase64", JsonNull.INSTANCE) else addProperty("loadedPackagesBase64", packages)
            add("nativeStatuses", JsonArray().apply { statuses.forEach(::add) })
        }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }
}
