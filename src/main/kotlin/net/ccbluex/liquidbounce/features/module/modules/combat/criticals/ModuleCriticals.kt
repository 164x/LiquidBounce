/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2024 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.criticals

import net.ccbluex.liquidbounce.config.types.NamedChoice
import net.ccbluex.liquidbounce.config.types.NoneChoice
import net.ccbluex.liquidbounce.config.types.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.modes.CriticalsBlink
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.modes.CriticalsJump
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.modes.CriticalsNoGround
import net.ccbluex.liquidbounce.features.module.modules.combat.criticals.modes.CriticalsPacket
import net.ccbluex.liquidbounce.features.module.modules.movement.fly.ModuleFly
import net.ccbluex.liquidbounce.features.module.modules.movement.liquidwalk.ModuleLiquidWalk
import net.ccbluex.liquidbounce.utils.block.collideBlockIntersects
import net.ccbluex.liquidbounce.utils.combat.findEnemy
import net.ccbluex.liquidbounce.utils.entity.box
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention
import net.minecraft.block.CobwebBlock
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffects.*
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket

/**
 * Criticals module
 *
 * Automatically crits every time you attack someone.
 */
object ModuleCriticals : ClientModule("Criticals", Category.COMBAT) {

    init {
        enableLock()
    }

    val modes = choices("Mode", 1) {
        arrayOf(
            NoneChoice(it),
            CriticalsPacket,
            CriticalsNoGround,
            CriticalsJump,
            CriticalsBlink
        )
    }.apply(::tagBy)

    object WhenSprinting : ToggleableConfigurable(ModuleCriticals, "WhenSprinting", false) {

        enum class StopSprintingMode(override val choiceName: String) : NamedChoice {
            NONE("None"),
            LEGIT("Legit"),
            ON_NETWORK("OnNetwork"),
            ON_ATTACK("OnAttack")
        }

        override val running: Boolean
            get() = super.running && wouldDoCriticalHit(true)
                && world.findEnemy(0.0f..enemyInRange) != null

        val stopSprinting by enumChoice("StopSprinting", StopSprintingMode.LEGIT)
        private val enemyInRange by float("Range", 4.0f, 0.0f..10.0f)

        @Suppress("unused")
        private val attackHandler = handler<AttackEntityEvent>(
            priority = EventPriorityConvention.FIRST_PRIORITY
        ) {
            if (stopSprinting == StopSprintingMode.ON_ATTACK && player.lastSprinting) {
                network.sendPacket(ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.STOP_SPRINTING))
                player.lastSprinting = false
            }
        }

    }

    /**
     * Just some visuals.
     */
    object VisualsConfigurable : ToggleableConfigurable(this, "Visuals", false) {

        val fake by boolean("Fake", false)

        private val critical by int("Critical", 1, 0..20)
        private val magic by int("Magic", 0, 0..20)

        @Suppress("unused")
        private val attackHandler = handler<AttackEntityEvent> { event ->
            if (event.entity !is LivingEntity) {
                return@handler
            }

            if (!fake && !wouldDoCriticalHit()) {
                return@handler
            }

            showCriticals(event.entity)
        }

        fun showCriticals(entity: Entity) {
            if (!enabled) {
                return
            }

            repeat(critical) {
                player.addCritParticles(entity)
            }

            repeat(magic) {
                player.addEnchantedHitParticles(entity)
            }
        }

    }

    init {
        tree(WhenSprinting)
        tree(VisualsConfigurable)
    }

    fun shouldWaitForCrit(target: Entity, ignoreState: Boolean = false) = when {
        CriticalsBlink.running && CriticalsBlink.isInState -> false
        else -> CriticalsJump.shouldWaitForCrit(target, ignoreState)
    }

    fun allowsCriticalHit(ignoreOnGround: Boolean = false): Boolean {
        val blockingEffects = arrayOf(LEVITATION, BLINDNESS, SLOW_FALLING)

        val blockingConditions = booleanArrayOf(
            // Modules
            ModuleFly.running,
            ModuleLiquidWalk.running && ModuleLiquidWalk.standingOnWater(),
            player.isInLava, player.isTouchingWater, player.hasVehicle(),
            // Cobwebs
            player.box.collideBlockIntersects(checkCollisionShape = false) { it is CobwebBlock },
            // Effects
            blockingEffects.any(player::hasStatusEffect),
            // Disabling conditions
            player.isClimbing, player.hasNoGravity(), player.isRiding,
            player.abilities.flying,
            // On Ground
            player.isOnGround && !ignoreOnGround
        )

        // Do not replace this with .none() since it is equivalent to .isEmpty()
        return blockingConditions.none { it }
    }

    fun canDoCriticalHit(ignoreOnGround: Boolean = false, ignoreSprint: Boolean = false) =
        allowsCriticalHit(ignoreOnGround) && player.getAttackCooldownProgress(0.5f) > 0.9f &&
            (!player.isSprinting || ignoreSprint)

    fun wouldDoCriticalHit(ignoreSprint: Boolean = false) =
        canDoCriticalHit(false, ignoreSprint) && player.fallDistance > 0.0

}