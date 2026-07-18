package me.jeyor.yguard.storage

import java.sql.Connection
import javax.sql.DataSource

class YGuardRepository(private val dataSource: DataSource) {
    fun isBanned(subjectType: BanSubjectType, subjectValue: String): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM bans WHERE subject_type = ? AND subject_value = ? AND lifted_at IS NULL LIMIT 1",
            ).use { statement ->
                statement.setString(1, subjectType.name)
                statement.setString(2, subjectValue)
                statement.executeQuery().use { it.next() }
            }
        }

    fun recordEnforcement(bans: List<NewBan>, auditEvents: List<AuditEvent>) {
        if (bans.isEmpty() && auditEvents.isEmpty()) {
            return
        }
        transaction { connection ->
            bans.forEach { insertBanIfAbsent(connection, it) }
            insertAudits(connection, auditEvents)
        }
    }

    fun recordAudit(auditEvent: AuditEvent) {
        transaction { connection -> insertAudits(connection, listOf(auditEvent)) }
    }

    fun unban(
        subjectType: BanSubjectType,
        subjectValue: String,
        actor: String,
        now: Long,
        auditEvent: AuditEvent,
    ): Int = transaction { connection ->
        val updated = connection.prepareStatement(
            "UPDATE bans SET lifted_at = ?, lifted_by = ? WHERE subject_type = ? AND subject_value = ? AND lifted_at IS NULL",
        ).use { statement ->
            statement.setLong(1, now)
            statement.setString(2, actor)
            statement.setString(3, subjectType.name)
            statement.setString(4, subjectValue)
            statement.executeUpdate()
        }
        if (updated > 0) {
            insertAudits(connection, listOf(auditEvent))
        }
        updated
    }

    private fun insertBanIfAbsent(connection: Connection, ban: NewBan) {
        val exists = connection.prepareStatement(
            "SELECT 1 FROM bans WHERE subject_type = ? AND subject_value = ? AND lifted_at IS NULL LIMIT 1",
        ).use { statement ->
            statement.setString(1, ban.subjectType.name)
            statement.setString(2, ban.subjectValue)
            statement.executeQuery().use { it.next() }
        }
        if (exists) {
            return
        }
        connection.prepareStatement(
            """
            INSERT INTO bans(subject_type, subject_value, source_detection, created_at, created_by, lifted_at, lifted_by)
            VALUES (?, ?, ?, ?, ?, NULL, NULL)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ban.subjectType.name)
            statement.setString(2, ban.subjectValue)
            statement.setString(3, ban.sourceDetection)
            statement.setLong(4, ban.createdAt)
            statement.setString(5, ban.createdBy)
            statement.executeUpdate()
        }
    }

    private fun insertAudits(connection: Connection, events: List<AuditEvent>) {
        if (events.isEmpty()) {
            return
        }
        connection.prepareStatement(
            """
            INSERT INTO audit_events(
                player_uuid, username_snapshot, session_id, detection_type, action_name, details, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            events.forEach { event ->
                statement.setString(1, event.playerUuid?.toString())
                statement.setString(2, event.usernameSnapshot)
                statement.setString(3, event.sessionId?.toString())
                statement.setString(4, event.detectionType)
                statement.setString(5, event.action)
                statement.setString(6, event.details)
                statement.setLong(7, event.createdAt)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }
}
