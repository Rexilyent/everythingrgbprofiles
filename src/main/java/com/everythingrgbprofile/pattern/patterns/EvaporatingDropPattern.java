package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Tough As Nails' thirst, as drops of water drying up on the keys. A drop
 * beads up on some random key, sits there for a moment, catches the light,
 * then fizzles out with a faint wisp of it rising off the top. The thirstier
 * you get, the more of them there are and the faster they go. Your keyboard is
 * dehydrated and it would like you to know that it's your fault.
 *
 * <p>One level in, 0..1, same contract as {@link HeartbeatPattern}. Just under
 * the thirst threshold it's one drop every second or so. At an empty thirst
 * bar it's a handful at once, flickering away as fast as they form. Nothing at
 * all above the threshold.
 *
 * <h2>Why the drops don't go anywhere</h2>
 * Water that moves is already taken, twice. Rain runs down the board, and the
 * dripstone biome beads water on its stalactites and drops it on the floor. A
 * cyan drop that fell would be a raindrop with extra steps, and "is it raining
 * or am I thirsty" is a question nobody should have to ask their keyboard
 * mid-fight. So these stay
 * exactly where they formed and disappear in place, which is also just what
 * evaporating looks like.
 *
 * <h2>Why it doesn't look like a twinkle</h2>
 * Plenty of biomes twinkle. A twinkle is a flash, on and gone. A drop here
 * spreads onto its neighbours as it beads up, holds, then pulls back in to the
 * one key and flickers out, and it's that swell and shrink that sells it as
 * water rather than as sparkle. The rising wisp at the end is there for the
 * same reason.
 */
public final class EvaporatingDropPattern implements Pattern {

    /** Drops per second just under the threshold, and at an empty thirst bar. */
    private static final double SLOW_RATE = 0.9;
    private static final double FAST_RATE = 6.0;

    /** How long one drop lasts, start to finish, at the faintest and strongest levels. */
    private static final double SLOW_LIFE_SECONDS = 1.8;
    private static final double FAST_LIFE_SECONDS = 1.0;

    /** Fraction of a drop's life spent beading up, and where the evaporating starts. */
    private static final double BEAD_END = 0.25;
    private static final double EVAPORATE_START = 0.55;

    /** How far the drop spreads onto its neighbours at its biggest, in board heights. A row is about a fifth, so this reaches the next key over and a bit. */
    private static final double SPREAD = 0.34;
    /** How far the wisp rises before it's gone, in board heights. A bit over one row. */
    private static final double WISP_RISE = 0.22;
    private static final double WISP_RADIUS = 0.11;

    /** Peak opacity at the faintest and strongest levels. */
    private static final double FAINT_ALPHA = 0.6;
    private static final double STRONG_ALPHA = 1.0;

    private static final double LEVEL_TAU_SECONDS = 0.6;

    /** Six a second at a second each is six at once. Twenty-four leaves room for the overlap and then some. */
    private static final int MAX_DROPS = 24;

    private volatile double targetLevel = 0;
    private double level = 0;
    private double lastElapsedMillis = 0;
    private double spawnPhase = 0;
    private boolean initialised = false;

    private final KeyGrid.LedPosition[] dropKey = new KeyGrid.LedPosition[MAX_DROPS];
    private final double[] dropStart = new double[MAX_DROPS];
    private final double[] dropLife = new double[MAX_DROPS];
    private final double[] dropSeed = new double[MAX_DROPS];
    private int nextDrop = 0;

    private final Random random = new Random();

    /** 0 = hydrated, 1 = thirst bar empty. Called from the worker thread. */
    public void setLevel(double level) {
        this.targetLevel = Math.max(0.0, Math.min(1.0, level));
    }

    /** Every drop gone at once. For death and world unload. */
    public void stop() {
        this.targetLevel = 0;
        this.initialised = false;
    }

