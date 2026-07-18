package me.jeyor.yguard.compatibility

import java.util.UUID

class FloodgateCompatibility(
    private val isFloodgateEnabled: () -> Boolean,
    private val isFloodgatePlayer: (UUID) -> Boolean,
) {
    fun isBedrockPlayer(playerUuid: UUID): Boolean {
        if (!isFloodgateEnabled()) {
            return false
        }
        return try {
            isFloodgatePlayer(playerUuid)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}
