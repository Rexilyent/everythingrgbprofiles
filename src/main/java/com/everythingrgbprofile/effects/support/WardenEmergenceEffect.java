package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.RingContractPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.HashMap;
import java.util.Map;

/**
 * A Warden hauling itself out of the floor, for as long as that actually
 * takes.
 *
 * <h2>Why this is not a MomentaryFlashEffect</h2>
 * It was one, and it borrowed {@code shriekerDurationMillis} — 900ms — for its
 * length. Vanilla's {@code WardenAi.EMERGE_DURATION} is
 * {@code Mth.ceil(133.6)} = 134 ticks, or <b>6.7 seconds</b>. So the effect
 * covered the first thirteen percent of the emergence and then stopped, while
 * the Warden was still visibly climbing out of the ground and had not moved
 * yet. It read as the effect breaking rather than ending.
 *
 * <p>Padding the duration to 6700ms would mostly work, and would be wrong for
 * the same reason {@link PortalTransitionEffect} does not use a stopwatch: the
 * length of the thing being depicted is not ours to assume. The trigger is
 * observed on a one-second poll, so a fixed length drifts against the animation
 * by up to a second in either direction, and any pack that alters the Warden
 * makes the constant a lie. Instead the hold is <b>open-ended</b> and closed by
 * {@link #release} when the Warden actually leaves its emerging pose.
 *
 * <h2>Why Tier 2</h2>
 * Identical reasoning to {@link PortalTransitionEffect}. Tier 3 blanks
 * everything beneath it, which makes fading back into the Warden presence
 * layer not merely difficult but definitionally impossible — and holding a
 * blanked board for nearly seven seconds throws away the presence wash at
 * exactly the moment it is most worth seeing. As a Tier 2 overlay the ring
 * closes <i>over</i> that dark wash and then dissolves into it.
 *
 * <p>Nothing is lost by leaving Tier 3, because
 * {@link #tier3SuppressionFloor} holds off every lesser interrupt for the
 * duration anyway. The floor sits below death (100) on purpose: dying during a
 * Warden emergence is information you still need.
 *
 * <h2>The ring repeats</h2>
 * {@link RingContractPattern} shapes one contraction against
 * {@code ctx.durationMillis()}. Stretching a single contraction across seven
 * seconds would creep inward so slowly it would read as static. So it is
 * driven on a short cycle instead, one closing ring per pulse, which turns the
 * whole emergence into a repeating heartbeat closing in — and gets more
 * intense as it goes.
 */
public final class WardenEmergenceEffect implements EffectController {

    private final int priority;

    private volatile long triggeredAtMillis = Long.MIN_VALUE;
    /** One ring contraction. Short, because it repeats. */
    private volatile long pulseMillis = 1100;
    /** Never shorter than this, even if the pose reading releases immediately. */
    private volatile long minHoldMillis = 2000;
    private volatile long fadeMillis = 1200;
    /** Hard cap, so a Warden that never leaves the pose costs a long effect and not a stuck one. */
    private volatile long maxTotalMillis = 14000;
    /** Roughly how long an emergence runs, used only to shape the build-up. */
    private volatile long nominalMillis = 6700;

    private volatile boolean awaitingRelease = false;
    private volatile long fadeStartMillis = Long.MAX_VALUE;
    private volatile int suppressionFloor = 96;
    private volatile RGBColor color = RGBColor.WHITE;
    private volatile Pattern ring;

    public WardenEmergenceEffect(int priority) {
        this.priority = priority;
    }

    /**
     * One is coming out of the ground.
     *
     * @param waitForRelease when false the effect runs to {@code minHold} and
     *                       fades on its own — the path taken for a Warden that
     *                       merely walked into range, which is a warning rather
     *                       than an event and has no pose to wait on.
     */
    public synchronized void trigger(long nowMillis, RGBColor color, double ringThicknessKeys,
                                     long pulseMillis, long minHoldMillis, long fadeMillis,
                                     long maxTotalMillis, long nominalMillis, boolean waitForRelease) {
        this.color = color;
        this.ring = new RingContractPattern(ringThicknessKeys);
        this.triggeredAtMillis = nowMillis;
        this.pulseMillis = Math.max(120, pulseMillis);
        this.minHoldMillis = Math.max(0, minHoldMillis);
        this.fadeMillis = Math.max(1, fadeMillis);
        this.nominalMillis = Math.max(1, nominalMillis);
        // The cap can never be tighter than one complete uninterrupted
        // envelope, or a small value would silently truncate the dissolve.
        this.maxTotalMillis = Math.max(this.minHoldMillis + this.fadeMillis, maxTotalMillis);
        this.awaitingRelease = waitForRelease;
        this.fadeStartMillis = waitForRelease ? Long.MAX_VALUE : nowMillis + this.minHoldMillis;
    }

