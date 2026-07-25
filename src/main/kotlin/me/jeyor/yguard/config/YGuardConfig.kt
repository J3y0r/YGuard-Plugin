package me.jeyor.yguard.config

import me.jeyor.yguard.domain.EnforcementAction
import me.jeyor.yguard.domain.DetectionType
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

enum class StorageType {
    SQLITE,
    MYSQL,
}

data class SqliteConfig(val file: Path)

data class MysqlConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
)

data class StorageConfig(
    val type: StorageType,
    val sqlite: SqliteConfig,
    val mysql: MysqlConfig,
)

data class KeyConfig(
    val activeKeyId: String,
    val privateKeys: Map<String, Path>,
)

data class PackageRules(
    val exact: Set<String>,
    val prefixes: Set<String>,
)

data class MessageTemplates(
    val kick: String,
    val ban: String,
    val serviceUnavailable: String,
) {
    fun renderKick(player: String, uuid: UUID, detections: String, action: String): String =
        render(kick, player, uuid, detections, action)

    fun renderBan(player: String, uuid: UUID, detections: String, action: String): String =
        render(ban, player, uuid, detections, action)

    fun renderServiceUnavailable(player: String, uuid: UUID): String =
        render(serviceUnavailable, player, uuid, "", "")

    private fun render(template: String, player: String, uuid: UUID, detections: String, action: String): String =
        template
            .replace("{player}", player)
            .replace("{uuid}", uuid.toString())
            .replace("{detections}", detections)
            .replace("{action}", action)

    companion object {
        fun defaults() = MessageTemplates(
            kick = "YGuard verification failed: {detections}",
            ban = "This account is banned by YGuard",
            serviceUnavailable = "YGuard verification service is temporarily unavailable",
        )
    }
}

data class YGuardConfig(
    val storage: StorageConfig,
    val keys: KeyConfig,
    val allowedBuildIds: Set<String>,
    val suspiciousPackages: PackageRules,
    val actions: Map<DetectionType, EnforcementAction>,
    val messages: MessageTemplates = MessageTemplates.defaults(),
)

class ConfigException(message: String) : IllegalArgumentException(message)

object YGuardConfigLoader {
    private val keyIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    fun load(configuration: FileConfiguration, dataDirectory: Path): YGuardConfig {
        requireKeys(
            configuration,
            "root",
            setOf("storage", "keys", "allowedBuildIds", "suspiciousPackages", "actions"),
            setOf("messages"),
        )
        val storageSection = configuration.requiredSection("storage")
        requireKeys(storageSection, "storage", setOf("type", "sqlite", "mysql"))
        val sqliteSection = storageSection.requiredSection("sqlite")
        requireKeys(sqliteSection, "storage.sqlite", setOf("file"))
        val mysqlSection = storageSection.requiredSection("mysql")
        requireKeys(mysqlSection, "storage.mysql", setOf("host", "port", "database", "username", "password"))

        val storageType = enumValue<StorageType>(storageSection.requiredString("type"), "storage.type")
        val sqlite = SqliteConfig(resolvePrivatePath(dataDirectory, sqliteSection.requiredString("file"), "storage.sqlite.file"))
        val mysql = MysqlConfig(
            host = mysqlSection.requiredString("host"),
            port = mysqlSection.requiredInt("port", 1..65535),
            database = mysqlSection.requiredString("database"),
            username = mysqlSection.requiredString("username"),
            password = mysqlSection.requiredString("password", allowEmpty = true),
        )

        val keysSection = configuration.requiredSection("keys")
        requireKeys(keysSection, "keys", setOf("activeKeyId", "privateKeys"))
        val activeKeyId = keysSection.requiredString("activeKeyId").also(::validateKeyId)
        val privateKeySection = keysSection.requiredSection("privateKeys")
        if (privateKeySection.getKeys(false).isEmpty()) {
            throw ConfigException("keys.privateKeys must contain at least one key")
        }
        val privateKeys = privateKeySection.getKeys(false).associateWith { keyId ->
            validateKeyId(keyId)
            resolvePrivatePath(dataDirectory, privateKeySection.requiredString(keyId), "keys.privateKeys.$keyId")
        }
        if (activeKeyId !in privateKeys) {
            throw ConfigException("keys.activeKeyId must reference a configured private key")
        }

        val allowedBuildIds = configuration.requiredStringList("allowedBuildIds").toStrictSet("allowedBuildIds")
        val packagesSection = configuration.requiredSection("suspiciousPackages")
        requireKeys(packagesSection, "suspiciousPackages", setOf("exact", "prefixes"))
        val rules = PackageRules(
            exact = packagesSection.requiredStringList("exact").toStrictSet("suspiciousPackages.exact"),
            prefixes = packagesSection.requiredStringList("prefixes").toStrictSet("suspiciousPackages.prefixes"),
        )

        val messages = configuration.getConfigurationSection("messages")?.let { messagesSection ->
            requireKeys(messagesSection, "messages", setOf("kick", "ban", "serviceUnavailable"))
            MessageTemplates(
                kick = messagesSection.requiredString("kick"),
                ban = messagesSection.requiredString("ban"),
                serviceUnavailable = messagesSection.requiredString("serviceUnavailable"),
            )
        } ?: MessageTemplates.defaults()

        val actionsSection = configuration.requiredSection("actions")
        val expectedDetections = DetectionType.entries.toSet()
        val actionNames = actionsSection.getKeys(false)
        if (actionNames != expectedDetections.mapTo(mutableSetOf()) { it.name }) {
            val missing = expectedDetections.map { it.name }.toSet() - actionNames
            val unknown = actionNames - expectedDetections.map { it.name }.toSet()
            throw ConfigException("actions must define every detection; missing=$missing, unknown=$unknown")
        }
        val actions = expectedDetections.associateWith { detection ->
            enumValue<EnforcementAction>(actionsSection.requiredString(detection.name), "actions.${detection.name}")
        }

        return YGuardConfig(
            storage = StorageConfig(storageType, sqlite, mysql),
            keys = KeyConfig(activeKeyId, privateKeys),
            allowedBuildIds = allowedBuildIds,
            suspiciousPackages = rules,
            actions = actions,
            messages = messages,
        )
    }

