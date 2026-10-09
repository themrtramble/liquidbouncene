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

import net.ccbluex.liquidbounce.event.events.MouseRotationEvent
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationWithVector
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.preference.LeastDifferencePreference
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.aiming.utils.setRotation
import net.ccbluex.liquidbounce.utils.client.Timer
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * AimAssist module
 *
 * Gently pulls your crosshair onto the closest player within range and keeps
 * it stuck on them while they move. It never attacks by itself — you land the
 * hits with your own left clicks, exactly like a legit player.
 *
 * - Picks the nearest player within [range] (closest one wins, e.g. when two
 *   players rush you the one that is closer gets the lock). Once locked, the
 *   target is only swapped when another player gets clearly closer, so the
 *   crosshair does not flicker between two enemies.
 * - Your own mouse input is never blocked: the assist shifts the destination
 *   along with your mouse movement, so it feels like the crosshair is being
 *   pulled onto the enemy instead of fighting your hand.
 * - The pull is speed-capped and proportional, which makes it look like a
 *   fast human flick instead of a machine snap. Once the crosshair is on the
 *   target, tracking becomes tight so strafing enemies stay under the crosshair.
 * - A subtle mean-reverting aim wobble makes the movement organic, so the
 *   rotation never locks onto the hitbox with surgical precision.
 */
object ModuleAimAssist : ClientModule("AimAssist", ModuleCategories.COMBAT) {

    /**
     * Maximum distance to a player for the assist to lock onto them.
     */
    private val range = float("Range", 6f, 1f..10f, "blocks")

    val targetTracker = tree(TargetTracker(TargetPriority.DISTANCE, range = range))

    private val pointTracker = tree(PointTracker(this))

    init {
        tree(TargetRenderer(this, targetTracker))
    }

    /**
     * Only lock onto players. Turn off to also assist onto mobs.
     */
    private val playersOnly by boolean("PlayersOnly", true)

    /**
     * Maximum degrees the crosshair travels per game tick. Keeps big turns
     * looking like fast human flicks instead of instant snaps.
     */
    private val turnSpeed by float("TurnSpeed", 30f, 5f..90f, "°/tick")

    /**
     * How hard the crosshair is pulled towards the target. Lower values drift
     * onto the target more gently, higher values chase it more aggressively.
     */
    private val stickiness by float("Stickiness", 0.35f, 0.05f..1f)

    /**
     * Only pull the crosshair while you are holding the attack button.
     */
    private val onlyWhileClicking by boolean("OnlyWhileClicking", false)

    /**
     * Let the crosshair wander organically around the target instead of
     * locking onto it with surgical precision.
     */
    private val aimWobble by boolean("AimWobble", true)

    /**
     * Rotation the crosshair is being pulled towards during the current tick.
     */
    private var steerRotation: Rotation? = null

    /**
     * Tracked current view rotation, kept in sync with the mouse between ticks.
     */
    private var playerRotation: Rotation? = null

    private var wobbleYaw = 0f
    private var wobblePitch = 0f

    @Suppress("unused", "ComplexCondition")
    private val tickHandler = handler<RotationUpdateEvent> { _ ->
        playerRotation = player.rotation

        if (mc.gui.screen() != null || ModuleKillAura.enabled ||
            (onlyWhileClicking && !mc.options.keyAttack.isDown)) {
            steerRotation = null
            targetTracker.reset()
            resetWobble()
            return@handler
        }

        val aim = findAim() ?: run {
            steerRotation = null
            resetWobble()
            return@handler
        }

        val destination = if (aimWobble) wobble(aim.second.rotation) else aim.second.rotation

        val current = player.rotation
        val diff = current.rotationDeltaLengthTo(destination)

        // Tracking is tight when the crosshair is already on the target and
        // relaxes to the configured stickiness for larger corrections.
        val nearFactor = (stickiness * NEAR_FACTOR_SCALE).coerceIn(0.75f, 0.95f)
        val blend = (diff / RAMP_DEGREES).coerceIn(0f, 1f)
        val factor = nearFactor + (stickiness - nearFactor) * blend
        val step = (diff * factor).coerceAtMost(turnSpeed)

        steerRotation = current.towardsLinear(destination, step, step)
    }

