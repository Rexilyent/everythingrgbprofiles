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
 * The dwell: those seconds you spend standing in a portal while the screen
 * warps and the destination loads. Half one of the portal sequence;
 * {@link PortalTransitionEffect} is half two.
 *
 * <h2>Why this exists — the spiral was in the wrong place</h2>
 * This used to be a {@link SustainedOverlayEffect} running
 * {@code PulsePattern(FAST)} in a flat colour. Which is why standing in a
 * portal read as "the keyboard is blinking" rather than "a portal is opening".
 *
 * <p>Meanwhile the good stuff — the fitted pillar field — was only reachable
 * on the <i>arrival</i>: a Tier 3 flash firing once on dimension change, at
 * the old 1200ms default, with 70% of that spent ramping. One rotation of the
 * field takes 1420ms. Do the arithmetic: <b>nobody had ever seen a complete
 * turn of it.</b> The best animation in the mod was rendering into a window
 * too short to contain it.
 *
 * <p>So the spiral moved to where the time actually is. The dwell now runs the
 * same fitted {@link CoreEmitterPattern}, spinning continuously for as long as
 * you stand there, easing up from nothing over {@link #rampMillis}.
 *
 * <p>Tier 2 rather than Tier 3, so the biome shows through while the field is
 * weak and gets progressively buried as it reaches full strength — the world
 * dissolving into the portal, instead of a hard cut to a spiral.
 *
 * <p>Note the pattern instance is rebuilt only when the palette actually
 * changes, never per frame: {@link CoreEmitterPattern} copies its settings bag
 * on construction, so rebuilding per frame would be pure allocation churn on
 * the render thread for zero benefit.
 */
public final class PortalChargeEffect implements EffectController {

    private final int priority;

    private volatile boolean active = false;
    private volatile long activatedAtMillis = 0;
    private volatile long rampMillis = 500;
    private volatile double intensityFloor = 0.35;
    private volatile int suppressionFloor = 90;

    private volatile ColorRamp ramp;
    private volatile CoreEmitterPattern field;

    public PortalChargeEffect(int priority, ColorRamp initialRamp) {
        this.priority = priority;
        setPalette(initialRamp, new CoreEmitterPattern.Settings());
    }

    /**
     * Swaps palette and geometry. Safe to call every time you enter a portal
     * with the same values — identity comparison on the ramp short-circuits
     * it, so the common "same dimension again" case costs one reference
     * compare and allocates nothing.
     */
    public synchronized void setPalette(ColorRamp ramp, CoreEmitterPattern.Settings settings) {
        if (ramp == null) return;
        if (this.ramp == ramp && this.field != null) return;
        CoreEmitterPattern.Settings s = settings != null ? settings.copy() : new CoreEmitterPattern.Settings();
        s.ramp = ramp;
        this.ramp = ramp;
        this.field = new CoreEmitterPattern(s);
    }

    /**
     * Hold the board against Tier 3 interrupts while dwelling.
     *
     * <p>At the default 90 that covers level up, advancement, sculk ping,
     * sleep/wake and lightning — the incidental traffic
     * that, in a large pack, fires often enough to erase the dwell entirely.
     * (See {@link EffectController#tier3SuppressionFloor} for the full story
     * of how that manifested as "the portal effect doesn't work".)
     *
     * <p>Death (100) and the portal's own arrival (92) still preempt, as they
     * must. A Warden emergence is a Tier 2 overlay, so it draws alongside the
     * dwell rather than needing to get past it.
     */
    public void setSuppressionFloor(int floor) {
        this.suppressionFloor = floor;
    }

    @Override
    public int tier3SuppressionFloor(long nowMillis) {
        return active ? suppressionFloor : 0;
    }

    /** How long the field takes to ease from {@link #setIntensityFloor} to full. */
    public void setRampMillis(long millis) {
        this.rampMillis = Math.max(1, millis);
    }

    /** Strength on the very first frame, before the ramp has done anything. */
    public void setIntensityFloor(double floor) {
        this.intensityFloor = Math.max(0.0, Math.min(1.0, floor));
    }

    /** Same off→on timestamp guard as SustainedOverlayEffect; see there for why. */
    public void setActive(boolean nowActive, long nowMillis) {
        if (nowActive && !this.active) {
            this.activatedAtMillis = nowMillis;
        }
        this.active = nowActive;
    }

    @Override
    public String id() {
        return "portal_charge";
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
        return active;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        CoreEmitterPattern f = field;
        ColorRamp r = ramp;
        if (!active || f == null || r == null) return Map.of();

        long elapsed = elapsedMillis(nowMillis);

        // Ease in from a FLOOR, not from zero. This looks like a fudge and is
        // in fact load-bearing.
        //
        // Dwell time varies enormously between portals. A nether portal gives
        // you about four seconds. The Aether and most modded teleporters
        // transfer in 160-420ms — measured against Iris's own dimension-change
        // log line in a 600-mod pack. Ramping from zero meant the field was
        // still at single-digit strength when the dimension changed, so
        // walking into an Aether portal looked like absolutely nothing
        // happened. Starting at the floor makes it legible on frame one while
        // still visibly building toward full.
        double p = Math.min(1.0, elapsed / (double) rampMillis);
        // Inline smoothstep (3p^2 - 2p^3). Eased so the build has a shape
        // rather than sliding up at constant speed.
        double strength = intensityFloor + (1.0 - intensityFloor) * (p * p * (3 - 2 * p));
        f.setIntensity(strength);

        // Elapsed drives the spin, which is why the arm keeps rotating for as
        // long as you stand there instead of freezing on one frame.
        PatternContext ctx = new PatternContext(grid, null, r.sample(1.0), null, 0, PatternParams.EMPTY);
        Map<KeyGrid.LedRef, LayerPixel> raw = f.render(ctx, elapsed);

        // The second half of the fix, and the less obvious one: `strength`
        // also controls how much of the board the dwell OWNS, not just how
        // bright it is.
        //
        // CoreEmitterPattern encodes its dark core and dim regions as low
        // ALPHA. On Tier 2 that means the biome shimmer read through most of
        // the board for the entire dwell. The portal was genuinely active
        // within ~25ms of the hitbox touching it (confirmed in the logs!) and
        // still never looked like it had taken over — which sent this whole
        // area down a latency rabbit hole for a while when the real problem
        // was compositing.
        //
        // Blending toward an opaque black backdrop as the field builds gives
        // the intended "world dissolving into the portal" read. It also hands
        // off to the arrival at the same opacity, so there's no visible step
        // at the moment of transfer.
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>(raw.size() * 2);
        for (Map.Entry<KeyGrid.LedRef, LayerPixel> e : raw.entrySet()) {
            LayerPixel px = e.getValue();
            // Flatten the pattern's own alpha against black...
            RGBColor flattened = RGBColor.BLACK.lerp(px.color(), px.alpha());
            // ...then push alpha toward 1 as strength rises. At strength 0 the
            // pattern's own alpha is untouched; at strength 1 every key is
            // fully opaque and the biome is completely covered.
            double alpha = px.alpha() + (1.0 - px.alpha()) * strength;
            out.put(e.getKey(), new LayerPixel(flattened, alpha));
        }
        return out;
    }

    /**
     * How long the dwell has been running.
     *
     * <p>Read by the arrival at hand-off so it can continue the rotation from
     * where the dwell left it instead of snapping back to phase zero. Small
     * detail, very visible if you get it wrong — the arm would visibly jump at
     * the exact instant the player is most likely to be watching it.
     *
     * <p>Returns 0 if the dwell never ran, which is a real case: on a heavy
     * pack the client tick loop can be frozen straight through a portal entry,
     * so the arrival is sometimes the first thing the board shows at all.
     */
    public long elapsedMillis(long nowMillis) {
        if (!active || activatedAtMillis == 0) return 0;
        return Math.max(0, nowMillis - activatedAtMillis);
    }

    /** Exposed for the tuning studio in {@code tuning/}. */
    public RGBColor peakColor() {
        ColorRamp r = ramp;
        return r != null ? r.sample(1.0) : RGBColor.WHITE;
    }
}
