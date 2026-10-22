package com.everythingrgbprofile;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Every path this mod touches, in one place, because the alternative is
 * {@code FMLPaths.CONFIGDIR.get().resolve(...)} copy-pasted into six classes
 * which then quietly start disagreeing about where the JSON lives.
 *
 * <p>Not a hypothetical failure mode. THE failure mode. The profile loader
 * writes its defaults into one directory, the reader goes looking in a
 * different one, and somebody ends up with a mod that ignores every edit they
 * make while cheerfully reporting zero errors. Good luck debugging that from
 * the outside. Single source of truth, non-negotiable.
 */
public final class RGBProfileFiles {

    /** {@code config/everythingrgbprofiles/}, where all of our stuff lives. */
    public static Path configDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(RGBProfileMod.MODID);
    }

    /**
     * Where the bundled Corsair DLL gets unpacked at runtime.
     *
     * <p>This is the normal path rather than an emergency one:
     * {@code CueSdkBridge.resolveDll} tries the extracted copy FIRST and only
     * falls back to whatever iCUE installed if the jar has no DLL in it.
     * Logitech's gets loaded straight out of G HUB's install folder instead,
     * and Razer, SteelSeries and OpenRGB want no DLL at all.
     *
     * <p>Worth saying plainly: yes, this means prying a native library out of
     * the jar and writing it onto somebody's disk. No, there is no nicer
     * option. You cannot {@code dlopen} a thing that only exists inside a zip,
     * and JNA wants a real path on a real filesystem. It goes under our own
     * config dir so that uninstalling this mod is "delete one folder" instead
     * of a scavenger hunt for orphaned DLLs somebody left in system32.
     *
     * <p>The copy here is checked against the one in the jar on every launch
     * and rewritten if they differ, so a mod update's DLL replaces the old one
     * and a damaged or swapped file does not get loaded. See
     * {@code CueSdkBridge.extractBundledDll}.
     */
    public static Path dllExtractDirectory() {
        return configDirectory().resolve("native");
    }

    /**
     * Where user-written board layouts live. See {@code KeyLayout}. This is
     * how somebody whose keyboard this project has never been anywhere near
     * can describe it themselves instead of waiting for us to buy one.
     */
    public static Path layoutsDirectory() {
        return configDirectory().resolve("layouts");
    }

    /** Biome colour overrides. Hand-editable on purpose, go wild. */
    public static Path biomeProfilesFile() {
        return configDirectory().resolve("biome_profiles.json");
    }

    /** Same deal, for bosses. */
    public static Path bossProfilesFile() {
        return configDirectory().resolve("boss_profiles.json");
    }

    /** Same deal for dimensions, and this one is what the portal effect reads. */
    public static Path dimensionProfilesFile() {
        return configDirectory().resolve("dimension_profiles.json");
    }

    /** Static-only. Instantiating this would accomplish precisely nothing. */
    private RGBProfileFiles() {
    }
}
