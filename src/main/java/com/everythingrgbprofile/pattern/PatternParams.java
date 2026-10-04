package com.everythingrgbprofile.pattern;

import java.util.HashMap;
import java.util.Map;

/**
 * A tiny typed window onto the freeform {@code patternParams} blob that a biome
 * profile entry can carry.
 *
 * <p><b>Nothing reads it yet.</b> No pattern calls any of the getters below,
 * {@code BiomeProfile.resolvedParams()} has no callers, and
 * {@code BiomeColorEffect} hands every pattern {@link #EMPTY}. So a
 * {@code patternParams} block in {@code biome_profiles.json} loads without
 * complaint and changes nothing. No bundled profile sets one. The plumbing
 * below is what it would go through once something does.
 *
 * <h2>Why absolutely everything has a default</h2>
 * Because the patterns are built as "one parameterised engine" rather than "a
 * subclass per preset", and that choice only pays off if a profile carrying
 * zero overrides still animates perfectly.
 *
 * <p>Get that wrong and every new biome anybody adds needs a complete
 * parameter set copy-pasted out of a working one, at which point the "just
 * tweak one number" workflow is dead and the whole design collapses back into
 * subclasses with extra steps.
 *
 * <p>So every getter takes a default and returns it for missing keys, wrong
 * types, nulls, and anything else the JSON throws. {@code {}} is a completely
 * valid params object.
 *
 * <p>No validation, no schema, and no warnings about unknown keys, all
 * deliberately. This reads hand-edited files, and being strict here means a
 * stray key somebody added while experimenting takes their entire profile down
 * with it.
 *
 * <p>Unknown keys are ignored and unparseable values fall back. Silently, and
 * on purpose.
 */
public final class PatternParams {

    /** The no-overrides case. A shared singleton, since it is immutable in practice. */
    public static final PatternParams EMPTY = new PatternParams(Map.of());

    private final Map<String, Object> raw;

    /**
     * Defensive copy, because the caller's map came out of a JSON parser and
     * nobody here knows what that parser intends to do with it afterwards.
     *
     * <p>Profiles are only loaded once today, so nothing currently mutates
     * that map behind a pattern's back. The copy is so that stays true if a
     * reload ever appears: patterns read this from the worker thread, and a
     * shared mutable map is a ConcurrentModificationException with a delay
     * fuse attached.
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
