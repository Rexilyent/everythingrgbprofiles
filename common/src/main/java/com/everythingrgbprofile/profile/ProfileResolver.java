package com.everythingrgbprofile.profile;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The "specific first, generic second, invent something if all else fails"
 * lookup, shared by biomes, bosses and dimensions.
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
 * The target here is a 600-mod pack. Hand-authoring an entry for every biome
 * in one of those is not happening, by anyone, ever, under any circumstances.
 * The wildcard means a mod author or a pack maintainer writes <i>one</i> line
 * and every biome that mod adds gets a coherent identity, and then step 3
 * mops up whatever is left so nothing is ever colourless. Full coverage out of
 * three lines of lookup.
 *
 * <p>Generic in T because biome, boss and dimension profiles are completely
 * unrelated types that need identical resolution. Writing this out three times
 * would be three separate chances to make them subtly disagree with each
 * other.
 */
public final class ProfileResolver {

    public static <T> T resolve(Map<String, T> profiles, String resourceLocationId, boolean fallbackEnabled, Supplier<T> derivedFallback) {
        T exact = profiles.get(resourceLocationId);
        if (exact != null) return exact;

        // colon > 0 rather than >= 0. A leading colon means an empty mod id,
        // which is malformed input, and letting it through produces a
        // nonsense ":*" lookup that could actually match something.
        int colon = resourceLocationId.indexOf(':');
        if (colon > 0) {
            String modId = resourceLocationId.substring(0, colon);
            T wildcard = profiles.get(modId + ":*");
            if (wildcard != null) return wildcard;
        }

        // Supplier rather than a plain value, because deriving a fallback
        // means hashing strings and doing an HSB conversion, and the
        // overwhelmingly common case is an exact hit that never needs any of
        // that. Lazy on purpose.
        if (fallbackEnabled && derivedFallback != null) {
            return derivedFallback.get();
        }
        return null;
    }

    private ProfileResolver() {
    }
}