    @Suppress("unused")
    private val mouseMovement = handler<MouseRotationEvent> { event ->
        fun updateRotation(rotation: Rotation): Rotation =
            RotationUtil.applyMouseTurnDelta(rotation, event.cursorDeltaX, event.cursorDeltaY)

        playerRotation = playerRotation?.let(::updateRotation)
        steerRotation = steerRotation?.let(::updateRotation)
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        if (mc.gui.screen() != null) {
            return@handler
        }

        val from = playerRotation ?: return@handler
        val to = steerRotation ?: return@handler

        val interpolated = from.interpolateTo(to, Timer.timerSpeed * event.partialTicks)

        player.setRotation(interpolated)
    }

    override fun onDisabled() {
        targetTracker.reset()
        steerRotation = null
        resetWobble()
    }

    /**
     * Finds the player to stick to and the rotation to look at them.
     *
     * Candidates are already sorted by distance (closest first). The current
     * lock is kept unless another player gets clearly closer, so the crosshair
     * does not flicker between two enemies at nearly the same distance. When
     * the locked player is behind a wall, the next visible one takes over.
     */
    private fun findAim(): Pair<LivingEntity, RotationWithVector>? {
        val candidates = targetTracker.targets()
            .let { entities -> if (playersOnly) entities.filter { it is Player } else entities }

        if (candidates.isEmpty()) {
            targetTracker.reset()
            return null
        }

        val ordered = orderedCandidates(candidates)
        val eyes = player.eyePosition

        for (entity in ordered) {
            val point = pointTracker.findPoint(eyes, entity)
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

        return null
    }

    /**
     * Reorders candidates so the current lock keeps priority unless the best
     * candidate is clearly closer than it.
     */
    private fun orderedCandidates(candidates: List<LivingEntity>): List<LivingEntity> {
        val current = targetTracker.target ?: return candidates
        if (current !in candidates || candidates.first() === current) {
            return candidates
        }

        val best = candidates.first()
        val bestSquared = best.squaredBoxedDistanceTo(player)
        val currentSquared = current.squaredBoxedDistanceTo(player)

        return if (bestSquared > currentSquared * HYSTERESIS_FACTOR) {
            listOf(current) + candidates.filter { it !== current }
        } else {
            candidates
        }
    }

    /**
     * Smooth mean-reverting random walk (Ornstein-Uhlenbeck style) around the
     * aim point. The magnitudes are small enough that the crosshair stays on
     * the hitbox of a player at combat distance while the movement keeps
     * looking organic.
     */
    private fun wobble(rotation: Rotation): Rotation {
        wobbleYaw += WOBBLE_YAW_SCALE * gaussian() - wobbleYaw * WOBBLE_REVERSION
        wobblePitch += WOBBLE_PITCH_SCALE * gaussian() - wobblePitch * WOBBLE_REVERSION

        wobbleYaw = wobbleYaw.coerceIn(-WOBBLE_YAW_LIMIT, WOBBLE_YAW_LIMIT)
        wobblePitch = wobblePitch.coerceIn(-WOBBLE_PITCH_LIMIT, WOBBLE_PITCH_LIMIT)

        return Rotation(rotation.yaw + wobbleYaw, rotation.pitch + wobblePitch)
    }

    private fun gaussian(): Float {
        // Box-Muller transform, clamped to avoid extreme outliers
        val u1 = (0.001f..0.999f).random()
        val u2 = (0.001f..0.999f).random()

        return (sqrt(-2f * ln(u1)) * cos(2f * kotlin.math.PI.toFloat() * u2)).coerceIn(-3f, 3f)
    }

    private fun resetWobble() {
        wobbleYaw = 0f
        wobblePitch = 0f
    }

    private companion object {
        /** Angle below which tracking switches from gentle pull to tight stick. */
        const val RAMP_DEGREES = 12f

        /** Scales [stickiness] into the on-target tracking factor. */
        const val NEAR_FACTOR_SCALE = 2.3f

        /** New target must be this much closer (squared) to steal the lock. */
        const val HYSTERESIS_FACTOR = 0.75f

        const val WOBBLE_YAW_SCALE = 0.35f
        const val WOBBLE_PITCH_SCALE = 0.22f
        const val WOBBLE_REVERSION = 0.22f
        const val WOBBLE_YAW_LIMIT = 1.6f
        const val WOBBLE_PITCH_LIMIT = 1.0f
    }

}