    private fun validateKeyId(keyId: String) {
        if (!keyIdPattern.matches(keyId)) {
            throw ConfigException("Invalid keyId: $keyId")
        }
    }

    private fun resolvePrivatePath(dataDirectory: Path, configured: String, field: String): Path {
        val root = dataDirectory.toAbsolutePath().normalize()
        val resolved = root.resolve(configured).normalize()
        if (!resolved.startsWith(root)) {
            throw ConfigException("$field must stay inside the plugin data directory")
        }
        return resolved
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
        try {
            enumValueOf<T>(value.uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            throw ConfigException("Invalid $field: $value")
        }

    private fun requireKeys(
        section: ConfigurationSection,
        name: String,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        val keys = section.getKeys(false)
        val unknown = keys - required - optional
        if (unknown.isNotEmpty()) {
            throw ConfigException("Unknown keys in $name: $unknown")
        }
        val missing = required - keys
        if (missing.isNotEmpty()) {
            throw ConfigException("Missing keys in $name: $missing")
        }
    }

    private fun ConfigurationSection.requiredSection(path: String): ConfigurationSection =
        getConfigurationSection(path) ?: throw ConfigException("$path must be a section")

    private fun ConfigurationSection.requiredString(path: String, allowEmpty: Boolean = false): String {
        if (!isString(path)) {
            throw ConfigException("$path must be a string")
        }
        val value = getString(path) ?: throw ConfigException("$path must be a string")
        if (!allowEmpty && value.isBlank()) {
            throw ConfigException("$path must not be blank")
        }
        return value
    }

    private fun ConfigurationSection.requiredInt(path: String, range: IntRange): Int {
        if (!isInt(path)) {
            throw ConfigException("$path must be an integer")
        }
        val value = getInt(path)
        if (value !in range) {
            throw ConfigException("$path must be in ${range.first}..${range.last}")
        }
        return value
    }

    private fun ConfigurationSection.requiredStringList(path: String): List<String> {
        if (!isList(path)) {
            throw ConfigException("$path must be a list")
        }
        val raw = getList(path) ?: throw ConfigException("$path must be a list")
        if (raw.any { it !is String }) {
            throw ConfigException("$path must contain only strings")
        }
        return raw.map { it as String }
    }

    private fun List<String>.toStrictSet(field: String): Set<String> {
        if (any { it.isBlank() }) {
            throw ConfigException("$field must not contain blank values")
        }
        if (size != toSet().size) {
            throw ConfigException("$field must not contain duplicate values")
        }
        return toSet()
    }
}
