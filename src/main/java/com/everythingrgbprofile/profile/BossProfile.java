package com.everythingrgbprofile.profile;

import com.everythingrgbprofile.RGBProfileFiles;
import com.everythingrgbprofile.color.RGBColor;

import java.util.Map;

/** One entry in {@code boss_profiles.json}. Gson-populated, same as {@link BiomeProfile}. */
public final class BossProfile {
    public String color;
    public String pattern = "pulse-medium"; // bosses pulse by default; scenery shimmers
    public String enrageColor;
    /**
     * Below this percentage the colour starts shifting toward
     * {@code enrageColor}. Boxed Integer, not int, specifically so null means
     * "this boss has no enrage phase" — a primitive would need a magic
     * sentinel like -1 and everyone would forget about it.
     */
    public Integer enrageThresholdPercent;
    /**
     * Regex/substring matched against the boss bar's display name.
     *
     * <p>The escape hatch for mods that offer no clean entity-type hook —
     * which, in a 600-mod pack, is a lot of them. Matching on display text is
     * fragile and locale-dependent and we'd rather not, but "fragile match" is
     * strictly better than "no lighting at all for half your bosses".
     */
    public String bossBarNamePattern;

    public RGBColor resolvedColor(RGBColor fallback) {
        return color != null ? RGBColor.fromHexOrDefault(color, fallback) : fallback;
    }

    /** Null means no enrage colour — the caller checks for it and skips the shift. */
    public RGBColor resolvedEnrageColor() {
        return enrageColor != null ? RGBColor.fromHexOrDefault(enrageColor, null) : null;
    }

    public static Map<String, BossProfile> loadAll() {
        return JsonProfileLoader.load("/everythingrgbprofile_defaults/boss_profiles.json",
                RGBProfileFiles.bossProfilesFile(), BossProfile.class);
    }
}
