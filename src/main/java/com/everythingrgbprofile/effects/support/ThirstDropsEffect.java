package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.EvaporatingDropPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * Tough As Nails' thirst, as drops of water drying up on the keys. See
 * {@link EvaporatingDropPattern} for what it looks like.
 *
 * <p>Level-driven, same arrangement as {@link HeartbeatEffect} and
 * {@link HungerGrowlEffect}: the event layer sends how far under the thirst
 * threshold you are, or 0, and this hands it to the pattern. That's it.
 * That's the class.
 *
 * <p>Base is the drop's cyan, accent is the glint it catches just as it starts
 * to dry up.
 */
public final class ThirstDropsEffect implements EffectController {

    private final EvaporatingDropPattern drops = new EvaporatingDropPattern();
    private volatile RGBColor color;
    private volatile RGBColor glintColor;

    public ThirstDropsEffect(RGBColor color, RGBColor glintColor) {
        this.color = color;
        this.glintColor = glintColor;
    }

    /** @param level 0 = hydrated enough not to bother, 1 = thirst bar empty. */
    public void setLevel(double level) {
        drops.setLevel(level);
    }

    public void setColors(RGBColor color, RGBColor glint) {
        this.color = color;
        this.glintColor = glint;
    }

    /** Bone dry, immediately. For death and world unload. */
    public void reset() {
        drops.stop();
    }

    @Override
    public String id() {
        return "thirst_drops";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER2_OVERLAY;
    }

    @Override
    public boolean isActive(long nowMillis) {
        return drops.visible();
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        PatternContext ctx = new PatternContext(grid, null, color, glintColor, 0, PatternParams.EMPTY);
        return drops.render(ctx, nowMillis);
    }
}
