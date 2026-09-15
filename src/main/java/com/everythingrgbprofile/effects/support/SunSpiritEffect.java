package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.SunSpiritPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Aether's Sun Spirit: its boss room on the keys, with the sun, the fire
 * and the crystals in it.
 *
 * <p>Same approach as {@link SliderEffect}, and read the same way: position
 * and health are vanilla and synced, whether it is awake comes from vanilla's
 * boss overlay, and nothing from the Aether is imported. See
 * {@link SunSpiritPattern} for the drawing.
 *
 * <h2>The fight, as the client sees it</h2>
 * The Sun Spirit cannot be hurt by anything but cold while it burns. Every
 * fifth crystal it throws is ice; knock one back into it and it freezes for
 * 175 ticks, slows to under a third of its speed, and for that window your
 * weapons work. So the two things this layer exists to show are where the
 * ice crystals are and how long a freeze has left.
 *
 * <ul>
 *   <li><b>Crystals</b> are ordinary entities the client tracks, so they are
 *       drawn where they are. A crystal id not seen before is a crystal just
 *       thrown, which is what lights the sun as it fires.</li>
 *   <li><b>Fire on the floor</b> is ordinary fire blocks, found by the event
 *       layer and handed over as positions.</li>
 *   <li><b>Frozen</b> is a synced flag private to an Aether class, and this
 *       mod does not import other mods' classes (see
 *       {@code ModCompatRegistry}). The flag has a tell that needs no import,
 *       though: the spirit's speed. It flies at a constant 0.35 blocks a tick,
 *       freezing multiplies that by 0.3, and before the fight it does not
 *       move at all. Seven blocks a second, two, or none — three states far
 *       enough apart that a smoothed speed separates them cleanly.</li>
 *   <li><b>How long the freeze has left</b> is counted from the moment it was
 *       seen to slow, against the 175 ticks the Aether sets. A freeze still
 *       holding when that runs out has been renewed by another ice crystal,
 *       and the count starts again.</li>
 * </ul>
 *
 * <h2>Finding the room</h2>
 * The spirit sits in the exact centre of its room before the fight and flies
 * no more than 9 blocks from that point, so the room is found exactly as the
 * Slider's is: the first sighting is taken as the centre, and a window is
 * dragged along behind the spirit if it ever leaves it.
 */
public final class SunSpiritEffect implements EffectController {

    /** One crystal in flight, in world coordinates. */
    public record Crystal(int id, double x, double z, boolean ice) {
    }

    /** {@code SunSpirit.FlyAroundGoal}'s bound: it turns back at 9 blocks from its origin. */
    private static final double FLIGHT_REACH_BLOCKS = 9.0;
    /** Anything further than this from the room's centre is not in the room. */
    private static final double ROOM_EDGE_BLOCKS = SunSpiritPattern.ROOM_HALF_BLOCKS + 0.5;
    /** How long an ice crystal freezes it: {@code SUN_SPIRIT_FROZEN_DURATION}, 175 ticks. */
    private static final long FREEZE_MILLIS = 175 * 50L;

    /**
     * Horizontal speeds, in blocks per second, that separate its three states.
     * Flying is 7, frozen 2.1, asleep 0. Two thresholds rather than one, so a
     * speed sitting right on the line does not flicker between states.
     */
    private static final double STILL_SPEED = 0.6;
    private static final double FREEZE_BELOW = 4.0;
    private static final double THAW_ABOVE = 5.2;
    /**
     * Smoothing on the measured speed. Short, because a freeze should show
     * within a quarter of a second of the hit, but not nothing: the client
     * interpolates each position update, and a bounce off the edge of its
     * flight area can make one tick's displacement read short.
     */
    private static final double SPEED_SMOOTHING_SECONDS = 0.15;
    /** How long a speed has to hold before it counts as a change of state. */
    private static final long CONFIRM_MILLIS = 250;
    private static final long RENEWAL_MARGIN_MILLIS = 1000;
    private static final long MOTION_MEANS_AWAKE_MILLIS = 2500;
    /** Faster than it can fly: the room putting it back at its origin. */
    private static final double TELEPORT_SPEED = 40.0;
    /** As for the Slider: vanilla's hurt immunity already spaces real hits at least this far apart. */
    private static final long MIN_HIT_GAP_MILLIS = 500;
    /** How long the board holds after a death before the fade starts, so the sunset plays out. */
    private static final long SUNSET_HOLD_MILLIS = 2600;
    private static final double SMOOTHING = 0.10;

    private final int priority;
    private final SunSpiritPattern sun = new SunSpiritPattern();

