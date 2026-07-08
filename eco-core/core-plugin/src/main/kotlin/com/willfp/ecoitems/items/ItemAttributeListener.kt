package com.willfp.ecoitems.items

import com.willfp.ecoitems.plugin
import com.willfp.libreforge.forceRefreshHolders
import com.willfp.libreforge.toDispatcher
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlotGroup
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

// https://minecraft.wiki/w/Attribute#Attack_Damage
private const val PLAYER_BASE_ATTACK_DAMAGE = 1.0

// https://minecraft.wiki/w/Attribute#Attack_Speed
private const val PLAYER_BASE_ATTACK_SPEED = 4.0

// https://minecraft.wiki/w/Attribute#Entity_Interaction_Range
private const val PLAYER_BASE_ENTITY_INTERACTION_RANGE = 3.0

object ItemAttributeListener : Listener {
    private val applyingPlayers = ConcurrentHashMap<UUID, Boolean>()
    @EventHandler
    fun handle(event: PlayerItemHeldEvent) {
        if (event.isCancelled) {
            return
        }

        apply(event.player)
    }

    @EventHandler
    fun handleDrop(event: PlayerDropItemEvent) {
        if (event.isCancelled) {
            return
        }

        event.itemDrop.itemStack.ecoItem ?: return

        runOnEntityLater(event.player, 1) {
            event.player.toDispatcher().forceRefreshHolders()
            apply(event.player)
        }
    }


    @EventHandler
    fun handlePickup(event: EntityPickupItemEvent) {
        if (event.isCancelled) {
            return
        }

        val player = event.entity as? Player ?: return

        event.item.itemStack.ecoItem ?: return

        apply(player)
    }

    @EventHandler
    fun handleInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return

        apply(player)
    }

    @EventHandler
    fun handleSwapHandItems(event: PlayerSwapHandItemsEvent) {
        if (event.isCancelled) {
            return
        }

        runOnEntityLater(event.player, 1) {
            event.player.toDispatcher().forceRefreshHolders()
            apply(event.player)
        }
    }

    @EventHandler
    fun handleInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        runOnEntityLater(player, 1) {
            player.toDispatcher().forceRefreshHolders()
            apply(player)
        }
    }

    @EventHandler
    fun handleInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return

        runOnEntityLater(player, 1) {
            player.toDispatcher().forceRefreshHolders()
            apply(player)
        }
    }

    private fun runOnEntityLater(player: Player, ticksLater: Long, task: () -> Unit) {
        val delay = max(1L, ticksLater)
        try {
            // Folia - use entity's region scheduler to ensure thread safety
            player.scheduler.runDelayed(
                plugin,
                { _ -> task() },
                null,
                delay
            )
        } catch (_: NoSuchMethodError) {
            // Non-Folia fallback - use Bukkit scheduler
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { task() }, delay)
        }
    }

    private fun apply(player: Player) {
        val uuid = player.uniqueId

        if (applyingPlayers.putIfAbsent(uuid, true) != null) {
            return
        }

        try {
            val items = EcoItemFinder.toHolderProvider().provide(player.toDispatcher()).map { it.holder }

            val damageInst = player.getAttribute(Attribute.ATTACK_DAMAGE) ?: return
            val speedInst = player.getAttribute(Attribute.ATTACK_SPEED) ?: return
            val rangeInst = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE) ?: return

            damageInst.modifiers.filter { it.isFromEcoItems }.forEach {
                damageInst.removeModifier(it)
            }

            speedInst.modifiers.filter { it.isFromEcoItems }.forEach {
                speedInst.removeModifier(it)
            }

            rangeInst.modifiers.filter { it.isFromEcoItems }.forEach {
                rangeInst.removeModifier(it)
            }

            for ((offset, item) in items.withIndex()) {
                val baselineMaterial = if (player.inventory.itemInMainHand.type == item.itemStack.type) {
                    player.inventory.itemInMainHand.type
                } else {
                    player.inventory.itemInOffHand.type
                }

                if (item.baseDamage != null) {
                    damageInst.addCompatibleModifier(
                        "Damage",
                        item.baseDamage - PLAYER_BASE_ATTACK_DAMAGE - baselineMaterial.attackDamageModifier,
                        offset
                    )
                }

                if (item.baseAttackSpeed != null) {
                    speedInst.addCompatibleModifier(
                        "Speed",
                        item.baseAttackSpeed - PLAYER_BASE_ATTACK_SPEED - baselineMaterial.attackSpeedModifier,
                        offset
                    )
                }

                if (item.baseAttackRange != null) {
                    rangeInst.addCompatibleModifier(
                        "Range",
                        item.baseAttackRange - PLAYER_BASE_ENTITY_INTERACTION_RANGE - baselineMaterial.entityInteractionRangeModifier,
                        offset
                    )
                }
            }
        } finally {
            applyingPlayers.remove(uuid)
        }
    }

    private val AttributeModifier.isFromEcoItems: Boolean
        get() {
            if (this.name.startsWith("EcoItems Damage", true)
                || this.name.startsWith("EcoItems Speed", true)
                || this.name.startsWith("EcoItems Range", true)
            ) {
                return true
            }

            return this.key.namespace == "ecoitems" && (this.key.key.startsWith("damage")
                    || this.key.key.startsWith("speed")
                    || this.key.key.startsWith("range"))
        }

    private fun AttributeInstance.addCompatibleModifier(
        attributeType: String,
        amount: Double,
        offset: Int
    ) {
        addModifier(
            AttributeModifier(
                plugin.createNamespacedKey("${attributeType.lowercase()}_$offset"),
                amount,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.ANY
            )
        )
    }
}
