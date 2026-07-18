package me.jeyor.yguard.storage

import java.util.UUID

enum class BanSubjectType {
    ACCOUNT,
    HWID,
}

data class NewBan(
    val subjectType: BanSubjectType,
    val subjectValue: String,
    val sourceDetection: String,
    val createdAt: Long,
    val createdBy: String,
)

data class AuditEvent(
    val playerUuid: UUID?,
    val usernameSnapshot: String?,
    val sessionId: UUID?,
    val detectionType: String,
    val action: String,
    val details: String,
    val createdAt: Long,
)

enum class DatabaseDialect {
    SQLITE,
    MYSQL,
}
