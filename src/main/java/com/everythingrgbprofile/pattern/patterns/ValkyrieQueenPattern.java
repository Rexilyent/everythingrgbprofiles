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
 * A winged figure in her throne room, seen from above: vanishing and
 * reappearing at your side, dropping onto you out of the air, and throwing
 * crystals that become lightning.
 *
 * <p>Built for the Aether's Valkyrie Queen, and drawn as a north-up map of her
 * room for the same reasons as {@link SliderCubePattern} and
 * {@link SunSpiritPattern}: the board is the room, so where things are on the
 * keys is where they are in the world.
 *
 * <h2>Wings, because they say what she is doing</h2>
 * She is a bright body with a wing to either side, and what the wings are
 * doing is the part worth reading:
 *
 * <ul>
 *   <li><b>Folded</b> in close while she walks.</li>
 *   <li><b>Open</b> while she is in the air, beating.</li>
 *   <li><b>Tucked</b> when she drops out of a jump onto you, which is her
 *       lunge, the way a diving bird's are, with a streak behind her that
 *       points the way she is coming.</li>
 * </ul>
 *
 * <p>The wings always spread along the board's rows rather than turning with
 * her. An earlier version spread them across the line between her and you,
 * which on paper also showed which way she faced. On a board three or four
 * times wider than it is tall that line is nearly always sideways, so the
 * wings nearly always stood up the few rows the board has, and read as a
 * smear rather than as wings. Along the rows there is room for them.
 *
 * <h2>Thunder crystals are fuses</h2>
 * A thunder crystal drifts after you for fifteen seconds and then turns into a
 * lightning bolt wherever it is. So each one is drawn as a fuse: its crackle
 * starts slow and speeds up as its time runs out, and over the last stretch a
 * ring opens around it at the reach of the bolt, which is the ground to be off
 * when it goes. Hitting a crystal knocks it away and burns off some of its
 * time by an amount the client is never told, so a struck crystal is drawn at
 * full urgency from then on. That can be early. It is never late.
 *
 * <h2>Health</h2>
 * The wings shrink as she is hurt, by a third over the fight. Nothing about
 * her appearance changes with health in game, so there is no tell to copy;
 * the wings are used because they are the largest thing on the board that is
 * hers.
 */
public final class ValkyrieQueenPattern implements Pattern {

    private static final double BODY_RADIUS_KEYS = 0.95;
    /** Where the white of her body gives way to the silver. */
    private static final double CORE_RADIUS_KEYS = 0.55;
    /**
     * Half the full wingspan, in key widths: folded, open, and tucked into a
     * lunge. Open is seven keys tip to tip, which on a full-size board is a
     * third of the room and unmistakably the largest thing in it.
     */
    private static final double WING_SPAN_FOLDED_KEYS = 1.9;
    private static final double WING_SPAN_OPEN_KEYS = 3.5;
    private static final double WING_SPAN_TUCKED_KEYS = 1.2;
    /** Half a wing's height at the body and at the tip, in rows. */
    private static final double WING_ROOT_HALF_KEYS = 0.55;
    private static final double WING_TIP_HALF_KEYS = 0.15;
    /** The soft edge on every side of a wing, so it glides between keys instead of snapping. */
    private static final double WING_EDGE_KEYS = 0.5;
    /** Share of the wingspan that is left at no health. */
    private static final double SPAN_AT_NO_HEALTH = 0.67;
    /** How much larger she draws at the top of a jump, as she would look from above. */
    private static final double LIFT_GROWTH = 0.15;
    /**
     * Wingbeats per second while in the air, and how far each beat lifts the
     * tips, in rows at the tip. The tips rise and fall rather than the wings
     * growing and shrinking, because a beat is a movement, and a span that
     * pulses reads as the wings breathing.
     */
    private static final double FLAP_RATE = 2.4;
    private static final double FLAP_ROWS = 0.45;
    /** The gold glow round her before the fight: how far it reaches, and breaths per second. */
    private static final double AURA_RADIUS_KEYS = 2.6;
    private static final double AURA_RATE = 0.25;
    /** How far back each copy in a lunge's streak looks, and how strongly it draws. */
    private static final double[] GHOST_DELAYS_SECONDS = {0.06, 0.12, 0.18};
    private static final double[] GHOST_WEIGHTS = {0.55, 0.32, 0.15};

