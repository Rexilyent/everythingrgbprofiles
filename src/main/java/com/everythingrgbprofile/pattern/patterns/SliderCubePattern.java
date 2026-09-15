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
 * A stone cube on the floor of its room, seen from above: sliding in straight
 * lines, slamming into the walls, and hunting a gold marker that is you.
 *
 * <p>Built for the Aether's Slider on the same principle as the Naga and the
 * Ender Dragon: draw the boss, do not tint the board. The Slider has no face
 * and no limbs to draw. What it has is a way of moving that nothing else in
 * the game shares — dead still, then a straight-line slide that picks up speed
 * until it hits something — and that motion is the thing to put on the keys.
 *
 * <h2>The board is the room</h2>
 * The keyboard is a map of the boss room with north at the top: the cube sits
 * where the real one is, and the board's edges are the room's walls. North-up
 * rather than turned to face wherever you are looking, because the real
 * Slider only ever moves along the world's axes. On a map fixed to the world
 * every slide is a straight line along a row or a column; on one that turns
 * with the camera, every glance you take mid-fight would swing the cube
 * diagonally across the board, which is the one way the Slider never moves.
 *
 * <p>The room is square and the board is not, so the map is stretched: a slide
 * east-west crosses twenty-odd keys and a slide north-south crosses a few
 * rows. That is an accepted cost. A map drawn to scale would leave a thin
 * strip of room in the middle of the board with the cube barely moving
 * vertically at all.
 *
 * <h2>A square, not a circle</h2>
 * Everything about the cube is measured with the larger of the two distances
 * rather than the straight-line one, so it has corners. The whole mod draws in
 * soft round blobs; a soft square among them is what reads as a block of stone
 * rather than as another glowing thing. The same measure shapes the shockwave
 * a slam throws out, so it spreads as a growing square too.
 *
 * <h2>The trail</h2>
 * Fainter copies of the cube where it was a moment ago, spaced by how far it
 * has actually travelled. A cube accelerating across the board stretches its
 * trail out behind it, and one that has stopped has none — so the speed-up
 * that is the Slider's whole threat is visible without a number.
 */
public final class SliderCubePattern implements Pattern {

    /**
     * Half the cube's side, in key widths. 2.6 keys across is a block you
     * recognise as a block and still leaves the height of the board somewhere
     * to slide: on a six-row board the centre travels a little over two rows.
     */
    private static final double CUBE_HALF_KEYS = 1.3;
    /** How much larger the cube draws at the top of its room, as it would look from above. */
    private static final double LIFT_GROWTH_KEYS = 0.35;
    /** Width of the soft edge, so the cube glides between keys instead of snapping from one to the next. */
    private static final double EDGE_SOFTNESS_KEYS = 0.55;
    /**
     * The cube's stone, as a multiple of the stone colour. The middle sits at
     * {@code BODY_LEVEL} and the raised border around the rune adds
     * {@code BORDER_LIFT} on top, so the edge keys are the brightest stone on
     * the board and the block has a visible rim.
     *
     * <p>The border goes past 1 on purpose. The stone colour is a mid grey,
     * and {@code LightBudget} keeps the hue and raises the brightness when a
     * key is given more than full, so this is what lifts the rim toward a
     * pale cool white while the middle stays grey. At anything up to 1 the
     * whole cube tops out at the stone colour's own half brightness, which
     * on a board is not much brighter than the floor.
     */
    private static final double BODY_LEVEL = 0.85;
    private static final double BORDER_LIFT = 0.45;
    /** How far in from the edge the raised border reaches. One key: the outer ring of a three-key block. */
    private static final double BORDER_KEYS = 1.0;
    /** How much brighter the lit corner of the border is than its middle, and how much darker the far corner. */
    private static final double BEVEL = 0.30;
    /** Width of the dark gap left in the floor around the cube, and how far it takes to fade back in. */
    private static final double MOAT_KEYS = 0.35;
    private static final double MOAT_FADE_KEYS = 1.2;

