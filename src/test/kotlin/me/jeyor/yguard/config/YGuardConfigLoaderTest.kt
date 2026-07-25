package me.jeyor.yguard.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YGuardConfigLoaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `loads complete strict configuration`() {
        val loaded = YGuardConfigLoader.load(configuration(validYaml()), temporaryDirectory)

        assertEquals(StorageType.SQLITE, loaded.storage.type)
        assertEquals("test-key", loaded.keys.activeKeyId)
        assertEquals(8, loaded.actions.size)
        assertEquals(
            "Kick Player BUILD_ID_INVALID KICK",
            loaded.messages.renderKick(
                "Player",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "BUILD_ID_INVALID",
                "KICK",
            ),
        )
    }

    @Test
    fun `uses default message templates when messages are omitted`() {
        val yaml = validYaml().replace(
            """
            messages:
              kick: "Kick {player} {detections} {action}"
              ban: "Ban {player}"
              serviceUnavailable: "Unavailable {uuid}"
            """.trimIndent() + "\n",
            "",
        )

        val loaded = YGuardConfigLoader.load(configuration(yaml), temporaryDirectory)

        assertEquals("YGuard verification failed: BUILD_ID_INVALID", loaded.messages.renderKick(
            "Player",
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "BUILD_ID_INVALID",
            "KICK",
        ))
    }

    @Test
    fun `rejects a missing action and unknown root key`() {
        val missingAction = validYaml().replace("  HOOK_UNAVAILABLE: WARN\n", "")
        val unknownRoot = validYaml() + "unknown: true\n"

        assertFailsWith<ConfigException> {
            YGuardConfigLoader.load(configuration(missingAction), temporaryDirectory)
        }
        assertFailsWith<ConfigException> {
            YGuardConfigLoader.load(configuration(unknownRoot), temporaryDirectory)
        }
    }

    private fun configuration(yaml: String) = YamlConfiguration().apply { loadFromString(yaml) }

    private fun validYaml() = """
        storage:
          type: sqlite
          sqlite:
            file: yguard.db
          mysql:
            host: localhost
            port: 3306
            database: yguard
            username: yguard
            password: secret
        keys:
          activeKeyId: test-key
          privateKeys:
            test-key: keys/test.pem
        allowedBuildIds: []
        suspiciousPackages:
          exact: []
          prefixes: []
        messages:
          kick: "Kick {player} {detections} {action}"
          ban: "Ban {player}"
          serviceUnavailable: "Unavailable {uuid}"
        actions:
          HWID_BANNED: BAN_HWID_ACCOUNT
          BUILD_ID_INVALID: KICK
          SUSPICIOUS_PACKAGE: KICK
          NATIVE_UNAVAILABLE: KICK
          HWID_UNAVAILABLE: KICK
          PACKAGES_UNAVAILABLE: KICK
          HOOK_UNAVAILABLE: WARN
          VERIFICATION_FAILED: KICK
    """.trimIndent() + "\n"
}
