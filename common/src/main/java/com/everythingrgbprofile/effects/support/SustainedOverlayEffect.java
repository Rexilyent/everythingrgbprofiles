package com.everythingrgbprofile.effects.support;

import java.util.List;
import java.util.Map;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

/**
 * The workhorse for every "on while a thing is true, off otherwise" effect.
 * One class, no subclasses, used by:
 *
 * <ul>
 *   <li>Health Flash, Hunger Warning, Thirst Warning, Temperature Extreme —
 *       Tier 2, confined to a single key each.</li>
 *   <li>Rain and snowfall — Tier 2, whole board.</li>
 *   <li>Warden presence, the menu theme and the death state — Tier 1, whole
 *       board.</li>
 * </ul>
 *
 * <p>Those are wildly different in tier, in scope and in urgency, and every
 * one of them is this class. Which is the point: "hold a pattern on a set of
 * keys while somebody tells me to" is the entire behaviour, and it does not
 * change depending on whether the thing telling you is low HP or a Warden
 * walking up.
 *
 * <p>Zero game logic lives in here. Not one reference to health, to hunger, or
 * to anything else Minecraft-shaped. All of that sits in the event handler,
 * which just calls {@link #setActive}. Keeping that split honest is the only
 * reason one class manages to cover nine features.
 */
public final class SustainedOverlayEffect implements EffectController {

    private final String id;
    private final EffectTier tier;
    private final int priority;
    /** null means the whole primary surface. */
    private final List<KeyGrid.LedRef> targetKeys;
    private final Pattern pattern;

    // volatile: written by worker-thread jobs, read by the render loop.
    private volatile boolean active = false;
    private volatile long activatedAtMillis = 0;
    private volatile RGBColor color;
    private volatile double layerOpacity = 1.0;
    private volatile RGBColor accentColor;
    private volatile boolean exclusive = false;
    private volatile boolean suppressesAmbient = false;
    private volatile int tier3SuppressionFloor = 0;

    public SustainedOverlayEffect(String id, EffectTier tier, int priority, List<KeyGrid.LedRef> targetKeys, Pattern pattern, RGBColor initialColor) {
        this.id = id;
        this.tier = tier;
        this.priority = priority;
        this.targetKeys = targetKeys;
        this.pattern = pattern;
        this.color = initialColor;
    }

    /**
     * Flip it on or off. Called from the worker thread via an enqueued job —
     * never straight from an event handler.
     *
     * <p>The {@code nowActive && !active} guard matters more than it looks:
     * the timestamp is only stamped on a genuine off→on transition. Polling
     * code calls this every tick while a condition holds, and without the
     * guard {@code activatedAtMillis} would be rewritten every tick, pinning
     * elapsed time near zero and freezing the pattern on its first frame
     * forever: a pulse that never pulses. An earlier version did exactly
     * that, which is why the guard is here.
     */
    public void setActive(boolean nowActive, long nowMillis) {
        if (nowActive && !this.active) {
            this.activatedAtMillis = nowMillis;
        }
        this.active = nowActive;
    }

    /** Recolourable live — the temperature warning swaps between hot and cold this way. */
    public void setColor(RGBColor color) {
        this.color = color;
    }

    /**
     * Highlight colour for two-tone patterns. Null (the default) lets
     * {@code PatternContext} derive one by lightening the base, which is right
     * for most things and wrong for anything dark: lightening a near-black
     * teal toward white produces grey, not cyan.
     */
    public SustainedOverlayEffect withAccentColor(RGBColor accent) {
        this.accentColor = accent;
        return this;
    }

    /** See {@link EffectController#suppressesAmbientOverlays}. For encounter layers. */
    public SustainedOverlayEffect withSuppressAmbientOverlays(boolean suppress) {
        this.suppressesAmbient = suppress;
        return this;
    }

    @Override
    public boolean suppressesAmbientOverlays(long nowMillis) {
        return suppressesAmbient && active;
    }

    /** See {@link EffectController#exclusive()}. Death uses it; nothing else should. */
    public SustainedOverlayEffect withExclusive(boolean exclusive) {
        this.exclusive = exclusive;
        return this;
    }

    /** Tier 3 flashes below this priority are held off while this effect is active. */
    public SustainedOverlayEffect withTier3SuppressionFloor(int floor) {
        this.tier3SuppressionFloor = floor;
        return this;
    }

    @Override
    public boolean exclusive() {
        return exclusive;
    }

    @Override
    public int tier3SuppressionFloor(long nowMillis) {
        return active ? tier3SuppressionFloor : 0;
    }

    /**
     * How much of the layer beneath survives. Returns {@code this} so it can
     * be chained onto a constructor call at the registration site.
     *
     * <p>Tier 2 is documented as the translucent tier — "rain over jungle
     * reads as lightened green" — but an earlier version of this class never
     * overrode {@code layerOpacity()}, so every sustained overlay inherited
     * the interface default of 1.0 and hard-replaced the keys it touched. That
     * was survivable while the rain pattern's own per-pixel alpha spent most
     * of a drop's life fading, since the multiplication came out translucent
     * anyway. It stopped being survivable once drops began holding full alpha
     * for the first 80% of the fall: at 1.0 x 1.0 a drop deletes the biome
     * under it outright, which on a dark biome reads as the biome having
     * vanished and left nothing but rain.
     */
    public SustainedOverlayEffect withLayerOpacity(double opacity) {
        this.layerOpacity = Math.max(0.0, Math.min(1.0, opacity));
        return this;
    }

    @Override
    public double layerOpacity(long nowMillis) {
        return layerOpacity;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public EffectTier tier() {
        return tier;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isActive(long nowMillis) {
        return active; // purely externally driven; there's no duration to expire
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        if (!active) return Map.of();
        // Duration 0 because sustained patterns are the endless kind (shimmer,
        // pulse) and ignore it entirely. Passing anything else would be a lie.
        PatternContext ctx = new PatternContext(grid, targetKeys, color, accentColor, 0, PatternParams.EMPTY);
        // Elapsed since ACTIVATION, not since forever — so the pattern always
        // starts at its own frame zero rather than picking up mid-cycle.
        return pattern.render(ctx, nowMillis - activatedAtMillis);
    }
}
