package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Flames rising up the board, for the burning alert. The drowning meter's
 * sibling: {@link WaterRisePattern} fills the board with water from the
 * bottom, and this sets it alight from the bottom instead.
 *
 * <p>Keys above the flames are left out of the output entirely, so the biome
 * keeps showing over them, exactly as it does above the waterline.
 *
 * <h2>What makes it fire rather than a red water level</h2>
 * Water has one surface and it is nearly flat. Fire has no surface at all: it
 * is tongues, each column reaching its own height and changing it several
 * times a second. So the top edge here is three sines on unrelated spatial
 * and temporal frequencies rather than one slow wave, which gives tongues
 * that lick upward and fall back out of step with their neighbours. The
 * colour runs the other way to water's too — hottest and brightest at the
 * base, reddening toward the tips — and a handful of sparks break off the top
 * and rise above it.
 *
 * <h2>Solid, not see-through</h2>
 * A key is either fire or biome, never a mix of the two. Water can be
 * translucent because blue over a biome still reads as water; fire cannot,
 * because red and orange blended into a green biome come out olive and
 * yellow-green, which is the colour of nothing burning. An earlier version
 * thinned the flames toward their tips, drew a faint glow above them, and
 * faded sparks by transparency, and over grassland all three put a greenish
 * haze on top of the fire. So everything here is drawn opaque, and
 * where something needs to flicker or fade it does so by getting darker
 * rather than more transparent.
 *
 * <h2>Why the level is eased here</h2>
 * The same reason as the water: the poll hands over a handful of fixed
 * heights, and easing toward them inside the render loop turns stepping into
 * fire, or out of lava, into flames that flare up or die down rather than
 * jump.
 */
public final class FlameRisePattern implements Pattern {

    /** How quickly the flames flare up to a higher level, and die down to a lower one. */
    private static final double RISE_TAU_SECONDS = 0.12;
    private static final double FALL_TAU_SECONDS = 0.45;

    /** Height of the tongues above the body of the fire, in board heights. */
    private static final double TONGUE_AMPLITUDE = 0.16;

    /**
     * Opacity of the flames: fully opaque, tips included, for the reason in
     * the class notes. The tips read as tips by colour and brightness instead.
     */
    private static final double FLAME_ALPHA = 1.0;
    /** Brightness of the tips relative to the base, so the fire still cools as it rises. */
    private static final double TIP_BRIGHTNESS = 0.7;

    private static final int SPARKS = 6;
    /** Seconds each spark takes to rise and fade, and how far it climbs, in board heights. */
    private static final double SPARK_LIFE_SECONDS = 0.9;
    private static final double SPARK_CLIMB = 0.35;

    private final double panicThreshold;

    private volatile double targetLevel = 0;
    private double displayedLevel = 0;
    private double lastElapsedMillis = 0;
    private boolean initialised = false;

    public FlameRisePattern(double panicThreshold) {
        this.panicThreshold = Math.max(0.0, Math.min(1.0, panicThreshold));
    }

    /** 0 = not burning, 1 = the whole board alight. Called from the worker thread. */
    public void setLevel(double level) {
        this.targetLevel = Math.max(0.0, Math.min(1.0, level));
    }

    /** Out at once, with no dying down: for a world going away, when there is nothing to fade into. */
    public void extinguish() {
        this.targetLevel = 0;
        this.initialised = false;
    }

