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
 * but WITHOUT ANY WARRANTY; without even the implied warranties of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.render

import net.ccbluex.liquidbounce.event.events.PlayerPostTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.minecraft.client.CloudStatus
import net.minecraft.server.level.ParticleStatus

/**
 * FPS Boost module
 *
 * Ships enabled by default. Combines several proven performance tweaks:
 *
 *  - NoWeather: cancels rain/snow rendering (weather particles are a
 *    notorious FPS killer during storms)
 *  - NoParticles: cancels world particle spawning (block break, eat,
 *    crit, etc.) — the biggest render-thread saving on entity-dense servers
 *  - FastVideoSettings: applies performance-friendly vanilla video settings
 *    once per session (clouds off, particles minimal, entity shadows off,
 *    biome blend 0, entity distance 0.5)
 */
object ModuleFPSBoost : ClientModule(
    "FPSBoost",
    ModuleCategories.RENDER,
    state = true,
    aliases = listOf("Performance", "FpsBoost")
) {

    val noWeather by boolean("NoWeather", true)
    val noParticles by boolean("NoParticles", true)
    private val fastVideoSettings by boolean("FastVideoSettings", true)

    /**
     * Whether the video settings were already applied during this game session.
     */
    private var appliedThisSession = false

    override suspend fun enabledEffect() {
        // Re-arm so the settings re-apply after a manual re-enable
        appliedThisSession = false
    }

    private val tickHandler = handler<PlayerPostTickEvent> {
        if (!appliedThisSession) {
            appliedThisSession = true

            if (fastVideoSettings) {
                applyVideoSettings()
            }
        }
    }

    /**
     * Applies performance-friendly vanilla video settings.
     *
     * These match the classic "FPS boost" setup guides: entity shadows are
     * expensive per-entity draw calls, clouds render a translucent layer
     * every frame, particles spawn thousands of quads, biome blending forces
     * extra color sampling, and high entity distance scales all bounding-box
     * culling volumes.
     */
    private fun applyVideoSettings() {
        mc.options.entityShadows().set(false)
        mc.options.cloudStatus().set(CloudStatus.OFF)
        mc.options.particles().set(ParticleStatus.MINIMAL)
        mc.options.biomeBlendRadius().set(0)
        mc.options.entityDistanceScaling().set(0.5)
    }

}
