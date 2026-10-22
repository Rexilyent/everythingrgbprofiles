package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.List;
import java.util.Map;

/**
 * A burning sun flying round its room, seen from above: the fire it leaves on
 * the floor, the crystals it throws, and a gold-and-blue fight between the two.
 *
 * <p>Built for the Aether's Sun Spirit, and drawn as a map of its boss room
 * with north at the top for the same reasons as {@link SliderCubePattern}: the
 * board is the room, so where things are on the keys is where they are in the
 * world. The Slider is a square because it is a block. This is round, with
 * rays, because it is a sun.
 *
 * <h2>Everything on the floor is real</h2>
 * The embers are the room's actual fire blocks, and the motes are the actual
 * crystals, positioned where the game has them. Nothing here is decoration
 * standing in for the fight — including the four fires in the corners, which
 * are the room's eternal netherrack fires, found the same way as the ones the
 * spirit sets. That matters most for the ice crystals: the only way to hurt
 * the Sun Spirit is to find one and knock it back, so the ice mote is drawn
 * brighter than anything else on the board.
 *
 * <h2>The frozen window</h2>
 * An ice crystal freezes the spirit for 175 ticks, and that is the only time
 * anything else hurts it. Frozen, the sun turns the colour of ice, its rays
 * stop turning, and a ring around it runs down the time left. The ring is an
 * arc that shrinks clockwise from the top, like a clock hand sweeping back to
 * noon, because an arc's length is readable at a glance where a brightness
 * fading out is not.
 *
 * <h2>Health</h2>
 * The sun shrinks as it is hurt. Its texture never changes with health, so
 * there is no in-game tell to copy; size is used because a smaller sun is the
 * one reading of "weaker" that needs no explaining.
 */
public final class SunSpiritPattern implements Pattern {

    /**
     * Half the room's floor in blocks, which a room coordinate of 1 stands
     * for. The gold dungeon's boss room is 21 blocks square inside its walls.
     */
    public static final double ROOM_HALF_BLOCKS = 10.5;

    /** Sun radius in key widths at no health and at full, so it shrinks by a third over the fight. */
    private static final double SUN_RADIUS_MIN_KEYS = 1.6;
    private static final double SUN_RADIUS_MAX_KEYS = 2.3;
    /** How far past the body the corona's rays reach. */
    private static final double CORONA_KEYS = 1.6;
    /** Number of rays, as the multiplier on the angle: cos(4θ) has eight peaks. */
    private static final int RAY_LOBES = 4;
    /**
     * How sharp each ray is; higher is thinner. Kept low: eight rays round a
     * sun a few keys across are only a couple of keys apart at their tips, and
     * rays thin enough to look like rays on a screen fall between the LEDs.
     */
    private static final double RAY_SHARPNESS = 4.0;
    /** Radians per second the corona turns while it burns. */
    private static final double SPIN_RATE = 0.45;

    private static final double EMBER_RADIUS_KEYS = 0.75;
    private static final double FIRE_CRYSTAL_RADIUS_KEYS = 0.7;
    private static final double ICE_CRYSTAL_RADIUS_KEYS = 1.0;
    private static final double MARKER_RADIUS_KEYS = 0.95;
    /** Where the countdown ring sits beyond the sun's edge, and how thick it is. */
    private static final double FROST_RING_GAP_KEYS = 1.0;
    private static final double FROST_RING_HALF_WIDTH_KEYS = 0.45;
    /** Blocks per second above which a knocked-back ice crystal draws a streak behind it. */
    private static final double STREAK_SPEED = 20.0;

    private static final double AWAKEN_MILLIS = 1100;
    private static final double SHOT_MILLIS = 300;
    private static final double ICE_SHOT_MILLIS = 700;
    private static final double FREEZE_MILLIS = 800;
    private static final double THAW_MILLIS = 650;
    private static final double HURT_MILLIS = 200;
    private static final double SUNSET_MILLIS = 2800;

