package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Pale Garden's other scene, and the creepier one: nothing but grey fog,
 * and things in it looking at you. The calmer option is
 * {@link PaleGardenPattern}; this one is opt-in, with
 * {@code "pattern": "creaking"} in biome_profiles.json.
 *
 * <h2>The fog</h2>
 * Two banks of it rolling across the board at different speeds, one each
 * way, so it thickens and thins and never settles into a loop you can spot.
 * No trees, no floor. The fog is the whole set.
 *
 * <h2>The one coming for you</h2>
 * A pair of orange eyes shows up in the fog. They hold. They go. They come
 * back a bit closer: wider apart, brighter, a dark shape in the fog under them
 * that wasn't there before. Four times, nearer each time, then nothing for a
 * while, then it starts again from somewhere else.
 *
 * <p>That's the Creaking's one trick, and it's real: it can't move while a
 * player is looking at it ({@code checkCanMove} runs a look check against
 * every nearby player). So you see it, it's frozen, you look away, it isn't.
 * On a keyboard you can't look away from, the gaps between appearances do the
 * looking away for you. The eyes are orange because the real ones glow
 * whenever it's active ({@code CreakingRenderer} draws the eye layer off
 * {@code isActive}).
 *
 * <h2>The others</h2>
 * A few more pairs blink on further back, here and there, hold for a second
 * or two and go. Those never come any closer. They just watch.
 *
 * <p>The real Creaking only exists at night: a heart spawns one when it's
 * natural night and removes it once it isn't. This ignores that completely,
 * on purpose. A version that followed the clock spent the whole day as fog
 * doing nothing, which for half of every Minecraft day is a keyboard that
 * looks broken. So it's always night in here, as far as the eyes care.
 *
 * <p>Fog colour from ColorPalette, eyes from the profile accent. Not checked
 * on real hardware yet.
 */
public final class CreakingPattern implements Pattern {

    /** The one preset, same as the garden. */
    public static CreakingPattern paleGarden() {
        return new CreakingPattern(47);
    }

    private final int seed;

    private CreakingPattern(int seed) {
        this.seed = seed;
    }

    // --- the fog -------------------------------------------------------

    /** The fog at its thickest. Leaves headroom so the eyes are always the brightest thing on the board. */
    private static final double FOG_LEVEL = 0.55;
    /** And at its thinnest. Not zero, or the board has holes in it instead of gaps in fog. */
    private static final double FOG_FLOOR = 0.12;
    /** Keys per second for each bank. Opposite signs, so they slide through each other. */
    private static final double FOG_DRIFT_NEAR = 0.6, FOG_DRIFT_FAR = -0.25;

    // --- the one coming for you ----------------------------------------

    /** Appearances per approach. The last one is the close one. */
    private static final int STEPS = 4;
    /** One appearance plus the gap after it. */
    private static final double STEP_SECONDS = 6.5;
    private static final double FADE_IN = 0.6, HOLD = 2.4, LAST_HOLD = 3.4, FADE_OUT = 0.35;
    /** Quiet between one approach and the next. */
    private static final double REST_SECONDS = 12.0;
    /** Grace after the scene starts, so it doesn't arrive the instant you walk in. */
    private static final double FIRST_DELAY = 5.0;
    /** Eye height, in rows up from under the bottom row: far, then near. On a six-row board, about mid-height. */
    private static final double EYE_ELEV_FAR = 3.2, EYE_ELEV_NEAR = 2.7;
    /** Gap between the two eyes in keys, far then near. Closer things are bigger, it turns out. */
    private static final double EYE_GAP_FAR = 1.3, EYE_GAP_NEAR = 2.1;
    /** Glow spilling onto the keys beside each eye at its closest. None at all when it is far off. */
    private static final double EYE_SPILL = 0.3;
    /** How much fog sits between you and it, far then near. */
    private static final double EYE_FOG_FAR = 0.55, EYE_FOG_NEAR = 0.0;
    /** How much fog its body blots out. A dark shape, so the fog is the only way to draw it. */
    private static final double BODY_SHADOW = 0.75;

    // --- the others ----------------------------------------------------

    /** How many other pairs of eyes can be out there at once. Not many. */
    private static final int WATCHERS = 3;
    /** Each one gets its own cycle in this range, so they never fall into step. */
    private static final double WATCHER_PERIOD_MIN = 9.0, WATCHER_PERIOD_MAX = 17.0;
    /** Odds a watcher actually shows up in a given cycle. The empty cycles are half the effect. */
    private static final double WATCHER_CHANCE = 0.55;
    private static final double WATCHER_FADE_IN = 0.5, WATCHER_FADE_OUT = 0.3;
    private static final double WATCHER_HOLD_MIN = 1.2, WATCHER_HOLD_MAX = 2.6;
    /** Dimmer than the one coming for you, and always at the far end's amount of fog. */
    private static final double WATCHER_LEVEL = 0.7;
    /** Eye height, same units as EYE_ELEV_FAR. Most of the board, since there's no canopy or floor to dodge any more. */
    private static final double WATCHER_ELEV_LOW = 1.6, WATCHER_ELEV_HIGH = 4.4;

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor glow = ctx.resolvedAccentColor();

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        double lastRow = Math.max(1, (maxY - minY) / rowStep);
        double widthKeys = maxX / keyWidth;
        double boardHeight = lastRow + 1;
        double since = t - FIRST_DELAY;