    /**
     * "It's out." Schedules the dissolve.
     *
     * <p>Idempotent, and a no-op if this run was never waiting on a pose.
     *
     * @return true if this call is what scheduled the fade, for debug logging.
     */
    public synchronized boolean release(long nowMillis) {
        if (!awaitingRelease) return false;
        awaitingRelease = false;
        if (triggeredAtMillis == Long.MIN_VALUE) return false;
        // A run that already hit the cap and dissolved must STAY dissolved —
        // otherwise a late release pushes fadeStartMillis back into the future
        // and relights the board seconds after the Warden finished arriving.
        if (nowMillis >= endMillis()) return false;
        fadeStartMillis = Math.max(triggeredAtMillis + minHoldMillis, nowMillis);
        return true;
    }

    /** Abandon immediately, no fade. For world unload. */
    public synchronized void cancel() {
        this.triggeredAtMillis = Long.MIN_VALUE;
        this.awaitingRelease = false;
        this.fadeStartMillis = Long.MAX_VALUE;
        this.ring = null;
    }

    /**
     * While unreleased, {@code fadeStartMillis} is MAX_VALUE, so this returns
     * the clamp — "hold indefinitely" degrades naturally into "hold until the
     * cap, then dissolve properly". The subtraction is what guarantees the
     * fade is never truncated: the hold gives up room, not the dissolve.
     */
    private long effectiveFadeStart() {
        return Math.min(fadeStartMillis, triggeredAtMillis + maxTotalMillis - fadeMillis);
    }

    private long endMillis() {
        return effectiveFadeStart() + fadeMillis;
    }

    public void setSuppressionFloor(int floor) {
        this.suppressionFloor = floor;
    }

    @Override
    public int tier3SuppressionFloor(long nowMillis) {
        return isActive(nowMillis) ? suppressionFloor : 0;
    }

    @Override
    public String id() {
        return "warden_emergence";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER2_OVERLAY;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isActive(long nowMillis) {
        return triggeredAtMillis != Long.MIN_VALUE && nowMillis < endMillis();
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        Pattern r = ring;
        if (r == null) return Map.of();
        long elapsed = nowMillis - triggeredAtMillis;
        if (elapsed < 0 || nowMillis >= endMillis()) return Map.of();

        // Hold at full until the dissolve is scheduled, then ease down.
        long fadeStart = effectiveFadeStart();
        double envelope;
        if (nowMillis <= fadeStart) {
            envelope = 1.0;
        } else {
            double p = (double) (nowMillis - fadeStart) / Math.max(1, fadeMillis);
            envelope = 1.0 - smoothstep(Math.min(1.0, p));
        }
        if (envelope <= 0) return Map.of();

        // The build-up. Rings start a little dim and a little cool and arrive
        // at full brightness around the time the Warden is fully out, so the
        // sequence gains weight instead of repeating flatly. Ramped on a
        // NOMINAL length rather than the real one, because the real one is not
        // known until it ends — and being slightly off on a build-up is
        // invisible, unlike being slightly off on an ending.
        double build = Math.min(1.0, elapsed / (double) nominalMillis);
        double intensity = 0.65 + 0.35 * build;
        RGBColor pulseColor = color.lerp(color.lightened(0.45), build);

        // Phase within the current contraction. Period is fixed for the whole
        // run on purpose: shortening it mid-flight would jump the phase and
        // visibly stutter the ring.
        long phase = elapsed % pulseMillis;
        PatternContext ctx = new PatternContext(grid, null, pulseColor, null, pulseMillis, PatternParams.EMPTY);

        Map<KeyGrid.LedRef, LayerPixel> raw = r.render(ctx, phase);
        double scale = envelope * intensity;
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>(raw.size());
        for (Map.Entry<KeyGrid.LedRef, LayerPixel> entry : raw.entrySet()) {
            out.put(entry.getKey(), entry.getValue().scaledAlpha(scale));
        }
        return out;
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }
}
