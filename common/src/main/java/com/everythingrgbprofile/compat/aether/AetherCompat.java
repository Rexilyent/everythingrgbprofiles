package com.everythingrgbprofile.compat.aether;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.compat.ModCompatRegistry;
import com.everythingrgbprofile.config.Feature;

import java.util.ArrayList;
import java.util.List;

/**
 * The Aether integration.
 *
 * <p>Its three dungeon bosses are already covered by the generic detector,
 * because the mod lists the Slider, the Valkyrie Queen and the Sun Spirit in
 * {@code c:bosses} all by itself.
 *
 * <p>The Slider gets more than that, for the same reason the Naga does. What
 * makes a Slider a Slider is the way it moves: a two-block cube of stone that
 * only ever travels in straight lines along the world axes, accelerating until
 * it hits something. A generic health pulse throws every bit of that away. So
 * its room gets drawn on the keys with the cube in it. See
 * {@code SliderEffect}.
 *
 * <p>The Sun Spirit gets the same treatment for a completely different reason:
 * its fight is a loop the board can genuinely help you with. It cannot be hurt
 * at all until one of its own ice crystals gets knocked back into it, and then
 * only during the few seconds it stays frozen. So where the ice crystal
 * currently is, and how much freeze is left on the clock, are the two things
 * worth putting in front of you. See {@code SunSpiritEffect}.
 *
 * <p>The Valkyrie Queen rounds out the set. Her fight is entirely about where
 * she is and what is about to hit you: she teleports to your side, drops on
 * you out of her jumps, and throws thunder crystals that drift after you and
 * become lightning fifteen seconds later. All of that has a place in her room,
 * so her room gets drawn too. See {@code ValkyrieQueenEffect}.
 *
 * <p>This class exists to gate all of it, exactly the way
 * {@code TwilightForestCompat} does: each poll costs one boolean read when the
 * Aether isn't installed. No import of anything from the mod. Entities and
 * blocks are matched by registry id, and whether a fight is happening comes
 * from vanilla's boss overlay rather than from the Aether's own synced flags.
 */
public final class AetherCompat {

    /** The Aether's modid, which is also the namespace of everything it registers. */
    public static final String NAMESPACE = "aether";

    /** Registry ids, matched as strings and never imported. */
    public static final String SLIDER_ID = "aether:slider";
    public static final String SUN_SPIRIT_ID = "aether:sun_spirit";
    /** What the Sun Spirit throws: mostly fire crystals, and every fifth one ice. */
    public static final String FIRE_CRYSTAL_ID = "aether:fire_crystal";
    public static final String ICE_CRYSTAL_ID = "aether:ice_crystal";
    public static final String VALKYRIE_QUEEN_ID = "aether:valkyrie_queen";
    /** What the Valkyrie Queen throws. */
    public static final String THUNDER_CRYSTAL_ID = "aether:thunder_crystal";
    /**
     * The tail end of every block path the Silver Dungeon's walls are built
     * out of: locked angelic stone, its light variant, and the two doorway
     * blocks set into the walls. Matched by suffix so that all four of them
     * count as wall without listing each one.
     */
    public static final String ANGELIC_STONE_SUFFIX = "angelic_stone";

    public static void logStatusIfPresent() {
        if (!ModCompatRegistry.isAetherLoaded()) return;
        List<String> rooms = new ArrayList<>();
        if (Feature.SLIDER.isAvailable()) rooms.add("the Slider");
        if (Feature.SUN_SPIRIT.isAvailable()) rooms.add("the Sun Spirit");
        if (Feature.VALKYRIE_QUEEN.isAvailable()) rooms.add("the Valkyrie Queen");
        RGBProfileMod.LOGGER.info(
                "RGB Profile: the Aether detected — its dungeon bosses are covered by the 'c:bosses' "
                        + "tag it declares{}.",
                rooms.isEmpty() ? ""
                        : ", and " + String.join(", ", rooms) + " additionally get their boss rooms "
                                + "drawn on the keys, with each boss where the real one is");
    }

    private AetherCompat() {
    }
}