        // --- where it is, if it's anywhere -----------------------------
        double eyeAmt = 0, eyeX = 0, eyeE = 0, eyeGap = 0, eyeFog = 0, near = 0;
        if (since >= 0) {
            double approach = STEPS * STEP_SECONDS + REST_SECONDS;
            int run = (int) Math.floor(since / approach);
            double inRun = since - run * approach;
            int step = (int) Math.floor(inRun / STEP_SECONDS);
            if (step < STEPS) {
                double age = inRun - step * STEP_SECONDS;
                double hold = step == STEPS - 1 ? LAST_HOLD : HOLD;
                eyeAmt = smoothstep(0, FADE_IN, age) * smoothstep(FADE_IN + hold + FADE_OUT, FADE_IN + hold, age);
                near = step / (double) (STEPS - 1);
                // From somewhere out to one side, ending up somewhere in the
                // middle of the board. Each run picks fresh.
                double startX = lerp(2, widthKeys - 2, hash(run, 50));
                double endX = widthKeys * lerp(0.35, 0.65, hash(run, 52));
                eyeX = lerp(startX, endX, near);
                eyeE = lerp(EYE_ELEV_FAR, EYE_ELEV_NEAR, near) * boardHeight / 6.0;
                eyeGap = lerp(EYE_GAP_FAR, EYE_GAP_NEAR, near);
                eyeFog = lerp(EYE_FOG_FAR, EYE_FOG_NEAR, near);
            }
        }

        // Each eye is snapped to one real key, both in the same row. Drawn as
        // two soft blobs instead, keyboard rows being staggered meant a pair
        // sitting between rows lit one key in each and came out as a diagonal
        // smudge. Eyes are level. That's most of what makes them eyes.
        KeyGrid.LedRef leftEye = null, rightEye = null;
        double leftCol = 0, rightCol = 0, eyeRow = 0;
        if (eyeAmt > 0) {
            Eyes at = snapEyes(grid, minY, rowStep, keyWidth, Math.round(lastRow + 0.5 - eyeE), eyeX, eyeGap);
            if (at == null) {
                eyeAmt = 0;
            } else {
                leftEye = at.left();
                rightEye = at.right();
                leftCol = at.leftCol();
                rightCol = at.rightCol();
                eyeRow = at.row();
            }
        }

        // --- the others ------------------------------------------------
        // Each one runs its own clock and skips some of its turns, so there's
        // no rhythm to learn. They're there, further off, for a second or
        // two, and then they aren't, which is somehow worse.
        List<Watcher> watchers = new ArrayList<>();
        for (int i = 0; i < WATCHERS; i++) {
            double period = lerp(WATCHER_PERIOD_MIN, WATCHER_PERIOD_MAX, hash(i, 60));
            double local = since - hash(i, 61) * period;
            if (local < 0) continue;
            int turn = (int) Math.floor(local / period);
            double age = local - turn * period;
            int roll = turn * WATCHERS + i;
            if (hash(roll, 62) > WATCHER_CHANCE) continue;
            double hold = lerp(WATCHER_HOLD_MIN, WATCHER_HOLD_MAX, hash(roll, 63));
            double amt = smoothstep(0, WATCHER_FADE_IN, age)
                    * smoothstep(WATCHER_FADE_IN + hold + WATCHER_FADE_OUT, WATCHER_FADE_IN + hold, age);
            if (amt <= 0) continue;

            // Not on top of the one that's coming, or another pair, or it's
            // just a brighter smear.
            double x = lerp(1.5, widthKeys - 1.5, hash(roll, 64));
            if (eyeAmt > 0 && Math.abs(x - eyeX) < 4) continue;
            boolean crowded = false;
            for (Watcher w : watchers) crowded |= Math.abs(x - w.x()) < 3;
            if (crowded) continue;

            double e = lerp(WATCHER_ELEV_LOW, WATCHER_ELEV_HIGH, hash(roll, 65)) * boardHeight / 6.0;
            Eyes at = snapEyes(grid, minY, rowStep, keyWidth, Math.round(lastRow + 0.5 - e), x, EYE_GAP_FAR);
            if (at != null) watchers.add(new Watcher(at, x, amt * WATCHER_LEVEL));
        }

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double rowF = (key.y() - minY) / rowStep;
            double elev = lastRow + 0.5 - rowF;
            KeyGrid.LedRef ref = key.ref();

