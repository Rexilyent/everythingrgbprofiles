package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.SliderCubePattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * The Aether's Slider: its boss room on the keys, with the cube in it.
 *
 * <p>Same idea as {@link NagaEffect} — draw the boss rather than tint the
 * board — for a boss whose identity is how it moves. See
 * {@link SliderCubePattern} for the drawing and for why the board is a
 * north-up map of the room.
 *
 * <h2>Read entirely off things the client already knows</h2>
 * Position and health are vanilla and synced. Everything else is worked out
 * from them, or from vanilla state that happens to line up with the Aether's:
 *
 * <ul>
 *   <li><b>Awake</b> — the Slider keeps a synced {@code awake} flag, but it is
 *       private to an Aether class, and this mod never imports another mod's
 *       classes (see {@code ModCompatRegistry}). The same fact is visible
 *       another way: its boss bar is created hidden, shown by the hit that
 *       wakes it, hidden again when it resets, and plays boss music. So "a boss
 *       bar with music is up, and a Slider is in range" is "the Slider is
 *       awake", through vanilla's public boss overlay alone. Movement counts
 *       too, because a sleeping Slider cannot move at all — which covers you
 *       watching someone else's fight, where the bar is never sent to you.</li>
 *   <li><b>Slams</b> — a slide ends by stopping dead, whether it hit a wall or
 *       reached the spot it was aiming for. Seen from the client, that is
 *       speed collapsing after a run of it, and the peak speed says how hard
 *       it landed.</li>
 *   <li><b>Hits</b> — health going down. The shove goes along whichever axis
 *       points further away from you, which is the same rule the Slider uses
 *       to pick which way to tilt when it is struck.</li>
 *   <li><b>Critical</b> — a quarter health or less, the Slider's own threshold
 *       for turning red and moving faster.</li>
 * </ul>
 *
 * <h2>Finding the room without being told where it is</h2>
 * The Slider sleeps in the exact centre of its room, and the room resets it
 * there. So the first place a Slider is seen is taken as the centre, and a
 * window the size of the room is kept around it. If the Slider ever leaves the
 * window — because it was first seen mid-fight, away from the middle — the
 * window is dragged along behind it rather than re-centred. A Slider hits
 * every wall of its room within a few slides, so a window that starts in the
 * wrong place ends up on the room anyway, and one that started right never
 * moves.
 */
public final class SliderEffect implements EffectController {

    /**
     * How far the Slider's centre can travel from the middle of its room, in
     * blocks. The Bronze Dungeon boss room is 16 blocks across including its
     * walls, which leaves 14 of floor, and a cube two blocks wide stops with
     * its centre 6 from the middle.
     */
    public static final double ROOM_REACH_BLOCKS = 6.0;
    /** Half the room's floor, which is how far you can stand from the middle. */
    private static final double ROOM_FLOOR_HALF_BLOCKS = 7.0;
    /**
     * Height the lift is measured against. The room is twelve blocks tall
     * inside, but the Slider only climbs to chase someone who has climbed, and
     * rarely by more than a few blocks.
     */
    private static final double LIFT_REACH_BLOCKS = 6.0;
    /** {@code Slider.isCritical}: health at or below a quarter. */
    private static final double CRITICAL_FRACTION = 0.25;

    /** Blocks per second above which it counts as moving. */
    private static final double MOVING_SPEED = 1.0;
    /**
     * Peak speed a slide has to have reached for its stop to count as a slam.
     * The Slider accelerates from nothing, so even a two-block slide gets past
     * this; a slower stop is a nudge not worth a shockwave.
     */
    private static final double SLAM_PEAK_SPEED = 5.0;
    /** Peak speed that makes the heaviest slam. About what a slide the width of the room ends at. */
    private static final double FULL_SLAM_SPEED = 20.0;
    /**
     * Faster than any slide. The Slider tops out at 2.5 blocks a tick, which is
     * 50 a second, and never reaches that inside one room; anything above this
     * is the room teleporting it back to the middle.
     */
    private static final double TELEPORT_SPEED = 60.0;
    /** How quickly a remembered peak speed is forgotten, so a slow coast to a halt is not a slam. */
    private static final double PEAK_DECAY_SECONDS = 0.3;
    /** How long after it last moved a Slider still counts as awake without a boss bar. */
    private static final long MOTION_MEANS_AWAKE_MILLIS = 2500;
    /** Speed at which the trail is drawn at full length. */
    private static final double FULL_TRAIL_SPEED = 14.0;
    /**
     * Shortest gap between two hit flashes. Vanilla's hurt immunity already
     * stops a mob losing health more than about twice a second, so in game
     * this never swallows a real hit. What it stops is health that falls
     * continuously — the tuner sweeping a whole fight into a few seconds —
     * holding the flash on permanently and turning the stone white.
     */
    private static final long MIN_HIT_GAP_MILLIS = 500;