    private volatile boolean present = false;
    private volatile long changedAtMillis = Long.MIN_VALUE;
    private volatile double presenceAtChange = 0;
    private volatile double fadeInSeconds = 0.5;
    private volatile double fadeOutSeconds = 1.5;

    private volatile RGBColor sunColor = ColorPalette.SUN_SPIRIT_SUN;
    private volatile RGBColor flameColor = ColorPalette.SUN_SPIRIT_FLAME;
    private volatile RGBColor iceColor = ColorPalette.SUN_SPIRIT_ICE;

    // Everything below is only ever touched on the SDK worker thread, as in
    // SliderEffect.
    private boolean tracking = false;
    private double anchorX, anchorZ;
    private double prevX, prevZ;
    private long prevAtMillis;
    private double curX, curZ;
    private long curAtMillis;
    private double playerX, playerZ;
    private double health = 1.0;
    private boolean awake = false;
    private double speed = 0;
    private long lastMovedAtMillis = Long.MIN_VALUE;
    private long lastHitAtMillis = Long.MIN_VALUE;
    private long deadAtMillis = Long.MIN_VALUE;

    /** Seen flying at full speed since it woke. A spirit accelerating from rest passes through the frozen band on the way, and must not read as frozen for it. */
    private boolean hasFlown = false;
    private boolean frozen = false;
    private long slowSinceMillis = Long.MIN_VALUE;
    private long fastSinceMillis = Long.MIN_VALUE;
    private long stillSinceMillis = Long.MIN_VALUE;
    private long freezeEndsAtMillis = Long.MIN_VALUE;

    private double shownIgnition = 0;
    private double shownFrost = 0;

    private final Set<Integer> knownCrystals = new HashSet<>();
    private boolean crystalsSeeded = false;
    /** Last position and time per crystal id, for the speed of one knocked back. */
    private final Map<Integer, double[]> crystalLast = new HashMap<>();

    public SunSpiritEffect(int priority) {
        this.priority = priority;
        sun.setMarker(0, 0, false, ColorPalette.SUN_SPIRIT_PLAYER);
        sun.setDuskColor(ColorPalette.SUN_SPIRIT_DUSK);
    }

    public void setColors(RGBColor sunBody, RGBColor flame, RGBColor ice) {
        this.sunColor = sunBody;
        this.flameColor = flame;
        this.iceColor = ice;
    }

    public void setFadeTimes(double fadeInSeconds, double fadeOutSeconds) {
        this.fadeInSeconds = Math.max(0.05, fadeInSeconds);
        this.fadeOutSeconds = Math.max(0.05, fadeOutSeconds);
    }

    /**
     * One sighting of a living Sun Spirit.
     *
     * @param bossMusic whether a boss bar that plays music is on screen
     */
    public void setSpirit(double x, double z, double playerX, double playerZ,
                          double healthFraction, boolean bossMusic, long nowMillis) {
        double newHealth = Math.max(0, Math.min(1, healthFraction));
        if (!tracking) {
            tracking = true;
            anchorX = x;
            anchorZ = z;
            prevX = curX = x;
            prevZ = curZ = z;
            prevAtMillis = curAtMillis = nowMillis;
            speed = 0;
            lastMovedAtMillis = Long.MIN_VALUE;
            lastHitAtMillis = Long.MIN_VALUE;
            health = newHealth;
            awake = bossMusic;
            hasFlown = false;
            frozen = false;
            slowSinceMillis = Long.MIN_VALUE;
            fastSinceMillis = Long.MIN_VALUE;
            shownIgnition = awake ? 1 : 0;
            shownFrost = 0;
            knownCrystals.clear();
            crystalLast.clear();
            crystalsSeeded = false;
            sun.reset();
        } else {
            track(x, z, nowMillis);
        }

        if (deadAtMillis != Long.MIN_VALUE) {
            deadAtMillis = Long.MIN_VALUE;
            sun.restore();
        }
        if (x > anchorX + FLIGHT_REACH_BLOCKS) anchorX = x - FLIGHT_REACH_BLOCKS;
        else if (x < anchorX - FLIGHT_REACH_BLOCKS) anchorX = x + FLIGHT_REACH_BLOCKS;
        if (z > anchorZ + FLIGHT_REACH_BLOCKS) anchorZ = z - FLIGHT_REACH_BLOCKS;
        else if (z < anchorZ - FLIGHT_REACH_BLOCKS) anchorZ = z + FLIGHT_REACH_BLOCKS;
        this.playerX = playerX;
        this.playerZ = playerZ;

        boolean nowAwake = bossMusic
                || (lastMovedAtMillis != Long.MIN_VALUE
                && nowMillis - lastMovedAtMillis < MOTION_MEANS_AWAKE_MILLIS);
        if (nowAwake && !awake) sun.awaken(nowMillis);
        if (!nowAwake) {
            hasFlown = false;
            frozen = false;
        }
        if (nowAwake && newHealth < health - 1e-4
                && (lastHitAtMillis == Long.MIN_VALUE || nowMillis - lastHitAtMillis >= MIN_HIT_GAP_MILLIS)) {
            lastHitAtMillis = nowMillis;
            sun.hurt(nowMillis);
        }
        awake = nowAwake;
        health = newHealth;
        if (awake) updateFreeze(nowMillis);
        flip(true, nowMillis);
    }

