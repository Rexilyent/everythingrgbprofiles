package com.everythingrgbprofile.compat.legendarymonsters;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.compat.ModCompatRegistry;

/**
 * Legendary Monsters "integration", where integration means: we log
 * that it's there, and then do nothing, because nothing needs doing.
 *
 * <p>Its bosses work through the generic detector
 * ({@code BossEncounterEffect} plus the {@code legendary_monsters:*} wildcard
 * in {@code boss_profiles.json}) with <b>zero mod-specific code</b>.
 *
 * <h2>Correction: the boss-bar bet did not pay</h2>
 * This class used to claim the bosses "already work perfectly through the
 * generic vanilla boss-bar hook". That was wrong twice over. The boss-bar hook
 * was a deliberately empty stub that never fired for anything, vanilla
 * included — and several of Legendary Monsters' bosses are bosses the way the
 * Warden is one: enormous health, an encounter you prepare for, and no bar
 * above the screen at all. A boss-bar hook would never have seen them however
 * well it had been written.
 *
 * <p>Better still, Legendary Monsters ships {@code data/c/tags/entity_type/bosses.json}
 * declaring exactly three bosses — the_obliterator, cloud_golem and
 * posessed_paladin — matching its own {@code IAnimatedBoss} classes. Detection
 * reads that {@code c:bosses} convention tag first, so the mod author's own
 * answer is the one used. Proximity plus a health threshold is only the
 * fallback for mods that declare nothing.
 *
 * <p>It also draws its own {@code CustomBossBar} rather than a vanilla one,
 * which is the second, independent reason the boss-bar hook could never
 * have worked here. See {@code ClientEventHandlers.pollBossEncounter}.
 *
 * <p>So why does the class exist? Two reasons, both honest:
 *
 * <ol>
 *   <li>It establishes the {@code ModCompatRegistry} pattern for future
 *       integrations that genuinely need more than the generic hook. Having
 *       one worked example beats writing the first one under pressure.</li>
 *   <li>The log line is a real diagnostic. "Legendary Monsters detected, and
 *       here is specifically why we're not doing anything special about it" is
 *       useful when someone wonders whether their boss mod is supported.</li>
 * </ol>
 *
 * <p>Note: no import of anything from Legendary Monsters. That's the point.
 */
public final class LegendaryMonstersCompat {

    public static void logStatusIfPresent() {
        if (ModCompatRegistry.isLegendaryMonstersLoaded()) {
            RGBProfileMod.LOGGER.info("RGB Profile: Legendary Monsters detected — its bosses are covered automatically "
                    + "via the 'c:bosses' tag it declares (the_obliterator, cloud_golem, posessed_paladin), "
                    + "each with its own entry in boss_profiles.json. Anything it adds that is not tagged "
                    + "still qualifies by proximity if it clears bossEvents.proximityMinMaxHealth.");
        }
    }

    private LegendaryMonstersCompat() {
    }
}
