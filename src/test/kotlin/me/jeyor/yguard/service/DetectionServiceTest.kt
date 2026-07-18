package me.jeyor.yguard.service

import me.jeyor.yguard.config.KeyConfig
import me.jeyor.yguard.config.MysqlConfig
import me.jeyor.yguard.config.PackageRules
import me.jeyor.yguard.config.SqliteConfig
import me.jeyor.yguard.config.StorageConfig
import me.jeyor.yguard.config.StorageType
import me.jeyor.yguard.config.YGuardConfig
import me.jeyor.yguard.domain.AttestationProof
import me.jeyor.yguard.domain.DetectionType
import me.jeyor.yguard.domain.EnforcementAction
import me.jeyor.yguard.domain.NativeStatus
import me.jeyor.yguard.storage.BanSubjectType
import me.jeyor.yguard.storage.DatabaseFactory
import me.jeyor.yguard.storage.DatabaseHandle
import me.jeyor.yguard.storage.NewBan
import me.jeyor.yguard.storage.YGuardRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectionServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `audits every detection and applies only strongest action`() {
        database().use { handle ->
            val repository = YGuardRepository(handle.dataSource)
            val playerUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
            val sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222")
            val hwid = "a".repeat(64)
            repository.recordEnforcement(
                listOf(NewBan(BanSubjectType.HWID, hwid, "MANUAL", 1, "Admin")),
                emptyList(),
            )
            val actions = DetectionType.entries.associateWith { EnforcementAction.WARN }.toMutableMap().apply {
                this[DetectionType.HWID_BANNED] = EnforcementAction.BAN_HWID_ACCOUNT
                this[DetectionType.BUILD_ID_INVALID] = EnforcementAction.KICK
                this[DetectionType.SUSPICIOUS_PACKAGE] = EnforcementAction.BAN_ACCOUNT
            }
            val config = YGuardConfig(
                storage = unusedStorage(),
                keys = KeyConfig("key", mapOf("key" to temporaryDirectory.resolve("unused.pem"))),
                allowedBuildIds = setOf("allowed"),
                suspiciousPackages = PackageRules(setOf("bad.package"), emptySet()),
                actions = actions,
            )
            val proof = AttestationProof(
                protocolVersion = 1,
                sessionId = sessionId,
                attempt = 1,
                nonce = "nonce",
                playerUuid = playerUuid,
                buildId = "blocked",
                hwidSha256 = hwid,
                loadedPackages = listOf("bad.package"),
                nativeStatuses = listOf(NativeStatus.HOOK_UNAVAILABLE),
            )

            val decision = DetectionService(config, repository).evaluateAndRecord(
                PlayerVerificationContext(playerUuid, "Player", sessionId),
                proof,
            )

            assertEquals(4, decision.detections.size)
            assertEquals(EnforcementAction.BAN_HWID_ACCOUNT, decision.action)
            assertTrue(repository.isBanned(BanSubjectType.ACCOUNT, playerUuid.toString()))
            assertEquals(4, count(handle, "audit_events"))
            assertEquals(setOf("BAN_HWID_ACCOUNT"), distinctActions(handle))
        }
    }

    private fun database() = DatabaseFactory.open(unusedStorage())

    private fun unusedStorage() = StorageConfig(
        type = StorageType.SQLITE,
        sqlite = SqliteConfig(temporaryDirectory.resolve("detection.db")),
        mysql = MysqlConfig("localhost", 3306, "unused", "unused", "unused"),
    )

    private fun count(handle: DatabaseHandle, table: String): Int = handle.dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun distinctActions(handle: DatabaseHandle): Set<String> = handle.dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT DISTINCT action_name FROM audit_events").use { result ->
                buildSet {
                    while (result.next()) add(result.getString(1))
                }
            }
        }
    }
}