    private static final double CRYSTAL_RADIUS_KEYS = 0.8;
    /** {@code AbstractCrystal.getLifeSpan}: 300 ticks. */
    public static final long CRYSTAL_LIFE_MILLIS = 300 * 50L;
    /** How far through its life a crystal is when the reach ring starts to open. */
    private static final double REACH_RING_FROM = 0.6;
    private static final double REACH_RING_HALF_WIDTH_KEYS = 0.6;
    private static final double BOLT_RADIUS_KEYS = 2.2;
    private static final int MAX_BOLTS = 4;

    private static final double MARKER_RADIUS_KEYS = 0.95;

    private static final double AWAKEN_MILLIS = 1100;
    private static final double HURT_MILLIS = 200;
    private static final double VANISH_MILLIS = 450;
    private static final double ARRIVE_MILLIS = 420;
    private static final double STRUCK_MILLIS = 250;
    private static final double BOLT_MILLIS = 700;
    private static final double DEFEAT_MILLIS = 2600;

    /** Room coordinates are -1 to 1 on each axis; the board's edges are the room's walls. */
    private volatile double queenU = 0;
    private volatile double queenV = 0;
    /** 0 on the floor to 1 at the top of a jump. */
    private volatile double lift = 0;
    private volatile double health = 1.0;
    /** 0 waiting to 1 fighting. */
    private volatile double ignition = 0;
    /** 0 folded to 1 open. */
    private volatile double spread = 0;
    /** 0 not lunging to 1 mid-lunge. */
    private volatile double dive = 0;
    /** Her velocity in room units per second, for the lunge's trail. */
    private volatile double velU = 0;
    private volatile double velV = 0;
    /** False while she is between one place and the next, and after she falls. */
    private volatile boolean queenVisible = true;

    private volatile double[] crystalU = new double[0];
    private volatile double[] crystalV = new double[0];
    /** Per crystal: when it was first seen, or {@code Long.MIN_VALUE} when its age is not known. */
    private volatile long[] crystalBornAt = new long[0];
    private volatile boolean[] crystalStruck = new boolean[0];
    /** A lightning bolt's reach in room units along each axis, which differ because the room is not square. */
    private volatile double reachU = 0.25;
    private volatile double reachV = 0.3;

    private volatile double markerU = 0;
    private volatile double markerV = 0;
    private volatile boolean markerVisible = false;
    private volatile RGBColor markerColor = RGBColor.WHITE;
    private volatile RGBColor lightningColor = RGBColor.WHITE;

    /** The last few strikes, oldest overwritten first: u, v, and when. */
    private final double[] boltU = new double[MAX_BOLTS];
    private final double[] boltV = new double[MAX_BOLTS];
    private final long[] boltAt = new long[MAX_BOLTS];
    private int nextBolt = 0;

    private volatile double vanishU = 0;
    private volatile double vanishV = 0;
    private volatile long vanishedAtMillis = Long.MIN_VALUE;
    private volatile long arrivedAtMillis = Long.MIN_VALUE;
    private volatile double struckU = 0;
    private volatile double struckV = 0;
    private volatile long struckAtMillis = Long.MIN_VALUE;
    private volatile long awakenedAtMillis = Long.MIN_VALUE;
    private volatile long hurtAtMillis = Long.MIN_VALUE;
    private volatile long defeatedAtMillis = Long.MIN_VALUE;

    // Render-thread only.
    private double lastQueenX = 0.5;
    private double lastQueenY = 0.5;

    public ValkyrieQueenPattern() {
        java.util.Arrays.fill(boltAt, Long.MIN_VALUE);
    }

