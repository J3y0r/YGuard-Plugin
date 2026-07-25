package me.jeyor.yguard.service

import me.jeyor.yguard.config.MessageTemplates
import me.jeyor.yguard.crypto.AttestationDecryptor
import me.jeyor.yguard.crypto.ChallengeBinding
import me.jeyor.yguard.domain.AttestationResultStatus
import me.jeyor.yguard.domain.DetectionDecision
import me.jeyor.yguard.domain.EnforcementAction
import me.jeyor.yguard.session.OnlineVerificationContext
import me.jeyor.yguard.session.VerificationSessionManager
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level

class AttestationCoordinator(
    private val plugin: Plugin,
    private val decryptor: AttestationDecryptor,
    private val detectionService: DetectionService,
    private val messages: MessageTemplates,
    private val cryptoExecutor: Executor,
    private val databaseExecutor: Executor,
    private val sessions: VerificationSessionManager,
) {
    fun processEnvelope(player: Player, binding: ChallengeBinding, envelope: ByteArray) {
        CompletableFuture.supplyAsync(
            { decryptor.decryptAndValidate(envelope, binding) },
            cryptoExecutor,
        ).whenComplete { proof, throwable ->
            runOnMain {
                if (throwable != null) {
                    sessions.markInvalid(binding)
                    plugin.logger.warning(
                        "Rejected invalid YGuard attestation for ${player.name} (${binding.playerUuid}); " +
                            "session=${binding.sessionId}, attempt=${binding.attempt}: ${unwrap(throwable).message}",
                    )
                    return@runOnMain
                }
                val context = sessions.claimValid(player, binding) ?: return@runOnMain
                CompletableFuture.supplyAsync(
                    { detectionService.evaluateAndRecord(context.verification, proof) },
                    databaseExecutor,
                ).whenComplete { decision, databaseFailure ->
                    runOnMain {
                        if (databaseFailure != null) {
                            handleStorageFailure(context, databaseFailure)
                            return@runOnMain
                        }
                        val status = if (decision.detections.isEmpty()) {
                            plugin.logger.info(
                                "YGuard verification accepted for ${context.verification.username} " +
                                    "(${context.verification.playerUuid}); session=${context.verification.sessionId}",
                            )
                            AttestationResultStatus.ACCEPTED
                        } else {
                            plugin.logger.warning(
                                "YGuard verification rejected for ${context.verification.username} " +
                                    "(${context.verification.playerUuid}); session=${context.verification.sessionId}, " +
                                    "detections=${decision.detections.joinToString(",") { it.type.name }}, " +
                                    "action=${decision.action}",
                            )
                            AttestationResultStatus.REJECTED
                        }
                        sessions.sendResult(context, binding.attempt, status)
                        decision.action?.let { enforce(context, decision, it) }
                    }
                }
            }
        }
    }

    fun expire(context: OnlineVerificationContext) {
        plugin.logger.warning(
            "YGuard verification expired for ${context.verification.username} " +
                "(${context.verification.playerUuid}); session=${context.verification.sessionId}",
        )
        sessions.sendResult(context, 3, AttestationResultStatus.EXPIRED)
        CompletableFuture.supplyAsync(
            { detectionService.recordVerificationFailure(context.verification) },
            databaseExecutor,
        ).whenComplete { decision, throwable ->
            runOnMain {
                if (throwable != null) {
                    handleStorageFailure(context, throwable)
                    return@runOnMain
                }
                decision.action?.let { enforce(context, decision, it) }
            }
        }
    }

    private fun enforce(
        context: OnlineVerificationContext,
        decision: DetectionDecision,
        action: EnforcementAction,
    ) {
        val detections = decision.detections.joinToString(",") { it.type.name }
        when (action) {
            EnforcementAction.WARN -> notifyAdmins(context, detections, action)
            EnforcementAction.KICK,
            EnforcementAction.BAN_ACCOUNT,
            EnforcementAction.BAN_HWID_ACCOUNT,
            -> if (context.player.isOnline && context.player.uniqueId == context.verification.playerUuid) {
                val message = when (action) {
                    EnforcementAction.KICK -> messages.renderKick(
                        context.verification.username,
                        context.verification.playerUuid,
                        detections,
                        action.name,
                    )
                    EnforcementAction.BAN_ACCOUNT, EnforcementAction.BAN_HWID_ACCOUNT -> messages.renderBan(
                        context.verification.username,
                        context.verification.playerUuid,
                        detections,
                        action.name,
                    )
                    EnforcementAction.WARN -> error("Unexpected WARN enforcement")
                }
                context.player.kick(Component.text(message))
                plugin.logger.warning(
                    "Enforced YGuard ${action.name} for ${context.verification.username} " +
                        "(${context.verification.playerUuid}); detections=$detections",
                )
            }
        }
    }

    private fun notifyAdmins(
        context: OnlineVerificationContext,
        detections: String,
        action: EnforcementAction,
    ) {
        val message = Component.text(
            "[YGuard] ${context.verification.username} (${context.verification.playerUuid}) triggered $detections; action=${action.name}",
        )
        Bukkit.getOnlinePlayers().asSequence()
            .filter { it.hasPermission("yguard.admin") }
            .forEach { it.sendMessage(message) }
        plugin.logger.warning(
            "${context.verification.username} (${context.verification.playerUuid}) triggered $detections; action=${action.name}",
        )
    }

    private fun handleStorageFailure(context: OnlineVerificationContext, throwable: Throwable) {
        plugin.logger.log(Level.SEVERE, "YGuard database operation failed", unwrap(throwable))
        Bukkit.getOnlinePlayers().asSequence()
            .filter { it.hasPermission("yguard.admin") }
            .forEach { it.sendMessage(Component.text("[YGuard] Database operation failed; check server logs")) }
        if (context.player.isOnline && context.player.uniqueId == context.verification.playerUuid) {
            context.player.kick(
                Component.text(
                    messages.renderServiceUnavailable(
                        context.verification.username,
                        context.verification.playerUuid,
                    ),
                ),
            )
            plugin.logger.warning(
                "Disconnected ${context.verification.username} (${context.verification.playerUuid}) " +
                    "because the YGuard verification service was unavailable",
            )
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (!plugin.isEnabled) {
            return
        }
        Bukkit.getScheduler().runTask(plugin, Runnable(block))
    }

    private fun unwrap(throwable: Throwable): Throwable = throwable.cause ?: throwable
}