    /** How far in from the cube's edge its faint rune tint reaches. */
    private static final double RIM_KEYS = 0.5;
    /**
     * The bright rune at the cube's centre. Just under a key, so it lights the
     * middle key of the block strongly and its neighbours hardly at all: a
     * stone block with one burning eye, not a block of light.
     */
    private static final double CORE_RADIUS_KEYS = 0.95;
    private static final double MARKER_RADIUS_KEYS = 0.95;

    /** How far back each ghost in the trail looks, and how strongly it draws. */
    private static final long[] GHOST_DELAYS_MILLIS = {50, 100, 160, 230};
    private static final double[] GHOST_WEIGHTS = {0.50, 0.34, 0.21, 0.11};
    /** Frames of position history kept for the trail. Covers the oldest ghost at any frame rate above about 5fps. */
    private static final int HISTORY = 48;

    private static final double IMPACT_MILLIS = 560;
    /** How far the slam's shockwave travels past the cube's edge before it is spent. */
    private static final double IMPACT_RING_KEYS = 7.0;
    private static final double HURT_MILLIS = 200;
    /** How far a hit knocks the cube, at the instant it lands. */
    private static final double SHOVE_KEYS = 0.45;
    private static final double IGNITE_MILLIS = 1000;
    private static final double ENRAGE_MILLIS = 900;
    private static final double SHATTER_MILLIS = 1400;

    /** Where the cube is: -1 to 1 across the room on each axis. */
    private volatile double roomU = 0;
    private volatile double roomV = 0;
    /** 0 on the floor, 1 as high as the effect bothers to measure. */
    private volatile double lift = 0;
    private volatile double glow = 1.0;
    /** 0 asleep, 1 awake with the runes fully lit. */
    private volatile double ignition = 0;
    /** 0 still, 1 sliding fast enough for a full trail. */
    private volatile double motion = 0;
    private volatile boolean cubeVisible = true;

    private volatile double markerU = 0;
    private volatile double markerV = 0;
    private volatile boolean markerVisible = false;
    private volatile RGBColor markerColor = RGBColor.WHITE;

    private volatile long impactAtMillis = Long.MIN_VALUE;
    private volatile double impactStrength = 0;
    private volatile int impactDirU = 0;
    private volatile int impactDirV = 0;
    private volatile long hurtAtMillis = Long.MIN_VALUE;
    private volatile int hurtDirU = 0;
    private volatile int hurtDirV = 0;
    private volatile long ignitedAtMillis = Long.MIN_VALUE;
    private volatile long enragedAtMillis = Long.MIN_VALUE;
    private volatile RGBColor enrageColor = RGBColor.WHITE;
    private volatile long shatteredAtMillis = Long.MIN_VALUE;

    // Render-thread only from here down.
    private final long[] historyTime = new long[HISTORY];
    private final double[] historyX = new double[HISTORY];
    private final double[] historyY = new double[HISTORY];
    private int historyHead = 0;
    private int historyCount = 0;
    private double lastDrawX = 0.5;
    private double lastDrawY = 0.5;
    /** Where the cube was when it broke, captured on the first frame of the burst. */
    private double shatterX = 0.5;
    private double shatterY = 0.5;
    private long shatterCapturedFor = Long.MIN_VALUE;
    private final double[] ghostAt = new double[2];

    /**
     * @param u        -1 at the west wall to 1 at the east
     * @param v        -1 at the north wall to 1 at the south
     * @param lift     0 on the floor to 1 high in the room
     * @param glow     overall brightness, raised as the fight goes on
     * @param ignition 0 asleep to 1 awake
     * @param motion   0 still to 1 sliding at full trail
     */
    public void setCube(double u, double v, double lift, double glow, double ignition, double motion) {
        this.roomU = clamp(u, -1, 1);
        this.roomV = clamp(v, -1, 1);
        this.lift = clamp(lift, 0, 1);
        this.glow = Math.max(0, glow);
        this.ignition = clamp(ignition, 0, 1);
        this.motion = clamp(motion, 0, 1);
    }

    /** Where you are, on the same -1 to 1 room axes as the cube. */
    public void setMarker(double u, double v, boolean visible, RGBColor color) {
        this.markerU = clamp(u, -1, 1);
        this.markerV = clamp(v, -1, 1);
        this.markerVisible = visible;
        if (color != null) this.markerColor = color;
    }

