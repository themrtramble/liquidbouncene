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
import net.ccbluex.liquidbounce.utils.clicking.ClickBehaviorProfile
import net.ccbluex.liquidbounce.utils.clicking.ClickTechnique

/**
 * Humanized combat profile for KillAura.
 *
 * While enabled, KillAura behaves like a real human player instead of a machine:
 *
 *  - Clicks at a natural, human-plausible CPS. The log-normal interval jitter of the
 *    [ClickTechnique.HUMAN] technique makes the click pattern look organic instead
 *    of metronome-like. The item cooldown pacing still ensures full damage per hit.
 *  - Clicks at most once per game tick — a human cannot physically press twice
 *    within a single 50ms tick.
 *  - Respects the vanilla miss cooldown after a failed hit, like the game client
 *    of a real player does.
 *  - Aims with acceleration-based angle smoothing (accelerate, decelerate, slight
 *    overshoot correction) including subtle aim error, instead of linear robotic turns.
 *  - Prioritises enemies in the direction the player is looking and only attacks
 *    within a limited view angle — humans do not hit things behind their back.
 *    Turn your camera towards an enemy to focus it.
 *
 * All overrides are applied at runtime only. The module's saved configuration
 * (CPS, MaxPerTick, technique, miss cooldown, angle smooth mode, target priority)
 * is never modified and takes effect again the moment this is disabled.
 */
object KillAuraHuman : ToggleableValueGroup(ModuleKillAura, "Human", false), ClickBehaviorProfile {

    /**
     * Clicks per second while humanized. Real players in combat click in this
     * range. Since the attack cooldown pacing stays active, every hit still
     * deals full damage on modern servers.
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

    init {
        // Install the runtime profiles. Reads are live, so changes made in the
        // GUI apply instantly and nothing is persisted by this.
        KillAuraClicker.behaviorProfile = this
        KillAuraRotationsValueGroup.angleSmoothOverrideProvider = {
            if (enabled) KillAuraRotationsValueGroup.accelerationModeCandidate else null
        }
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

}
