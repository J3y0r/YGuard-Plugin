package me.jeyor.yguard.command

import me.jeyor.yguard.storage.AuditEvent
import me.jeyor.yguard.storage.BanSubjectType
import me.jeyor.yguard.storage.YGuardRepository
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level

class YGuardCommand(
    private val plugin: Plugin,
    private val repository: YGuardRepository,
    private val databaseExecutor: Executor,
) : CommandExecutor, TabCompleter {
    private val hwidPattern = Regex("[0-9a-f]{64}")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("yguard.admin")) {
            sender.sendMessage(Component.text("You do not have permission to use this command"))
            return true
        }
        if (args.size != 3 || !args[0].equals("unban", ignoreCase = true)) {
            return false
        }
        val subjectType = when (args[1].lowercase(Locale.ROOT)) {
            "account" -> BanSubjectType.ACCOUNT
            "hwid" -> BanSubjectType.HWID
            else -> return false
        }
        val subjectValue = when (subjectType) {
            BanSubjectType.ACCOUNT -> parseCanonicalUuid(args[2])?.toString()
            BanSubjectType.HWID -> args[2].takeIf(hwidPattern::matches)
        }
        if (subjectValue == null) {
            sender.sendMessage(Component.text("Invalid ${subjectType.name.lowercase(Locale.ROOT)} value"))
            return true
        }
        val now = System.currentTimeMillis()
        val actor = if (sender is Player) "${sender.name} (${sender.uniqueId})" else sender.name
        val targetUuid = if (subjectType == BanSubjectType.ACCOUNT) UUID.fromString(subjectValue) else null
        val audit = AuditEvent(
            playerUuid = targetUuid,
            usernameSnapshot = null,
            sessionId = null,
            detectionType = "ADMIN_UNBAN",
            action = "UNBAN",
            details = "Administrator removed active ${subjectType.name} ban",
            createdAt = now,
        )
        CompletableFuture.supplyAsync(
            { repository.unban(subjectType, subjectValue, actor, now, audit) },
            databaseExecutor,
        ).whenComplete { count, throwable ->
            if (!plugin.isEnabled) {
                return@whenComplete
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (throwable != null) {
                    plugin.logger.log(Level.SEVERE, "YGuard unban command failed", throwable.cause ?: throwable)
                    sender.sendMessage(Component.text("Database operation failed; check server logs"))
                } else if (count == 0) {
                    sender.sendMessage(Component.text("No active ban matched"))
                } else {
                    sender.sendMessage(Component.text("Removed $count active ban record(s)"))
                }
            })
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (!sender.hasPermission("yguard.admin")) {
            return emptyList()
        }
        val options = when (args.size) {
            1 -> listOf("unban")
            2 -> if (args[0].equals("unban", ignoreCase = true)) listOf("account", "hwid") else emptyList()
            else -> emptyList()
        }
        val current = args.lastOrNull().orEmpty()
        return options.filter { it.startsWith(current, ignoreCase = true) }
    }

    private fun parseCanonicalUuid(value: String): UUID? {
        val uuid = try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return uuid.takeIf { it.toString() == value }
    }
}
