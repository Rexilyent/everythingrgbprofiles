package com.everythingrgbprofile.compat.twilightforest;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.compat.ModCompatRegistry;
import com.everythingrgbprofile.config.Feature;

/**
 * Twilight Forest integration.
 *
 * <p>Its nine bosses are already covered by the generic detector without a
 * line of mod-specific code, because the mod declares
 * {@code twilightforest:bosses} and nests it into the {@code c:bosses}
 * convention tag. That is the whole reason the tag-first approach was worth
 * building.
 *
 * <p>The Naga gets more than that. It is the one boss in the pack whose
 * identity is a <i>shape</i> — a long segmented snake that visibly shortens as
 * you cut it down — and a generic health pulse throws all of that away. So it
 * gets a serpent drawn on the keys, the same way the Ender Dragon gets a
 * wingspan. See {@code NagaEffect}.
 *
 * <p>This class exists to gate that: the Naga poll costs nothing at all if
 * Twilight Forest is not installed, since the modid check fails first and no
 * entity scan ever runs. As always, no import of anything from the mod —
 * detection is a modid string and the entity is matched by its registry id.
 */
public final class TwilightForestCompat {

    /** Registry id of the Naga. Matched as a string; never imported. */
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