    /**
     * @param u        -1 at the west wall to 1 at the east
     * @param v        -1 at the north wall to 1 at the south
     * @param lift     0 on the floor to 1 high in the air
     * @param health   0 to 1
     * @param ignition 0 waiting to 1 fighting
     * @param spread   0 wings folded to 1 open
     * @param dive     0 to 1 lunging
     * @param velU     east-west velocity in room units per second
     * @param velV     north-south velocity
     */
    public void setQueen(double u, double v, double lift, double health, double ignition,
                         double spread, double dive, double velU, double velV) {
        this.queenU = clamp(u, -1, 1);
        this.queenV = clamp(v, -1, 1);
        this.lift = clamp(lift, 0, 1);
        this.health = clamp(health, 0, 1);
        this.ignition = clamp(ignition, 0, 1);
        this.spread = clamp(spread, 0, 1);
        this.dive = clamp(dive, 0, 1);
        this.velU = velU;
        this.velV = velV;
    }

    /**
     * Crystals in flight, in room coordinates. The arrays are kept, not
     * copied, and must all be the same length.
     *
     * @param bornAt when each was first seen, {@code Long.MIN_VALUE} for unknown
     * @param struck whether each has been hit, which makes its fuse unknowable
     */
    public void setCrystals(double[] u, double[] v, long[] bornAt, boolean[] struck) {
        if (u == null || v == null || bornAt == null || struck == null) return;
        int n = u.length;
        if (v.length != n || bornAt.length != n || struck.length != n) return;
        this.crystalU = u;
        this.crystalV = v;
        this.crystalBornAt = bornAt;
        this.crystalStruck = struck;
    }

    /** A lightning bolt's reach, in room units along each axis. */
    public void setBoltReach(double u, double v) {
        this.reachU = Math.max(0.01, u);
        this.reachV = Math.max(0.01, v);
    }

    public void setMarker(double u, double v, boolean visible, RGBColor color) {
        this.markerU = clamp(u, -1, 1);
        this.markerV = clamp(v, -1, 1);
        this.markerVisible = visible;
        if (color != null) this.markerColor = color;
    }

    public void setLightningColor(RGBColor lightning) {
        if (lightning != null) this.lightningColor = lightning;
    }

    /** The fight started. */
    public void awaken(long nowMillis) {
        this.awakenedAtMillis = nowMillis;
    }

    public void hurt(long nowMillis) {
        this.hurtAtMillis = nowMillis;
    }

    /** She left this spot. She stays hidden until {@link #arrived}. */
    public void vanished(double u, double v, long nowMillis) {
        this.vanishU = clamp(u, -1, 1);
        this.vanishV = clamp(v, -1, 1);
        this.vanishedAtMillis = nowMillis;
        this.queenVisible = false;
    }

    /** She reappeared, wherever {@link #setQueen} last put her. */
    public void arrived(long nowMillis) {
        this.arrivedAtMillis = nowMillis;
        this.queenVisible = true;
    }

    /** A crystal was knocked away. */
    public void struck(double u, double v, long nowMillis) {
        this.struckU = clamp(u, -1, 1);
        this.struckV = clamp(v, -1, 1);
        this.struckAtMillis = nowMillis;
    }

    /** Lightning landed. Called from the worker thread only, which is also the thread that renders. */
    public void strike(double u, double v, long nowMillis) {
        boltU[nextBolt] = clamp(u, -1, 1);
        boltV[nextBolt] = clamp(v, -1, 1);
        boltAt[nextBolt] = nowMillis;
        nextBolt = (nextBolt + 1) % MAX_BOLTS;
    }

    /** She was defeated, and her dungeon unlocks. */
    public void defeated(long nowMillis) {
        this.defeatedAtMillis = nowMillis;
        this.queenVisible = false;
    }

    /** A living Queen again after a defeat. */
    public void restore() {
        this.defeatedAtMillis = Long.MIN_VALUE;
        this.queenVisible = true;
    }

