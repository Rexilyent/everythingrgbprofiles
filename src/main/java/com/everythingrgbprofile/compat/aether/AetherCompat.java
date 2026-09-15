package com.everythingrgbprofile.compat.aether;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.compat.ModCompatRegistry;
import com.everythingrgbprofile.config.Feature;

import java.util.ArrayList;
import java.util.List;

/**
 * The Aether integration.
 *
 * <p>Its three dungeon bosses are already covered by the generic detector:
 * the mod lists the Slider, the Valkyrie Queen and the Sun Spirit in
 * {@code c:bosses} itself.
 *
 * <p>The Slider gets more than that, for the same reason the Naga does. What
 * makes it the Slider is the way it moves — a two-block cube of stone that
 * only ever travels in straight lines along the world's axes, accelerating
 * until it hits something — and a generic health pulse throws all of that
 * away. So its room is drawn on the keys with the cube in it. See
 * {@code SliderEffect}.
 *
 * <p>The Sun Spirit gets the same treatment for a different reason: its fight
 * is a loop the board can actually help with. It cannot be hurt until one of
 * its own ice crystals is knocked back into it, and then only for the few
 * seconds it stays frozen — so where the ice crystal is, and how long the
 * freeze has left, are the two things worth putting in front of the player.
 * See {@code SunSpiritEffect}.
 *
 * <p>The Valkyrie Queen completes the set. Her fight is about where she is
 * and what is about to hit you: she teleports to your side, drops onto you out
 * of her jumps, and throws thunder crystals that drift after you and turn into
 * lightning fifteen seconds later. All of that has a place in her room, so her
 * room is drawn too. See {@code ValkyrieQueenEffect}.
 *
 * <p>This class exists to gate that, exactly as {@code TwilightForestCompat}
 * does: each poll costs one boolean read when the Aether is not installed. No
 * import of anything from the mod — entities and blocks are matched by their
 * registry ids, and whether a fight is on comes from vanilla's boss overlay
 * rather than from the Aether's own synced flags.
 */
public final class AetherCompat {

    /** The Aether's modid, which is also the namespace of everything it registers. */
    public static final String NAMESPACE = "aether";

    /** Registry ids, matched as strings and never imported. */
    public static final String SLIDER_ID = "aether:slider";
    public static final String SUN_SPIRIT_ID = "aether:sun_spirit";
    /** What the Sun Spirit throws: fire crystals mostly, and every fifth one ice. */
    public static final String FIRE_CRYSTAL_ID = "aether:fire_crystal";
    public static final String ICE_CRYSTAL_ID = "aether:ice_crystal";
    public static final String VALKYRIE_QUEEN_ID = "aether:valkyrie_queen";
    /** What the Valkyrie Queen throws. */
    public static final String THUNDER_CRYSTAL_ID = "aether:thunder_crystal";
    /**
     * The end of every block path the Silver Dungeon's walls are built from:
     * locked angelic stone, its light variant, and the two doorway blocks set
     * into the walls. Matched by suffix so all four count as wall.
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
