package me.jeyor.yguard.core

import me.jeyor.yguard.command.YGuardCommand
import me.jeyor.yguard.compatibility.FloodgateCompatibility
import me.jeyor.yguard.config.YGuardConfigLoader
import me.jeyor.yguard.crypto.AttestationDecryptor
import me.jeyor.yguard.crypto.PrivateKeyRegistry
import me.jeyor.yguard.listener.AccountBanListener
import me.jeyor.yguard.protocol.ProtocolConstants
import me.jeyor.yguard.service.AttestationCoordinator
import me.jeyor.yguard.service.DetectionService
import me.jeyor.yguard.session.VerificationSessionManager
import me.jeyor.yguard.storage.DatabaseFactory
import me.jeyor.yguard.storage.DatabaseHandle
import me.jeyor.yguard.storage.YGuardRepository
import org.geysermc.floodgate.api.FloodgateApi
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level

class Core : JavaPlugin() {
    private var database: DatabaseHandle? = null
    private var databaseExecutor: ExecutorService? = null
    private var cryptoExecutor: ExecutorService? = null
    private var sessions: VerificationSessionManager? = null

    override fun onEnable() {
        try {
            saveDefaultConfig()
            val configuration = YamlConfiguration.loadConfiguration(File(dataFolder, "config.yml"))
            val yguardConfig = YGuardConfigLoader.load(configuration, dataFolder.toPath())
            val keyRegistry = PrivateKeyRegistry.load(yguardConfig.keys)
            val databaseHandle = DatabaseFactory.open(yguardConfig.storage)
            database = databaseHandle
            val repository = YGuardRepository(databaseHandle.dataSource)
            val dbExecutor = Executors.newFixedThreadPool(2, namedThreadFactory("YGuard-Database"))
            val decryptExecutor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                namedThreadFactory("YGuard-Crypto"),
            )
            databaseExecutor = dbExecutor
            cryptoExecutor = decryptExecutor
            val detectionService = DetectionService(yguardConfig, repository)
            val floodgateCompatibility = FloodgateCompatibility(
                isFloodgateEnabled = { server.pluginManager.isPluginEnabled("floodgate") },
                isFloodgatePlayer = { playerUuid -> FloodgateApi.getInstance().isFloodgatePlayer(playerUuid) },
            )
            lateinit var coordinator: AttestationCoordinator
            val sessionManager = VerificationSessionManager(
                plugin = this,
                activeKeyId = keyRegistry.activeKeyId,
                envelopeHandler = { player, binding, envelope -> coordinator.processEnvelope(player, binding, envelope) },
                expirationHandler = { context -> coordinator.expire(context) },
                shouldSkipVerification = floodgateCompatibility::isBedrockPlayer,
            )
            coordinator = AttestationCoordinator(
                plugin = this,
                decryptor = AttestationDecryptor(keyRegistry),
                detectionService = detectionService,
                cryptoExecutor = decryptExecutor,
                databaseExecutor = dbExecutor,
                sessions = sessionManager,
            )
            sessions = sessionManager

            server.messenger.registerOutgoingPluginChannel(this, ProtocolConstants.CHALLENGE_CHANNEL)
            server.messenger.registerOutgoingPluginChannel(this, ProtocolConstants.RESULT_CHANNEL)
            server.messenger.registerIncomingPluginChannel(this, ProtocolConstants.FRAGMENT_CHANNEL, sessionManager)
            server.pluginManager.registerEvents(sessionManager, this)
            server.pluginManager.registerEvents(AccountBanListener(this, repository, dbExecutor), this)

            val command = requireNotNull(getCommand("yguard")) { "plugin.yml is missing the yguard command" }
            val commandHandler = YGuardCommand(this, repository, dbExecutor)
            command.setExecutor(commandHandler)
            command.tabCompleter = commandHandler
            server.onlinePlayers.forEach(sessionManager::start)
            logger.info("YGuard enabled with ${yguardConfig.storage.type.name.lowercase(Locale.ROOT)} storage")
        } catch (exception: Exception) {
            logger.log(
                Level.SEVERE,
                "YGuard refused to start. Check config.yml and install the configured PKCS#8 RSA-3072 private key. " +
                    "The client development key task writes an importable private key under its build directory.",
                exception,
            )
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        server.messenger.unregisterIncomingPluginChannel(this)
        server.messenger.unregisterOutgoingPluginChannel(this)
        sessions?.close()
        sessions = null
        cryptoExecutor?.shutdownNow()
        databaseExecutor?.shutdownNow()
        cryptoExecutor = null
        databaseExecutor = null
        database?.close()
        database = null
    }

    private fun namedThreadFactory(prefix: String): ThreadFactory {
        val sequence = AtomicInteger()
        return ThreadFactory { runnable ->
            Thread(runnable, "$prefix-${sequence.incrementAndGet()}").apply { isDaemon = true }
        }
    }
}