    /**
     * Per-frame easing for the runes lighting and turning red. Frame-rate
     * dependent, and fine for the same reason as the Elder Guardian's spikes:
     * a cosmetic ease on the one steady SDK thread.
     */
    private static final double SMOOTHING = 0.08;
    /** How long the board holds after a death before the fade starts, so the cube's burst plays out first. */
    private static final long SHATTER_HOLD_MILLIS = 1300;

    private final int priority;
    private final SliderCubePattern cube = new SliderCubePattern();

    private volatile boolean present = false;
    /** When present last flipped, and what the fade level was at that instant. */
    private volatile long changedAtMillis = Long.MIN_VALUE;
    private volatile double presenceAtChange = 0;
    private volatile double fadeInSeconds = 0.5;
    private volatile double fadeOutSeconds = 1.2;

    private volatile RGBColor stoneColor = ColorPalette.SLIDER_STONE;
    private volatile RGBColor runeColor = ColorPalette.SLIDER_RUNE;
    private volatile RGBColor criticalColor = ColorPalette.SLIDER_CRITICAL;

    // Everything below is only ever touched on the SDK worker thread: the
    // setters arrive through SdkWorkerThread.enqueue, and render runs there.
    private boolean tracking = false;
    private double anchorX, anchorY, anchorZ;
    /** The two most recent samples, so render can glide between them rather than stepping at 20Hz. */
    private double prevX, prevY, prevZ;
    private long prevAtMillis;
    private double curX, curY, curZ;
    private long curAtMillis;
    private double playerX, playerZ;
    private double health = 1.0;
    private boolean awake = false;
    private double speed = 0;
    private double peakSpeed = 0;
    private int travelU = 0;
    private int travelV = 0;
    private long lastMovedAtMillis = Long.MIN_VALUE;
    private long lastHitAtMillis = Long.MIN_VALUE;
    private long deadAtMillis = Long.MIN_VALUE;
    private double shownIgnition = 0;
    private double shownCritical = 0;

    public SliderEffect(int priority) {
        this.priority = priority;
    }

    public void setColors(RGBColor stone, RGBColor rune, RGBColor critical) {
        this.stoneColor = stone;
        this.runeColor = rune;
        this.criticalColor = critical;
    }

    public void setFadeTimes(double fadeInSeconds, double fadeOutSeconds) {
        this.fadeInSeconds = Math.max(0.05, fadeInSeconds);
        this.fadeOutSeconds = Math.max(0.05, fadeOutSeconds);
    }

