package com.everythingrgbprofile.compat.betterendisland;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.compat.ModCompatRegistry;

/**
 * YUNG's Better End Island ({@code betterendisland}) integration.
 *
 * <p>The mod replaces vanilla's abrupt dragon respawn with a staged ritual:
 * its {@code DragonRespawnStage} runs START to PREPARING_TO_SUMMON_PILLARS to
 * SUMMONING_PILLARS to SUMMONING_DRAGON to END. Crystals in the central
 * structure fire upward, then each obsidian pillar fires a beam into the
 * middle and reshapes itself, and only once every pillar has gone does the
 * dragon arrive.
 *
 * <h2>The stage is not readable, and it does not need to be</h2>
 * The mod ships <b>no network classes at all</b> — it works through mixins
 * into {@code EndDragonFight}, {@code PrimaryLevelData} and
 * {@code ServerLevel}, every one of them server-side. Its respawn stage is
 * therefore server-only state that no client can see, and no amount of
 * reflection will change that.
 *
 * <p>Reading end crystals instead sidesteps the problem entirely. The beams
 * ARE the ritual, {@code EndCrystal.getBeamTarget()} is
 * {@link net.minecraft.network.syncher.SynchedEntityData} because vanilla has
 * to render them, and counting how many are lit tells you how far along the
 * sequence is without knowing the mod exists. So the effect needs no mod
 * imports, no server component, and works on a vanilla server.
 *
 * <h2>Then why does this class exist?</h2>
 * Two reasons, and unlike {@code LegendaryMonstersCompat} the second one
 * changes what the lighting does:
 *
 * <ol>
 *   <li>The log line answers "is my End overhaul supported" without anyone
 *       having to enable debug logging after the fact.</li>
 *   <li><b>It changes the expected pillar count.</b> Vanilla's respawn lights
 *       four crystals on the exit portal. This mod's ritual walks the ten
 *       obsidian pillars one at a time. That number is what the ray pattern
 *       divides the circle by, so getting it wrong makes the rays sit at the
 *       wrong angles for the whole sequence — the one place the two paths
 *       genuinely diverge.</li>
 * </ol>
 *
 * <p>As ever: no import of anything from the mod. Detection is a modid string.
 */
public final class BetterEndIslandCompat {

    /** Vanilla lights the four crystals placed on the exit portal. */
    private static final int VANILLA_RITUAL_SOURCES = 4;
    /** The staged ritual walks the ten obsidian pillars. */
    private static final int BETTER_END_ISLAND_PILLARS = 10;

    /**
     * How many beam sources the ritual is expected to involve, which sets the
     * angular spacing of the rays.
     *
     * <p>Used as a FLOOR, not a fixed value: the detector takes the max of
     * this and the highest beam count it has actually seen, so if the real
     * number differs the display corrects itself rather than clipping.
     */
    public static int expectedRitualSources() {
        return ModCompatRegistry.isBetterEndIslandLoaded()
                ? BETTER_END_ISLAND_PILLARS
                : VANILLA_RITUAL_SOURCES;
    }

    public static void logStatusIfPresent() {
        if (!ModCompatRegistry.isBetterEndIslandLoaded()) return;
        RGBProfileMod.LOGGER.info(
                "RGB Profile: YUNG's Better End Island detected — the dragon summoning ritual will be "
                        + "read from end crystal beams (the mod keeps its respawn stage server-side and "
                        + "ships no packets, so beams are the only client-visible signal — and they are "
                        + "enough). Ray layout assumes {} pillars; vanilla's respawn uses {}.",
                BETTER_END_ISLAND_PILLARS, VANILLA_RITUAL_SOURCES);
    }

    private BetterEndIslandCompat() {
    }
}