    /** The crystals near the room this tick. Ones outside the room are ignored. */
    public void setCrystals(List<Crystal> crystals, long nowMillis) {
        if (!tracking) return;
        int n = 0;
        double[] u = new double[crystals.size()];
        double[] v = new double[crystals.size()];
        boolean[] ice = new boolean[crystals.size()];
        double[] vu = new double[crystals.size()];
        double[] vv = new double[crystals.size()];
        Set<Integer> seen = new HashSet<>();
        for (Crystal c : crystals) {
            double dx = c.x() - anchorX;
            double dz = c.z() - anchorZ;
            if (Math.abs(dx) > ROOM_EDGE_BLOCKS || Math.abs(dz) > ROOM_EDGE_BLOCKS) continue;
            seen.add(c.id());
            // A crystal id not seen before was just thrown. The first batch
            // after the spirit is found only seeds the set, or walking into a
            // fight in progress would fire every crystal already in the air.
            if (crystalsSeeded && awake && !knownCrystals.contains(c.id())) {
                if (c.ice()) sun.firedIce(nowMillis);
                else sun.fired(nowMillis);
            }
            double[] last = crystalLast.get(c.id());
            double velU = 0;
            double velV = 0;
            if (last != null && nowMillis > last[2]) {
                double dt = (nowMillis - last[2]) / 1000.0;
                if (dt < 0.5) {
                    velU = (c.x() - last[0]) / dt / SunSpiritPattern.ROOM_HALF_BLOCKS;
                    velV = (c.z() - last[1]) / dt / SunSpiritPattern.ROOM_HALF_BLOCKS;
                }
            }
            crystalLast.put(c.id(), new double[]{c.x(), c.z(), nowMillis});
            u[n] = dx / SunSpiritPattern.ROOM_HALF_BLOCKS;
            v[n] = dz / SunSpiritPattern.ROOM_HALF_BLOCKS;
            ice[n] = c.ice();
            vu[n] = velU;
            vv[n] = velV;
            n++;
        }
        crystalLast.keySet().retainAll(seen);
        knownCrystals.clear();
        knownCrystals.addAll(seen);
        crystalsSeeded = true;
        sun.setCrystals(java.util.Arrays.copyOf(u, n), java.util.Arrays.copyOf(v, n),
                java.util.Arrays.copyOf(ice, n), java.util.Arrays.copyOf(vu, n), java.util.Arrays.copyOf(vv, n));
    }

