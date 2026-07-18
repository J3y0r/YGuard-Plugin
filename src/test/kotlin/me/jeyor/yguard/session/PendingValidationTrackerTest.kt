package me.jeyor.yguard.session

import me.jeyor.yguard.crypto.ChallengeBinding
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingValidationTrackerTest {
    @Test
    fun `timely received older attempt remains claimable after newer attempt is received`() {
        val playerUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val first = ChallengeBinding(sessionId, 1, "first", "key", playerUuid)
        val second = ChallengeBinding(sessionId, 2, "second", "key", playerUuid)
        val tracker = PendingValidationTracker()

        tracker.received(first)
        tracker.received(second)

        assertTrue(tracker.claim(first))
        assertTrue(tracker.hasPending)
        assertTrue(tracker.failed(second))
        assertFalse(tracker.hasPending)
    }
}
