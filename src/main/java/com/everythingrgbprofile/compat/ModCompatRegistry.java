package com.everythingrgbprofile.compat;

import com.everythingrgbprofile.RGBProfileMod;
import net.neoforged.fml.ModList;

/**
 * "Is that mod installed?" — asked once, at startup, cached forever.
 *
 * <p>Cheap enough to run before SDK gating resolves, and safe on both sides:
 * it's a {@code ModList} lookup against an already-built list, touching no JNA
 * or SDK classes.
 *
 * <h2>The rule this class enforces</h2>
 * <b>Nothing in this mod ever imports another mod's classes.</b>
 * Not once. Detection is a modid string check, and anything deeper — actually
 * reading Tough As Nails' stats, say — goes through a small isolated compat
 * class using reflection or a {@code compileOnly} API artifact.
 *
 * <p>Why so strict: a direct import means a hard classload, and a hard
 * classload of a mod that isn't present is a {@code NoClassDefFoundError} at
 * the worst possible moment. In a 600-mod pack where any given mod might be
 * absent, updated, or renamed, the only sane assumption is that everything
 * except vanilla is optional.
 *
 * <p>Fields are cached rather than re-queried because these are checked from
 * per-tick polling paths, and volatile is enough: written once during setup,
 * read everywhere after.
 */
public final class ModCompatRegistry {

    private static volatile boolean toughAsNailsLoaded = false;
    private static volatile boolean legendaryMonstersLoaded = false;
    private static volatile boolean betterEndIslandLoaded = false;
    private static volatile boolean twilightForestLoaded = false;
    private static volatile boolean aetherLoaded = false;

    public static void detectAll() {
        toughAsNailsLoaded = ModList.get().isLoaded("toughasnails");
        legendaryMonstersLoaded = ModList.get().isLoaded("legendary_monsters");
        betterEndIslandLoaded = ModList.get().isLoaded("betterendisland");
        twilightForestLoaded = ModList.get().isLoaded("twilightforest");
        aetherLoaded = ModList.get().isLoaded("aether");

        // Logged at INFO deliberately. When someone reports "the thirst
        // lighting doesn't work", this single line answers the first question
        // anyone would ask, without needing debug logging enabled after the
        // fact.
        RGBProfileMod.LOGGER.info(
                "RGB Profile: mod-compat detection — Tough As Nails: {}, Legendary Monsters: {}, "
                        + "Better End Island: {}, Twilight Forest: {}, Aether: {}",
                toughAsNailsLoaded, legendaryMonstersLoaded, betterEndIslandLoaded, twilightForestLoaded,
                aetherLoaded);
    }

    /**
     * Every mod this one has dedicated support for, as modid to name, in the
     * order the detection line logs them. For the diagnostic report, which
     * adds whether each is installed and its version.
     */
    public static java.util.Map<String, String> supportedMods() {
        java.util.Map<String, String> mods = new java.util.LinkedHashMap<>();
        mods.put("toughasnails", "Tough As Nails");
        mods.put("legendary_monsters", "Legendary Monsters");
        mods.put("betterendisland", "Better End Island");
        mods.put("twilightforest", "Twilight Forest");
        mods.put("aether", "Aether");
        return mods;
    }

    public static boolean isToughAsNailsLoaded() {
        return toughAsNailsLoaded;
    }

    public static boolean isLegendaryMonstersLoaded() {
        return legendaryMonstersLoaded;
    }

    public static boolean isBetterEndIslandLoaded() {
        return betterEndIslandLoaded;
    }

    public static boolean isTwilightForestLoaded() {
        return twilightForestLoaded;
    }

    public static boolean isAetherLoaded() {
        return aetherLoaded;
    }

    private ModCompatRegistry() {
    }
}
