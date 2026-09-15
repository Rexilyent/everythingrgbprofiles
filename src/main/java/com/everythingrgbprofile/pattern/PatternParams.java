package com.everythingrgbprofile.pattern;

import java.util.HashMap;
import java.util.Map;

/**
 * A tiny typed window onto the freeform {@code patternParams} blob that
 * profile JSON entries can carry — {@code spiralRotations},
 * {@code spiralArmCount}, {@code twinkleCount}, {@code particleCount},
 * {@code direction}, and friends.
 *
 * <h2>Why everything has a default</h2>
 * Because the patterns are built as "one parameterised engine" rather than "a
 * subclass per preset", and that choice only pays off if a profile with zero
 * overrides still animates perfectly. Otherwise every new biome someone adds
 * needs a complete parameter set copy-pasted from a working one, and the "just
 * tweak one number" workflow dies immediately.
 *
 * <p>So every getter takes a default and returns it for missing keys, wrong
 * types, nulls, and anything else the JSON throws. {@code {}} is a completely
 * valid params object.
 *
 * <p>No validation, no schema, no warnings on unknown keys — deliberately.
 * This reads hand-edited files; being strict here means a stray key someone
 * added while experimenting bricks their whole profile. Unknown keys are
 * ignored and unparseable values fall back, silently and on purpose.
 */
public final class PatternParams {

    /** The no-overrides case. Shared singleton — it's immutable in practice. */
    public static final PatternParams EMPTY = new PatternParams(Map.of());

    private final Map<String, Object> raw;

    /**
     * Defensive copy: the caller's map came from a JSON parser and we have no
     * idea what it plans to do with it afterwards. Patterns read this from the
     * worker thread while profiles get reloaded from elsewhere, so a shared
     * mutable map is a ConcurrentModificationException with a delay fuse.
     */
    public PatternParams(Map<String, Object> raw) {
        this.raw = new HashMap<>(raw);
    }

    /**
     * {@code instanceof Number} rather than a cast to Double, because JSON
     * parsers are wildly inconsistent about whether {@code 3} arrives as an
     * Integer, a Long, or a Double. This accepts all of them; a cast would
     * ClassCastException on two thirds of them.
     */
    public double getDouble(String key, double def) {
        Object v = raw.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    /** Same deal. {@code 3.0} in the JSON truncates to 3 rather than exploding. */
    public int getInt(String key, int def) {
        Object v = raw.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    public String getString(String key, String def) {
        Object v = raw.get(key);
        return v instanceof String s ? s : def;
    }
}
