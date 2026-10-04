package com.everythingrgbprofile.compat.legendarymonsters;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.compat.ModCompatRegistry;

/**
 * Legendary Monsters "integration", where integration means we log that it's
 * installed and then do absolutely nothing, because nothing needs doing.
 *
 * <p>Its bosses work through the generic detector
 * ({@code BossEncounterEffect} plus the {@code legendary_monsters:*} wildcard
 * in {@code boss_profiles.json}) with <b>zero mod-specific code</b>.
 *
 * <h2>Correction: the boss-bar bet did not pay off</h2>
 * This class used to claim the bosses "already work perfectly through the
 * generic vanilla boss-bar hook", which was wrong in two separate ways at
 * once. The boss-bar hook was a deliberately empty stub that never fired for
 * anything, vanilla included. And several of Legendary Monsters' bosses are
 * bosses in the way the Warden is one: enormous health, an encounter you
 * prepare for, and no bar above the screen whatsoever. No boss-bar hook was
 * ever going to see them, no matter how well it had been written.
 *
 * <p>Better still, Legendary Monsters ships
 * {@code data/c/tags/entity_type/bosses.json} declaring exactly three bosses
 * (the_obliterator, cloud_golem and posessed_paladin, matching its own
 * {@code IAnimatedBoss} classes). Detection reads that {@code c:bosses}
 * convention tag, so the mod author's own answer is the one that gets used.
 * The only other way in is an exact entry in {@code boss_profiles.json}; a
 * mob that is in neither is not a boss, however much health it has.
 *
 * <p>It also draws its own {@code CustomBossBar} instead of a vanilla one,
 * which is the second and completely independent reason a boss-bar hook was
 * never going to work here. See {@code ClientEventHandlers.pollBossEncounter}.
 *
 * <p>So why does this class still exist? Two reasons, both honest:
 *
 * <ol>
 *   <li>It establishes the {@code ModCompatRegistry} pattern for future
 *       integrations that genuinely do need more than the generic hook.
 *       Having one worked example lying around beats writing the first one
 *       under pressure at midnight.</li>
 *   <li>The log line is a real diagnostic. "Legendary Monsters detected, and
 *       here is specifically why we are not doing anything special about it"
 *       is exactly what somebody wants to see when they're wondering whether
 *       their boss mod is supported.</li>
 * </ol>
 *
 * <p>Note the complete absence of any import from Legendary Monsters. That is
 * the entire point.
 */
public final class LegendaryMonstersCompat {

    public static void logStatusIfPresent() {
        if (ModCompatRegistry.isLegendaryMonstersLoaded()) {
            RGBProfileMod.LOGGER.info("RGB Profile: Legendary Monsters detected — its bosses are covered automatically "
                    + "via the 'c:bosses' tag it declares (the_obliterator, cloud_golem, posessed_paladin), "
                    + "each with its own entry in boss_profiles.json. Anything it adds that is not tagged "
                    + "counts only if boss_profiles.json names its exact id.");
        }
    }

    private LegendaryMonstersCompat() {
    }
}