            // Two banks, the near one big and quick, the far one finer and
            // going the other way. Averaged rather than multiplied, so where
            // one thins the other usually still covers.
            double nearBank = valueNoise((col - FOG_DRIFT_NEAR * t) * 0.28 + seed, elev * 0.45 + t * 0.03);
            double farBank = valueNoise((col - FOG_DRIFT_FAR * t) * 0.5 + seed * 3, elev * 0.7 - t * 0.02 + 40);
            double fog = smoothstep(0.2, 0.8, 0.6 * nearBank + 0.4 * farBank);

            // Its body: a tall dark shape under the eyes, cut out of the fog.
            // Nothing is drawn for it; there's just less fog where it stands.
            double shadow = 0;
            if (eyeAmt > 0) {
                double bodyW = lerp(0.8, 1.4, near), bodyH = lerp(1.5, 2.6, near) * boardHeight / 6.0;
                double bx = (col - eyeX) / bodyW, be = (elev - (eyeE - bodyH * 0.55)) / bodyH;
                shadow = BODY_SHADOW * eyeAmt * smoothstep(1.0, 0.6, Math.hypot(bx, be));
            }

            budget.add(ref, ColorPalette.CREAKING_FOG, lerp(FOG_FLOOR, FOG_LEVEL, fog) * (1 - shadow));

            // --- the eyes ----------------------------------------------
            if (eyeAmt > 0) {
                double e;
                if (ref.equals(leftEye) || ref.equals(rightEye)) {
                    e = 1;
                } else if (Math.abs(rowF - eyeRow) < 0.5) {
                    // A little spill onto the keys either side, and only once
                    // it's close. Far away it's two points and nothing else.
                    double d = Math.min(Math.abs(col - leftCol), Math.abs(col - rightCol));
                    e = EYE_SPILL * near * smoothstep(1.4, 0.7, d);
                } else {
                    e = 0;
                }
                if (e > 0) {
                    double through = 1 - eyeFog * fog;
                    budget.add(ref, glow, eyeAmt * e * through);
                    budget.add(ref, ColorPalette.EYEBLOSSOM_HOT, eyeAmt * e * through * 0.3 * near);
                }
            }

            // The far-off ones: two keys each, no spill, no body, full far fog.
            for (Watcher w : watchers) {
                if (ref.equals(w.at().left()) || ref.equals(w.at().right())) {
                    budget.add(ref, glow, w.amt() * (1 - EYE_FOG_FAR * fog));
                }
            }
        }

        return budget.resolve();
    }

    /** A pair of eyes pinned to two real keys in one row. */
    private record Eyes(KeyGrid.LedRef left, KeyGrid.LedRef right, double leftCol, double rightCol, double row) {}

    /** One of the far-off pairs this frame: where, roughly how far across, and how lit. */
    private record Watcher(Eyes at, double x, double amt) {}

    /**
     * The two keys in {@code row} nearest to either side of {@code x}, the
     * right one strictly right of the left one. Null when the row can't fit
     * a pair there, which near the board's edges it sometimes can't.
     */
    private static Eyes snapEyes(KeyGrid grid, double minY, double rowStep, double keyWidth,
                                 double row, double x, double gap) {
        double wantL = x - gap / 2, wantR = x + gap / 2;
        KeyGrid.LedRef left = null, right = null;
        double leftCol = 0, rightCol = 0;
        double bestL = Double.MAX_VALUE, bestR = Double.MAX_VALUE;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (Math.abs((key.y() - minY) / rowStep - row) > 0.5) continue;
            double col = key.x() / keyWidth;
            if (Math.abs(col - wantL) < bestL) {
                bestL = Math.abs(col - wantL);
                left = key.ref();
                leftCol = col;
            }
        }
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (Math.abs((key.y() - minY) / rowStep - row) > 0.5 || key.ref().equals(left)) continue;
            double col = key.x() / keyWidth;
            if (col > leftCol && Math.abs(col - wantR) < bestR) {
                bestR = Math.abs(col - wantR);
                right = key.ref();
                rightCol = col;
            }
        }
        return left == null || right == null ? null : new Eyes(left, right, leftCol, rightCol, row);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private double hash(int n, int salt) {
        return latticeHash(n * 7919 + seed * 131, salt * 104729 + 17);
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double v = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return v * v * (3 - 2 * v);
    }

    /** Same lattice hash the canopy and the swamp use; see CanopyDapplePattern for the notes. */
    private static double latticeHash(int x, int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >> 13)) * 1274126177;
        return ((n ^ (n >> 16)) & 0x7fffffff) / (double) 0x7fffffff;
    }

    private static double valueNoise(double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        double sx = fx * fx * (3 - 2 * fx);
        double sy = fy * fy * (3 - 2 * fy);
        double top = lerp(latticeHash(x0, y0), latticeHash(x0 + 1, y0), sx);
        double bottom = lerp(latticeHash(x0, y0 + 1), latticeHash(x0 + 1, y0 + 1), sx);
        return lerp(top, bottom, sy);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }
}
