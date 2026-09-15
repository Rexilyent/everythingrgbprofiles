package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorRamp;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.HashMap;
import java.util.Map;

/**
 * The arrival: the pillar field keeps spinning straight through the dimension
 * change and then dissolves back into the biome layer. Half two of the portal
 * sequence — {@link PortalChargeEffect} is half one.
 *
 * <p>This class has been rewritten twice for two completely different reasons.
 * Both are documented below, because both were non-obvious and both will look
 * like arbitrary complexity to whoever reads this next.
 *
 * <h2>Rewrite 1: it used to be a Tier 3 flash</h2>
 * Tier 3's defining behaviour is blanking everything beneath it. So what a
 * player actually experienced was: dwell overlay → <b>hard cut to black</b> →
 * spiral → whole-board white bloom → <b>hard cut back</b> to the biome layer.
 *
 * <p>Three discontinuities and a flashbang, for something that was supposed to
 * read as one continuous event. Now:
 *
 * <ul>
 *   <li><b>No arrival flash at all.</b> The {@code sharpArrival} /
 *       {@code arrivalFlashStyle} bloom is gone entirely. The field holds, then
 *       fades. Turns out the flash was never the good part.</li>
 *   <li><b>It fades into the biome instead of cutting.</b> Tier 2 now, with the
 *       pattern composited over its own black core <i>inside this class</i>
 *       before being handed over with a single global alpha. At alpha 1 that is
 *       pixel-identical to the old Tier 3 blank-then-draw — so nothing
 *       regressed — and easing that alpha to 0 gives a true crossfade down to
 *       whatever Tier 1 is rendering. No blank, no pop.</li>
 *   <li><b>The spin is continuous with the dwell.</b> {@code phaseOffsetMillis}
 *       carries the charge effect's clock across, so the arm doesn't snap back
 *       to phase zero at the exact moment you're staring at it.</li>
 *   <li><b>It holds the board.</b> {@link #tier3SuppressionFloor} stays raised
 *       for the whole sequence, not just the dwell, so an advancement or a
 *       lightning strike landing mid-transition can no longer steal it.</li>
 * </ul>
 *
 * <h2>Rewrite 2: the fade was finishing while you couldn't see it</h2>
 * The arrival used to run on a fixed stopwatch: hold for
 * {@code holdFraction} of {@code durationMillis}, then fade for the rest.
 * Sounds fine. Wasn't.
 *
 * <p>That stopwatch starts at the dimension change, which this mod
 * deliberately detects <b>as early as it possibly can</b> — {@code
 * LevelEvent.Load}, ahead of the multi-second client stall where
 * Iris/Sodium/Flywheel rebuild everything. And the SDK worker has its own
 * thread, so the animation happily kept playing during that stall.
 *
 * <p>Result at the old 4200ms/0.5 default: the board began dimming 2.1s in and
 * was fully back to the biome layer well before "Downloading terrain" cleared.
 * The player looked up from a <i>finished</i> keyboard as they landed. The
 * effect wasn't broken, wasn't late, and was completely invisible.
 *
 * <p>Picking a bigger number does not fix this, and this is the important
 * part: <b>the thing being waited on has no fixed length.</b> It's a chunk
 * load on the player's pack. Anywhere from a few hundred milliseconds to tens
 * of seconds. Any constant you choose is wrong for somebody.
 *
 * <p>So the fade is anchored to the <i>end</i> of the load instead of the
 * start of the transfer. {@link #notifyWorldReady} gets called on the first
 * client tick after {@code ReceivingLevelScreen} closes — the game's own
 * definition of "you are in the world now" — and only then is the fade
 * scheduled, {@code postArrivalHoldMillis} later. Until then the field holds
 * at full indefinitely, bounded by {@code maxTotalMillis} so a load that never
 * completes can't strand the board.
 */
public final class PortalTransitionEffect implements EffectController {

    private final int priority;

    private volatile long triggeredAtMillis = Long.MIN_VALUE;
    /** Shortest time the field holds at full, measured from the transfer. */
    private volatile long minHoldMillis = 2100;
    /** Length of the crossfade down into the biome layer. */
    private volatile long fadeMillis = 2100;
    /** Extra hold after the world is ready — the part the player actually sees. */
    private volatile long postArrivalHoldMillis = 1200;
    /** Absolute ceiling on the whole arrival, load stall included. */
    private volatile long maxTotalMillis = 30000;
    /** True while the fade is deliberately unscheduled, waiting on the load. */
    private volatile boolean awaitingWorldReady = false;
    /** Wall-clock ms the fade begins, or MAX_VALUE while still waiting. */
    private volatile long fadeStartMillis = Long.MAX_VALUE;
    private volatile long phaseOffsetMillis = 0;
    private volatile int suppressionFloor = 96;
    private volatile CoreEmitterPattern field;
    private volatile ColorRamp ramp;

