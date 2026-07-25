package me.jeyor.yguard.listener

import me.jeyor.yguard.config.MessageTemplates
import me.jeyor.yguard.storage.BanSubjectType
import me.jeyor.yguard.storage.YGuardRepository
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class AccountBanListener(
    private val plugin: Plugin,
    private val repository: YGuardRepository,
    private val databaseExecutor: Executor,
    private val messages: MessageTemplates,
) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return
        }
        try {
            val banned = CompletableFuture.supplyAsync(
                { repository.isBanned(BanSubjectType.ACCOUNT, event.uniqueId.toString()) },
                databaseExecutor,
            ).get(10, TimeUnit.SECONDS)
            if (banned) {
                plugin.logger.info("Denied login for ${event.name} (${event.uniqueId}); active YGuard account ban")
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    Component.text(
                        messages.renderBan(
                            player = event.name,
                            uuid = event.uniqueId,
                            detections = "ACCOUNT_BANNED",
                            action = "BAN_ACCOUNT",
                        ),
                    ),
                )
            }
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Unable to check YGuard account ban during login", exception)
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text(messages.renderServiceUnavailable(event.name, event.uniqueId)),
            )
        }
    }
}
