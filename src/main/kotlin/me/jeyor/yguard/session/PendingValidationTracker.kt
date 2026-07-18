package me.jeyor.yguard.session

import me.jeyor.yguard.crypto.ChallengeBinding

class PendingValidationTracker {
    private val bindings = HashSet<ChallengeBinding>()

    val hasPending: Boolean
        get() = bindings.isNotEmpty()

    fun received(binding: ChallengeBinding) {
        bindings += binding
    }

    fun failed(binding: ChallengeBinding): Boolean = bindings.remove(binding)

    fun claim(binding: ChallengeBinding): Boolean = bindings.remove(binding)

    fun clear() {
        bindings.clear()
    }
}
