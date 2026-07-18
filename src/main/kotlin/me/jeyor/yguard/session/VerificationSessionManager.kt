package me.jeyor.yguard.session

import me.jeyor.yguard.crypto.ChallengeBinding
import me.jeyor.yguard.domain.AttestationResultStatus
import me.jeyor.yguard.protocol.FragmentAssembly
import me.jeyor.yguard.protocol.FragmentCodec
import me.jeyor.yguard.protocol.PayloadCodec
import me.jeyor.yguard.protocol.ProtocolConstants
import me.jeyor.yguard.protocol.ProtocolException
import me.jeyor.yguard.service.PlayerVerificationContext
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener
import org.bukkit.scheduler.BukkitTask
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class OnlineVerificationContext(
    val player: Player,
    val verification: PlayerVerificationContext,
)

class VerificationSessionManager(
    private val plugin: Plugin,
    private val activeKeyId: String,
    private val envelopeHandler: (Player, ChallengeBinding, ByteArray) -> Unit,
    private val expirationHandler: (OnlineVerificationContext) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : Listener, PluginMessageListener, AutoCloseable {
    private data class AttemptState(
        val binding: ChallengeBinding,
        val expiresAt: Long,
        val assembly: FragmentAssembly = FragmentAssembly(),
        var processing: Boolean = false,
        var invalid: Boolean = false,
    )

    private data class Session(
        val player: Player,
        val id: UUID,
        val tasks: MutableList<BukkitTask> = ArrayList(),
        val pendingValidations: PendingValidationTracker = PendingValidationTracker(),
        var attempt: AttemptState? = null,
        var expirationPoll: BukkitTask? = null,
    )

    private val sessions = HashMap<UUID, Session>()

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        start(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        remove(event.player.uniqueId)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != ProtocolConstants.FRAGMENT_CHANNEL) {
            return
        }
        val payload = message.copyOf()
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, Runnable { receive(player, payload) })
            return
        }
        receive(player, payload)
    }

    fun start(player: Player) {
        check(Bukkit.isPrimaryThread())
        remove(player.uniqueId)
        val session = Session(player, UUID.randomUUID())
        sessions[player.uniqueId] = session
        activate(session, 1)
        session.tasks += Bukkit.getScheduler().runTaskLater(plugin, Runnable { advance(session.id, player.uniqueId, 2) }, 100L)
        session.tasks += Bukkit.getScheduler().runTaskLater(plugin, Runnable { advance(session.id, player.uniqueId, 3) }, 200L)
        session.tasks += Bukkit.getScheduler().runTaskLater(plugin, Runnable { expire(session.id, player.uniqueId) }, 300L)
    }

    fun markInvalid(binding: ChallengeBinding) {
        check(Bukkit.isPrimaryThread())
        val session = sessions[binding.playerUuid] ?: return
        if (!session.pendingValidations.failed(binding)) {
            return
        }
        session.attempt?.takeIf { it.binding == binding }?.let { attempt ->
            attempt.processing = false
            attempt.invalid = true
            attempt.assembly.clear()
        }
    }

    fun claimValid(player: Player, binding: ChallengeBinding): OnlineVerificationContext? {
        check(Bukkit.isPrimaryThread())
        val session = sessions[player.uniqueId] ?: return null
        if (
            session.player !== player ||
            !session.pendingValidations.claim(binding)
        ) {
            return null
        }
        sessions.remove(player.uniqueId)
        cancelTasks(session)
        session.attempt?.assembly?.clear()
        session.pendingValidations.clear()
        return OnlineVerificationContext(
            player,
            PlayerVerificationContext(player.uniqueId, player.name, session.id),
        )
    }

    fun sendResult(context: OnlineVerificationContext, attempt: Int, status: AttestationResultStatus) {
        check(Bukkit.isPrimaryThread())
        if (!sameConnection(context.player, context.verification.playerUuid)) {
            return
        }
        context.player.sendPluginMessage(
            plugin,
            ProtocolConstants.RESULT_CHANNEL,
            PayloadCodec.encodeResult(context.verification.sessionId, attempt, status),
        )
    }

    override fun close() {
        check(Bukkit.isPrimaryThread())
        sessions.values.forEach { session ->
            session.attempt?.assembly?.clear()
            session.pendingValidations.clear()
            cancelTasks(session)
        }
        sessions.clear()
    }

    private fun receive(player: Player, payload: ByteArray) {
        check(Bukkit.isPrimaryThread())
        val fragment = try {
            FragmentCodec.decode(payload)
        } catch (_: ProtocolException) {
            return
        }
        val session = sessions[player.uniqueId] ?: return
        val attempt = session.attempt ?: return
        if (
            session.player !== player ||
            fragment.sessionId != session.id ||
            fragment.attempt != attempt.binding.attempt ||
            attempt.invalid ||
            attempt.processing ||
            clock() >= attempt.expiresAt
        ) {
            return
        }
        val envelope = try {
            attempt.assembly.add(fragment)
        } catch (_: ProtocolException) {
            attempt.invalid = true
            attempt.assembly.clear()
            return
        } ?: return
        attempt.processing = true
        session.pendingValidations.received(attempt.binding)
        envelopeHandler(player, attempt.binding, envelope)
    }

    private fun activate(session: Session, attemptNumber: Int) {
        session.attempt?.assembly?.clear()
        val nonceBytes = ByteArray(32).also(random::nextBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val expiresAt = clock() + ProtocolConstants.CHALLENGE_LIFETIME_MILLIS
        val binding = ChallengeBinding(session.id, attemptNumber, nonce, activeKeyId, session.player.uniqueId)
        session.attempt = AttemptState(binding, expiresAt)
        if (sameConnection(session.player, session.player.uniqueId)) {
            session.player.sendPluginMessage(
                plugin,
                ProtocolConstants.CHALLENGE_CHANNEL,
                PayloadCodec.encodeChallenge(
                    sessionId = session.id,
                    attempt = attemptNumber,
                    nonce = nonce,
                    keyId = activeKeyId,
                    expiresAtEpochMs = expiresAt,
                    playerUuid = session.player.uniqueId,
                ),
            )
        }
    }

    private fun advance(sessionId: UUID, playerUuid: UUID, attempt: Int) {
        val session = sessions[playerUuid] ?: return
        if (session.id != sessionId || !sameConnection(session.player, playerUuid)) {
            remove(playerUuid)
            return
        }
        activate(session, attempt)
    }

    private fun expire(sessionId: UUID, playerUuid: UUID) {
        val session = sessions[playerUuid] ?: return
        if (session.id != sessionId) {
            return
        }
        if (session.pendingValidations.hasPending) {
            session.expirationPoll = Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable { expire(sessionId, playerUuid) },
                1L,
            )
            return
        }
        sessions.remove(playerUuid)
        session.attempt?.assembly?.clear()
        session.pendingValidations.clear()
        cancelTasks(session)
        if (!sameConnection(session.player, playerUuid)) {
            return
        }
        val context = OnlineVerificationContext(
            session.player,
            PlayerVerificationContext(playerUuid, session.player.name, session.id),
        )
        expirationHandler(context)
    }

    private fun remove(playerUuid: UUID) {
        val removed = sessions.remove(playerUuid) ?: return
        removed.attempt?.assembly?.clear()
        removed.pendingValidations.clear()
        cancelTasks(removed)
    }

    private fun cancelTasks(session: Session) {
        session.tasks.forEach(BukkitTask::cancel)
        session.tasks.clear()
        session.expirationPoll?.cancel()
        session.expirationPoll = null
    }

    private fun sameConnection(player: Player, playerUuid: UUID): Boolean =
        player.uniqueId == playerUuid && player.isOnline
}
