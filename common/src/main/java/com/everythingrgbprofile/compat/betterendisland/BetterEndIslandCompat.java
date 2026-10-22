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
 * <h2>The stage is not readable, and it turns out not to matter</h2>
 * The mod ships <b>no network classes whatsoever</b>. Its respawn works through
 * mixins into {@code EndDragonFight}, {@code PrimaryLevelData} and
 * {@code ServerLevel}, and the rest of its mixins are worldgen features;
 * every single one of them is server-side. Its respawn stage
 * is therefore server-only state that no client can see, and no quantity of
 * reflection is going to change that.
 *
 * <p>Reading end crystals sidesteps the entire problem. The beams ARE the
 * ritual, {@code EndCrystal.getBeamTarget()} lives in
 * {@link net.minecraft.network.syncher.SynchedEntityData} because vanilla has
 * to render them anyway, and counting how many are lit tells you exactly how
 * far along the sequence is without ever knowing the mod exists. No mod
 * imports, no server component, works on a vanilla server.
 *
 * <h2>Then why does this class exist at all?</h2>
 * Because being able to read the beams is not the same thing as wanting to
 * draw them:
 *
 * <ol>
 *   <li><b>It decides whether the respawn gets lit at all.</b>
 *       {@link #isStagedRitual()} is the switch. With this mod installed, the
 *       pillar walk is the centrepiece of the End and earns the full ritual
 *       effect. Without it, the board sits the respawn out entirely and never
 *       looks at a crystal, because a vanilla crystal spends essentially its
 *       whole existence bolted to a pillar topping the dragon back up, and
 *       that is the only thing it should mean.</li>
 *   <li>The log line answers "is my End overhaul supported" without anybody
 *       having to turn debug logging on and do the whole respawn again.</li>
 * </ol>
 *
 * <p>Worth knowing before anybody re-derives it: vanilla runs a staged
 * respawn of its own ({@code DragonRespawnAnimation}: the four crystals beam
 * at (0,128,0) to start, then retarget to each of the ten spikes in turn,
 * forty ticks apiece), and it would be perfectly readable from here. Leaving it dark is a call about what the End
 * should feel like without an overhaul installed, not a detection limit.
 *
 * <p>Only the respawn is gated. The fight runs off synced {@code EnderDragon}
 * state, which is there whatever else is installed, so it needs no branch.
 *
 * <p>As ever, no import of anything from the mod. Detection is a modid string.
 */
public final class BetterEndIslandCompat {

    /** The staged ritual walks the ten obsidian pillars. */
    private static final int BETTER_END_ISLAND_PILLARS = 10;

    /**
     * Whether the End respawn is the staged, ten-pillar kind.
     *
     * <p>The one gate. Everything downstream of it reads plain vanilla entity
     * state, so this is the only line in the End code that knows another mod
     * is installed.
     */
    public static boolean isStagedRitual() {
        return ModCompatRegistry.isBetterEndIslandLoaded();
    }

    /**
     * How many beam sources the staged ritual involves.
     *
     * <p>A denominator, nothing more: lit sources over this is how far along
     * the ritual is, which drives how loaded the core looks. Ray angles come
     * from the crystals' real positions in the world, not from dividing a
     * circle by this, so a wrong value here makes the core fill at the wrong
     * rate and moves nothing.
     *
     * <p>Only ever asked while {@link #isStagedRitual()} holds, since the
     * vanilla path never reads a crystal in the first place.
     */
    public static int expectedRitualSources() {
        return BETTER_END_ISLAND_PILLARS;
    }

    public static void logStatusIfPresent() {
        if (!isStagedRitual()) return;
        RGBProfileMod.LOGGER.info(
                "RGB Profile: YUNG's Better End Island detected, so the End respawn gets the staged "
                        + "ritual effect, read from end crystal beams (the mod keeps its respawn stage "
                        + "server-side and ships no packets, so beams are the only client-visible signal, "
                        + "and they turn out to be enough). Pacing assumes {} pillars. Without this mod "
                        + "the respawn is left dark on purpose and only the dragon itself is drawn.",
                BETTER_END_ISLAND_PILLARS);
    }

    private BetterEndIslandCompat() {
    }
}