    /**
     * The cube stopped dead.
     *
     * @param strength 0 to 1, from how fast it was going
     * @param dirU     -1, 0 or 1: which way it was travelling east-west
     * @param dirV     -1, 0 or 1: which way it was travelling north-south;
     *                 both zero for a slide straight up or down
     */
    public void impact(double strength, int dirU, int dirV, long nowMillis) {
        this.impactStrength = clamp(strength, 0, 1);
        this.impactDirU = Integer.signum(dirU);
        this.impactDirV = Integer.signum(dirV);
        this.impactAtMillis = nowMillis;
    }

    /** A hit landed. The direction is away from whoever struck it. */
    public void hurt(int dirU, int dirV, long nowMillis) {
        this.hurtDirU = Integer.signum(dirU);
        this.hurtDirV = Integer.signum(dirV);
        this.hurtAtMillis = nowMillis;
    }

    /** It just woke up. */
    public void ignite(long nowMillis) {
        this.ignitedAtMillis = nowMillis;
    }

    /** It just dropped to critical health. */
    public void enrage(RGBColor color, long nowMillis) {
        if (color != null) this.enrageColor = color;
        this.enragedAtMillis = nowMillis;
    }

    /** It died: the cube is gone, and the dust of it bursts out from where it was. */
    public void shatter(long nowMillis) {
        this.cubeVisible = false;
        this.shatteredAtMillis = nowMillis;
    }

    /** A living Slider again after a death, so the cube draws. */
    public void restore() {
        this.cubeVisible = true;
    }

