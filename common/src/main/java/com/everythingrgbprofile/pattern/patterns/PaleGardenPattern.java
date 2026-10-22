package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.NightAware;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Pale Garden, side on: pale oaks, moss hanging off them, and the
 * eyeblossoms on the floor that open when it gets dark. One of two scenes for
 * the biome; {@link CreakingPattern} is the other. Pick one in
 * biome_profiles.json with {@code "pattern": "pale-garden"} or
 * {@code "pattern": "creaking"}.
 *
 * <p>This biome used to get the derived-colour shimmer, which off a grass
 * colour this washed out came to a grey keyboard gently fizzing. That's the
 * exact one-colour-per-biome thing this mod exists to not do, and it was
 * doing it in the one biome where the colour is barely a colour.
 *
 * <h2>The scene</h2>
 * Canopy across the top in the profile colour. Thick dark trunks, because pale
 * oaks are two blocks wide and a one-key trunk reads as a fence post. Strands
 * of pale hanging moss off the underside of the leaves, swaying a little, each
 * its own length. Moss carpet along the bottom. Haze in the gaps, which is
 * what a dense forest with a white canopy mostly looks like from inside.
 *
 * <p>All of it tinted sage and silver rather than grey, which is a lie, but
 * a necessary one: true grey on an LED is a key that looks like it's dying.
 * The real textures run #6A7068 for the moss and #A0A69C at the brightest
 * leaf pixel, so the tint is a nudge, not a repaint.
 *
 * <h2>The thing that moves</h2>
 * The eyeblossoms. Closed, they're dim mauve dots in the moss. When night
 * comes they open, orange, one after another rather than all at once, and
 * that order is lifted straight from vanilla: when an eyeblossom flips, every
 * one still in the old state within 3 blocks sideways and 2 up or down gets
 * scheduled to follow, 5 to 10 ticks per block of distance later. So it ripples outward from wherever it started. Here
 * one random blossom goes first and the rest follow at 0.08 to 0.16 seconds
 * per key away from it. That's about a third of vanilla's pace if a key were
 * a block, and on purpose: a real ripple only has to cross a patch of
 * flowers, this one crosses a whole keyboard, and at the real speed the far
 * end was still shut eight seconds later. Each flip also throws a
 * little mote upward, orange when opening and grey when closing, because so
 * does the real one (its trail particle is #FC7812 one way and #5F5F5F the
 * other). At dawn the whole thing runs in reverse.
 *
 * <p>The rest of the scene dims toward night as well, so the orange actually
 * has something to stand out against.
 *
 * <p>"Night" is pushed in by {@code BiomeColorEffect} through
 * {@link NightAware}, and it's vanilla's own definition, storms included. See
 * {@code ClientEventHandlers.pollBiomeNight}. A tuner or anything else that
 * never calls {@link #setNight} gets a garden stuck in daytime, which still
 * looks like a garden.
 *
 * <p>None of these colours have been looked at on a real keyboard yet. If the
 * haze reads as "off" rather than "pale", that's the first thing to turn up.
 */
public final class PaleGardenPattern implements Pattern, NightAware {

    private static final double TAU = 2 * Math.PI;

    /** The one preset. The garden is one biome with one look, so this is a factory method mostly for symmetry. */
    public static PaleGardenPattern paleGarden() {
        return new PaleGardenPattern(29);
    }

    private final int seed;

    private PaleGardenPattern(int seed) {
        this.seed = seed;
    }

    // --- levels --------------------------------------------------------

    private static final double HAZE_LEVEL = 0.38;
    private static final double CANOPY_LEVEL = 0.85;
    private static final double TRUNK_LEVEL = 0.70;
    private static final double MOSS_LEVEL = 0.85;
    private static final double FLOOR_LEVEL = 0.65;
    /** Closed blossoms. Present, but you'd have to be looking for them. */
    private static final double CLOSED_LEVEL = 0.55;
    /** How dark everything but the flowers gets at night. Not black: it's a forest, not a void. */
    private static final double NIGHT_SCENE = 0.65;
    /** Seconds the scene takes to settle into night or back out of it. A time constant, so about three of these to get there. */
    private static final double NIGHT_TAU = 1.6;

    // --- moss ----------------------------------------------------------

    /** Keys the tip of a strand travels at full sway. The top stays pinned to the leaves. */
    private static final double MOSS_SWAY = 0.45;
    /** Seconds per sway. Slow. There's no wind under that canopy worth the name. */
    private static final double MOSS_SWAY_PERIOD = 5.3;

    // --- blossoms ------------------------------------------------------

    /** Seconds a single blossom takes to open or close once it's its turn. */
    private static final double BLOOM_SECONDS = 0.35;
    /** Seconds per key of distance from the first one to flip. Vanilla's 5-10 ticks per block, sped up; see the class doc. */
    private static final double RIPPLE_MIN = 0.08, RIPPLE_MAX = 0.16;
    private static final double MOTE_SECONDS = 1.1;
    /** How far a mote rises, in rows. The real particle goes somewhere between half a block and a block and a half. */
    private static final double MOTE_RISE = 1.4;

    // --- layout --------------------------------------------------------

    private KeyGrid layoutFor;
    /** {x, halfWidth}. */
    private final List<double[]> trunks = new ArrayList<>();
    /** {x, length in rows, sway phase}. */
    private final List<double[]> strands = new ArrayList<>();
    /** {x, elevation}. */
    private final List<double[]> blossoms = new ArrayList<>();

    // --- night state ---------------------------------------------------
    //
    // Stateful, unlike most of the scene. A blossom's turn to flip depends on
    // when night arrived, and night arrives from outside this class, so there
    // is nothing to recompute it from. Everything here lives on the worker
    // thread; setNight is the only thing that crosses in, hence the volatile.

    private volatile boolean nightTarget;
    /** What the blossoms are currently heading for. Lags nightTarget by at most one frame. */
    private boolean heading;
    private boolean initialised;
    private double lastT;
    private int flips;
    /** Smoothed 0..1, for the scene dimming. */
    private double nightAmt;
    private boolean[] open = new boolean[0];
    /** When each blossom last changed, in this pattern's seconds. */
    private double[] changedAt = new double[0];
    /** When each blossom is due to change, or NaN if it isn't. */
    private double[] dueAt = new double[0];

    @Override
    public void setNight(boolean night) {
        this.nightTarget = night;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor leaves = ctx.baseColor();
        RGBColor bloom = ctx.resolvedAccentColor();

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        double lastRow = Math.max(1, (maxY - minY) / rowStep);
        double widthKeys = maxX / keyWidth;
        double boardHeight = lastRow + 1;
        if (layoutFor != grid) rollLayout(grid, widthKeys, boardHeight);

        stepNight(t);
        double scene = lerp(1.0, NIGHT_SCENE, nightAmt);
        double sway = Math.sin(TAU * t / MOSS_SWAY_PERIOD);

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double rowF = (key.y() - minY) / rowStep;
            double elev = lastRow + 0.5 - rowF;
            KeyGrid.LedRef ref = key.ref();

            double canopyLow = canopyBottom(col, boardHeight);
            double floorTop = floorTop(col);
            double canopy = smoothstep(-0.3, 0.3, elev - canopyLow);
            double floor = smoothstep(-0.3, 0.3, floorTop - elev);

            double trunk = 0;
            for (double[] tr : trunks) {
                trunk = Math.max(trunk, smoothstep(tr[1] + 0.3, tr[1] - 0.1, Math.abs(col - tr[0])));
            }
            trunk *= (1 - canopy) * (1 - floor);

            // Moss hangs off the leaves, so it starts wherever the canopy's
            // underside is at that strand's column, and only the bottom of it
            // swings. The sway goes as depth to the power 1.5, so the top
            // stays put and the tip does the travelling.
            double moss = 0;
            for (double[] st : strands) {
                double top = canopyBottom(st[0], boardHeight) + 0.2;
                double down = top - elev;
                if (down < -0.3 || down > st[1] + 0.3) continue;
                double f = Math.max(0, down) / st[1];
                double sx = st[0] + MOSS_SWAY * Math.pow(f, 1.5)
                        * Math.sin(TAU * t / MOSS_SWAY_PERIOD + st[2]);
                double w = 0.42 * (1 - 0.35 * f);
                double c = smoothstep(w + 0.25, w - 0.1, Math.abs(col - sx))
                        * smoothstep(st[1] + 0.2, st[1] - 0.3, down);
                moss = Math.max(moss, c);
            }
            moss *= 1 - floor;

            double gap = (1 - canopy) * (1 - floor) * (1 - trunk) * (1 - moss);
            if (gap > 0) {
                double drift = 0.75 + 0.5 * valueNoise((col - 0.25 * t) * 0.3 + seed, elev * 0.5);
                budget.add(ref, ColorPalette.PALE_HAZE, gap * HAZE_LEVEL * drift * scene);
            }
            if (canopy > 0) {
                // Lumpy and very slowly breathing. A flat band of one colour
                // across the top would be the shimmer this replaced, minus the shimmer.
                double lump = 0.8 + 0.25 * (valueNoise(col * 0.9 + seed, elev * 1.2 + 0.1 * t) - 0.5)
                        + 0.05 * sway;
                budget.add(ref, leaves, canopy * CANOPY_LEVEL * lump * scene);
            }
            if (trunk > 0) {
                double bark = 0.85 + 0.3 * (valueNoise(col * 2.0 + seed, elev * 0.4) - 0.5);
                budget.add(ref, ColorPalette.PALE_OAK_LOG, trunk * (1 - moss) * TRUNK_LEVEL * bark * scene);
            }
            if (moss > 0) {
                budget.add(ref, ColorPalette.PALE_HANGING_MOSS, moss * MOSS_LEVEL * scene);
            }
            if (floor > 0) {
                double tuft = 0.85 + 0.3 * (valueNoise(col * 1.4 + seed, 3.7) - 0.5);
                budget.add(ref, ColorPalette.PALE_MOSS, floor * FLOOR_LEVEL * tuft * scene);
            }

            // --- eyeblossoms -------------------------------------------
            for (int i = 0; i < blossoms.size(); i++) {
                double[] b = blossoms.get(i);
                double dx = col - b[0], de = (elev - b[1]) * 1.2;
                double d = Math.hypot(dx, de);
                double amt = openAmount(i, t);
                if (d < 1.6) {
                    double core = smoothstep(0.75, 0.25, d);
                    double halo = smoothstep(1.6, 0.4, d);
                    if (amt < 1) budget.add(ref, ColorPalette.EYEBLOSSOM_CLOSED, core * CLOSED_LEVEL * (1 - amt) * scene);
                    if (amt > 0) {
                        // A slow breath on the glow, a few percent either way,
                        // so a board full of open flowers isn't a still image.
                        double breathe = 0.92 + 0.08 * Math.sin(TAU * t / 3.7 + i * 1.9);
                        budget.add(ref, bloom, amt * breathe * (core + 0.22 * halo));
                        budget.add(ref, ColorPalette.EYEBLOSSOM_HOT, amt * breathe * 0.35 * smoothstep(0.45, 0.0, d));
                    }
                }
                double mote = t - changedAt[i];
                if (mote >= 0 && mote < MOTE_SECONDS) {
                    double f = mote / MOTE_SECONDS;
                    double mx = b[0] + 0.3 * Math.sin(TAU * f + i);
                    double me = b[1] + 0.4 + MOTE_RISE * f;
                    double md = Math.hypot(col - mx, (elev - me) * 1.3);
                    if (md < 0.7) {
                        RGBColor c = open[i] ? bloom : ColorPalette.EYEBLOSSOM_CLOSE_MOTE;
                        budget.add(ref, c, 0.8 * Math.sin(Math.PI * f) * smoothstep(0.7, 0.2, md));
                    }
                }
            }
        }

        return budget.resolve();
    }

    /**
     * Moves the blossoms along. A change of night schedules every blossom
     * that's in the wrong state, timed by its distance from one picked to go
     * first; a change back before they all got there just reschedules
     * whoever's still wrong, so a storm flickering the sky doesn't leave
     * flowers stuck half the way through a ripple that got called off.
     */
    private void stepNight(double t) {
        int n = blossoms.size();
        if (!initialised || t < lastT || open.length != n) {
            // First frame, a restarted clock, or a new layout. Everything
            // simply is whatever it should be by now, no ripple. Walking into
            // the garden at midnight finds it already open.
            heading = nightTarget;
            nightAmt = heading ? 1 : 0;
            open = new boolean[n];
            changedAt = new double[n];
            dueAt = new double[n];
            for (int i = 0; i < n; i++) {
                open[i] = heading;
                changedAt[i] = -1e9;
                dueAt[i] = Double.NaN;
            }
            initialised = true;
            lastT = t;
            return;
        }
        double dt = t - lastT;
        lastT = t;
        nightAmt += ((nightTarget ? 1 : 0) - nightAmt) * (1 - Math.exp(-dt / NIGHT_TAU));

        if (nightTarget != heading && n > 0) {
            heading = nightTarget;
            flips++;
            int first = (int) (hash(flips, 70) * n) % n;
            double[] origin = blossoms.get(first);
            for (int i = 0; i < n; i++) {
                if (open[i] == heading) {
                    dueAt[i] = Double.NaN;
                    continue;
                }
                double[] b = blossoms.get(i);
                double dist = Math.hypot(b[0] - origin[0], b[1] - origin[1]);
                dueAt[i] = t + dist * lerp(RIPPLE_MIN, RIPPLE_MAX, hash(flips * 31 + i, 71));
            }
        }
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(dueAt[i]) && t >= dueAt[i]) {
                open[i] = heading;
                changedAt[i] = t;
                dueAt[i] = Double.NaN;
            }
        }
    }

    /** 0 shut, 1 fully open, eased through BLOOM_SECONDS either way. */
    private double openAmount(int i, double t) {
        double f = smoothstep(0, BLOOM_SECONDS, t - changedAt[i]);
        return open[i] ? f : 1 - f;
    }

    /** Underside of the leaves at a column, in rows up from under the bottom row. */
    private double canopyBottom(double col, double boardHeight) {
        return boardHeight - 1.7 + 0.6 * (valueNoise(col * 0.4 + seed, 9.1) - 0.5);
    }

    /** Top of the moss carpet. Mostly the bottom row, lapping up into the one above here and there. */
    private double floorTop(double col) {
        return 1.05 + 0.4 * (valueNoise(col * 0.5 + seed, 2.3) - 0.5);
    }

    /**
     * Trunks first, every five to seven keys. Then moss strands across the
     * whole width, trunks or not, since moss hangs off leaves and not off bark.
     * Then the blossoms, sitting just above the moss line, three to five keys
     * apart so each one gets a key or two of its own to glow on.
     */
    private void rollLayout(KeyGrid grid, double widthKeys, double boardHeight) {
        trunks.clear();
        strands.clear();
        blossoms.clear();
        double x = 1.2 + hash(0, 10) * 2.0;
        for (int i = 0; x < widthKeys + 0.5 && i < 16; i++) {
            trunks.add(new double[]{x, lerp(0.55, 0.85, hash(i, 11))});
            x += lerp(5.0, 7.0, hash(i, 12));
        }
        x = 0.4 + hash(0, 20);
        for (int i = 0; x < widthKeys + 0.5 && i < 48; i++) {
            strands.add(new double[]{x, lerp(0.9, 2.6, hash(i, 21)) * boardHeight / 6.0, TAU * hash(i, 22)});
            x += lerp(1.5, 2.8, hash(i, 23));
        }
        x = 1.0 + hash(0, 30) * 2.0;
        for (int i = 0; x < widthKeys - 0.3 && i < 24; i++) {
            blossoms.add(new double[]{x, floorTop(x) + 0.35});
            x += lerp(3.0, 4.8, hash(i, 31));
        }
        layoutFor = grid;
        initialised = false;
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
