package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * The Ender Dragon summoning ritual, drawn top-down, fitted to a real capture
 * rather than to a guess.
 *
 * <h2>What actually happens</h2>
 * From a traced respawn under YUNG's Better End Island:
 *
 * <pre>
 *   t=0       four crystals in the central structure all beam to (0,128,0)
 *             — straight up to a point high above the middle
 *   t=5.1s    all four swing together onto tower 1 (bearing 000)
 *   t=7.0s    tower 1's crystal lights, beaming back to (0,128,0), and STAYS lit
 *   ...       repeat every ~2000ms, 36 degrees around the ring each time:
 *             000, 036, 073, 108, 145, 181, 216, 252, 287, 323
 *   t=25.1s   the four swing back to (0,128,0); all ten towers still lit
 *   t=30.2s   every beam cuts, the four central crystals are destroyed,
 *             and the dragon appears
 * </pre>
 *
 * <h2>How that maps to the board</h2>
 * Three things run at once, and the two kinds of ray deliberately flow in
 * opposite directions because the real beams do:
 *
 * <ul>
 *   <li><b>The core</b> is (0,128,0) — the point everything aims at. It builds
 *       as towers are added.</li>
 *   <li><b>Locked rays</b>, one per lit tower, at that tower's <i>real</i>
 *       bearing. Energy runs <b>inward</b> along them, because a lit tower
 *       beams back to the core, and they accumulate because the towers never
 *       go out until the very end.</li>
 *   <li><b>The targeting ray</b> sweeps around the ring, brighter and wider,
 *       with energy running <b>outward</b> — that is the four central crystals
 *       firing at the tower they are currently activating.</li>
 * </ul>
 *
 * <p>Bearings are passed in from the world rather than assumed evenly spaced.
 * The capture shows 35-37 degree steps, not a clean 36, and using the real
 * angle means the ray on the board points where the tower on the island is.
 */
public final class EndRitualPattern implements Pattern {

    private static final double RAY_HALF_WIDTH = 0.20;
    private static final double SWEEP_HALF_WIDTH = 0.30;
    private static final double CORE_RADIUS = 0.28;
    /** Time for energy to run the length of a ray. */
    private static final double FLOW_SECONDS = 1.1;

    private volatile double[] locked = new double[0];
    private volatile boolean hasSweep = false;
    private volatile double sweepBearing = 0;
    private volatile boolean converging = false;
    private volatile double completion = 0;
    /** Rises to 1 when the sweep jumps to a new tower, decayed by the caller. */
    private volatile double surge = 0;

    /**
     * @param lockedBearings radians, one per tower already lit
     * @param sweepBearing   radians, where the central crystals are aiming
     * @param hasSweep       false during the opening and closing convergence,
     *                       when the central crystals point straight up instead
     * @param completion     0..1, lit towers over expected total
     */
    public void setState(double[] lockedBearings, double sweepBearing, boolean hasSweep,
                         boolean converging, double completion) {
        this.locked = lockedBearings;
        this.sweepBearing = sweepBearing;
        this.hasSweep = hasSweep;
        this.converging = converging;
        this.completion = Math.max(0, Math.min(1, completion));
    }

    public void setSurge(double surge) {
        this.surge = Math.max(0, Math.min(1, surge));
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        double seconds = elapsedMillis / 1000.0;
        double aspect = Math.max(0.05, grid.aspectRatio());
        double cx = grid.centerX(), cy = grid.centerY();
        RGBColor rayColor = ctx.baseColor();
        RGBColor coreColor = ctx.resolvedAccentColor();

        double maxRadius = 0.0001;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            maxRadius = Math.max(maxRadius, Math.hypot(key.x() - cx, (key.y() - cy) * aspect));
        }

        double[] rays = locked;
        double flow = (seconds % FLOW_SECONDS) / FLOW_SECONDS;
        // Inward for the towers, outward for the targeting beam.
        double inwardFront = 1.0 - flow;
        double outwardFront = flow;

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double dx = key.x() - cx;
            double dy = (key.y() - cy) * aspect;
            double radius = Math.hypot(dx, dy) / maxRadius;
            double angle = Math.atan2(dy, dx);

            // --- core --------------------------------------------------
            if (radius < CORE_RADIUS) {
                double falloff = 1.0 - radius / CORE_RADIUS;
                // During the two convergence phases the core IS the effect, so
                // it pulses hard; mid-ritual it just sits there loading.
                double base = converging ? 0.55 + 0.45 * completion : 0.20 + 0.55 * completion;
                double rate = converging ? 1.6 : 0.8;
                double breathe = 0.82 + 0.18 * Math.sin(2 * Math.PI * rate * seconds);
                budget.add(key.ref(), coreColor, falloff * falloff * base * breathe * (1 + surge * 0.5));
            }

            // --- locked rays: energy running inward --------------------
            for (double bearing : rays) {
                double delta = Math.abs(wrapPi(angle - bearing));
                if (delta > RAY_HALF_WIDTH) continue;
                double across = 1.0 - delta / RAY_HALF_WIDTH;
                double along = Math.max(0, 1.0 - Math.abs(radius - inwardFront) / 0.32);
                double standing = 0.26;
                budget.add(key.ref(), rayColor, across * (standing + along * 0.75));
            }

            // --- targeting ray: energy running outward -----------------
            if (hasSweep) {
                double delta = Math.abs(wrapPi(angle - sweepBearing));
                if (delta > SWEEP_HALF_WIDTH) continue;
                double across = 1.0 - delta / SWEEP_HALF_WIDTH;
                double along = Math.max(0, 1.0 - Math.abs(radius - outwardFront) / 0.30);
                // Brighter than a locked ray, and it reaches all the way out —
                // this is the one that is doing something right now.
                budget.add(key.ref(), coreColor, across * (0.30 + along * 1.0) * (1 + surge * 0.7));
            }
        }
        return budget.resolve();
    }

    private static double wrapPi(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
