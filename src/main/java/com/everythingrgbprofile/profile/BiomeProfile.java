package com.everythingrgbprofile.profile;

import com.everythingrgbprofile.RGBProfileFiles;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.pattern.PatternParams;

import java.util.Map;

/**
 * One entry in {@code biome_profiles.json}. Gson fills these
 * fields in directly, which is why they're public and mutable rather than a
 * nice immutable record — Gson wants a no-arg constructor and field access,
 * and fighting it here would buy nothing.
 *
 * <p>Field defaults double as the "user omitted this" defaults, since Gson
 * leaves untouched anything the JSON doesn't mention. So {@code pattern}
 * defaults to {@code shimmer} simply by being initialised to it — and the
 * bundled entries either omit it (implying shimmer) or name
 * {@code pulse-slow}, {@code drift-particle} or {@code twinkle-particle}.
 *
 * <p>{@code preset} only means anything for the two particle patterns
 * ({@code petal-drift}, {@code firefly-glow}, and friends). Setting it on a
 * shimmer entry is harmless and does nothing.
 */
public final class BiomeProfile {
    public String color;
    public String accentColor;
    public String pattern = "shimmer"; // the default, expressed as an initialiser
    public String preset;
    public Map<String, Object> patternParams;

    /** Parses the hex, or hands back {@code fallback} if it's missing or malformed. */
    public RGBColor resolvedColor(RGBColor fallback) {
        return color != null ? RGBColor.fromHexOrDefault(color, fallback) : fallback;
    }

    /**
     * The highlight colour, for patterns that draw two things at once.
     *
     * <p>Null when the entry doesn't set one, and that null is meaningful:
     * {@code PatternContext.resolvedAccentColor()} then derives a lightened
     * base instead, which is the behaviour every profile had before this field
     * existed. So adding it changed nothing for entries that ignore it.
     *
     * <p>It earns its keep on dark biomes. Deriving an accent by lightening
     * {@code #10182B} gives a washed-out grey, because lightening toward white
     * strips the hue out of a colour that had very little to begin with — the
     * Deep Dark needs a genuinely cyan glint, and the only way to get one is
     * to say so.
     */
    public RGBColor resolvedAccentColor() {
        return accentColor != null ? RGBColor.fromHexOrDefault(accentColor, null) : null;
    }

    /** EMPTY rather than null, so callers never have to null-check params. */
    public PatternParams resolvedParams() {
        return patternParams != null ? new PatternParams(patternParams) : PatternParams.EMPTY;
    }

    public static Map<String, BiomeProfile> loadAll() {
        return JsonProfileLoader.load("/everythingrgbprofile_defaults/biome_profiles.json",
                RGBProfileFiles.biomeProfilesFile(), BiomeProfile.class);
    }
}
