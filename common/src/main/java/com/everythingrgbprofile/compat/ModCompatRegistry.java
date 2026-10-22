package com.everythingrgbprofile.compat;

import com.everythingrgbprofile.RGBProfileMod;
import net.neoforged.fml.ModList;

/**
 * "Is that mod installed?" Asked once, at startup, cached forever.
 *
 * <p>Cheap enough to run before SDK gating has resolved anything, and safe on
 * both sides, because it is a {@code ModList} lookup against a list that has
 * already been built and it touches zero JNA or SDK classes.
 *
 * <h2>The rule this class exists to enforce</h2>
 * <b>Nothing in this mod ever imports another mod's classes.</b> Not once, not
 * anywhere. Detection is a modid string check, and anything deeper than that
 * (actually reading Tough As Nails' stats, for instance) goes through a small
 * isolated compat class using reflection or a {@code compileOnly} API
 * artifact.
 *
 * <p>Why so strict about it: a direct import means a hard classload, and a
 * hard classload of a mod that isn't there is a
 * {@code NoClassDefFoundError} at the worst imaginable moment. In a 600-mod
 * pack where any given mod might be absent, updated, renamed or abandoned, the
 * only sane assumption is that everything except vanilla is optional.
 *
 * <p>The fields are cached instead of re-queried because these get checked
 * from per-tick polling paths, and volatile is plenty: written once during
 * setup, read everywhere forever after.
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

        // INFO on purpose. When somebody reports "the thirst lighting doesn't
        // work", this one line answers the first question anybody would ask,
        // and it is already sitting in the log they sent rather than needing
        // them to go and turn debug logging on and do it all again.
        RGBProfileMod.LOGGER.info(
                "RGB Profile: mod-compat detection — Tough As Nails: {}, Legendary Monsters: {}, "
                        + "Better End Island: {}, Twilight Forest: {}, Aether: {}",
                toughAsNailsLoaded, legendaryMonstersLoaded, betterEndIslandLoaded, twilightForestLoaded,
                aetherLoaded);
    }

    /**
     * Every mod this one has dedicated support for, as modid to display name,
     * in the same order the detection line logs them. Used by the diagnostic
     * report, which adds whether each one is actually installed and what
     * version it is.
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