    /** Fire blocks near the room, as the world coordinates of their centres. Ones outside the room are ignored. */
    public void setFires(double[] x, double[] z) {
        if (!tracking || x == null || z == null || x.length != z.length) return;
        double[] u = new double[x.length];
        double[] v = new double[x.length];
        int n = 0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - anchorX;
            double dz = z[i] - anchorZ;
            if (Math.abs(dx) > ROOM_EDGE_BLOCKS || Math.abs(dz) > ROOM_EDGE_BLOCKS) continue;
            u[n] = dx / SunSpiritPattern.ROOM_HALF_BLOCKS;
            v[n] = dz / SunSpiritPattern.ROOM_HALF_BLOCKS;
            n++;
        }
        sun.setFires(java.util.Arrays.copyOf(u, n), java.util.Arrays.copyOf(v, n));
    }

    /** Its health reached zero. The sun sets, and the board holds long enough for that to be seen. */
    public void setDead(long nowMillis) {
        if (deadAtMillis != Long.MIN_VALUE) return;
        deadAtMillis = nowMillis;
        frozen = false;
        sun.sunset(nowMillis);
        // Scheduled rather than started, exactly as SliderEffect#setDead.
        flip(false, nowMillis + SUNSET_HOLD_MILLIS);
    }

    public void setGone(long nowMillis) {
        flip(false, nowMillis);
        tracking = false;
    }

    /** Off immediately, for the world going away. */
    public void clear() {
        present = false;
        changedAtMillis = Long.MIN_VALUE;
        presenceAtChange = 0;
        tracking = false;
        deadAtMillis = Long.MIN_VALUE;
        sun.reset();
    }

    private void track(double x, double z, long nowMillis) {
        double dt = (nowMillis - curAtMillis) / 1000.0;
        if (dt <= 0) {
            curX = x;
            curZ = z;
            return;
        }
        double measured = Math.hypot(x - curX, z - curZ) / dt;
        prevX = curX;
        prevZ = curZ;
        prevAtMillis = curAtMillis;
        curX = x;
        curZ = z;
        curAtMillis = nowMillis;

        if (dt > 0.5 || measured > TELEPORT_SPEED) {
            prevX = x;
            prevZ = z;
            prevAtMillis = nowMillis;
            speed = 0;
            return;
        }
        speed += (measured - speed) * (1 - Math.exp(-dt / SPEED_SMOOTHING_SECONDS));
        if (speed > 1.0) lastMovedAtMillis = nowMillis;
    }

    /** Reads frozen, thawed and renewed off the smoothed speed. See the class notes. */
    private void updateFreeze(long nowMillis) {
        boolean fast = speed >= THAW_ABOVE;
        boolean slow = speed >= STILL_SPEED && speed < FREEZE_BELOW;
        if (fast) hasFlown = true;

        if (!frozen) {
            if (hasFlown && slow) {
                if (slowSinceMillis == Long.MIN_VALUE) slowSinceMillis = nowMillis;
                if (nowMillis - slowSinceMillis >= CONFIRM_MILLIS) {
                    frozen = true;
                    fastSinceMillis = Long.MIN_VALUE;
                    // Counted from when it slowed, not from when the slowing
                    // was confirmed, so the ring runs out when the real freeze does.
                    freezeEndsAtMillis = slowSinceMillis + FREEZE_MILLIS;
                    sun.froze(nowMillis);
                }
            } else {
                slowSinceMillis = Long.MIN_VALUE;
            }
            return;
        }

        if (fast) {
            if (fastSinceMillis == Long.MIN_VALUE) fastSinceMillis = nowMillis;
            if (nowMillis - fastSinceMillis >= CONFIRM_MILLIS) {
                frozen = false;
                slowSinceMillis = Long.MIN_VALUE;
                sun.thawed(nowMillis);
            }
            return;
        }
        fastSinceMillis = Long.MIN_VALUE;
        // Stopped dead. A frozen spirit still flies at a third of its speed;
        // one that is not moving at all has been reset to the middle of its
        // room, or is dying, and is not frozen in any sense worth drawing.
        if (speed < STILL_SPEED) {
            if (stillSinceMillis == Long.MIN_VALUE) stillSinceMillis = nowMillis;
            if (nowMillis - stillSinceMillis >= CONFIRM_MILLIS) {
                frozen = false;
                slowSinceMillis = Long.MIN_VALUE;
                stillSinceMillis = Long.MIN_VALUE;
            }
            return;
        }
        stillSinceMillis = Long.MIN_VALUE;
        // Still frozen well past the end of the count: another ice crystal
        // landed while it was frozen and started a fresh 175 ticks. "Well
        // past" because an ordinary thaw takes the smoothing and the
        // confirmation to be seen, a few hundred milliseconds, and must not be
        // mistaken for a renewal in the meantime.
        if (nowMillis > freezeEndsAtMillis + RENEWAL_MARGIN_MILLIS) {
            freezeEndsAtMillis += FREEZE_MILLIS;
        }
    }

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
        return "sun_spirit";
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
        shownFrost += ((frozen && !dead ? 1 : 0) - shownFrost) * SMOOTHING * 1.5;

        // One sample behind, gliding between the last two, as SliderEffect does.
        double span = curAtMillis - prevAtMillis;
        double t = span > 0 ? Math.max(0, Math.min(1, (nowMillis - curAtMillis) / span)) : 1;
        double x = prevX + (curX - prevX) * t;
        double z = prevZ + (curZ - prevZ) * t;

        double remaining = frozen
                ? Math.max(0, Math.min(1, (freezeEndsAtMillis - nowMillis) / (double) FREEZE_MILLIS))
                : 0;
        sun.setSun((x - anchorX) / SunSpiritPattern.ROOM_HALF_BLOCKS,
                (z - anchorZ) / SunSpiritPattern.ROOM_HALF_BLOCKS,
                health, shownIgnition, shownFrost, remaining);
        sun.setMarker((playerX - anchorX) / SunSpiritPattern.ROOM_HALF_BLOCKS,
                (playerZ - anchorZ) / SunSpiritPattern.ROOM_HALF_BLOCKS, !dead, ColorPalette.SUN_SPIRIT_PLAYER);
        sun.setIceColor(iceColor);

        PatternContext ctx = new PatternContext(grid, null, sunColor, flameColor, 0, PatternParams.EMPTY);
        return sun.render(ctx, nowMillis);
    }
}