    /** Forgets everything in flight, for a Slider seen for the first time. */
    public void reset() {
        impactAtMillis = Long.MIN_VALUE;
        hurtAtMillis = Long.MIN_VALUE;
        ignitedAtMillis = Long.MIN_VALUE;
        enragedAtMillis = Long.MIN_VALUE;
        shatteredAtMillis = Long.MIN_VALUE;
        cubeVisible = true;
        historyCount = 0;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        List<KeyGrid.LedPosition> keys = grid.allKeys();
        double aspect = Math.max(0.05, grid.aspectRatio());
        // One key's width in each axis of normalised space. The board is about
        // three times wider than it is tall, so a key is a much larger share of
        // the height than of the width.
        double keyX = Math.max(1e-4, grid.keyWidthNormalised());
        double keyY = keyX / aspect;
        double seconds = elapsedMillis / 1000.0;

        RGBColor stone = ctx.baseColor();
        RGBColor rune = ctx.resolvedAccentColor();

        double half = CUBE_HALF_KEYS + LIFT_GROWTH_KEYS * lift;
        double hurt = decay(hurtAtMillis, elapsedMillis, HURT_MILLIS);
        // The knock is squared so the cube springs back quickly rather than
        // drifting home, which is what a heavy thing being struck looks like.
        double shove = hurt * hurt * SHOVE_KEYS;
        double drawX = toBoard(roomU, half * keyX) + hurtDirU * shove * keyX;
        double drawY = toBoard(roomV, half * keyY) + hurtDirV * shove * keyY;
        if (cubeVisible) {
            record(elapsedMillis, drawX, drawY);
            lastDrawX = drawX;
            lastDrawY = drawY;
        }
        if (shatteredAtMillis != Long.MIN_VALUE && shatterCapturedFor != shatteredAtMillis) {
            shatterCapturedFor = shatteredAtMillis;
            shatterX = lastDrawX;
            shatterY = lastDrawY;
        }

        LightBudget budget = new LightBudget();

        // --- the dungeon floor -----------------------------------------
        // Dim, and dimmer still while the Slider sleeps. The room is a place
        // you have walked into rather than a fight you are in until it wakes.
        //
        // The floor is kept well below the cube and goes fully dark in a
        // moat just around it. Floor and cube are the same grey-blue family,
        // and an earlier version with the floor at about half the cube's
        // brightness and no gap left the cube's own stone indistinguishable
        // from the room: the only thing that read as the boss was its eye.
        // The dark band is what gives the block an outline on a board that
        // cannot draw one.
        RGBColor floor = stone.lerp(rune, 0.25);
        double floorLevel = 0.06 + 0.04 * ignition;
        for (KeyGrid.LedPosition key : keys) {
            double level = floorLevel;
            if (cubeVisible) {
                double dx = (key.x() - drawX) / keyX;
                double dy = (key.y() - drawY) / keyY;
                double outside = Math.max(Math.abs(dx), Math.abs(dy)) - half;
                level *= clamp((outside - MOAT_KEYS) / MOAT_FADE_KEYS, 0, 1);
            }
            budget.add(key.ref(), floor, level);
        }

        // --- the slam -----------------------------------------------------
        double impact = decay(impactAtMillis, elapsedMillis, IMPACT_MILLIS);
        if (impact > 0 && cubeVisible) {
            double strength = impactStrength * impact * impact;
            double ring = half + (1 - impact) * IMPACT_RING_KEYS;
            RGBColor dust = stone.lightened(0.45);
            RGBColor wallLight = dust.lerp(rune, 0.2);
            // A wall only lights if the cube stopped against it while heading
            // into it. A Slider that stops in the middle of the room has
            // reached the spot it was aiming for, not hit anything.
            boolean wallU = impactDirU != 0 && Math.abs(roomU) > 0.97 && Math.signum(roomU) == impactDirU;
            boolean wallV = impactDirV != 0 && Math.abs(roomV) > 0.97 && Math.signum(roomV) == impactDirV;
            for (KeyGrid.LedPosition key : keys) {
                double dx = (key.x() - drawX) / keyX;
                double dy = (key.y() - drawY) / keyY;
                double d = Math.max(Math.abs(dx), Math.abs(dy));
                double off = Math.abs(d - ring);
                if (off < 1.0) budget.add(key.ref(), dust, (1 - off) * strength * 1.5);

                // The struck wall, lit along its length either side of the
                // cube. Mostly the part beyond the cube is what shows, since
                // the cube is sitting on the rest of it.
                if (wallU) {
                    double fromWall = impactDirU > 0 ? (1 - key.x()) / keyX : key.x() / keyX;
                    double along = Math.max(0, Math.abs(dy) - half);
                    if (fromWall < 1.5 && along < 3.0) {
                        budget.add(key.ref(), wallLight, (1 - fromWall / 1.5) * (1 - along / 3.0) * strength * 2.0);
                    }
                }
                if (wallV) {
                    double fromWall = impactDirV > 0 ? (1 - key.y()) / keyY : key.y() / keyY;
                    // Further along than the east and west walls, because those
                    // are a few rows long and these run the width of the board.
                    double along = Math.max(0, Math.abs(dx) - half);
                    if (fromWall < 1.5 && along < 5.0) {
                        budget.add(key.ref(), wallLight, (1 - fromWall / 1.5) * (1 - along / 5.0) * strength * 2.0);
                    }
                }
            }
        }

        // --- the trail ----------------------------------------------------
        if (cubeVisible && motion > 0.02) {
            RGBColor ghostColor = stone.lerp(rune, 0.35 * ignition);
            for (int g = 0; g < GHOST_DELAYS_MILLIS.length; g++) {
                if (!positionAt(elapsedMillis - GHOST_DELAYS_MILLIS[g], ghostAt)) break;
                double gx = ghostAt[0];
                double gy = ghostAt[1];
                // Weighted by how far the ghost is from the cube, so a cube
                // that has only just stopped does not sit on four copies of
                // itself and glow twice as bright.
                double apart = Math.hypot((gx - drawX) / keyX, (gy - drawY) / keyY);
                double weight = GHOST_WEIGHTS[g] * motion * Math.min(1.0, apart);
                if (weight < 0.01) continue;
                for (KeyGrid.LedPosition key : keys) {
                    double dx = (key.x() - gx) / keyX;
                    double dy = (key.y() - gy) / keyY;
                    double cover = cover(Math.max(Math.abs(dx), Math.abs(dy)), half * 0.92);
                    if (cover > 0) budget.add(key.ref(), ghostColor, cover * weight * 0.6);
                }
            }
        }

        // --- the cube -----------------------------------------------------
        if (cubeVisible) {
            // A sleeping Slider's runes are not quite dark: a slow breath of
            // them, so the stone reads as something that might wake rather
            // than as scenery.
            double breathe = 0.5 + 0.5 * Math.sin(2 * Math.PI * 0.25 * seconds);
            double lit = ignition + (1 - ignition) * (0.06 + 0.08 * breathe);
            double body = (0.95 + 0.15 * lift) * glow;
            RGBColor struck = stone.lightened(0.85);
            for (KeyGrid.LedPosition key : keys) {
                double dx = (key.x() - drawX) / keyX;
                double dy = (key.y() - drawY) / keyY;
                double d = Math.max(Math.abs(dx), Math.abs(dy));
                double cover = cover(d, half);
                if (cover <= 0) continue;

                double e = Math.hypot(dx, dy);
                double core = e < CORE_RADIUS_KEYS ? 1 - e / CORE_RADIUS_KEYS : 0;
                // The top of the real Slider is a raised stone border around
                // its rune, so the ring of keys at the cube's edge is brighter
                // than its middle. The border is also lit from the top left and
                // shaded toward the bottom right, which is what makes a flat
                // square of keys read as a block with a top face.
                double border = clamp((d - (half - BORDER_KEYS)) / 0.5, 0, 1);
                double light = clamp((-dx - dy) / (2 * half), -1, 1);
                double stoneLevel = (BODY_LEVEL + BORDER_LIFT * border) * (1 + BEVEL * border * light);
                // The stone gives way to the rune where the rune is lit rather
                // than sitting under it. Grey is white light on an LED, so
                // adding it beneath the rune washes that key out to a pale
                // sky blue and the eye stops reading as an eye.
                budget.add(key.ref(), stone, cover * stoneLevel * body * (1 - core * lit));
                // The faintest tint of rune at the very edge. It has to stay
                // faint: a cube is only three keys across, so the edge IS most
                // of it, and an earlier version that lit the edge as strongly
                // as the core turned the whole block a pale blue that read as
                // light rather than as stone.
                double rim = Math.max(0, 1 - Math.abs(d - half) / RIM_KEYS);
                budget.add(key.ref(), rune, cover * rim * 0.12 * lit * glow);
                if (core > 0) budget.add(key.ref(), rune, core * core * 1.5 * lit * glow);
                if (hurt > 0) budget.add(key.ref(), struck, cover * hurt * 0.8);
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
                double f = 1 - e / MARKER_RADIUS_KEYS;
                // Driven past full at its centre, so whichever key it lands
                // on shows the gold at full strength rather than a dim amber
                // that reads as a dirty key.
                budget.add(key.ref(), markerColor, Math.sqrt(f) * pulse * 1.8);
            }
        }

        // --- waking, and going critical -----------------------------------
        // Both throw a ring of their colour out from the cube to the edges of
        // the board over a faint wash, so the change reads as coming from the
        // Slider rather than as the board changing colour by itself.
        double ignite = decay(ignitedAtMillis, elapsedMillis, IGNITE_MILLIS);
        double enrage = decay(enragedAtMillis, elapsedMillis, ENRAGE_MILLIS);
        if (ignite > 0 || enrage > 0) {
            double reach = 1.0 / keyX;
            for (KeyGrid.LedPosition key : keys) {
                double dx = (key.x() - drawX) / keyX;
                double dy = (key.y() - drawY) / keyY;
                double d = Math.max(Math.abs(dx), Math.abs(dy));
                if (ignite > 0) {
                    double off = Math.abs(d - (half + (1 - ignite) * reach));
                    double wave = off < 1.5 ? (1 - off / 1.5) : 0;
                    budget.add(key.ref(), rune, ignite * (0.22 + 0.9 * wave));
                }
                if (enrage > 0) {
                    double off = Math.abs(d - (half + (1 - enrage) * reach));
                    double wave = off < 1.5 ? (1 - off / 1.5) : 0;
                    budget.add(key.ref(), enrageColor, enrage * enrage * (0.25 + 1.0 * wave));
                }
            }
        }

        // --- breaking apart -----------------------------------------------
        double shatter = decay(shatteredAtMillis, elapsedMillis, SHATTER_MILLIS);
        if (shatter > 0) {
            double age = 1 - shatter;
            RGBColor dust = stone.lightened(0.5);
            for (KeyGrid.LedPosition key : keys) {
                double dx = (key.x() - shatterX) / keyX;
                double dy = (key.y() - shatterY) / keyY;
                double e = Math.hypot(dx, dy);
                // The flash where it stood, gone in the first quarter.
                if (e < half + 0.5) budget.add(key.ref(), dust, Math.pow(shatter, 5) * 1.2);
                // The puff of dust racing outward.
                double off = Math.abs(e - age * 9.0);
                if (off < 1.4) budget.add(key.ref(), dust, (1 - off / 1.4) * Math.pow(shatter, 1.5) * 1.1);
                // Debris: a scattering of keys inside the cloud, each on its
                // own flicker, so it settles as grit rather than as a ring.
                double h = hash(key.ref());
                if (h < 0.35 && e < 1 + age * 7.0) {
                    double flicker = 0.3 + 0.7 * (0.5 + 0.5 * Math.sin(2 * Math.PI * (h * 11.0 + seconds * 3.0)));
                    budget.add(key.ref(), stone, shatter * flicker * 0.8);
                }
            }
        }

        return budget.resolve();
    }

