package me.jeyor.yguard.listener

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
                event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    Component.text("This account is banned by YGuard"),
                )
            }
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Unable to check YGuard account ban during login", exception)
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("YGuard verification service is temporarily unavailable"),
            )
        }
    }
}
