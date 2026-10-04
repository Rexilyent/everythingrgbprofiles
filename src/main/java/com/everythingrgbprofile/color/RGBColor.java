package com.everythingrgbprofile.color;

/**
 * An immutable 8-bit RGB triple and the handful of blend helpers this mod
 * actually uses. That's it. That's the class.
 *
 * <p>Before anybody asks: no, this is not colour-managed. No sRGB transfer
 * function, no linear light, no CIELAB, no perceptual anything. It lerps in
 * gamma space like it's 1998 and it sleeps fine at night.
 *
 * <p>Which is <b>correct here</b>, because the output device is not a monitor.
 * It is a keyboard with RGB LEDs sitting behind chunky plastic, driven by a
 * vendor SDK that takes 0-255 per channel and then does its own completely
 * opaque thing to the numbers anyway. Proper linear-light blending would buy
 * us a pile of extra maths and a pow() per channel per LED per frame, in
 * exchange for a difference nobody can see on a Corsair keycap. We are
 * colouring buttons here, not grading a film.
 */
public record RGBColor(int r, int g, int b) {

    public static final RGBColor BLACK = new RGBColor(0, 0, 0);
    public static final RGBColor WHITE = new RGBColor(255, 255, 255);

    /**
     * Compact constructor that clamps on the way in, so there is no way for an
     * instance of this record to exist holding an out-of-range channel. Every
     * helper below then gets to do arithmetic without a single defensive
     * check, because the type itself already guarantees it. This is the entire
     * point of records and it is genuinely lovely.
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
     * <p>This one throws on purpose. It is the strict version, for callers who
     * genuinely do want to find out they were handed garbage. If you are
     * reading anything a human might have edited, you want
     * {@link #fromHexOrDefault} instead.
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
     * <p>Used everywhere this mod reads user-editable JSON or TOML, under the
     * standing rule that a malformed edit must never crash anything. Somebody
     * is eventually going to type {@code "#GGGGGG"} or drop a digit, and the
     * correct response to that is one slightly-wrong key colour. Not a crash
     * report titled "mod broke my game" that turns out to be a typo they made.
     *
     * <p>Note that it swallows the exception without logging, which is
     * deliberate. This runs per-entry while profiles load, and the
     * <i>caller</i> is the one holding the context worth logging: which file,
     * which key. Logging in here would just emit "bad hex" over and over with
     * no indication of whose hex it was, which helps nobody.
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

    /** Dims toward black. 0 is fully off, 1 leaves it alone. */
    public RGBColor scaled(double brightness) {
        double c = Math.max(0.0, Math.min(1.0, brightness));
        return new RGBColor((int) Math.round(r * c), (int) Math.round(g * c), (int) Math.round(b * c));
    }

    /**
     * Straight linear interpolation toward {@code other}, t in [0,1]. Clamped,
     * so a caller with a slightly-overshooting eased value can't accidentally
     * extrapolate past the endpoint and produce a colour nobody asked for.
     *
     * <p>This is comfortably the most-called method in the whole mod. Every
     * crossfade, every pattern gradient and every alpha composite bottoms out
     * right here, so keep it boring.
     */
    public RGBColor lerp(RGBColor other, double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return new RGBColor(
                (int) Math.round(r + (other.r - r) * c),
                (int) Math.round(g + (other.g - g) * c),
                (int) Math.round(b + (other.b - b) * c)
        );
    }

    /** Tints toward white. Used to auto-invent an accent colour when nobody supplied one. */
    public RGBColor lightened(double amount) {
        return lerp(WHITE, amount);
    }

    /**
     * Invents a stable, decent-looking colour out of any string key, which in
     * practice means a dimension or boss id. This is the fallback covering
     * every dimension and boss nobody ever hand-authored a profile for, so some
     * random person's custom dimension still gets a consistent identity of its
     * own instead of defaulting to grey. (Biomes get theirs a different way,
     * from the biome's own colours; see ClientEventHandlers.deriveBiomeColor.)
     *
     * <h2>Why a hand-rolled FNV-1a and not {@link String#hashCode()}</h2>
     * {@code String.hashCode()} genuinely is specified in the Javadoc and it
     * genuinely is stable in practice. Relying on it here is still the wrong
     * call, because the requirement is not "stable within one JVM run". The
     * requirement is "the Twilight Forest is the same green it was last
     * Tuesday, and the same green on your friend's machine, on a different
     * JDK, forever". Betting a user-visible value on a platform hash has no
     * upside at all. FNV-1a is nine lines. We wrote the nine lines.
     */
    public static RGBColor deterministicFromKey(String key) {
        long hash = fnv1a(key);
        // Hue comes straight off the hash, but saturation and value get PINNED
        // to narrow mid-range bands rather than being hashed freely across
        // 0..1.
        //
        // Free-range S/V means some dimensions roll near-black, some roll
        // near-white, and some roll a washed-out pastel. All three read as
        // "the mod is broken" rather than as a deliberate colour choice.
        // Clamping to 0.55-0.85 saturation and 0.55-0.80 value guarantees
        // every generated colour is saturated enough to look intentional on an
        // LED.
        //
        // Note what this does NOT do: hue is still the full 0-360, so a
        // generated dimension absolutely can land on the same fiery orange as
        // the Nether or the same pale gold as the End. Nothing here reserves
        // vanilla's hues, and a previous version of this comment claimed it
        // did. If two dimensions ever need to be told apart at a glance, give
        // one of them a real entry in dimension_profiles.json.
        float hue = (Math.abs(hash) % 360) / 360f;
        float saturation = 0.55f + (Math.abs(hash >> 8) % 30) / 100f; // 0.55–0.85
        float value = 0.55f + (Math.abs(hash >> 16) % 25) / 100f;     // 0.55–0.80
        // Shifted slices of the same hash instead of three separate hashes.
        // Different bits, so hue, saturation and value don't end up correlated
        // with each other, and it's one pass over the string rather than
        // three. Free win, take it.
        int argb = java.awt.Color.HSBtoRGB(hue, saturation, value);
        return new RGBColor((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
    }

    /**
     * FNV-1a, 64-bit. Textbook apart from one thing: it hashes UTF-16 chars
     * rather than bytes, which is identical for the plain-ASCII ids it gets
     * fed. Zero cleverness, aggressively boring on purpose. The entire value proposition of this method is that
     * it returns the same number on every machine until the heat death of the
     * universe, and cleverness is how you lose that.
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
