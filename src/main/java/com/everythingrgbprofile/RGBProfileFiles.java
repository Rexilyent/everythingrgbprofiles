package com.everythingrgbprofile;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Every path this mod touches, in one place, because the alternative is
 * {@code FMLPaths.CONFIGDIR.get().resolve(...)} copy-pasted into six classes
 * that then quietly disagree about where the JSON lives.
 *
 * <p>That is not a hypothetical failure mode, it is <i>the</i> failure mode:
 * the profile loader writes defaults to one directory, the reader looks in
 * another, and the user gets a mod that ignores every edit they make while
 * cheerfully reporting no errors. Single source of truth. Non-negotiable.
 */
public final class RGBProfileFiles {

    /** {@code config/everythingrgbprofiles/} — where all our stuff lives. */
    public static Path configDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(RGBProfileMod.MODID);
    }

    /**
     * Where the bundled Corsair DLL gets unpacked to at runtime. (Logitech's
     * DLL is loaded from G HUB's own install folder instead, and the other
     * vendors need no DLL at all.)
     *
     * <p>Yes, we extract a native library out of the jar and onto disk. No,
     * there isn't a nicer option — you cannot {@code dlopen} something that
     * only exists inside a zip file, and JNA needs a real path on a real
     * filesystem. It goes under our own config dir so uninstalling the mod
     * means deleting one folder rather than hunting for orphaned DLLs.
     */
    public static Path dllExtractDirectory() {
        return configDirectory().resolve("native");
    }

    /**
     * Where user-written board layouts live. See {@code KeyLayout} — this is
     * how someone whose keyboard this project has never been tested on can
     * describe it.
     */
    public static Path layoutsDirectory() {
        return configDirectory().resolve("layouts");
    }

    /** User-editable biome colour overrides. Hand-editable on purpose. */
    public static Path biomeProfilesFile() {
        return configDirectory().resolve("biome_profiles.json");
    }

    /** Same deal, for bosses. */
    public static Path bossProfilesFile() {
        return configDirectory().resolve("boss_profiles.json");
    }

    /** Same deal, for dimensions — this is what the portal effect reads. */
    public static Path dimensionProfilesFile() {
        return configDirectory().resolve("dimension_profiles.json");
    }

    /** Static-only. Instantiating this would accomplish nothing. */
    private RGBProfileFiles() {
    }
}