    /** Whether there's anything to draw. Drops already forming get to dry up on their own after you drink. */
    public boolean visible() {
        if (targetLevel > 0.001) return true;
        if (!initialised) return false;
        double now = lastElapsedMillis / 1000.0;
        for (int i = 0; i < MAX_DROPS; i++) {
            if (dropKey[i] != null && now - dropStart[i] < dropLife[i]) return true;
        }
        return false;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        if (!initialised || elapsedMillis < lastElapsedMillis) {
            level = targetLevel;
            lastElapsedMillis = elapsedMillis;
            // Primed so the first drop lands on the very next frame.
            spawnPhase = 0.999;
            for (int i = 0; i < MAX_DROPS; i++) dropKey[i] = null;
            initialised = true;
        }
        double now = elapsedMillis / 1000.0;
        double dt = Math.max(0, (elapsedMillis - lastElapsedMillis) / 1000.0);
        lastElapsedMillis = elapsedMillis;
        if (dt > 0) level += (targetLevel - level) * (1 - Math.exp(-dt / LEVEL_TAU_SECONDS));

        List<KeyGrid.LedPosition> keys = ctx.grid().allKeys();
        if (keys.isEmpty()) return Map.of();

        if (targetLevel > 0.001 && level > 0.005) {
            spawnPhase += dt * (SLOW_RATE + (FAST_RATE - SLOW_RATE) * level);
            // A while loop, not an if. A slow frame at six drops a second can
            // owe more than one, and dropping the extras is how the rate
            // quietly comes out lower than it says on the tin.
            while (spawnPhase >= 1.0) {
                spawnPhase -= 1.0;
                spawn(keys, now);
            }
        }

        double aspect = ctx.grid().aspectRatio();
        if (aspect <= 0) aspect = 1.0;
        double peakAlpha = FAINT_ALPHA + (STRONG_ALPHA - FAINT_ALPHA) * level;
        RGBColor base = ctx.baseColor();
        RGBColor glint = ctx.resolvedAccentColor();

        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        for (int i = 0; i < MAX_DROPS; i++) {
            KeyGrid.LedPosition drop = dropKey[i];
            if (drop == null) continue;
            double t = (now - dropStart[i]) / dropLife[i];
            if (t >= 1) {
                dropKey[i] = null;
                continue;
            }

            // Swell, hold, shrink. The size is how far onto the neighbours it
            // reaches. The core is how lit the key itself is.
            double size;
            double core;
            if (t < BEAD_END) {
                size = smooth(t / BEAD_END);
                core = size;
            } else if (t < EVAPORATE_START) {
                size = 1;
                core = 1;
            } else {
                double gone = (t - EVAPORATE_START) / (1 - EVAPORATE_START);
                size = 1 - smooth(gone * 1.6);
                // The flicker as it goes. Fast, and faster at the end, which
                // is the bit that reads as fizzing rather than fading.
                double flicker = 0.65 + 0.35 * Math.sin(2 * Math.PI * (gone * (6 + 6 * gone) + dropSeed[i]));
                core = (1 - gone * gone) * flicker;
            }

            // The light catches it just as it starts to go. Narrow, so it's a
            // glint and not a colour change.
            double catchLight = Math.exp(-Math.pow((t - EVAPORATE_START) / 0.07, 2));

            // The wisp: a faint point rising off the drop while it dries.
            boolean wisp = t > EVAPORATE_START;
            double wispProgress = wisp ? (t - EVAPORATE_START) / (1 - EVAPORATE_START) : 0;
            double wispY = drop.y() - WISP_RISE * wispProgress;
            double wispStrength = wisp ? 0.5 * Math.sin(Math.PI * wispProgress) : 0;

            // Still walks the whole board per drop, but bails on anything
            // outside a box round it before paying for a square root.
            double reach = Math.max(SPREAD * size, wisp ? WISP_RISE + WISP_RADIUS : 0);
            for (KeyGrid.LedPosition key : keys) {
                double dx = (key.x() - drop.x()) / aspect;
                double dy = key.y() - drop.y();
                if (Math.abs(dx) > reach + 0.01 || Math.abs(dy) > reach + 0.01) continue;
                double dist = Math.hypot(dx, dy);

                double s = 0;
                boolean isDrop = key.ref().equals(drop.ref());
                if (isDrop) {
                    s = core;
                } else if (size > 0 && dist < SPREAD * size) {
                    // Neighbours get a thinner film than the drop itself,
                    // or the drop has no middle and is just a cyan blob.
                    s = 0.55 * size * (1 - dist / (SPREAD * size));
                }
                if (wispStrength > 0) {
                    double wd = Math.hypot(dx, key.y() - wispY) / WISP_RADIUS;
                    if (wd < 1 && !isDrop) s = Math.max(s, wispStrength * (1 - wd));
                }
                if (s < 0.04) continue;

                RGBColor color = isDrop ? base.lerp(glint, catchLight) : base;
                LayerPixel px = new LayerPixel(color, peakAlpha * Math.min(1.0, s));
                // Two drops on neighbouring keys overlap at the edges. The
                // stronger one wins, rather than whichever happened to come
                // later in the buffer.
                LayerPixel prior = out.get(key.ref());
                if (prior == null || prior.alpha() < px.alpha()) out.put(key.ref(), px);
            }
        }
        return out;
    }

    /**
     * A new drop on a random key. Tries a few times for a key that isn't
     * already wet, and settles for a wet one if the board's that crowded,
     * because a drop landing on another drop is less wrong than a missing one.
     */
    private void spawn(List<KeyGrid.LedPosition> keys, double now) {
        KeyGrid.LedPosition pick = keys.get(random.nextInt(keys.size()));
        for (int attempt = 0; attempt < 4 && isWet(pick, now); attempt++) {
            pick = keys.get(random.nextInt(keys.size()));
        }
        dropKey[nextDrop] = pick;
        dropStart[nextDrop] = now;
        dropLife[nextDrop] = (SLOW_LIFE_SECONDS + (FAST_LIFE_SECONDS - SLOW_LIFE_SECONDS) * level)
                * (0.85 + 0.3 * random.nextDouble());
        dropSeed[nextDrop] = random.nextDouble();
        nextDrop = (nextDrop + 1) % MAX_DROPS;
    }

    private boolean isWet(KeyGrid.LedPosition key, double now) {
        for (int i = 0; i < MAX_DROPS; i++) {
            if (dropKey[i] != null && dropKey[i].ref().equals(key.ref()) && now - dropStart[i] < dropLife[i]) return true;
        }
        return false;
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
