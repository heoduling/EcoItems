package com.willfp.ecoitems.util

import com.willfp.ecoitems.plugin
import org.bukkit.Bukkit
import org.bukkit.Keyed
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Recipe

object DiscoverRecipeListener : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!plugin.configYml.getBool("discover-recipes")) {
            return
        }

        val player = event.player

        runOnEntity(player) {
            mutableListOf<Recipe>()
                .apply { Bukkit.getServer().recipeIterator().forEachRemaining(this::add) }
                .filterIsInstance<Keyed>().map { it.key }
                .filter { it.namespace == plugin.name.lowercase() }
                .filter { !it.key.contains("displayed") }
                .forEach { player.discoverRecipe(it) }
        }
    }

    private fun runOnEntity(player: Player, task: () -> Unit) {
        try {
            // Folia - use entity's region scheduler to ensure thread safety
            // Prevents ConcurrentModificationException in PlayerAdvancements.flushDirty()
            player.scheduler.run(
                plugin,
                { _ -> task() },
                null
            )
        } catch (_: NoSuchMethodError) {
            // Non-Folia fallback - use Bukkit scheduler
            Bukkit.getScheduler().runTask(plugin, Runnable { task() })
        }
    }
}