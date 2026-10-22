package com.everythingrgbprofile.profile;

import com.everythingrgbprofile.RGBProfileFiles;
import com.everythingrgbprofile.color.RGBColor;

import java.util.Map;

/** One entry in {@code boss_profiles.json}. Gson-populated, same deal as {@link BiomeProfile}. */
public final class BossProfile {
    public String color;
    // Loaded, and currently read by nothing: the generic boss layer always
    // draws its own health-driven pulse whatever this says. The bundled
    // entries set it anyway, so it's there if that ever changes.
    public String pattern = "pulse-medium";
    public String enrageColor;
    /**
     * Below this percentage the colour starts shifting toward
     * {@code enrageColor}. Boxed Integer rather than int, specifically so that
     * null can mean "this boss has no enrage phase at all". A primitive would
     * need a magic sentinel like -1, and everybody would eventually forget
     * which magic number it was.
     */
    public Integer enrageThresholdPercent;
    /**
     * Meant to be a regex/substring matched against the boss bar's display
     * name, as an escape hatch for mods that offer no clean entity-type hook.
     *
     * <p><b>Nothing reads this yet.</b> It loads from the JSON and then sits
     * here; no bundled profile sets it, and the boss detection never looks at
     * it. Setting it in a profile currently does nothing at all.
     */
    public String bossBarNamePattern;

    public RGBColor resolvedColor(RGBColor fallback) {
        return color != null ? RGBColor.fromHexOrDefault(color, fallback) : fallback;
    }

    /** Null means no enrage colour, and the caller checks for it and skips the shift. */
    public RGBColor resolvedEnrageColor() {
        return enrageColor != null ? RGBColor.fromHexOrDefault(enrageColor, null) : null;
    }

    public static Map<String, BossProfile> loadAll() {
        return JsonProfileLoader.load("/everythingrgbprofile_defaults/boss_profiles.json",
                RGBProfileFiles.bossProfilesFile(), BossProfile.class);
    }
}
