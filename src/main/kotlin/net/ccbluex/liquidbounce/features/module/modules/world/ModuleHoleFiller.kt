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
package net.ccbluex.liquidbounce.features.module.modules.world

import it.unimi.dsi.fastutil.booleans.BooleanDoubleImmutablePair
import it.unimi.dsi.fastutil.objects.ObjectDoubleImmutablePair
import net.ccbluex.liquidbounce.event.events.SimulatedTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.block.Region
import net.ccbluex.liquidbounce.utils.block.hole.Hole
import net.ccbluex.liquidbounce.utils.block.hole.HoleManager
import net.ccbluex.liquidbounce.utils.block.hole.HoleManagerSubscriber
import net.ccbluex.liquidbounce.utils.block.hole.HoleTracker
import net.ccbluex.liquidbounce.utils.block.placer.BlockPlacer
import net.ccbluex.liquidbounce.utils.collection.Filter
import net.ccbluex.liquidbounce.utils.collection.getSlot
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.inventory.HOTBAR_SLOTS
import net.ccbluex.liquidbounce.utils.item.getBlock
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.sq
import net.minecraft.block.Blocks
import net.minecraft.entity.Entity
import net.minecraft.util.math.BlockPos
import org.joml.Vector2d
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.max

/**
 * Module HoleFiller
 *
 * Automatically fills holes.
 *
 * @author ccetl
 */
object ModuleHoleFiller : ClientModule("HoleFiller", Category.WORLD) {

    /**
     * When enabled, only places when entities are about to enter a hole, otherwise fills all holes.
     */
    private val smart by boolean("Smart", true)

    /**
     * Prevents the module from filling the hole you want to enter.
     * The criteria to allow filling are:
     * The hole is higher than you, the hole doesn't intersect your own fill area, or you are already in a hole.
     */
    private val preventSelfFill by boolean("PreventSelfFill", true)

    /**
     * Only operate when you're in a hole yourself.
     */
    private val onlyWhenSelfInHole by boolean("OnlyWhenSelfInHole", false)

    /**
     * The area around entities' feet that will be checked for holes.
     */
    private val fillArea by int("Area", 2, 1..5)

    /**
     * Checks the movement angle. Won't fill holes that lie further away than 30° from the entities' velocity direction.
     * Only applies when smart is enabled.
     */
    private val checkMovement by boolean("CheckMovement", true)

    /**
     * Only fills 1x1 holes - ignores 2x2 and 2x1 holes.
     */
    private val only1by1 by boolean("Only1x1", false)

    /**
     * How the blocklist is used.
     */
    private val filter by enumChoice("Filter", Filter.WHITELIST)

    /**
     * Blocks that are used to fill holes, by default just obsidian.
     */
    private val blocks by blocks("Blocks", hashSetOf(Blocks.OBSIDIAN))

    /**
     * The core of the module, the placer.
     */
    private val placer = tree(BlockPlacer(
        "Placing",
        this,
        Priority.NORMAL,
        { filter.getSlot(blocks) },
        allowSupportPlacements = false
    ))

    override fun enable() {
        val range = ceil(max(placer.range, placer.wallRange)).toInt()
        HoleManager.subscribe(this, HoleManagerSubscriber({ range }, { range }))
    }

    override fun disable() {
        HoleManager.unsubscribe(this)
        placer.disable()
    }

    @Suppress("unused")
    private val targetUpdater = handler<SimulatedTickEvent> {
        // all holes, if required 1x1 holes filtered out
        val holes = HoleTracker.holes.filter { !only1by1 || it.type == Hole.Type.ONE_ONE }

        val blockPos = player.blockPos
        val selfInHole = holes.any { it.contains(blockPos) }
        if (onlyWhenSelfInHole && !selfInHole) {
            return@handler
        }

        val selfRegion = Region.quadAround(blockPos, fillArea, fillArea)

        val blocks = linkedSetOf<BlockPos>()
        val holeContext = HoleContext(holes, selfInHole, selfRegion, blocks)

        if (!smart) {
            collectHolesSimple(holeContext)
        } else {
            val availableItems = getAvailableItemsCount()
            if (availableItems == 0) {
                return@handler
            }

            // the range in which entities are considered as a target
            val range = ceil(max(placer.range, placer.wallRange)).toInt().sq() + 10.0
            collectHolesSmart(range, holeContext, availableItems)
        }

        placer.update(blocks)
    }