    /**
     * One sighting of a living Slider.
     *
     * @param x              world position of the Slider
     * @param y              its feet, which is what the lift is measured from
     * @param z              world position of the Slider
     * @param playerX        your position, for the marker and the direction of a hit
     * @param playerZ        your position
     * @param healthFraction 0 to 1
     * @param bossMusic      whether a boss bar that plays music is on screen
     */
    public void setSlider(double x, double y, double z, double playerX, double playerZ,
                          double healthFraction, boolean bossMusic, long nowMillis) {
        double newHealth = Math.max(0, Math.min(1, healthFraction));
        if (!tracking) {
            tracking = true;
            anchorX = x;
            anchorY = y;
            anchorZ = z;
            prevX = curX = x;
            prevY = curY = y;
            prevZ = curZ = z;
            prevAtMillis = curAtMillis = nowMillis;
            speed = 0;
            peakSpeed = 0;
            lastMovedAtMillis = Long.MIN_VALUE;
            lastHitAtMillis = Long.MIN_VALUE;
            health = newHealth;
            // Taken as it is found rather than as a change: walking into a
            // fight already under way is not the moment the Slider woke.
            awake = bossMusic;
            shownIgnition = awake ? 1 : 0;
            shownCritical = awake && newHealth <= CRITICAL_FRACTION ? 1 : 0;
            cube.reset();
        } else {
            track(x, y, z, nowMillis);
        }

        if (deadAtMillis != Long.MIN_VALUE) {
            deadAtMillis = Long.MIN_VALUE;
            cube.restore();
        }
        followRoom(x, y, z);
        this.playerX = playerX;
        this.playerZ = playerZ;

        boolean nowAwake = bossMusic
                || (lastMovedAtMillis != Long.MIN_VALUE
                && nowMillis - lastMovedAtMillis < MOTION_MEANS_AWAKE_MILLIS);
        if (nowAwake && !awake) cube.ignite(nowMillis);
        // Waking restores it to full health, so the hit that wakes it never
        // reads as damage here — health goes up, not down.
        if (nowAwake && newHealth < health - 1e-4
                && (lastHitAtMillis == Long.MIN_VALUE || nowMillis - lastHitAtMillis >= MIN_HIT_GAP_MILLIS)) {
            lastHitAtMillis = nowMillis;
            double awayX = curX - playerX;
            double awayZ = curZ - playerZ;
            boolean alongX = Math.abs(awayX) >= Math.abs(awayZ);
            cube.hurt(alongX ? (int) Math.signum(awayX) : 0,
                    alongX ? 0 : (int) Math.signum(awayZ), nowMillis);
        }
        if (nowAwake && newHealth <= CRITICAL_FRACTION && health > CRITICAL_FRACTION) {
            cube.enrage(criticalColor, nowMillis);
        }
        awake = nowAwake;
        health = newHealth;
        flip(true, nowMillis);
    }

    /**
     * The Slider's health reached zero. The cube bursts, and the board holds
     * long enough for the burst to be seen before it fades.
     *
     * <p>The fade is scheduled rather than started: {@code flip} is handed a
     * moment in the future, and {@link #presence} holds at the current level
     * until that moment arrives and ramps down from there.
     */
    public void setDead(long nowMillis) {
        if (deadAtMillis != Long.MIN_VALUE) return;
        deadAtMillis = nowMillis;
        cube.shatter(nowMillis);
        flip(false, nowMillis + SHATTER_HOLD_MILLIS);
    }

    /** The Slider is out of range. The room fades; the next Slider seen is found afresh. */
    public void setGone(long nowMillis) {
        flip(false, nowMillis);
        tracking = false;
    }

    /** Off immediately, for the world going away, when there is nothing left to fade into. */
    public void clear() {
        present = false;
        changedAtMillis = Long.MIN_VALUE;
        presenceAtChange = 0;
        tracking = false;
        deadAtMillis = Long.MIN_VALUE;
        cube.reset();
    }

    private void track(double x, double y, double z, long nowMillis) {
        double dt = (nowMillis - curAtMillis) / 1000.0;
        if (dt <= 0) {
            // Same instant: take the newer position without inventing a speed
            // out of a division by zero.
            curX = x;
            curY = y;
            curZ = z;
            return;
        }
        double dx = x - curX;
        double dy = y - curY;
        double dz = z - curZ;
        double measured = Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;

        prevX = curX;
        prevY = curY;
        prevZ = curZ;
        prevAtMillis = curAtMillis;
        curX = x;
        curY = y;
        curZ = z;
        curAtMillis = nowMillis;

        if (dt > 0.5 || measured > TELEPORT_SPEED) {
            // A gap in the samples, or a jump no slide could make. Neither is
            // motion, and reading one as a slide that stopped would put a slam
            // on the board for something that never hit anything — nor should
            // render glide the cube across the room to its new spot.
            speed = 0;
            peakSpeed = 0;
            prevX = x;
            prevY = y;
            prevZ = z;
            prevAtMillis = nowMillis;
            return;
        }

        speed = measured;
        if (measured > MOVING_SPEED) {
            lastMovedAtMillis = nowMillis;
            double ax = Math.abs(dx);
            double ay = Math.abs(dy);
            double az = Math.abs(dz);
            if (ax >= az && ax >= ay) {
                travelU = (int) Math.signum(dx);
                travelV = 0;
            } else if (az >= ay) {
                travelU = 0;
                travelV = (int) Math.signum(dz);
            } else {
                travelU = 0;
                travelV = 0;
            }
        }
        peakSpeed = Math.max(measured, peakSpeed * Math.exp(-dt / PEAK_DECAY_SECONDS));
        if (measured < MOVING_SPEED && peakSpeed > SLAM_PEAK_SPEED) {
            cube.impact(Math.min(1.0, peakSpeed / FULL_SLAM_SPEED), travelU, travelV, nowMillis);
            peakSpeed = 0;
        }
    }

