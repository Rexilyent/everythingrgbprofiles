package com.everythingrgbprofile.compat.twilightforest;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.compat.ModCompatRegistry;
import com.everythingrgbprofile.config.Feature;

/**
 * Twilight Forest integration.
 *
 * <p>All nine of its bosses are already covered by the generic detector
 * without a single line of mod-specific code, because the mod declares
 * {@code twilightforest:bosses} and nests that into the {@code c:bosses}
 * convention tag. This is the entire reason building the tag-first approach
 * was worth the trouble.
 *
 * <p>The Naga gets more than that, though. It is the one boss in the pack
 * whose identity is a <i>shape</i>: a long segmented snake that visibly gets
 * shorter as you cut it down. A generic health pulse throws all of that in the
 * bin. So it gets an actual serpent drawn on the keys, the same way the Ender
 * Dragon gets a wingspan. See {@code NagaEffect}.
 *
 * <p>This class exists to gate that. The Naga poll costs literally nothing if
 * Twilight Forest isn't installed, because the modid check fails first and the
 * entity scan never runs at all. And as always: no import of anything from the
 * mod. Detection is a modid string, the entity is matched by registry id.
 */
public final class TwilightForestCompat {

    /** Registry id of the Naga. Matched as a string, never imported. */
    public static final String NAGA_ID = "twilightforest:naga";

    public static void logStatusIfPresent() {
        if (!ModCompatRegistry.isTwilightForestLoaded()) return;
        RGBProfileMod.LOGGER.info(
                "RGB Profile: Twilight Forest detected — all nine of its bosses are covered by the "
                        + "'c:bosses' tag it declares{}.",
                Feature.NAGA.isAvailable()
                        ? ", and the Naga additionally gets its own serpent animation that shortens as "
                                + "the fight goes on"
                        : "");
    }

    private TwilightForestCompat() {
    }
}
