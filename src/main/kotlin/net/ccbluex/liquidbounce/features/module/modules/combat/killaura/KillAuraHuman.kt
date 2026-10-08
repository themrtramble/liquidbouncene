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
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features.KillAuraRange
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.features.processors.RotationProcessor
import net.ccbluex.liquidbounce.utils.clicking.ClickBehaviorProfile
import net.ccbluex.liquidbounce.utils.clicking.ClickTechnique
import net.ccbluex.liquidbounce.utils.kotlin.random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Humanized combat profile for KillAura.
 *
 * While enabled, KillAura behaves like a real human player instead of a machine.
 * Every override below is applied at runtime only — the module's saved
 * configuration is never modified and takes effect again once disabled.
 *
 *  - [CPS]/[MaxPerTick]: natural click speed with log-normal interval jitter
 *    and at most one press per game tick (a human cannot press twice in 50ms).
 *  - Miss cooldown is respected, like the game client of a real player.
 *  - Attack strength varies (90-100% instead of always near-full), so hit
 *    timings are not machine-perfect. Crits still happen naturally.
 *  - [VanillaReach]: attacks are capped to vanilla-legal reach (3.0 blocks)
 *    and never go through walls — extended reach is the most common reason
 *    for "suspicious combat" flags on modern servers.
 *  - [AimWobble]: the crosshair wanders around the target with a smooth
 *    mean-reverting random walk instead of locking onto the optimal hitbox
 *    point with surgical precision.
 *  - Aims with acceleration-based angle smoothing including subtle aim error.
 *  - Vanilla sprint-reset behavior on hits (no sprint-keeping), matching the
 *    vanilla game client.
 *  - [DirectionFOV]: only attacks enemies within the view direction angle and
 *    prioritises the ones the camera is facing — humans do not hit things
 *    behind their back. Turn your camera towards an enemy to focus it.
 */
object KillAuraHuman : ToggleableValueGroup(ModuleKillAura, "Human", false), ClickBehaviorProfile {

    /**
     * Clicks per second while humanized. Real players in combat click in this
     * range. Since the attack cooldown pacing stays active, every hit still
     * deals (near-)full damage on modern servers.
     */
    private val humanCps by intRange("CPS", 9..13, 1..20, "clicks")

    /**
     * Maximum clicks per game tick while humanized.
     */
    private val humanMaxPerTick by int("MaxPerTick", 1, 1..2, "clicks")

    /**
     * Only attack enemies within this angle of the view direction.
     */
    val directionalFov by float("DirectionFOV", 120f, 30f..180f, "°")

    /**
     * Cap the attack range to vanilla-legal reach (3.0 blocks) and disable
     * attacks through walls. Extended reach is the most common trigger of
     * server-side "suspicious combat" detection.
     */
    private val vanillaReach by boolean("VanillaReach", true)

    /**
     * Let the crosshair wander around the target with organic noise instead
     * of locking onto the optimal hitbox point.
     */
    private val aimWobble by boolean("AimWobble", true)

    /**
     * Smooth mean-reverting random walk (Ornstein-Uhlenbeck style) that makes
     * the aim wander around the target like a human hand does.
     */
    private val wobbleProcessor = HumanAimWobbleProcessor()

    init {
        // Install the runtime profiles. Reads are live, so changes made in the
        // GUI apply instantly and nothing is persisted by this.
        KillAuraClicker.behaviorProfile = this
        KillAuraRotationsValueGroup.angleSmoothOverrideProvider = {
            if (enabled) KillAuraRotationsValueGroup.accelerationModeCandidate else null
        }
        KillAuraRotationsValueGroup.extraProcessorsProvider = {
            if (enabled && aimWobble) listOf(wobbleProcessor) else emptyList()
        }

        // Vanilla-legal reach while humanized
        KillAuraRange.runtimeRangeCapProvider = { if (enabled && vanillaReach) 3.0f else null }
        KillAuraRange.runtimeThroughWallsCapProvider = { if (enabled && vanillaReach) 0.0f else null }

        // Vary the attack strength threshold (90-100%) instead of always near-full
        KillAuraClicker.itemCooldown?.runtimeMinimumProvider = {
            if (enabled) 0.9f..1.0f else null
        }

        // Vanilla sprint-reset behavior on hits
        ModuleKillAura.keepSprintOverride = { if (enabled) false else null }
    }

    override val profileActive: Boolean
        get() = enabled

    override val overrideCps: IntRange
        get() = humanCps

    override val overrideMaxPerTick: Int
        get() = humanMaxPerTick

    override val overrideTechnique: ClickTechnique
        get() = ClickTechnique.HUMAN

    override val overrideMissCooldown: Boolean
        get() = true

    /**
     * Organic aim wander around the target rotation.
     *
     * The offsets follow a mean-reverting random walk, so the crosshair drifts
     * around the aim point without ever running away from it. The horizontal
     * magnitude is chosen so the crosshair stays on the hitbox most of the time
     * at combat distance but occasionally clips past its edge — which naturally
     * delays attacks and produces the non-perfect hit placement of a real player.
     */
    private class HumanAimWobbleProcessor : RotationProcessor {

        private var yawOffset = 0f
        private var pitchOffset = 0f

        override fun process(
            rotationTarget: RotationTarget,
            currentRotation: Rotation,
            targetRotation: Rotation
        ): Rotation {
            // Mean-reverting random walk (discrete Ornstein-Uhlenbeck process)
            yawOffset += 1.2f * gaussian() - yawOffset * 0.25f
            pitchOffset += 0.7f * gaussian() - pitchOffset * 0.25f

            yawOffset = yawOffset.coerceIn(-5.5f, 5.5f)
            pitchOffset = pitchOffset.coerceIn(-3.0f, 3.0f)

            return Rotation(targetRotation.yaw + yawOffset, targetRotation.pitch + pitchOffset)
        }

        private fun gaussian(): Float {
            // Box-Muller transform, clamped to avoid extreme outliers
            val u1 = (0.001f..0.999f).random()
            val u2 = (0.001f..0.999f).random()
            return (sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)).coerceIn(-3f, 3f)
        }

    }

}
