package me.jeyor.yguard.compatibility

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FloodgateCompatibilityTest {
    private val playerUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `does not query Floodgate when the plugin is disabled`() {
        val compatibility = FloodgateCompatibility(
            isFloodgateEnabled = { false },
            isFloodgatePlayer = { error("Floodgate API should not be called") },
        )

        assertFalse(compatibility.isBedrockPlayer(playerUuid))
    }

    @Test
    fun `recognizes Floodgate players when the plugin is enabled`() {
        val compatibility = FloodgateCompatibility(
            isFloodgateEnabled = { true },
            isFloodgatePlayer = { it == playerUuid },
        )

        assertTrue(compatibility.isBedrockPlayer(playerUuid))
        assertFalse(compatibility.isBedrockPlayer(UUID.randomUUID()))
    }

    @Test
    fun `falls back to normal verification when Floodgate lookup fails`() {
        val compatibility = FloodgateCompatibility(
            isFloodgateEnabled = { true },
            isFloodgatePlayer = { throw IllegalStateException("Floodgate API unavailable") },
        )

        assertFalse(compatibility.isBedrockPlayer(playerUuid))
    }
}