    private fun getAvailableItemsCount(): Int {
        var itemCount = 0
        HOTBAR_SLOTS.forEach { slot ->
            val block = slot.itemStack.getBlock() ?: return@forEach
            if (filter(block, blocks)) {
                itemCount += slot.itemStack.count
            }
        }

        return itemCount
    }

    private fun collectHolesSimple(holeContext: HoleContext) {
        holeContext.holes.forEach { hole ->
            val y = hole.positions.from.y + 1.0
            if (!preventSelfFill ||
                y > player.y ||
                holeContext.selfInHole ||
                !hole.positions.intersects(holeContext.selfRegion)
                ) {
                BlockPos.iterate(hole.positions.from, hole.positions.to).forEach {
                    holeContext.blocks += it.toImmutable()
                }
            }
        }
    }

    private fun collectHolesSmart(range: Double, holeContext: HoleContext, availableItems: Int) {
        val checkedHoles = hashSetOf<Hole>()
        var remainingItems = availableItems

        world.entities.forEach { entity ->
            if (entity.squaredDistanceTo(player) > range || entity == player || !entity.shouldBeAttacked()) {
                return@forEach
            }

            val found = hashSetOf<ObjectDoubleImmutablePair<BlockPos>>()
            remainingItems = iterateHoles(
                holeContext,
                checkedHoles,
                entity,
                remainingItems,
                found
            )

            holeContext.blocks += found.sortedByDescending { it.rightDouble() }.map { it.left() }
            if (remainingItems <= 0) {
                return
            }
        }
    }

    private fun iterateHoles(
        holeContext: HoleContext,
        checkedHoles: HashSet<Hole>,
        entity: Entity,
        remainingItems: Int,
        found: HashSet<ObjectDoubleImmutablePair<BlockPos>>
    ): Int {
        var remainingItems1 = remainingItems
        val region = Region.quadAround(entity.blockPos, fillArea, fillArea)

        holeContext.holes.forEach { hole ->
            if (hole in checkedHoles) {
               return@forEach
            }

            val valid = isValidHole(hole, entity, region, holeContext.selfInHole, holeContext.selfRegion)
            if (!valid.firstBoolean()) {
                return@forEach
            }

            val holeSize = hole.type.size
            remainingItems1 -= holeSize
            if (remainingItems1 < 0 && !player.abilities.creativeMode) {
                remainingItems1 += holeSize
                return@forEach
            }

            checkedHoles += hole
            BlockPos.iterate(hole.positions.from, hole.positions.to).forEach {
                found += ObjectDoubleImmutablePair(it.toImmutable(), valid.rightDouble())
            }

            if (remainingItems1 == 0 && !player.abilities.creativeMode) {
                return 0
            }
        }

        return remainingItems
    }

    private fun isValidHole(
        hole: Hole,
        entity: Entity,
        region: Region,
        selfInHole: Boolean,
        selfRegion: Region
    ) : BooleanDoubleImmutablePair {
        val y = hole.positions.from.y + 1.0
        val movingTowardsHole = isMovingTowardsHole(hole, entity)
        val requirementsMet = movingTowardsHole.firstBoolean() && hole.positions.intersects(region) && y <= entity.y
        val noSelfFillViolation = !preventSelfFill ||
            y > player.y ||
            selfInHole ||
            !hole.positions.intersects(selfRegion)

        return BooleanDoubleImmutablePair(requirementsMet && noSelfFillViolation, movingTowardsHole.rightDouble())
    }

    private fun isMovingTowardsHole(hole: Hole, entity: Entity): BooleanDoubleImmutablePair {
        val holePos = hole.positions.from.toCenterPos()
        val velocity = entity.pos.subtract(entity.prevX, entity.prevY, entity.prevZ)
        val playerPos = entity.pos

        val normalizedVelocity = Vector2d(velocity.x, velocity.z).normalize()
        val normalizedDelta = Vector2d(holePos.x - playerPos.x, holePos.z - playerPos.z).normalize()
        val angle = acos(normalizedDelta.dot(normalizedVelocity))

        if (!checkMovement) {
            return BooleanDoubleImmutablePair(true, angle)
        }

        // cos(30°) = 0.866
        return BooleanDoubleImmutablePair(angle >= 0.866, angle)
    }

    data class HoleContext(
        val holes: List<Hole>,
        val selfInHole: Boolean,
        val selfRegion: Region,
        val blocks: MutableSet<BlockPos>
    )

}