    public PortalTransitionEffect(int priority) {
        this.priority = priority;
    }

    /**
     * @param minHoldMillis         floor on the hold, measured from the transfer
     *                              itself. Covers the case where the destination
     *                              was already loaded and the terrain screen
     *                              closes almost immediately — without it, a fast
     *                              local transfer would fade out instantly.
     * @param fadeMillis            length of the crossfade into the biome layer.
     * @param postArrivalHoldMillis how long the field stays at full AFTER the
     *                              world is ready. This is the knob that
     *                              actually matters — it's the only part of the
     *                              arrival the player is present for.
     * @param maxTotalMillis        ceiling on the whole thing. Only ever reached
     *                              if {@link #notifyWorldReady} never arrives.
     * @param waitForWorldReady     false restores the old fixed-stopwatch
     *                              behaviour, i.e. the bug, on purpose, for
     *                              anyone who wants it.
     * @param phaseOffsetMillis     the dwell's elapsed time at hand-off, so the
     *                              spiral continues rotating rather than
     *                              restarting. Pass 0 if no dwell ran — the
     *                              client tick loop can be frozen straight
     *                              through a portal entry on a heavy pack, in
     *                              which case the arrival is the first thing the
     *                              board shows at all.
     */
    public synchronized void trigger(long nowMillis, ColorRamp ramp, long minHoldMillis, long fadeMillis,
                                     long postArrivalHoldMillis, long maxTotalMillis, boolean waitForWorldReady,
                                     CoreEmitterPattern.Settings settings, long phaseOffsetMillis) {
        this.ramp = ramp;
        CoreEmitterPattern.Settings s = settings.copy();
        s.ramp = ramp;
        CoreEmitterPattern built = new CoreEmitterPattern(s);
        built.setIntensity(1.0); // arrival starts at full; the dwell already did the build-up
        this.field = built;
        this.triggeredAtMillis = nowMillis;
        this.minHoldMillis = Math.max(0, minHoldMillis);
        this.fadeMillis = Math.max(1, fadeMillis);
        this.postArrivalHoldMillis = Math.max(0, postArrivalHoldMillis);
        // The cap can never be tighter than one complete uninterrupted
        // envelope. Otherwise a small maxTotalMillis would silently truncate
        // the fade mid-dissolve, which is the exact abrupt cut this whole
        // class exists to prevent.
        this.maxTotalMillis = Math.max(this.minHoldMillis + this.fadeMillis, maxTotalMillis);
        this.awaitingWorldReady = waitForWorldReady;
        this.fadeStartMillis = waitForWorldReady ? Long.MAX_VALUE : nowMillis + this.minHoldMillis;
        this.phaseOffsetMillis = Math.max(0, phaseOffsetMillis);
    }

    /**
     * "You're in the world now." Called on the first client tick after
     * {@code ReceivingLevelScreen} closes. Schedules the fade.
     *
     * <p>Idempotent, and a no-op if the arrival wasn't waiting on the load.
     *
     * @return true if this call is what scheduled the fade, for debug logging.
     */
    public synchronized boolean notifyWorldReady(long nowMillis) {
        if (!awaitingWorldReady) return false;
        awaitingWorldReady = false;
        if (triggeredAtMillis == Long.MIN_VALUE) return false;
        // An arrival that already hit maxTotalMillis and dissolved must STAY
        // dissolved. Without this guard, a release arriving late — a load that
        // outran the cap, or a disconnect mid-load followed by a rejoin —
        // would push fadeStartMillis back into the future and light the whole
        // board up again out of nowhere, seconds after the portal was over.
        // Deeply confusing to witness. Do not remove.
        if (nowMillis >= endMillis()) return false;
        // max() of the two floors: never fade before the minimum hold has
        // elapsed, AND never before postArrivalHold after landing. Whichever
        // is later wins.
        fadeStartMillis = Math.max(triggeredAtMillis + minHoldMillis, nowMillis + postArrivalHoldMillis);
        return true;
    }

