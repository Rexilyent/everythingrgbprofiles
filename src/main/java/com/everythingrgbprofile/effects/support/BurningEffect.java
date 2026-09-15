package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.FlameRisePattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * "You are on fire." A Tier 2 overlay that sets the board alight from the
 * bottom, higher the worse the burning is, and lets it die down when the fire
 * goes out.
 *
 * <p>Built the same way as {@link DrowningEffect}, and a Tier 2 overlay for the
 * same reason: it is information laid over wherever you are, and the biome
 * above the flames is what makes their height readable.
 *
 * <h2>Why the height is not the time left burning</h2>
 * The drowning meter shows air, which the client is sent. The obvious fire
 * equivalent — how long until you stop burning — is not: the client clears its
 * own copy of the fire timer every tick, and only the "on fire" flag is
 * synced. So the height is how dangerous the burning is right now instead,
 * chosen by the event layer from things the client does know: whether Fire
 * Resistance is protecting you, whether you are standing in fire, and whether
 * you are in lava.
 *
 * <p>Base is the hot colour at the bottom of the flames and accent is the
 * tips.
 */
public final class BurningEffect implements EffectController {

    private final FlameRisePattern flames;
    private volatile RGBColor coreColor;
    private volatile RGBColor tipColor;

    public BurningEffect(double panicThreshold, RGBColor coreColor, RGBColor tipColor) {
        this.flames = new FlameRisePattern(panicThreshold);
        this.coreColor = coreColor;
        this.tipColor = tipColor;
    }

    /**
     * @param heat 0 = not burning, 1 = the whole board alight. The pattern
     *             eases toward this.
     */
    public void setHeat(double heat) {
        flames.setLevel(heat);
    }

    public void setColors(RGBColor core, RGBColor tip) {
        this.coreColor = core;
        this.tipColor = tip;
    }

    /** Out immediately, no dying down. For world unload. */
    public void reset() {
        flames.extinguish();
    }

    @Override
    public String id() {
        return "burning";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER2_OVERLAY;
    }

    @Override
    public boolean isActive(long nowMillis) {
        // Asks the pattern rather than the target, so the flames finish dying
        // down after the fire goes out.
        return flames.visible();
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        PatternContext ctx = new PatternContext(grid, null, coreColor, tipColor, 0, PatternParams.EMPTY);
        return flames.render(ctx, nowMillis);
    }
}