    /** Room coordinates are -1 to 1 on each axis; the board's edges are the room's walls. */
    private volatile double sunU = 0;
    private volatile double sunV = 0;
    /** 0 dead to 1 full health. */
    private volatile double health = 1.0;
    /** 0 asleep to 1 awake. */
    private volatile double ignition = 0;
    /** 0 burning to 1 frozen solid. */
    private volatile double frost = 0;
    /** The fraction of the freeze still to run, 1 at the moment it froze. */
    private volatile double frostRemaining = 0;
    private volatile boolean sunVisible = true;

    private volatile double[] fireU = new double[0];
    private volatile double[] fireV = new double[0];
    private volatile double[] crystalU = new double[0];
    private volatile double[] crystalV = new double[0];
    private volatile boolean[] crystalIce = new boolean[0];
    /** Per crystal: its streak direction in room units per second, zero for none. */
    private volatile double[] crystalVU = new double[0];
    private volatile double[] crystalVV = new double[0];

    private volatile double markerU = 0;
    private volatile double markerV = 0;
    private volatile boolean markerVisible = false;
    private volatile RGBColor markerColor = RGBColor.WHITE;
    private volatile RGBColor duskColor = RGBColor.BLACK;
    private volatile RGBColor iceColor = RGBColor.WHITE;

    private volatile long awakenedAtMillis = Long.MIN_VALUE;
    private volatile long firedAtMillis = Long.MIN_VALUE;
    private volatile long iceFiredAtMillis = Long.MIN_VALUE;
    private volatile long frozeAtMillis = Long.MIN_VALUE;
    private volatile long thawedAtMillis = Long.MIN_VALUE;
    private volatile long hurtAtMillis = Long.MIN_VALUE;
    private volatile long sunsetAtMillis = Long.MIN_VALUE;

    // Render-thread only.
    private double spin = 0;
    private long lastRenderMillis = Long.MIN_VALUE;
    private double lastSunX = 0.5;
    private double lastSunY = 0.5;

    /**
     * @param u        -1 at the west wall to 1 at the east
     * @param v        -1 at the north wall to 1 at the south
     * @param health   0 to 1
     * @param ignition 0 asleep to 1 awake
     * @param frost    0 burning to 1 frozen
     * @param frostRemaining how much of the freeze is left, 0 to 1
     */
    public void setSun(double u, double v, double health, double ignition, double frost, double frostRemaining) {
        this.sunU = clamp(u, -1, 1);
        this.sunV = clamp(v, -1, 1);
        this.health = clamp(health, 0, 1);
        this.ignition = clamp(ignition, 0, 1);
        this.frost = clamp(frost, 0, 1);
        this.frostRemaining = clamp(frostRemaining, 0, 1);
    }

    /** Fire blocks on the floor, in room coordinates. The arrays are kept, not copied. */
    public void setFires(double[] u, double[] v) {
        if (u == null || v == null || u.length != v.length) return;
        this.fireU = u;
        this.fireV = v;
    }

    /**
     * Crystals in flight, in room coordinates. The arrays are kept, not
     * copied, and must all be the same length.
     *
     * @param vu streak velocity east-west in room units per second
     * @param vv streak velocity north-south
     */
    public void setCrystals(double[] u, double[] v, boolean[] ice, double[] vu, double[] vv) {
        if (u == null || v == null || ice == null || vu == null || vv == null) return;
        int n = u.length;
        if (v.length != n || ice.length != n || vu.length != n || vv.length != n) return;
        this.crystalU = u;
        this.crystalV = v;
        this.crystalIce = ice;
        this.crystalVU = vu;
        this.crystalVV = vv;
    }

    public void setMarker(double u, double v, boolean visible, RGBColor color) {
        this.markerU = clamp(u, -1, 1);
        this.markerV = clamp(v, -1, 1);
        this.markerVisible = visible;
        if (color != null) this.markerColor = color;
    }

    public void setDuskColor(RGBColor dusk) {
        if (dusk != null) this.duskColor = dusk;
    }

