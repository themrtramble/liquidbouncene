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
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.getActualHealth
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.CrossbowItem
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * BowAimbotWurst module
 *
 * Automatically aims your bow or crossbow at the closest valid entity,
 * including movement prediction and a real ballistic arc calculation.
 * While the bow is charging it keeps tracking the target and shows the
 * charge state, so you know exactly when to release for a full shot.
 *
 * This is a faithful port of the "BowAimbot" hack from the Wurst client
 * (https://github.com/Wurst-Imperium/Wurst7, BowAimbotHack.java),
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors, GPL-3.0.
 *
 *  - Works while drawing a bow (right click held) or holding a loaded
 *    crossbow.
 *  - [Priority] decides which entity is aimed at first. Defaults to
 *    Angle+Dist, a hybrid that is usually the best at figuring out what
 *    you want to aim at.
 *  - [predictMovement] controls the strength of the movement prediction
 *    algorithm. 0% disables prediction, 100% predicts ~1 tick per block
 *    of distance, 200% doubles that.
 *  - The pitch is solved from the real projectile equation with gravity,
 *    so arrows fly in a proper arc instead of a straight line. If the
 *    target is out of the current charge's reach, it flat-aims at the
 *    target until the charge is strong enough to arc.
 *  - A target box (like Wurst's red ESP box) and a "Charging: X%" /
 *    "Target Locked" indicator are drawn on the HUD.
 */
object ModuleBowAimbotWurst : ClientModule(
    "BowAimbotWurst",
    ModuleCategories.COMBAT,
    aliases = listOf("BowAimbot"),
) {

    /**
     * Determines which entity will be aimed at first.
     */
    private val priority by enumChoice("Priority", Priority.ANGLE_DIST)

    /**
     * Controls the strength of the movement prediction algorithm.
     */
    private val predictMovement by float("PredictMovement", 0.2f, 0f..2f, "%")

    init {
        // Target box around the entity being aimed at (Wurst draws a red ESP box)
        tree(TargetRenderer(this) { target })
    }

    private var target: LivingEntity? = null
    private var velocity = 0f

    @Suppress("unused", "LongMethod", "MagicNumber")
    private val tickHandler = handler<RotationUpdateEvent> { _ ->
        // check if item is ranged weapon
        val stack = player.mainHandItem
        val item = stack.item
        if (item !is BowItem && item !is CrossbowItem) {
            target = null
            return@handler
        }

        // check if using bow
        if (item is BowItem && !mc.options.keyUse.isDown && !player.isUsingItem) {
            target = null
            return@handler
        }

        // check if crossbow is loaded
        if (item is CrossbowItem && !CrossbowItem.isCharged(stack)) {
            target = null
            return@handler
        }

        // set target (the current one is kept while it stays valid)
        if (!isValidTarget(target)) {
            target = findTarget()
        }

        val target = this.target ?: return@handler

        // set velocity (bow charge power, 0..1 — crossbows are always fully charged)
        velocity = (72000 - player.useItemRemainingTicks) / 20f
        velocity = (velocity * velocity + velocity * 2) / 3
        if (velocity > 1) {
            velocity = 1f
        }

        // set position to aim at (extrapolated by the predicted movement)
        val eyes = player.eyePosition
        val d = eyes.distanceTo(target.boundingBox.center) * predictMovement
        val posX = target.x + (target.x - target.xOld) * d - eyes.x
        val posY = target.y + (target.y - target.yOld) * d + target.bbHeight * 0.5 - eyes.y
        val posZ = target.z + (target.z - target.zOld) * d - eyes.z

        // set yaw
        val neededYaw = Math.toDegrees(atan2(posZ, posX)).toFloat() - 90f
        player.yRot = limitAngleChange(player.yRot, neededYaw)

        // calculate needed pitch (ballistic arc solve, Wurst's formula and gravity)
        val hDistance = sqrt(posX * posX + posZ * posZ)
        val hDistanceSq = hDistance * hDistance
        val g = 0.006f
        val velocitySq = velocity * velocity
        val velocityPow4 = velocitySq * velocitySq
        val neededPitch = -Math.toDegrees(
            atan(
                (velocitySq - sqrt(velocityPow4 - g * (g * hDistanceSq + 2 * posY * velocitySq))) /
                    (g * hDistance),
            ),
        ).toFloat()

        // set pitch; if the target is out of the current charge's reach,
        // just face the target directly until the charge is strong enough
        if (neededPitch.isNaN()) {
            val facing = Rotation.lookingAt(point = target.boundingBox.center, from = eyes)

            player.yRot = limitAngleChange(player.yRot, facing.yaw)
            player.xRot = facing.pitch
        } else {
            player.xRot = neededPitch.coerceIn(-90f, 90f)
        }
    }

    @Suppress("unused")
    private val overlayRenderHandler = handler<OverlayRenderEvent> { event ->
        val target = this.target ?: return@handler

        val message = if (velocity < 1) {
            "Charging: ${(velocity * 100).toInt()}%"
        } else {
            "Target Locked"
        }

        val font = mc.font
        val msgWidth = font.width(message)

        val msgX1 = mc.window.guiScaledWidth / 2 - msgWidth / 2
        val msgX2 = msgX1 + msgWidth + 3
        val msgY1 = mc.window.guiScaledHeight / 2 + 1
        val msgY2 = msgY1 + 10

        // background
        @Suppress("MagicNumber")
        val backgroundColor = 0x80000000.toInt()

        event.context.fill(msgX1, msgY1, msgX2, msgY2, backgroundColor)

        // text
        event.context.text(font, message, msgX1 + 2, msgY1 + 1, Color4b.WHITE.argb)
    }

    override fun onDisabled() {
        target = null
        velocity = 0f
    }

    /**
     * Wurst's limitAngleChange without a turn-speed limit: removes the
     * unnecessary 360-degree wrap-around so the yaw takes the shortest turn.
     */
    private fun limitAngleChange(current: Float, intended: Float): Float {
        val currentWrapped = Mth.wrapDegrees(current)
        val intendedWrapped = Mth.wrapDegrees(intended)
        val change = Mth.wrapDegrees(intendedWrapped - currentWrapped)

        return current + change
    }

    /**
     * Finds the entity to aim at, picked by the selected priority.
     */
    private fun findTarget(): LivingEntity? = world.entitiesForRendering()
        .asSequence()
        .filterIsInstance<LivingEntity>()
        .filter(::isValidTarget)
        .minByOrNull { priority.score(it) }

    private fun isValidTarget(entity: LivingEntity?): Boolean {
        if (entity == null || entity === player || entity.isRemoved || !entity.isAlive) {
            return false
        }

        return entity.shouldBeAttacked()
    }

    /**
     * Squared distance from the eyes to the closest point on the entity's
     * hitbox (Wurst's distanceToHitboxSq).
     */
    private fun distanceToHitboxSq(entity: LivingEntity): Double {
        val eyes = player.eyePosition
        val box = entity.boundingBox

        val x = Mth.clamp(eyes.x, box.minX, box.maxX)
        val y = Mth.clamp(eyes.y, box.minY, box.maxY)
        val z = Mth.clamp(eyes.z, box.minZ, box.maxZ)

        return eyes.distanceToSqr(x, y, z)
    }

    /**
     * Angle in degrees between the current look direction and the direction
     * to the entity's hitbox center (Wurst's getAngleToLookVec).
     */
    private fun angleToHitboxCenter(entity: LivingEntity): Double {
        val eyes = player.eyePosition
        val facing = Rotation.lookingAt(point = entity.boundingBox.center, from = eyes)

        return player.rotation.directionAngleTo(facing).toDouble()
    }

    /**
     * Wurst's Priority enum.
     */
    private enum class Priority(override val tag: String) : Tagged {
        /**
         * Aims at the closest entity.
         */
        DISTANCE("Distance") {
            override fun score(entity: LivingEntity): Double = distanceToHitboxSq(entity)
        },

        /**
         * Aims at the entity that requires the least head movement.
         */
        ANGLE("Angle") {
            override fun score(entity: LivingEntity): Double = angleToHitboxCenter(entity)
        },

        /**
         * A hybrid of Angle and Distance. This is usually the best at
         * figuring out what you want to aim at.
         */
        ANGLE_DIST("Angle+Dist") {
            override fun score(entity: LivingEntity): Double {
                val angle = angleToHitboxCenter(entity)
                return angle * angle + distanceToHitboxSq(entity)
            }
        },

        /**
         * Aims at the weakest entity.
         */
        HEALTH("Health") {
            override fun score(entity: LivingEntity): Double = entity.getActualHealth().toDouble()
        };

        abstract fun score(entity: LivingEntity): Double
    }

}
