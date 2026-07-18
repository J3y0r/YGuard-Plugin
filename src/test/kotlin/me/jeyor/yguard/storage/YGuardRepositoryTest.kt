package me.jeyor.yguard.storage

import me.jeyor.yguard.config.MysqlConfig
import me.jeyor.yguard.config.SqliteConfig
import me.jeyor.yguard.config.StorageConfig
import me.jeyor.yguard.config.StorageType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YGuardRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `migrates records bans idempotently and audits unban`() {
        val handle = DatabaseFactory.open(
            StorageConfig(
                type = StorageType.SQLITE,
                sqlite = SqliteConfig(temporaryDirectory.resolve("yguard.db")),
                mysql = MysqlConfig("localhost", 3306, "unused", "unused", "unused"),
            ),
        )
        handle.use {
            val repository = YGuardRepository(handle.dataSource)
            val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
            val ban = NewBan(BanSubjectType.ACCOUNT, uuid.toString(), "BUILD_ID_INVALID", 10, "YGuard")
            val audit = AuditEvent(uuid, "Player", uuid, "BUILD_ID_INVALID", "BAN_ACCOUNT", "Rejected", 10)

            repository.recordEnforcement(listOf(ban), listOf(audit))
            repository.recordEnforcement(listOf(ban), emptyList())

            assertTrue(repository.isBanned(BanSubjectType.ACCOUNT, uuid.toString()))
            assertEquals(1, count(handle, "bans"))
            assertEquals(1, count(handle, "audit_events"))

            val unbanAudit = AuditEvent(uuid, null, null, "ADMIN_UNBAN", "UNBAN", "Removed", 20)
            assertEquals(1, repository.unban(BanSubjectType.ACCOUNT, uuid.toString(), "Console", 20, unbanAudit))
            assertFalse(repository.isBanned(BanSubjectType.ACCOUNT, uuid.toString()))
            assertEquals(2, count(handle, "audit_events"))
            assertEquals(0, repository.unban(BanSubjectType.ACCOUNT, uuid.toString(), "Console", 30, unbanAudit))
            assertEquals(2, count(handle, "audit_events"))
        }
    }

    private fun count(handle: DatabaseHandle, table: String): Int = handle.dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }
}
