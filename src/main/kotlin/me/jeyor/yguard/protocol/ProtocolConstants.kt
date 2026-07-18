package me.jeyor.yguard.protocol

object ProtocolConstants {
    const val VERSION = 1
    const val CHALLENGE_CHANNEL = "yguard:attestation_challenge"
    const val FRAGMENT_CHANNEL = "yguard:attestation_fragment"
    const val RESULT_CHANNEL = "yguard:attestation_result"
    const val FRAGMENT_HEADER_BYTES = 32
    const val MAX_FRAGMENT_PAYLOAD_BYTES = 24_576
    const val MAX_FRAGMENT_DATA_BYTES = MAX_FRAGMENT_PAYLOAD_BYTES - FRAGMENT_HEADER_BYTES
    const val MAX_FRAGMENT_COUNT = 22
    const val MAX_ENVELOPE_BYTES = 524_288
    const val MAX_PROOF_BYTES = 1_048_576
    const val CHALLENGE_LIFETIME_MILLIS = 5_000L
}

class ProtocolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