    public void setIceColor(RGBColor ice) {
        if (ice != null) this.iceColor = ice;
    }

    /** The fight started. */
    public void awaken(long nowMillis) {
        this.awakenedAtMillis = nowMillis;
    }

    /** A fire crystal left it. */
    public void fired(long nowMillis) {
        this.firedAtMillis = nowMillis;
    }

    /** An ice crystal left it: the one to go and find. */
    public void firedIce(long nowMillis) {
        this.iceFiredAtMillis = nowMillis;
    }

    public void froze(long nowMillis) {
        this.frozeAtMillis = nowMillis;
    }

    public void thawed(long nowMillis) {
        this.thawedAtMillis = nowMillis;
    }

    public void hurt(long nowMillis) {
        this.hurtAtMillis = nowMillis;
    }

    /** It died, and the Aether's eternal day with it. */
    public void sunset(long nowMillis) {
        this.sunVisible = false;
        this.sunsetAtMillis = nowMillis;
    }

    /** A living spirit again after a death. */
    public void restore() {
        this.sunVisible = true;
    }

    /** Forgets everything in flight, for a spirit seen for the first time. */
    public void reset() {
        awakenedAtMillis = Long.MIN_VALUE;
        firedAtMillis = Long.MIN_VALUE;
        iceFiredAtMillis = Long.MIN_VALUE;
        frozeAtMillis = Long.MIN_VALUE;
        thawedAtMillis = Long.MIN_VALUE;
        hurtAtMillis = Long.MIN_VALUE;
        sunsetAtMillis = Long.MIN_VALUE;
        sunVisible = true;
        fireU = new double[0];
        fireV = new double[0];
        crystalU = new double[0];
        crystalV = new double[0];
        crystalIce = new boolean[0];
        crystalVU = new double[0];
        crystalVV = new double[0];
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        List<KeyGrid.LedPosition> keys = grid.allKeys();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyX = Math.max(1e-4, grid.keyWidthNormalised());
        double keyY = keyX / aspect;
        double seconds = elapsedMillis / 1000.0;

        // A context carries two colours and this needs three, so ice comes in
        // through its own setter.
        RGBColor sun = ctx.baseColor();
        RGBColor flame = ctx.resolvedAccentColor();
        RGBColor ice = iceColor;

        // The corona turns by accumulated angle rather than a function of the
        // clock, so it can slow to a stop as it freezes and pick up again from
        // where it stopped instead of jumping.
        if (lastRenderMillis != Long.MIN_VALUE && elapsedMillis >= lastRenderMillis) {
            double dt = Math.min(0.25, (elapsedMillis - lastRenderMillis) / 1000.0);
            spin += dt * SPIN_RATE * (1 - frost) * (0.3 + 0.7 * ignition);
        }
        lastRenderMillis = elapsedMillis;

        double sx = 0.5 + sunU * 0.5;
        double sy = 0.5 + sunV * 0.5;
        if (sunVisible) {
            lastSunX = sx;
            lastSunY = sy;
        }

        LightBudget budget = new LightBudget();

        // --- the room ------------------------------------------------------
        // Hellfire stone lit from below: a low red glow that breathes a little
        // key by key, cooler while the spirit is frozen and darker before the
        // fight starts.
        RGBColor floor = flame.lerp(ice, 0.35 * frost);
        double floorLevel = (0.05 + 0.04 * ignition) * (1 - 0.3 * frost);
        for (KeyGrid.LedPosition key : keys) {
            double shimmer = 0.8 + 0.2 * Math.sin(2 * Math.PI * (0.35 * seconds + hash(key.ref()) * 5.0));
            budget.add(key.ref(), floor, floorLevel * shimmer);
        }

        // --- fire on the floor --------------------------------------------
        // Fire on the floor, the fire crystals and the sun are all orange, and
        // the sun has to win. So there is a ladder: embers are the dimmest and
        // reddest, fire crystals are small and brighter, and only the sun gets
        // yellow and white. An earlier version gave all three much the same
        // orange at much the same strength, and in a busy fight the sun was
        // just one more orange shape among a dozen.
        double[] fu = fireU;
        double[] fv = fireV;
        RGBColor emberColor = flame;
        for (int i = 0; i < fu.length; i++) {
            double ex = 0.5 + fu[i] * 0.5;
            double ey = 0.5 + fv[i] * 0.5;
            // Each fire flickers on its own two rates, keyed by where it is,
            // so a row of them never pulses in step.
            double phase = fu[i] * 13.1 + fv[i] * 7.7;
            double flicker = 0.65 + 0.2 * Math.sin(seconds * 9.0 + phase) + 0.15 * Math.sin(seconds * 23.0 + phase * 2.3);
            for (KeyGrid.LedPosition key : keys) {
                double e = Math.hypot((key.x() - ex) / keyX, (key.y() - ey) / keyY);
                if (e >= EMBER_RADIUS_KEYS) continue;
                double f = 1 - e / EMBER_RADIUS_KEYS;
                budget.add(key.ref(), emberColor, f * flicker * 0.6);
            }
        }

        // --- crystals -----------------------------------------------------
        double[] cu = crystalU;
        double[] cv = crystalV;
        boolean[] ci = crystalIce;
        double[] cvu = crystalVU;
        double[] cvv = crystalVV;
        int crystals = Math.min(cu.length, Math.min(cv.length, Math.min(ci.length, Math.min(cvu.length, cvv.length))));
        for (int i = 0; i < crystals; i++) {
            boolean isIce = ci[i];
            double radius = isIce ? ICE_CRYSTAL_RADIUS_KEYS : FIRE_CRYSTAL_RADIUS_KEYS;
            RGBColor color = isIce ? ice.lightened(0.15) : flame.lerp(sun, 0.25);
            // The ice crystal sparkles, faster and brighter than anything else
            // moving on the board, because it is the one thing in the room the
            // player has to go and find.
            double level = isIce
                    ? 2.2 * (0.8 + 0.2 * Math.sin(seconds * 12.0 + i))
                    : 0.95 * (0.8 + 0.2 * Math.sin(seconds * 17.0 + i * 1.7));
            // A crystal knocked back at the spirit is fast enough to cross the
            // room in a few ticks, so it draws as a streak along its path.
            double speedRoom = Math.hypot(cvu[i], cvv[i]);
            int trail = isIce && speedRoom * ROOM_HALF_BLOCKS > STREAK_SPEED ? 3 : 0;
            for (int t = 0; t <= trail; t++) {
                double back = t * 0.05;
                double px = 0.5 + (cu[i] - cvu[i] * back) * 0.5;
                double py = 0.5 + (cv[i] - cvv[i] * back) * 0.5;
                double fade = 1.0 - t * 0.28;
                for (KeyGrid.LedPosition key : keys) {
                    double e = Math.hypot((key.x() - px) / keyX, (key.y() - py) / keyY);
                    if (e >= radius) continue;
                    // A rounder falloff for ice, so a crystal sitting between
                    // two keys still lights both of them brightly instead of
                    // leaving two dim blue keys that read as nothing.
                    double f = isIce ? Math.sqrt(1 - e / radius) : 1 - e / radius;
                    budget.add(key.ref(), color, f * level * fade);
                }
            }
        }

        // --- the sun ------------------------------------------------------
        double radius = SUN_RADIUS_MIN_KEYS + (SUN_RADIUS_MAX_KEYS - SUN_RADIUS_MIN_KEYS) * health;
        double sunsetT = progress(sunsetAtMillis, elapsedMillis, SUNSET_MILLIS);
        if (sunVisible || sunsetT < 0.6) {
            if (!sunVisible) radius *= 1 - sunsetT / 0.6;
            double hurt = decay(hurtAtMillis, elapsedMillis, HURT_MILLIS);
            double shot = decay(firedAtMillis, elapsedMillis, SHOT_MILLIS);
            double breathe = 0.5 + 0.5 * Math.sin(2 * Math.PI * 0.3 * seconds);
            // Asleep it is a dim, slow ember of itself; awake it is a sun.
            double body = (0.45 + 0.55 * ignition + 0.08 * breathe * (1 - ignition)) * (1 + 0.35 * shot);
            RGBColor bodyColor = sun.lerp(ice, frost);
            RGBColor coreColor = bodyColor.lightened(0.65 - 0.25 * frost);
            RGBColor rayColor = flame.lerp(sun, 0.35).lerp(ice.lightened(0.3), frost);
            double corona = CORONA_KEYS * (0.45 + 0.55 * ignition) * (1 - 0.55 * frost);
            for (KeyGrid.LedPosition key : keys) {
                double dx = (key.x() - lastSunX) / keyX;
                double dy = (key.y() - lastSunY) / keyY;
                double e = Math.hypot(dx, dy);
                if (e > radius + corona) continue;

                if (e < radius) {
                    double r = e / radius;
                    double f = 1 - r * r;
                    budget.add(key.ref(), bodyColor, (0.35 + 1.0 * f) * body);
                    // The white-hot middle.
                    if (r < 0.6) budget.add(key.ref(), coreColor, (1 - r / 0.6) * 1.3 * body);
                    if (hurt > 0) budget.add(key.ref(), RGBColor.WHITE, f * hurt * 0.9);
                }
                if (e > radius * 0.7 && corona > 0.01) {
                    double theta = Math.atan2(dy, dx);
                    double ray = Math.pow(Math.max(0, Math.cos(RAY_LOBES * (theta - spin))), RAY_SHARPNESS);
                    // Flickering rides on the rays only while it burns; a
                    // frozen corona holds still.
                    double flicker = 0.75 + 0.25 * (1 - frost) * Math.sin(seconds * 9.0 + theta * 3.0);
                    double t = (e - radius * 0.7) / (corona + radius * 0.3);
                    double fall = Math.max(0, 1 - t);
                    budget.add(key.ref(), rayColor, ray * Math.pow(fall, 1.2) * 1.6 * flicker * body);
                    budget.add(key.ref(), rayColor, fall * fall * 0.3 * body);
                }
            }

            // --- the countdown ------------------------------------------
            if (frost > 0.5 && frostRemaining > 0 && sunVisible) {
                double ringR = radius + FROST_RING_GAP_KEYS;
                // Nearly white rather than ice blue. While it is frozen the sun
                // is blue and the ice crystals are blue, and an earlier version
                // with a blue ring as well left a single blue cluster in which
                // pieces of the ring read as crystals.
                RGBColor ringColor = RGBColor.WHITE.lerp(ice, 0.2);
                for (KeyGrid.LedPosition key : keys) {
                    double dx = (key.x() - lastSunX) / keyX;
                    double dy = (key.y() - lastSunY) / keyY;
                    double off = Math.abs(Math.hypot(dx, dy) - ringR);
                    if (off >= FROST_RING_HALF_WIDTH_KEYS * 1.6) continue;
                    // Clockwise from the top, the way a clock reads.
                    double around = Math.atan2(dx, -dy);
                    if (around < 0) around += 2 * Math.PI;
                    double left = frostRemaining * 2 * Math.PI;
                    if (around > left) continue;
                    double f = Math.max(0, 1 - off / (FROST_RING_HALF_WIDTH_KEYS * 1.6));
                    budget.add(key.ref(), ringColor, f * 1.2 * (frost - 0.5) * 2);
                }
            }
        }

        // --- you ----------------------------------------------------------
        if (markerVisible) {
            double mx = 0.5 + markerU * 0.5;
            double my = 0.5 + markerV * 0.5;
            double pulse = 0.75 + 0.25 * Math.sin(2 * Math.PI * 1.1 * seconds);
            for (KeyGrid.LedPosition key : keys) {
                double e = Math.hypot((key.x() - mx) / keyX, (key.y() - my) / keyY);
                if (e >= MARKER_RADIUS_KEYS) continue;
                budget.add(key.ref(), markerColor, Math.sqrt(1 - e / MARKER_RADIUS_KEYS) * pulse * 1.8);
            }
        }

        // --- rings from the spirit ----------------------------------------
        // Waking, an ice crystal launched, freezing and thawing all throw a
        // ring out from the sun in their own colour, so each reads as
        // something the spirit did rather than as the board changing.
        double reach = 1.0 / keyX;
        ring(budget, keys, keyX, keyY, radius, reach, decay(awakenedAtMillis, elapsedMillis, AWAKEN_MILLIS), sun, 0.25, 1.0);
        ring(budget, keys, keyX, keyY, radius, reach * 0.35, decay(iceFiredAtMillis, elapsedMillis, ICE_SHOT_MILLIS), ice.lightened(0.2), 0.0, 1.1);
        ring(budget, keys, keyX, keyY, radius, reach, decay(frozeAtMillis, elapsedMillis, FREEZE_MILLIS), ice, 0.3, 1.1);
        ring(budget, keys, keyX, keyY, radius, reach * 0.6, decay(thawedAtMillis, elapsedMillis, THAW_MILLIS), flame.lerp(sun, 0.5), 0.2, 1.0);

        // --- sunset -------------------------------------------------------
        // The spirit's death ends the Aether's eternal day, so it goes out as
        // one: a white flash where it was, then the whole board burning down
        // from gold through red into dusk.
        if (sunsetT < 1) {
            RGBColor wash = sunsetT < 0.35
                    ? sun.lerp(flame, sunsetT / 0.35)
                    : flame.lerp(duskColor, (sunsetT - 0.35) / 0.65);
            double level = Math.min(1, sunsetT * 8) * (0.55 - 0.3 * sunsetT);
            double flash = Math.max(0, 1 - sunsetT / 0.15);
            for (KeyGrid.LedPosition key : keys) {
                budget.add(key.ref(), wash, level);
                if (flash > 0) {
                    double e = Math.hypot((key.x() - lastSunX) / keyX, (key.y() - lastSunY) / keyY);
                    if (e < SUN_RADIUS_MAX_KEYS + 1) budget.add(key.ref(), RGBColor.WHITE, flash * flash * 1.3);
                }
            }
        }

        return budget.resolve();
    }

