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
package net.ccbluex.liquidbounce.utils.aiming.anglesmooth

import net.ccbluex.liquidbounce.config.types.ChoiceConfigurable
import net.ccbluex.liquidbounce.utils.aiming.Rotation
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class EaseSmoothMode(override val parent: ChoiceConfigurable<*>) : AngleSmoothMode("Ease") {
    private val horizontalDiffLimit by floatRange("HorizontalDiffLimit", 180f..180f,
        0.0f..180f)
    private val horizontalFactor by floatRange("HorizontalFactor", 0.1f..1.0f, 0.0f..1.0f)
    private val verticalDiffLimit by floatRange("VerticalDiffLimit", 180f..180f,
        0.0f..180f)
    private val verticalFactor by floatRange("VerticalFactor", 0.1f..1.0f, 0.0f..1.0f)

    override fun limitAngleChange(
        factorModifier: Float,
        currentRotation: Rotation,
        targetRotation: Rotation,
        vec3d: Vec3d?,
        entity: Entity?
    ): Rotation {
        val yawDifference = RotationManager.angleDifference(targetRotation.yaw, currentRotation.yaw)
        val pitchDifference = RotationManager.angleDifference(targetRotation.pitch, currentRotation.pitch)

        val rotationDifference = hypot(abs(yawDifference), abs(pitchDifference))
        val (factorH, factorV) = horizontalDiffLimit.random().toFloat() to
            verticalDiffLimit.random().toFloat()

        val straightLineYaw = abs(yawDifference / rotationDifference) * (factorH * factorModifier)
        val straightLinePitch = abs(pitchDifference / rotationDifference) * (factorV * factorModifier)

        val interpolatedYaw = interpolate(
            currentRotation.yaw,
            currentRotation.yaw + yawDifference.coerceIn(-straightLineYaw, straightLineYaw),
            horizontalFactor.random().toFloat()
        )
        val interpolatedPitch = interpolate(
            currentRotation.pitch,
            currentRotation.pitch + pitchDifference.coerceIn(-straightLinePitch, straightLinePitch),
            verticalFactor.random().toFloat()
        )

        return Rotation(interpolatedYaw, interpolatedPitch)
    }

    override fun howLongToReach(currentRotation: Rotation, targetRotation: Rotation): Int {
        val difference = RotationManager.rotationDifference(targetRotation, currentRotation)
        val turnSpeed = min(horizontalDiffLimit.start, verticalDiffLimit.start)

        if (difference <= 0.0 || turnSpeed <= 0.0) {
            return 0
        }

        return (difference / turnSpeed).roundToInt()
    }

    private fun interpolate(start: Float, end: Float, speed: Float): Float {
        return start + (end - start) * speed
    }
}