    /**
     * Where a cube with this half-size sits on one axis of the board, so it
     * stops with its edge on the board's edge rather than hanging off it: the
     * room's walls are the board's edges, and a cube against a wall is
     * touching it, not half inside it.
     */
    private static double toBoard(double room, double halfNormalised) {
        double travel = Math.max(0, 0.5 - halfNormalised);
        return 0.5 + room * travel;
    }

    /** How much of a key the cube covers, from its square distance to the centre. */
    private static double cover(double squareDistance, double half) {
        return clamp((half + EDGE_SOFTNESS_KEYS * 0.5 - squareDistance) / EDGE_SOFTNESS_KEYS, 0, 1);
    }

    private void record(long t, double x, double y) {
        if (historyCount > 0) {
            long newest = historyTime[historyHead];
            // The clock went backwards — a tool restarting its animation —
            // so the history describes a different run and has to go.
            if (t < newest) historyCount = 0;
            else if (t == newest) {
                historyX[historyHead] = x;
                historyY[historyHead] = y;
                return;
            }
        }
        historyHead = (historyHead + 1) % HISTORY;
        historyTime[historyHead] = t;
        historyX[historyHead] = x;
        historyY[historyHead] = y;
        historyCount = Math.min(HISTORY, historyCount + 1);
    }