    private void ring(LightBudget budget, List<KeyGrid.LedPosition> keys, double keyX, double keyY,
                      double start, double reach, double strength, RGBColor color, double wash, double peak) {
        if (strength <= 0) return;
        double r = start + (1 - strength) * reach;
        for (KeyGrid.LedPosition key : keys) {
            double e = Math.hypot((key.x() - lastSunX) / keyX, (key.y() - lastSunY) / keyY);
            double off = Math.abs(e - r);
            double wave = off < 1.5 ? 1 - off / 1.5 : 0;
            budget.add(key.ref(), color, strength * strength * (wash + peak * wave));
        }
    }

    private static double hash(KeyGrid.LedRef ref) {
        long h = (ref.luid() * 2654435761L) ^ (ref.deviceId() == null ? 0 : ref.deviceId().hashCode());
        h ^= (h >>> 15);
        h *= 0x2C1B3C6DL;
        h ^= (h >>> 12);
        return (h & 0xFFFF) / 65536.0;
    }

    /** 1 at the moment of the event falling to 0 over its length; 0 before and after. */
    private static double decay(long sinceMillis, long nowMillis, double lengthMillis) {
        if (sinceMillis == Long.MIN_VALUE) return 0;
        double age = (nowMillis - sinceMillis) / lengthMillis;
        return age < 0 || age >= 1 ? 0 : 1 - age;
    }

    /** How far through an event, 0 to 1, or 1 when there is none. */
    private static double progress(long sinceMillis, long nowMillis, double lengthMillis) {
        if (sinceMillis == Long.MIN_VALUE) return 1;
        return clamp((nowMillis - sinceMillis) / lengthMillis, 0, 1);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
