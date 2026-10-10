/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.KillAuraRequirements
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugGeometry
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationWithVector
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.InterpolationAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.LinearAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.SigmoidAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.preference.LeastDifferencePreference
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.aiming.utils.setRotation
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity

/**
 * Aimbot module
 *
 * Automatically faces selected entities around you.
 */
object ModuleAimbot : ClientModule("Aimbot", ModuleCategories.COMBAT, aliases = listOf("AutoAim")) {

    private val range = float("Range", 4.2f, 1f..8f)

    val targetTracker = tree(TargetTracker(TargetPriority.DIRECTION, range = range))

    init {
        tree(TargetRenderer(this, targetTracker))
    }
    private val pointTracker = tree(PointTracker(this))
    private val lazyRotation by boolean("LazyRotation", false)

    /**
     * Nearest Enemy Auto Aim
     *
     * When multiple enemies are around, always locks the aim onto the nearest
     * valid one. The moment the current target is eliminated or becomes
     * invalid (dead, gone or out of range), the aim automatically moves over
     * to the next nearest enemy — no manual target switching needed.
     *
     * Example: one enemy 5 blocks away and another 9 blocks away — the aim
     * locks onto the 5-block enemy first; once that enemy is gone, it moves to
     * the 9-block enemy. Distances are recalculated every tick, so whichever
     * enemy is closest at the moment is the one being tracked.
     *
     * OFF by default — the regular crosshair-direction targeting stays active
     * until this is enabled manually.
     */
    private val nearestEnemy by boolean("NearestEnemy", false)

    private val requires by multiEnumChoice<KillAuraRequirements>("Requires")

    private val requirementsMet
        get() = mc.gui.screen() == null && requires.all { it.asBoolean }

    private var angleSmooth = modes(this, "AngleSmooth") {
        arrayOf(
            InterpolationAngleSmooth(it),
            SigmoidAngleSmooth(it),
            LinearAngleSmooth(it)
        )
    }

    private val axis by multiEnumChoice<Axis>("Axis", Axis.HORIZONTAL, Axis.VERTICAL)

    private val ignores by multiEnumChoice<IgnoreOpened>("Ignore")

    private var targetRotation: Rotation? = null
    private var playerRotation: Rotation? = null

    @Suppress("unused", "ComplexCondition")
    private val tickHandler = handler<RotationUpdateEvent> { _ ->
        playerRotation = player.rotation

        if (!requirementsMet) {
            targetTracker.reset()
            targetRotation = null
            return@handler
        }

        targetRotation = findNextTargetRotation()?.let { (target, rotation) ->
            angleSmooth.activeMode.process(
                RotationTarget(
                    rotation = rotation.rotation,
                    entity = target,
                    processors = listOf(angleSmooth.activeMode),
                    ticksUntilReset = 1,
                    resetThreshold = 1f,
                    considerInventory = true,
                    movementCorrection = MovementCorrection.CHANGE_LOOK
                ),
                player.rotation,
                rotation.rotation
            )
        }

        // Update Auto Weapon
        ModuleAutoWeapon.onTarget(targetTracker.target)
    }

    override fun onDisabled() {
        targetTracker.reset()
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val partialTicks = event.partialTicks
        val target = targetTracker.target ?: return@handler

        if (IgnoreOpened.SCREEN !in ignores && mc.gui.screen() != null) {
            return@handler
        }

        if (IgnoreOpened.CONTAINER !in ignores && (InventoryManager.isInventoryOpen ||
                mc.gui.screen() is AbstractContainerScreen<*>)) {
            return@handler
        }

        lookAt(partialTicks)
    }

    @Suppress("unused")
    private val mouseMovement = handler<MouseRotationEvent> { event ->
        fun updateRotation(rotation: Rotation): Rotation =
            RotationUtil.applyMouseTurnDelta(rotation, event.cursorDeltaX, event.cursorDeltaY)

        playerRotation?.let { rotation ->
            playerRotation = updateRotation(rotation)
        }

        targetRotation?.let { rotation ->
            targetRotation = updateRotation(rotation)
        }
    }

    /**
     * Looks at the target rotation, with interpolation based on the timer speed and partial ticks to make it smooth.
     */
    private fun lookAt(partialTicks: Float) {
        val playerRotation = playerRotation ?: return
        val targetRotation = targetRotation ?: return
        val timerSpeed = Timer.timerSpeed
        val interpolatedRotation = playerRotation.interpolateTo(targetRotation, timerSpeed * partialTicks)

        player.setRotation(
            Rotation(
                yaw = if (Axis.HORIZONTAL in axis) interpolatedRotation.yaw else playerRotation.yaw,
                pitch = if (Axis.VERTICAL in axis) interpolatedRotation.pitch else playerRotation.pitch,
            )
        )
    }

    private fun findNextTargetRotation(): Pair<Entity, RotationWithVector>? {
        for (entity in candidateEntities()) {
            if (lazyRotation) {
                val currentRotation = player.rotation
                val currentHit = isLookingAtEntity(
                    fromEntity = player,
                    toEntity = entity,
                    rotation = currentRotation,
                    range = targetTracker.maxRange.toDouble(),
                    throughWallsRange = 0.0,
                )

                if (currentHit != null) {
                    targetTracker.target = entity
                    return entity to RotationWithVector(currentRotation, currentHit.location)
                }
            }

            val eyes = player.eyePosition
            val point = pointTracker.findPoint(eyes, entity)

            debugGeometry("Box") { ModuleDebug.DebuggedBox(point.box, Color4b.ORANGE.with(a = 90)) }
            debugGeometry("Point") { ModuleDebug.DebuggedPoint(point.pos, Color4b.WHITE, size = 0.1) }

            val rotationPreference = LeastDifferencePreference.leastDifferenceToLastPoint(eyes, point.pos)
            val rotation = raytraceBox(
                eyes = eyes,
                box = point.box,
                range = targetTracker.maxRange.toDouble(),
                wallsRange = 0.0,
                rotationPreference = rotationPreference
            ) ?: continue

            targetTracker.target = entity
            return entity to rotation
        }

        targetTracker.reset()
        return null
    }

    /**
     * Entities to aim at, in selection order.
     *
     * With [nearestEnemy] enabled, the entities are re-sorted by distance on
     * every tick, so the closest valid enemy always gets the aim — and once it
     * is eliminated or leaves the range, the next nearest one takes over
     * automatically. Otherwise the tracker's own priority order is used,
     * completely unchanged.
     */
    private fun candidateEntities(): List<LivingEntity> {
        val targets = targetTracker.targets()

        return if (nearestEnemy) {
            targets.sortedBy { it.squaredBoxedDistanceTo(player) }
        } else {
            targets
        }
    }

    private enum class IgnoreOpened(
        override val tag: String
    ) : Tagged {
        SCREEN("Screen"),
        CONTAINER("Container")
    }

    private enum class Axis(override val tag: String) : Tagged {
        HORIZONTAL("Horizontal"),
        VERTICAL("Vertical")
    }
}
