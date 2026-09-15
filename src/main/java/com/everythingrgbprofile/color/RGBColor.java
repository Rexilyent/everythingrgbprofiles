package com.everythingrgbprofile.color;

/**
 * An immutable 8-bit RGB triple and the four blend helpers this mod actually
 * uses. That's it. That's the class.
 *
 * <p>Before anyone asks: no, this is not colour-managed. There is no sRGB
 * transfer function, no linear light, no CIELAB, no perceptual anything. It
 * lerps in gamma space like it's 1998.
 *
 * <p>And that is <b>correct here</b>, because the output device is not a
 * monitor — it's a keyboard with RGB LEDs behind chunky plastic, driven by a
 * vendor SDK that takes 0–255 per channel and does its own opaque thing to
 * them anyway. Doing proper linear-light blending would add a pile of maths
 * and a pow() per channel per LED per frame in exchange for a difference
 * nobody can see on a Corsair keycap. We're colouring buttons, not grading a
 * film.
 */
public record RGBColor(int r, int g, int b) {

    public static final RGBColor BLACK = new RGBColor(0, 0, 0);
    public static final RGBColor WHITE = new RGBColor(255, 255, 255);

    /**
     * Compact constructor that clamps on the way in, so no instance of this
     * record can ever hold an out-of-range channel. Every helper below gets to
     * do maths without a defensive check, because the type itself already
     * guarantees the invariant. This is the whole point of records.
     */
    public RGBColor {
        r = clamp(r);
        g = clamp(g);
        b = clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /**
     * Parses {@code "#RRGGBB"} or {@code "RRGGBB"}. Throws on anything else.
     *
     * <p>This one throws on purpose — it's the strict version, for callers who
     * genuinely want to know they were handed garbage. If you're reading a
     * user-editable file, you want {@link #fromHexOrDefault} instead.
     */
    public static RGBColor fromHex(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) {
            throw new IllegalArgumentException("Expected 6 hex digits, got: " + hex);
        }
        int r = Integer.parseInt(h.substring(0, 2), 16);
        int g = Integer.parseInt(h.substring(2, 4), 16);
        int b = Integer.parseInt(h.substring(4, 6), 16);
        return new RGBColor(r, g, b);
    }

    /**
     * The forgiving version: parse it, and if it's malformed just quietly use
     * {@code fallback}.
     *
     * <p>Used everywhere we read user-editable JSON/TOML, on the standing rule
     * that a malformed user edit must never crash the mod. Somebody is going to type
     * {@code "#GGGGGG"} or leave off a digit, and the correct response to that
     * is one slightly-wrong keyboard colour, not a crash report titled "mod
     * broke my game" that turns out to be a typo in a config file.
     *
     * <p>Note it swallows the exception without logging. That's deliberate —
     * this runs per-entry while loading profiles, and the <i>caller</i> has the
     * context worth logging (which file, which key). Logging here would just
     * spam "bad hex" with no idea whose hex it was.
     */
    public static RGBColor fromHexOrDefault(String hex, RGBColor fallback) {
        try {
            return fromHex(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    public String toHex() {
        return String.format("#%02X%02X%02X", r, g, b);
    }

    /** Dims toward black. 0 = fully off, 1 = untouched. */
    public RGBColor scaled(double brightness) {
        double c = Math.max(0.0, Math.min(1.0, brightness));
        return new RGBColor((int) Math.round(r * c), (int) Math.round(g * c), (int) Math.round(b * c));
    }

    /**
     * Straight linear interpolation toward {@code other}, t in [0,1]. Clamped,
     * so a caller with a slightly-overshooting eased value can't accidentally
     * extrapolate past the endpoint and produce a colour nobody asked for.
     *
     * <p>This is the single most-called method in the mod — every crossfade,
     * every pattern gradient, every alpha composite bottoms out here.
     */
    public RGBColor lerp(RGBColor other, double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return new RGBColor(
                (int) Math.round(r + (other.r - r) * c),
                (int) Math.round(g + (other.g - g) * c),
                (int) Math.round(b + (other.b - b) * c)
        );
    }

    /** Tints toward white. Used to auto-invent an accent colour. */
    public RGBColor lightened(double amount) {
        return lerp(WHITE, amount);
    }

    /**
     * Invents a stable, decent-looking colour out of any string key — a
     * dimension id, usually. This is the fallback that covers biomes and
     * dimensions nobody has hand-authored a profile for: some rando's custom
     * dimension still gets its own consistent identity instead of defaulting
     * to grey.
     *
     * <h2>Why a hand-rolled FNV-1a instead of {@link String#hashCode()}</h2>
     * {@code String.hashCode()} is in fact specified in the Javadoc and stable
     * in practice. Relying on it is still the wrong call here, because the
     * requirement isn't "stable within a JVM run" — it's "the Twilight Forest
     * is the same shade of green it was last Tuesday, and on your friend's
     * machine, on a different JDK, forever." Depending on a platform hash for
     * a value users will notice changing is a bet with no upside. FNV-1a is
     * nine lines. We wrote the nine lines.
     */
    public static RGBColor deterministicFromKey(String key) {
        long hash = fnv1a(key);
        // Pick the hue from the hash, but PIN saturation and value to narrow
        // mid-range bands instead of hashing them freely across 0..1.
        //
        // Free-range S/V means some dimensions roll near-black, near-white, or
        // a washed-out pastel — all of which look like the mod is broken
        // rather than like a deliberate colour. Clamping to 0.55–0.85 sat and
        // 0.55–0.80 value guarantees every generated colour is saturated
        // enough to read as intentional on an LED, and keeps them clear of
        // vanilla's own Nether (fiery orange-red) and End (pale gold) so a
        // modded dimension never gets mistaken for a vanilla one at a glance.
        float hue = (Math.abs(hash) % 360) / 360f;
        float saturation = 0.55f + (Math.abs(hash >> 8) % 30) / 100f; // 0.55–0.85
        float value = 0.55f + (Math.abs(hash >> 16) % 25) / 100f;     // 0.55–0.80
        // Shifted slices of the same hash rather than three separate hashes:
        // different bits, so hue/sat/value aren't correlated, and it's one
        // pass over the string instead of three. Free win.
        int argb = java.awt.Color.HSBtoRGB(hue, saturation, value);
        return new RGBColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
    }

    /**
     * FNV-1a, 64-bit. Textbook implementation, no cleverness, deliberately
     * boring — the entire value proposition is that this produces the same
     * number on every machine until the heat death of the universe.
     */
    private static long fnv1a(String s) {
        long hash = 0xcbf29ce484222325L; // FNV offset basis
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x100000001b3L;      // FNV prime
        }
        return hash;
    }
}
