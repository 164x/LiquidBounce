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
package net.ccbluex.liquidbounce.features.module.modules.combat.velocity.mode

import net.ccbluex.liquidbounce.config.types.Choice
import net.ccbluex.liquidbounce.config.types.ChoiceConfigurable
import net.ccbluex.liquidbounce.config.types.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.modules.combat.velocity.ModuleVelocity
import net.ccbluex.liquidbounce.features.module.modules.combat.velocity.ModuleVelocity.modes

/**
 * Jump Reset mode. A technique most players use to minimize the amount of knockback they get.
 */
internal object VelocityJumpReset : VelocityMode("JumpReset") {

    object JumpByReceivedHits : ToggleableConfigurable(ModuleVelocity, "JumpByReceivedHits", false) {
        val hitsUntilJump by int("HitsUntilJump", 2, 0..10)
    }

    object JumpByDelay : ToggleableConfigurable(ModuleVelocity, "JumpByDelay", true) {
        val ticksUntilJump by int("UntilJump", 2, 0..20, "ticks")
    }

    init {
        tree(JumpByReceivedHits)
        tree(JumpByDelay)
    }

    var limitUntilJump = 0

    @Suppress("unused")
    private val tickJumpHandler = handler<MovementInputEvent> {
        // To be able to alter velocity when receiving knockback, player must be sprinting.
        if (player.hurtTime != 9 || !player.isOnGround || !player.isSprinting || !isCooldownOver()) {
            updateLimit()
            return@handler
        }

        it.jump = true
        limitUntilJump = 0
    }

    private fun isCooldownOver(): Boolean {
        return when {
            JumpByReceivedHits.enabled -> limitUntilJump >= JumpByReceivedHits.hitsUntilJump
            JumpByDelay.enabled -> limitUntilJump >= JumpByDelay.ticksUntilJump
            else -> true // If none of the options are enabled, it will go automatic
        }
    }

    private fun updateLimit() {
        if (JumpByReceivedHits.enabled) {
            if (player.hurtTime == 9) {
                limitUntilJump++
            }
            return
        }

        limitUntilJump++
    }

}
