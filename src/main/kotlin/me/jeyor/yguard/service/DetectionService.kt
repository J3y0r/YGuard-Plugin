package me.jeyor.yguard.service

import me.jeyor.yguard.config.YGuardConfig
import me.jeyor.yguard.domain.AttestationProof
import me.jeyor.yguard.domain.Detection
import me.jeyor.yguard.domain.DetectionDecision
import me.jeyor.yguard.domain.DetectionType
import me.jeyor.yguard.domain.EnforcementAction
import me.jeyor.yguard.domain.NativeStatus
import me.jeyor.yguard.storage.AuditEvent
import me.jeyor.yguard.storage.BanSubjectType
import me.jeyor.yguard.storage.NewBan
import me.jeyor.yguard.storage.YGuardRepository
import java.util.UUID

data class PlayerVerificationContext(
    val playerUuid: UUID,
    val username: String,
    val sessionId: UUID,
)

class DetectionService(
    private val config: YGuardConfig,
    private val repository: YGuardRepository,
) {
    fun evaluateAndRecord(context: PlayerVerificationContext, proof: AttestationProof): DetectionDecision {
        val detections = ArrayList<Detection>()
        if (proof.hwidSha256 != null && repository.isBanned(BanSubjectType.HWID, proof.hwidSha256)) {
            detections += Detection(DetectionType.HWID_BANNED, "Submitted hardware identifier has an active ban")
        }
        if (proof.buildId !in config.allowedBuildIds) {
            detections += Detection(DetectionType.BUILD_ID_INVALID, "Client build is not allowed")
        }
        findSuspiciousPackages(proof.loadedPackages).takeIf { it.isNotEmpty() }?.let { packages ->
            val displayed = packages.take(32).joinToString(",")
            val suffix = if (packages.size > 32) ",... (${packages.size} total)" else ""
            detections += Detection(DetectionType.SUSPICIOUS_PACKAGE, "Suspicious packages: $displayed$suffix")
        }
        proof.nativeStatuses.forEach { status ->
            val detectionType = when (status) {
                NativeStatus.READY -> null
                NativeStatus.NATIVE_UNAVAILABLE -> DetectionType.NATIVE_UNAVAILABLE
                NativeStatus.HWID_UNAVAILABLE -> DetectionType.HWID_UNAVAILABLE
                NativeStatus.PACKAGES_UNAVAILABLE -> DetectionType.PACKAGES_UNAVAILABLE
                NativeStatus.HOOK_UNAVAILABLE -> DetectionType.HOOK_UNAVAILABLE
            }
            if (detectionType != null) {
                detections += Detection(detectionType, "Native status reported ${status.name}")
            }
        }
        if (detections.isEmpty()) {
            return DetectionDecision(emptyList(), null)
        }
        val selectedAction = config.actions.getValue(
            detections.maxBy { detection -> config.actions.getValue(detection.type).severity }.type,
        )
        record(context, proof.hwidSha256, detections, selectedAction)
        return DetectionDecision(detections, selectedAction)
    }

    fun recordVerificationFailure(context: PlayerVerificationContext): DetectionDecision {
        val detection = Detection(DetectionType.VERIFICATION_FAILED, "No valid proof was received after three attempts")
        val action = config.actions.getValue(DetectionType.VERIFICATION_FAILED)
        record(context, null, listOf(detection), action)
        return DetectionDecision(listOf(detection), action)
    }

    private fun record(
        context: PlayerVerificationContext,
        hwidSha256: String?,
        detections: List<Detection>,
        selectedAction: EnforcementAction,
    ) {
        val now = System.currentTimeMillis()
        val sourceDetection = detections.maxBy { config.actions.getValue(it.type).severity }.type.name
        val bans = when (selectedAction) {
            EnforcementAction.WARN, EnforcementAction.KICK -> emptyList()
            EnforcementAction.BAN_ACCOUNT -> listOf(accountBan(context, sourceDetection, now))
            EnforcementAction.BAN_HWID_ACCOUNT -> buildList {
                add(accountBan(context, sourceDetection, now))
                if (hwidSha256 != null) {
                    add(
                        NewBan(
                            subjectType = BanSubjectType.HWID,
                            subjectValue = hwidSha256,
                            sourceDetection = sourceDetection,
                            createdAt = now,
                            createdBy = "YGuard",
                        ),
                    )
                }
            }
        }
        val auditEvents = detections.map { detection ->
            AuditEvent(
                playerUuid = context.playerUuid,
                usernameSnapshot = context.username,
                sessionId = context.sessionId,
                detectionType = detection.type.name,
                action = selectedAction.name,
                details = detection.detail,
                createdAt = now,
            )
        }
        repository.recordEnforcement(bans, auditEvents)
    }

    private fun accountBan(context: PlayerVerificationContext, sourceDetection: String, now: Long) = NewBan(
        subjectType = BanSubjectType.ACCOUNT,
        subjectValue = context.playerUuid.toString(),
        sourceDetection = sourceDetection,
        createdAt = now,
        createdBy = "YGuard",
    )

    private fun findSuspiciousPackages(packages: List<String>?): List<String> {
        if (packages == null || packages.isEmpty()) {
            return emptyList()
        }
        return packages.filter { packageName ->
            packageName in config.suspiciousPackages.exact ||
                config.suspiciousPackages.prefixes.any(packageName::startsWith)
        }
    }
}
