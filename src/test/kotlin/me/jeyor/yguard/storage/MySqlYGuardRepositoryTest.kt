package me.jeyor.yguard.storage

import ch.vorburger.mariadb4j.DB
import ch.vorburger.mariadb4j.DBConfigurationBuilder
import me.jeyor.yguard.config.MysqlConfig
import me.jeyor.yguard.config.SqliteConfig
import me.jeyor.yguard.config.StorageConfig
import me.jeyor.yguard.config.StorageType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MySqlYGuardRepositoryTest {
    @Test
    fun `mysql migrates and persists bans unbans and audits`(@TempDir temporaryDirectory: Path) {
        val port = ServerSocket(0).use { it.localPort }
        val configuration = DBConfigurationBuilder.newBuilder()
            .setPort(port)
            .setDataDir(temporaryDirectory.resolve("data").toFile())
            .setBaseDir(temporaryDirectory.resolve("base").toFile())
            .setDeletingTemporaryBaseAndDataDirsOnShutdown(true)
            .build()
        val database = DB.newEmbeddedDB(configuration)
        try {
            database.start()
            database.createDB("yguard")
            val storage = StorageConfig(
                type = StorageType.MYSQL,
                sqlite = SqliteConfig(temporaryDirectory.resolve("unused.db")),
                mysql = MysqlConfig(
                    host = "127.0.0.1",
                    port = port,
                    database = "yguard",
                    username = "root",
                    password = "",
                ),
            )
            DatabaseFactory.open(storage).use { handle ->
                assertEquals(DatabaseDialect.MYSQL, handle.dialect)
                val repository = YGuardRepository(handle.dataSource)
                val playerUuid = UUID.randomUUID()
                val sessionId = UUID.randomUUID()
                val hwid = "a".repeat(64)
                val createdAt = System.currentTimeMillis()
                repository.recordEnforcement(
                    bans = listOf(NewBan(BanSubjectType.HWID, hwid, "HWID_BANNED", createdAt, "test")),
                    auditEvents = listOf(
                        AuditEvent(
                            playerUuid,
                            "Player",
                            sessionId,
                            "HWID_BANNED",
                            "BAN_HWID_ACCOUNT",
                            "integration",
                            createdAt,
                        ),
                    ),
                )
                assertTrue(repository.isBanned(BanSubjectType.HWID, hwid))
                val lifted = repository.unban(
                    BanSubjectType.HWID,
                    hwid,
                    "integration-test",
                    createdAt + 1,
                    AuditEvent(playerUuid, "Player", sessionId, "ADMIN_UNBAN", "UNBAN", "integration", createdAt + 1),
                )
                assertEquals(1, lifted)
                assertFalse(repository.isBanned(BanSubjectType.HWID, hwid))
                handle.dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT COUNT(*) FROM audit_events").use { result ->
                            assertTrue(result.next())
                            assertEquals(2, result.getInt(1))
                        }
                    }
                }
            }
        } finally {
            database.stop()
        }
    }
}
