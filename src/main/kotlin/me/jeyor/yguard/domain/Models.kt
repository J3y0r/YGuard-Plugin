package me.jeyor.yguard.domain

import java.util.UUID

enum class DetectionType {
    HWID_BANNED,
    BUILD_ID_INVALID,
    SUSPICIOUS_PACKAGE,
    NATIVE_UNAVAILABLE,
    HWID_UNAVAILABLE,
    PACKAGES_UNAVAILABLE,
    HOOK_UNAVAILABLE,
    VERIFICATION_FAILED,
}

enum class EnforcementAction(val severity: Int) {
    WARN(0),
    KICK(1),
    BAN_ACCOUNT(2),
    BAN_HWID_ACCOUNT(3),
}

enum class NativeStatus {
    READY,
    NATIVE_UNAVAILABLE,
    HWID_UNAVAILABLE,
    PACKAGES_UNAVAILABLE,
    HOOK_UNAVAILABLE,
}

enum class AttestationResultStatus {
    ACCEPTED,
    REJECTED,
    EXPIRED,
}

data class AttestationProof(
    val protocolVersion: Int,
    val sessionId: UUID,
    val attempt: Int,
    val nonce: String,
    val playerUuid: UUID,
    val buildId: String,
    val hwidSha256: String?,
    val loadedPackages: List<String>?,
    val nativeStatuses: List<NativeStatus>,
)

data class Detection(
    val type: DetectionType,
    val detail: String,
)

data class DetectionDecision(
    val detections: List<Detection>,
    val action: EnforcementAction?,
)