    /**
     * Abandon an arrival outright, with no fade. For world unload only.
     *
     * <p>The open-ended hold is the feature that makes this necessary. An
     * arrival that is still waiting on {@code ReceivingLevelScreen} holds the
     * board at full alpha until either the release arrives or
     * {@code maxDurationMillis} expires — 30 seconds by default. Disconnect
     * mid-transfer and neither happens: {@code pollPortalWorldReady} is driven
     * from the client tick, and the tick loop stops looking the moment
     * {@code mc.level} goes null. The result is half a minute of portal field
     * sitting on top of the main menu.
     *
     * <p>Deliberately not a fade. The world is already gone; there is nothing
     * left to dissolve back into, and a dissolve would just be a slower
     * version of the same wrong thing.
     */
    public synchronized void cancel() {
        this.triggeredAtMillis = Long.MIN_VALUE;
        this.awaitingWorldReady = false;
        this.fadeStartMillis = Long.MAX_VALUE;
        // Dropped so render() short-circuits on the null check even if a frame
        // somehow reads a stale triggeredAtMillis.
        this.field = null;
    }

    /**
     * When the fade actually starts, clamped so the cap is always honoured.
     *
     * <p>While unreleased, {@code fadeStartMillis} is MAX_VALUE, so this
     * returns the clamp — meaning "hold indefinitely" naturally degrades into
     * "hold until the cap, then fade properly" with no special-casing. The
     * subtraction is what guarantees the fade is never truncated: the hold
     * gives up room, not the dissolve.
     */
    private long effectiveFadeStart() {
        long latest = triggeredAtMillis + maxTotalMillis - fadeMillis;
        return Math.min(fadeStartMillis, latest);
    }

    /** Wall-clock ms at which the arrival is fully dissolved. */
    private long endMillis() {
        return effectiveFadeStart() + fadeMillis;
    }

    /** Tier 3 flashes below this priority are held off for the whole sequence. */
    public void setSuppressionFloor(int floor) {
        this.suppressionFloor = floor;
    }

    @Override
    public int tier3SuppressionFloor(long nowMillis) {
        return isActive(nowMillis) ? suppressionFloor : 0;
    }

    @Override
    public String id() {
        return "portal_transition";
    }

    /**
     * Tier 2, not Tier 3. Tier 3 blanks everything beneath it, which makes a
     * fade-to-biome not merely difficult but definitionally impossible.
     */
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
        CoreEmitterPattern f = field;
        ColorRamp r = ramp;
        if (f == null || r == null) return Map.of();

        long elapsed = nowMillis - triggeredAtMillis;
        if (elapsed < 0 || nowMillis >= endMillis()) return Map.of();

        // The whole envelope: hold at full until the fade is scheduled, then
        // ease down to nothing. That's it. No attack, no bloom, no white lerp,
        // no surprises. Everything an earlier version did extra made it worse.
        long fadeStart = effectiveFadeStart();
        double alpha;
        if (nowMillis <= fadeStart) {
            alpha = 1.0;
        } else {
            double p = (double) (nowMillis - fadeStart) / Math.max(1, fadeMillis);
            alpha = 1.0 - smoothstep(Math.min(1.0, p));
        }
        if (alpha <= 0) return Map.of();

        PatternContext ctx = new PatternContext(grid, null, r.sample(1.0), null,
                minHoldMillis + fadeMillis, PatternParams.EMPTY);
        // elapsed + phaseOffset: the dwell's clock carried across, so the arm
        // picks up mid-rotation instead of snapping to zero.
        Map<KeyGrid.LedRef, LayerPixel> raw = f.render(ctx, elapsed + phaseOffsetMillis);

        // Flatten against black FIRST, then apply one global alpha. The order
        // matters and here's why:
        //
        // CoreEmitterPattern encodes its dark core as alpha 0. Hand that
        // straight to the Compositor and the biome shimmer reads through the
        // middle of the spiral — the exact class of bug the old Tier 3 blank
        // existed to work around in the first place.
        //
        // Compositing over black here reproduces that blank precisely, while
        // leaving ONE free alpha to fade the entire thing out with. Best of
        // both: opaque when it should be, and still crossfadeable.
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>(raw.size() * 2);
        for (Map.Entry<KeyGrid.LedRef, LayerPixel> e : raw.entrySet()) {
            LayerPixel px = e.getValue();
            RGBColor flattened = RGBColor.BLACK.lerp(px.color(), px.alpha());
            out.put(e.getKey(), new LayerPixel(flattened, alpha));
        }
        return out;
    }

    /** Local copy rather than borrowing FadePattern's cubic — this wants the gentler quadratic shoulder. */
    private static double smoothstep(double p) {
        return p * p * (3 - 2 * p);
    }
}