    /** Drags the room window along behind a Slider that has left it. See the class notes. */
    private void followRoom(double x, double y, double z) {
        if (x > anchorX + ROOM_REACH_BLOCKS) anchorX = x - ROOM_REACH_BLOCKS;
        else if (x < anchorX - ROOM_REACH_BLOCKS) anchorX = x + ROOM_REACH_BLOCKS;
        if (z > anchorZ + ROOM_REACH_BLOCKS) anchorZ = z - ROOM_REACH_BLOCKS;
        else if (z < anchorZ - ROOM_REACH_BLOCKS) anchorZ = z + ROOM_REACH_BLOCKS;
        if (y < anchorY) anchorY = y;
    }

    /**
     * How much of the room is on screen, 0 to 1. Clock-driven, the same way as
     * {@code NagaEffect}, so walking out of the room and straight back in
     * picks the fade up from wherever it had got to.
     */
    private double presence(long nowMillis) {
        if (changedAtMillis == Long.MIN_VALUE) return present ? 1 : 0;
        double elapsed = Math.max(0, (nowMillis - changedAtMillis) / 1000.0);
        return present
                ? Math.min(1.0, presenceAtChange + elapsed / fadeInSeconds)
                : Math.max(0.0, presenceAtChange - elapsed / fadeOutSeconds);
    }

    private void flip(boolean nowPresent, long nowMillis) {
        if (nowPresent == present && changedAtMillis != Long.MIN_VALUE) return;
        presenceAtChange = presence(nowMillis);
        changedAtMillis = nowMillis;
        present = nowPresent;
    }

    @Override
    public String id() {
        return "slider";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER1_OPAQUE_BASE;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isActive(long nowMillis) {
        // Stays active through the fade-out, or there would be nothing left
        // to fade. See EffectController#layerOpacity.
        return presence(nowMillis) > 0.004;
    }

    @Override
    public boolean suppressesAmbientOverlays(long nowMillis) {
        return presence(nowMillis) > 0.004;
    }

    @Override
    public double layerOpacity(long nowMillis) {
        return presence(nowMillis);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        if (presence(nowMillis) <= 0.004) return Map.of();

        boolean dead = deadAtMillis != Long.MIN_VALUE;
        shownIgnition += ((awake ? 1 : 0) - shownIgnition) * SMOOTHING;
        boolean critical = awake && health <= CRITICAL_FRACTION;
        shownCritical += ((critical ? 1 : 0) - shownCritical) * SMOOTHING;

        // One sample behind, gliding from the previous position to the latest
        // over the time the two were apart. A tick of lag buys a cube that
        // slides rather than hopping a key and a half every 50ms, which is
        // what a fast slide covers per tick on a full-size board.
        double span = curAtMillis - prevAtMillis;
        double t = span > 0 ? Math.max(0, Math.min(1, (nowMillis - curAtMillis) / span)) : 1;
        double x = prevX + (curX - prevX) * t;
        double y = prevY + (curY - prevY) * t;
        double z = prevZ + (curZ - prevZ) * t;

        double lift = Math.max(0, Math.min(1, (y - anchorY) / LIFT_REACH_BLOCKS));
        double glow = 0.9 + 0.3 * (1 - health) + 0.15 * shownCritical;
        cube.setCube((x - anchorX) / ROOM_REACH_BLOCKS, (z - anchorZ) / ROOM_REACH_BLOCKS,
                lift, glow, shownIgnition, speed / FULL_TRAIL_SPEED);
        cube.setMarker((playerX - anchorX) / ROOM_FLOOR_HALF_BLOCKS,
                (playerZ - anchorZ) / ROOM_FLOOR_HALF_BLOCKS, !dead, ColorPalette.SLIDER_PLAYER);

        RGBColor rune = runeColor.lerp(criticalColor, shownCritical);
        PatternContext ctx = new PatternContext(grid, null, stoneColor, rune, 0, PatternParams.EMPTY);
        return cube.render(ctx, nowMillis);
    }
}