    /** Forgets everything in flight, for a Queen seen for the first time. */
    public void reset() {
        awakenedAtMillis = Long.MIN_VALUE;
        hurtAtMillis = Long.MIN_VALUE;
        vanishedAtMillis = Long.MIN_VALUE;
        arrivedAtMillis = Long.MIN_VALUE;
        struckAtMillis = Long.MIN_VALUE;
        defeatedAtMillis = Long.MIN_VALUE;
        queenVisible = true;
        java.util.Arrays.fill(boltAt, Long.MIN_VALUE);
        crystalU = new double[0];
        crystalV = new double[0];
        crystalBornAt = new long[0];
        crystalStruck = new boolean[0];
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        List<KeyGrid.LedPosition> keys = grid.allKeys();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyX = Math.max(1e-4, grid.keyWidthNormalised());
        double keyY = keyX / aspect;
        double seconds = elapsedMillis / 1000.0;

        // A context carries two colours and this needs three, so lightning
        // comes in through its own setter.
        RGBColor silver = ctx.baseColor();
        RGBColor gold = ctx.resolvedAccentColor();
        RGBColor lightning = lightningColor;

        double qx = 0.5 + queenU * 0.5;
        double qy = 0.5 + queenV * 0.5;
        if (queenVisible) {
            lastQueenX = qx;
            lastQueenY = qy;
        }
        double mx = 0.5 + markerU * 0.5;
        double my = 0.5 + markerV * 0.5;

        LightBudget budget = new LightBudget();

        // --- the room ------------------------------------------------------
        // Pale stone in the gold of the room's torches and glowstone, dimmer
        // before the fight.
        RGBColor floor = silver.lerp(gold, 0.75);
        double floorLevel = 0.05 + 0.04 * ignition;
        for (KeyGrid.LedPosition key : keys) {
            double shimmer = 0.8 + 0.2 * Math.sin(2 * Math.PI * (0.25 * seconds + hash(key.ref()) * 5.0));
            budget.add(key.ref(), floor, floorLevel * shimmer);
        }

        // --- lightning ----------------------------------------------------
        // Each strike is a hard white-hot flash where it landed, a flicker
        // across the whole room with it, and a second, weaker flicker a moment
        // later, because a real bolt flashes more than once.
        for (int i = 0; i < MAX_BOLTS; i++) {
            if (boltAt[i] == Long.MIN_VALUE) continue;
            double age = elapsedMillis - boltAt[i];
            if (age < 0 || age >= BOLT_MILLIS) continue;
            double envelope = Math.exp(-age / 90.0) + (age > 180 ? 0.6 * Math.exp(-(age - 180) / 120.0) : 0);
            double bx = 0.5 + boltU[i] * 0.5;
            double by = 0.5 + boltV[i] * 0.5;
            RGBColor wash = lightning.lightened(0.35);
            for (KeyGrid.LedPosition key : keys) {
                budget.add(key.ref(), wash, 0.4 * envelope);
                double e = Math.hypot((key.x() - bx) / keyX, (key.y() - by) / keyY);
                if (e >= BOLT_RADIUS_KEYS) continue;
                double f = 1 - e / BOLT_RADIUS_KEYS;
                budget.add(key.ref(), lightning, f * 1.6 * envelope);
                budget.add(key.ref(), RGBColor.WHITE, f * f * 1.2 * envelope);
            }
        }

        // --- thunder crystals ---------------------------------------------
        double[] cu = crystalU;
        double[] cv = crystalV;
        long[] cb = crystalBornAt;
        boolean[] cs = crystalStruck;
        int crystals = Math.min(cu.length, Math.min(cv.length, Math.min(cb.length, cs.length)));
        double ringX = reachU * 0.5 / keyX;
        double ringY = reachV * 0.5 / keyY;
        for (int i = 0; i < crystals; i++) {
            boolean known = cb[i] != Long.MIN_VALUE && !cs[i];
            double ageSeconds = known ? Math.max(0, (elapsedMillis - cb[i]) / 1000.0) : 0;
            double life = CRYSTAL_LIFE_MILLIS / 1000.0;
            double urgency = known ? Math.min(1, ageSeconds / life) : 1;
            // The crackle is a chirp: its rate climbs steadily from 2 to 12
            // flickers a second over the crystal's life. Its phase is the
            // integral of that rate over the crystal's age, so the flicker
            // speeds up smoothly instead of stuttering every time the rate
            // is recomputed.
            double phase = known
                    ? 2 * Math.PI * (2 * ageSeconds + 5 * ageSeconds * ageSeconds / life)
                    : 2 * Math.PI * 12 * seconds;
            double crackle = 0.5 + 0.5 * Math.sin(phase + i * 1.3);
            // Dim while there is time and bright when there is not, so a fresh
            // crystal never outshines her and a ripe one outshines everything.
            double level = 0.45 + 0.35 * crackle + urgency * (0.5 + 0.9 * crackle);
            double px = 0.5 + cu[i] * 0.5;
            double py = 0.5 + cv[i] * 0.5;
            double rx = Math.max(0.3, ringX);
            double ry = Math.max(0.3, ringY);
            for (KeyGrid.LedPosition key : keys) {
                double dx = (key.x() - px) / keyX;
                double dy = (key.y() - py) / keyY;
                double e = Math.hypot(dx, dy);
                if (e < CRYSTAL_RADIUS_KEYS) {
                    double f = 1 - e / CRYSTAL_RADIUS_KEYS;
                    budget.add(key.ref(), lightning, f * level);
                    if (urgency > 0.8) budget.add(key.ref(), RGBColor.WHITE, f * (urgency - 0.8) * 3.0 * crackle);
                }
                if (urgency > REACH_RING_FROM) {
                    // An ellipse, because the map is stretched: three blocks
                    // is more keys east-west than it is rows north-south. The
                    // distance from its edge is the usual first-order
                    // estimate, how far off the edge the key is divided by how
                    // steeply that changes, which keeps the ring the same
                    // width all the way round.
                    double q = Math.hypot(dx / rx, dy / ry);
                    double slope = Math.hypot(dx / (rx * rx), dy / (ry * ry)) / Math.max(q, 1e-6);
                    double off = Math.abs(q - 1) / Math.max(slope, 1e-6);
                    if (off >= REACH_RING_HALF_WIDTH_KEYS) continue;
                    double f = 1 - off / REACH_RING_HALF_WIDTH_KEYS;
                    double open = (urgency - REACH_RING_FROM) / (1 - REACH_RING_FROM);
                    budget.add(key.ref(), lightning, f * open * (0.3 + 0.25 * crackle));
                }
            }
        }

        // --- her ----------------------------------------------------------
        double hurt = decay(hurtAtMillis, elapsedMillis, HURT_MILLIS);
        double defeatT = progress(defeatedAtMillis, elapsedMillis, DEFEAT_MILLIS);
        double arrive = decay(arrivedAtMillis, elapsedMillis, ARRIVE_MILLIS);
        boolean defeated = defeatedAtMillis != Long.MIN_VALUE;
        if (queenVisible || (defeated && defeatT < 0.5)) {
            // Falling, her wings open wide and she fades to white.
            double fade = defeated ? 1 - defeatT / 0.5 : 1;
            double wings = defeated ? 1 : spread * (1 - dive);
            double presence = (0.65 + 0.35 * ignition) * fade;
            double open = WING_SPAN_FOLDED_KEYS + (WING_SPAN_OPEN_KEYS - WING_SPAN_FOLDED_KEYS) * wings;
            double span = (open + (WING_SPAN_TUCKED_KEYS - open) * (defeated ? 0 : dive))
                    * (SPAN_AT_NO_HEALTH + (1 - SPAN_AT_NO_HEALTH) * health)
                    * (1 + LIFT_GROWTH * lift);
            // The beat: tips up and down together, only while the wings are open.
            double beat = queenVisible ? FLAP_ROWS * wings * Math.sin(2 * Math.PI * FLAP_RATE * seconds) : 0;
            RGBColor white = defeated ? RGBColor.WHITE : null;
            double flash = Math.max(hurt, defeated ? Math.max(0, 1 - defeatT / 0.15) : 0);

            // Before the fight she will talk to you rather than fight you, and
            // she is drawn that way: dimmer, wings in, and the room's gold
            // breathing slowly round her. It goes out as the fight starts.
            double calm = queenVisible ? 1 - ignition : 0;
            if (calm > 0.01) {
                double breathe = 0.5 + 0.5 * Math.sin(2 * Math.PI * AURA_RATE * seconds);
                for (KeyGrid.LedPosition key : keys) {
                    double e = Math.hypot((key.x() - lastQueenX) / keyX, (key.y() - lastQueenY) / keyY);
                    if (e >= AURA_RADIUS_KEYS) continue;
                    double f = 1 - e / AURA_RADIUS_KEYS;
                    budget.add(key.ref(), gold, f * calm * (0.2 + 0.35 * breathe));
                }
            }

            // A lunge leaves fainter copies of her behind, along the way she
            // came, so the streak points at where she is about to land.
            if (dive > 0.2 && queenVisible) {
                for (int g = 0; g < GHOST_DELAYS_SECONDS.length; g++) {
                    double back = GHOST_DELAYS_SECONDS[g] * 0.5;
                    figure(budget, keys, keyX, keyY, lastQueenX - velU * back, lastQueenY - velV * back,
                            span, 0, silver, null, presence * GHOST_WEIGHTS[g] * dive, 0);
                }
            }
            figure(budget, keys, keyX, keyY, lastQueenX, lastQueenY, span, beat, silver, white,
                    presence * (1 + 0.6 * arrive), flash);
        }

        // --- teleports ----------------------------------------------------
        // Where she was: a puff of silver that spreads and thins. Where she
        // is: a burst that races outward from her. Nothing in between,
        // because nothing in between happened.
        double vanish = decay(vanishedAtMillis, elapsedMillis, VANISH_MILLIS);
        if (vanish > 0) {
            double vx = 0.5 + vanishU * 0.5;
            double vy = 0.5 + vanishV * 0.5;
            double r = 0.8 + 1.2 * (1 - vanish);
            RGBColor puff = silver.lightened(0.5);
            for (KeyGrid.LedPosition key : keys) {
                double e = Math.hypot((key.x() - vx) / keyX, (key.y() - vy) / keyY);
                if (e >= r) continue;
                budget.add(key.ref(), puff, (1 - e / r) * vanish * vanish * 1.1);
            }
        }
        if (arrive > 0) {
            double r = 2.4 * (1 - arrive);
            RGBColor burst = silver.lightened(0.6);
            for (KeyGrid.LedPosition key : keys) {
                double e = Math.hypot((key.x() - lastQueenX) / keyX, (key.y() - lastQueenY) / keyY);
                double off = Math.abs(e - r);
                if (off >= 1.0) continue;
                budget.add(key.ref(), burst, (1 - off) * arrive * arrive * 1.4);
            }
        }

        // --- a struck crystal ---------------------------------------------
        double struck = decay(struckAtMillis, elapsedMillis, STRUCK_MILLIS);
        if (struck > 0) {
            double sx = 0.5 + struckU * 0.5;
            double sy = 0.5 + struckV * 0.5;
            for (KeyGrid.LedPosition key : keys) {
                double e = Math.hypot((key.x() - sx) / keyX, (key.y() - sy) / keyY);
                if (e >= 1.4) continue;
                budget.add(key.ref(), RGBColor.WHITE, (1 - e / 1.4) * struck * 1.2);
            }
        }

        // --- you ----------------------------------------------------------
        if (markerVisible) {
            double pulse = 0.75 + 0.25 * Math.sin(2 * Math.PI * 1.1 * seconds);
            for (KeyGrid.LedPosition key : keys) {
                double e = Math.hypot((key.x() - mx) / keyX, (key.y() - my) / keyY);
                if (e >= MARKER_RADIUS_KEYS) continue;
                budget.add(key.ref(), markerColor, Math.sqrt(1 - e / MARKER_RADIUS_KEYS) * pulse * 1.8);
            }
        }

        // --- waking -------------------------------------------------------
        double awake = decay(awakenedAtMillis, elapsedMillis, AWAKEN_MILLIS);
        if (awake > 0) {
            double r = BODY_RADIUS_KEYS + (1 - awake) / keyX;
            for (KeyGrid.LedPosition key : keys) {
                double e = Math.hypot((key.x() - lastQueenX) / keyX, (key.y() - lastQueenY) / keyY);
                double off = Math.abs(e - r);
                double wave = off < 1.5 ? 1 - off / 1.5 : 0;
                budget.add(key.ref(), gold, awake * awake * (0.2 + 1.0 * wave));
            }
        }

        // --- her defeat ---------------------------------------------------
        // Beating her unlocks her dungeon: the locked stone of the room turns
        // to plain stone and the doors open. So she goes out as the room
        // opening, a gold ring running out from where she fell through the
        // walls, and the room warming to gold behind it.
        if (defeated && defeatT < 1) {
            double r = defeatT / 0.7 / keyX;
            double wash = Math.min(1, defeatT * 6) * 0.35 * (1 - defeatT);
            for (KeyGrid.LedPosition key : keys) {
                double e = Math.hypot((key.x() - lastQueenX) / keyX, (key.y() - lastQueenY) / keyY);
                double off = Math.abs(e - r);
                double ring = defeatT < 0.7 && off < 1.8 ? (1 - off / 1.8) * (1 - defeatT / 0.7) * 1.3 : 0;
                budget.add(key.ref(), gold, wash + ring);
            }
        }

        return budget.resolve();
    }

