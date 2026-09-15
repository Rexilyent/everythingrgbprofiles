package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.WaterRisePattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * "You are running out of air." A Tier 2 overlay that floods the board from
 * the bottom as breath runs out, and drains when you surface.
 *
 * <p>Tier 2 for the same reason the rain cascade is: it is information laid
 * over wherever you happen to be, not a replacement for it. The biome keeps
 * showing above the waterline, which is what makes the line itself legible as
 * a level rather than as a colour change.
 *
 * <h2>Driven by a level, not a boolean</h2>
 * {@link SustainedOverlayEffect} covers "on while a thing is true", and that
 * is the wrong shape here — the whole point is the value in between. This is
 * the same arrangement {@link NightIndicatorEffect} uses: a small effect class
 * whose only job is to hold a number and hand it to a pattern that knows what
 * to do with it.
 *
 * <p>Both colours come through the context: base is the deep water and accent
 * is the surface, so the pattern can shade by depth without either being
 * hardcoded.
 */
public final class DrowningEffect implements EffectController {

    private final WaterRisePattern water;
    private volatile RGBColor deepColor;
    private volatile RGBColor surfaceColor;

    public DrowningEffect(double panicThreshold, RGBColor deepColor, RGBColor surfaceColor) {
        this.water = new WaterRisePattern(panicThreshold);
        this.deepColor = deepColor;
        this.surfaceColor = surfaceColor;
    }

    /**
     * @param submersion 0 = full breath, 1 = out of air. The pattern eases
     *                   toward this, so a caller polling once a tick still
     *                   produces continuous motion.
     */
    public void setSubmersion(double submersion) {
        water.setLevel(submersion);
    }

    public void setColors(RGBColor deep, RGBColor surface) {
        this.deepColor = deep;
        this.surfaceColor = surface;
    }

    /** Immediately dry, no drain. For world unload. */
    public void reset() {
        water.setLevel(0);
    }

    @Override
    public String id() {
        return "drowning";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER2_OVERLAY;
    }

    @Override
    public boolean isActive(long nowMillis) {
        // Asks the pattern rather than the target, so the water finishes
        // draining after you surface instead of vanishing the instant air
        // starts refilling.
        return water.visible();
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        PatternContext ctx = new PatternContext(grid, null, deepColor, surfaceColor, 0, PatternParams.EMPTY);
        // Wall-clock rather than time-since-activation: the pattern uses it
        // only for the surface wave and the panic pulse, both of which want to
        // run continuously rather than restart every time you go under.
        return water.render(ctx, nowMillis);
    }
}