    /** Whether there is still anything to draw, so the flames can die down after the fire goes out. */
    public boolean visible() {
        return targetLevel > 0.001 || (initialised && displayedLevel > 0.005);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        if (!initialised || elapsedMillis < lastElapsedMillis) {
            // Snapped to nothing rather than to the target, so catching fire
            // flares up from the bottom instead of appearing at full height.
            displayedLevel = 0;
            lastElapsedMillis = elapsedMillis;
            initialised = true;
        }
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;
        if (dt > 0) {
            // Fire catches fast and goes out slowly, so the two directions
            // ease at different rates.
            double tau = targetLevel > displayedLevel ? RISE_TAU_SECONDS : FALL_TAU_SECONDS;
            displayedLevel += (targetLevel - displayedLevel) * (1 - Math.exp(-dt / tau));
        }
        double level = displayedLevel;
        if (level <= 0.005) return Map.of();

        double seconds = elapsedMillis / 1000.0;
        RGBColor core = ctx.baseColor();
        RGBColor tip = ctx.resolvedAccentColor();

        // Small fires have small tongues: a flicker along the bottom row
        // should not throw flames halfway up the board.
        double amplitude = TONGUE_AMPLITUDE * Math.min(1.0, level * 2.5);

        // Panic, as for the water: the whole fire surges once it is high
        // enough to mean real trouble, which by default is only in lava.
        double panic = panicThreshold >= 1.0 ? 0.0
                : Math.max(0.0, (level - panicThreshold) / (1.0 - panicThreshold));
        // A surge in brightness, not in opacity, for the reason in the class notes.
        double panicPulse = panic <= 0 ? 1.0
                : 1.0 - panic * 0.3 * (0.5 + 0.5 * Math.sin(2 * Math.PI * 3.1 * seconds));

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            double x = key.x();
            // Three tongues' worth of motion per column. The frequencies share
            // no common period, so the edge never settles into a pattern the
            // eye can follow.
            double tongue = 0.55 * Math.sin(2 * Math.PI * (x * 3.1 + seconds * 1.3))
                    + 0.30 * Math.sin(2 * Math.PI * (x * 7.3 - seconds * 2.1) + 1.0)
                    + 0.15 * Math.sin(2 * Math.PI * (x * 13.0 + seconds * 3.7) + 2.3);
            double height = level + amplitude * (0.5 + 0.5 * tongue);
            // y is 0 at the top row and 1 at the bottom, so the tips sit at
            // 1 - height and the fire is everything below them.
            double tips = 1.0 - height;
            double depth = key.y() - tips;

            // Above the tips: not burning, and left out, so the biome keeps
            // this key untouched.
            if (depth < 0) continue;

            // 0 at the tips, 1 at the base: red and dimmer at the top, hot
            // and bright at the bottom.
            double heat = Math.min(1.0, depth / Math.max(0.15, height));
            double eased = heat * heat * (3 - 2 * heat);
            // A little per-key flicker inside the body, so it does not sit
            // there as a flat gradient between moving edges.
            double flicker = 0.85 + 0.15 * Math.sin(2 * Math.PI * (seconds * 5.3 + hash(key.ref()) * 7.0));
            double brightness = (TIP_BRIGHTNESS + (1 - TIP_BRIGHTNESS) * eased) * flicker * panicPulse;
            RGBColor color = tip.lerp(core, eased).scaled(brightness);
            out.put(key.ref(), new LayerPixel(color, FLAME_ALPHA));
        }

        // Sparks break off the top and climb above it. Each one's column and
        // timing is a function of its index and the clock, so they need no
        // state and never all rise together.
        double sparkLevel = Math.min(1.0, level * 3);
        for (int i = 0; i < SPARKS; i++) {
            double cycle = seconds / SPARK_LIFE_SECONDS + i * 0.37;
            long round = (long) Math.floor(cycle);
            double age = cycle - round;
            double sx = fraction((round * 0.618) + i * 0.1937);
            double sy = 1.0 - level - SPARK_CLIMB * age;
            double fade = (1 - age) * sparkLevel;
            if (sy < -0.05 || fade <= 0.01) continue;
            KeyGrid.LedPosition nearest = nearest(ctx.grid(), sx, sy);
            if (nearest == null) continue;
            // Only above the flames, where the key would otherwise be biome;
            // a spark inside the fire is just more fire. Solid, and fading by
            // cooling to a dim ember rather than going see-through.
            if (out.containsKey(nearest.ref())) continue;
            out.put(nearest.ref(), new LayerPixel(tip.lerp(core, 0.6).scaled(0.35 + 0.65 * fade), FLAME_ALPHA));
        }
        return out;
    }

    private static KeyGrid.LedPosition nearest(KeyGrid grid, double x, double y) {
        KeyGrid.LedPosition best = null;
        double bestD = 0.03;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            // Rows are further apart than columns in normalised units, so the
            // vertical distance is weighted down to find the key the spark is
            // actually over.
            double d = Math.hypot(key.x() - x, (key.y() - y) * 0.3);
            if (d < bestD) {
                bestD = d;
                best = key;
            }
        }
        return best;
    }

    private static double fraction(double v) {
        return v - Math.floor(v);
    }

    private static double hash(KeyGrid.LedRef ref) {
        long h = (ref.luid() * 2654435761L) ^ (ref.deviceId() == null ? 0 : ref.deviceId().hashCode());
        h ^= (h >>> 15);
        h *= 0x2C1B3C6DL;
        h ^= (h >>> 12);
        return (h & 0xFFFF) / 65536.0;
    }

}
