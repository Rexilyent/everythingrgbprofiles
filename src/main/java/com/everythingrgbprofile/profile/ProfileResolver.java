package com.everythingrgbprofile.profile;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The "specific first, generic after, invent something if all else fails"
 * lookup shared by biomes, bosses and dimensions.
 *
 * <ol>
 *   <li><b>Exact id</b> — {@code twilightforest:dark_forest}. Someone
 *       hand-authored this. Use it.</li>
 *   <li><b>Mod-wide wildcard</b> — {@code twilightforest:*}. Nobody wrote an
 *       entry for this specific thing, but somebody did say "everything from
 *       this mod should look like X". Honour that.</li>
 *   <li><b>Derived fallback</b> — invent a stable colour from the id (see
 *       {@code RGBColor.deterministicFromKey}). Only when
 *       {@code fallbackEnabled}.</li>
 *   <li><b>Nothing.</b> Caller decides what "no effect" means.</li>
 * </ol>
 *
 * <h2>Why the wildcard tier earns its keep</h2>
 * The target is a 600-mod pack. Hand-authoring an entry for every biome in
 * that is not happening, by anyone, ever. The wildcard means a mod author or
 * pack maintainer writes <i>one</i> line and every biome that mod adds gets a
 * coherent identity — and then step 3 catches whatever's left, so nothing is
 * ever colourless. Full coverage from three lines of lookup.
 *
 * <p>Generic in T because biome, boss and dimension profiles are unrelated
 * types that need identical resolution. Writing it three times would be three
 * chances to make them subtly disagree.
 */
public final class ProfileResolver {

    public static <T> T resolve(Map<String, T> profiles, String resourceLocationId, boolean fallbackEnabled, Supplier<T> derivedFallback) {
        T exact = profiles.get(resourceLocationId);
        if (exact != null) return exact;

        // colon > 0, not >= 0: a leading colon means an empty mod id, which is
        // malformed and would produce a nonsense ":*" lookup.
        int colon = resourceLocationId.indexOf(':');
        if (colon > 0) {
            String modId = resourceLocationId.substring(0, colon);
            T wildcard = profiles.get(modId + ":*");
            if (wildcard != null) return wildcard;
        }

        // Supplier rather than a plain value: deriving a fallback can mean
        // hashing strings and doing HSB conversion, and the overwhelmingly
        // common case is an exact hit that never needs it. Lazy by design.
        if (fallbackEnabled && derivedFallback != null) {
            return derivedFallback.get();
        }
        return null;
    }

    private ProfileResolver() {
    }
}
