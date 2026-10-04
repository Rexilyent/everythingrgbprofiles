package com.everythingrgbprofile.profile;

import com.everythingrgbprofile.RGBProfileFiles;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.pattern.PatternParams;

import java.util.Map;

/**
 * One entry in {@code biome_profiles.json}. Gson fills these fields in
 * directly, which is why they are public and mutable instead of a nice
 * immutable record. Gson wants a no-arg constructor and field access, and
 * fighting it here would buy us exactly nothing.
 *
 * <p>The field initialisers double as the "user left this out" defaults, since
 * Gson doesn't touch anything the JSON never mentions. So {@code pattern}
 * defaults to {@code shimmer} purely by being initialised to it. The bundled
 * entries all name one explicitly anyway; PatternFactory has the full list.
 *
 * <p>{@code preset} only means anything to the pattern families that take one
 * (the particle engines, canopy, windswept, strata and friends: again, see
 * PatternFactory). Putting it on a shimmer entry is harmless and does
 * absolutely nothing.
 */
public final class BiomeProfile {
    public String color;
    public String accentColor;
    public String pattern = "shimmer"; // the default, expressed as an initialiser
    public String preset;
    public Map<String, Object> patternParams;

    /** Parses the hex, or hands back {@code fallback} if it's missing or nonsense. */
    public RGBColor resolvedColor(RGBColor fallback) {
        return color != null ? RGBColor.fromHexOrDefault(color, fallback) : fallback;
    }

    /**
     * The highlight colour, for patterns that draw two things at once.
     *
     * <p>Null when the entry doesn't set one, and that null carries meaning:
     * {@code PatternContext.resolvedAccentColor()} derives a lightened base
     * instead, which is what every profile did before this field existed. So
     * adding it changed nothing whatsoever for entries that ignore it.
     *
     * <p>Where it earns its keep is dark biomes. Deriving an accent by
     * lightening a dark navy like {@code #10182B} gets you a washed-out grey, because
     * lightening toward white strips the hue out of a colour that barely had
     * any to begin with. The Deep Dark needs a properly cyan glint, and the
     * only way to get one is to come out and say so.
     */
    public RGBColor resolvedAccentColor() {
        return accentColor != null ? RGBColor.fromHexOrDefault(accentColor, null) : null;
    }

    /**
     * EMPTY rather than null, so no caller ever has to null-check params.
     * Currently uncalled: see {@link PatternParams} for why patternParams does
     * nothing yet.
     */
    public PatternParams resolvedParams() {
        return patternParams != null ? new PatternParams(patternParams) : PatternParams.EMPTY;
    }

    public static Map<String, BiomeProfile> loadAll() {
        return JsonProfileLoader.load("/everythingrgbprofile_defaults/biome_profiles.json",
                RGBProfileFiles.biomeProfilesFile(), BiomeProfile.class);
    }
}