    /** The cube's drawn position at time {@code t}, interpolated between frames. False if history does not reach back that far. */
    private boolean positionAt(long t, double[] out) {
        for (int i = 0; i < historyCount; i++) {
            int index = Math.floorMod(historyHead - i, HISTORY);
            if (historyTime[index] > t) continue;
            if (i == 0) {
                out[0] = historyX[index];
                out[1] = historyY[index];
                return true;
            }
            int newer = (index + 1) % HISTORY;
            double span = historyTime[newer] - historyTime[index];
            double f = span > 0 ? (t - historyTime[index]) / span : 0;
            out[0] = historyX[index] + (historyX[newer] - historyX[index]) * f;
            out[1] = historyY[index] + (historyY[newer] - historyY[index]) * f;
            return true;
        }
        return false;
    }

    /** A stable 0..1 value per key, so the debris lands on the same scattering of keys every frame. */
    private static double hash(KeyGrid.LedRef ref) {
        long h = (ref.luid() * 2654435761L) ^ (ref.deviceId() == null ? 0 : ref.deviceId().hashCode());
        h ^= (h >>> 15);
        h *= 0x2C1B3C6DL;
        h ^= (h >>> 12);
        return (h & 0xFFFF) / 65536.0;
    }

    private static double decay(long sinceMillis, long nowMillis, double lengthMillis) {
        if (sinceMillis == Long.MIN_VALUE) return 0;
        double age = (nowMillis - sinceMillis) / lengthMillis;
        return age < 0 || age >= 1 ? 0 : 1 - age;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