    /**
     * Her body and both wings, centred on a board position.
     *
     * @param span     half the wingspan in keys
     * @param beat     how far the wingtips are raised, in rows; negative lowers them
     * @param override a colour to draw her in instead of silver, or null
     * @param level    overall brightness
     * @param flash    0 to 1 of white laid over the whole figure
     */
    private static void figure(LightBudget budget, List<KeyGrid.LedPosition> keys, double keyX, double keyY,
                               double cx, double cy, double span, double beat, RGBColor silver,
                               RGBColor override, double level, double flash) {
        if (level <= 0.001 && flash <= 0.001) return;
        RGBColor body = override != null ? silver.lerp(override, 0.5) : silver;
        RGBColor core = body.lightened(0.6);
        RGBColor wing = body.lightened(0.15);
        for (KeyGrid.LedPosition key : keys) {
            double dx = (key.x() - cx) / keyX;
            double dy = (key.y() - cy) / keyY;
            double along = Math.abs(dx);
            if (along > span + WING_EDGE_KEYS + BODY_RADIUS_KEYS) continue;
            double e = Math.hypot(dx, dy);

            double lit = 0;
            if (e < BODY_RADIUS_KEYS) {
                double r = e / BODY_RADIUS_KEYS;
                double f = 1 - r * r;
                budget.add(key.ref(), body, (0.5 + 1.0 * f) * level);
                if (e < CORE_RADIUS_KEYS) budget.add(key.ref(), core, (1 - e / CORE_RADIUS_KEYS) * 1.0 * level);
                lit = f;
            }

            if (along < span + WING_EDGE_KEYS) {
                double t = Math.min(1, along / span);
                // The tips move most and the roots not at all, which is what
                // makes it a wingbeat rather than the whole wing sliding.
                double centre = -beat * t * t;
                double half = WING_ROOT_HALF_KEYS * (1 - t) + WING_TIP_HALF_KEYS * t;
                double off = Math.abs(dy - centre);
                double across = off <= half ? 1 : Math.max(0, 1 - (off - half) / WING_EDGE_KEYS);
                double tip = along <= span ? 1 : Math.max(0, 1 - (along - span) / WING_EDGE_KEYS);
                double w = across * tip;
                if (w > 0) {
                    budget.add(key.ref(), wing, w * (0.8 + 0.4 * (1 - t)) * level);
                    lit = Math.max(lit, w);
                }
            }
            if (flash > 0 && lit > 0) budget.add(key.ref(), RGBColor.WHITE, lit * flash * 0.9);
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
