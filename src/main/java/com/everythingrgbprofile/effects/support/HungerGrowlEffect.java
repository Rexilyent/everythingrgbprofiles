package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.HungerGrowlPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * A low food bar, as a growling pit in the bottom of the board that carves
 * the biome away around it. See {@link HungerGrowlPattern} for what it looks
 * like.
 *
 * <p>Built exactly like {@link HeartbeatEffect}, because it is the heartbeat's
 * less dramatic cousin. The event layer works out whether you're under the
 * hunger threshold and by how much, and sends 0 the rest of the time, so a
 * fed player never costs this anything past one check.
 *
 * <p>Base is the amber lip on the edge of the pit and its ripples, accent is
 * the hollow they carve, black by default.
 */
public final class HungerGrowlEffect implements EffectController {

    private final HungerGrowlPattern growl = new HungerGrowlPattern();
    private volatile RGBColor color;
    private volatile RGBColor hollowColor;

    public HungerGrowlEffect(RGBColor color, RGBColor hollowColor) {
        this.color = color;
        this.hollowColor = hollowColor;
    }

    /** @param level 0 = fed enough not to growl, 1 = food bar empty. */
    public void setLevel(double level) {
        growl.setLevel(level);
    }

    public void setColors(RGBColor color, RGBColor hollow) {
        this.color = color;
        this.hollowColor = hollow;
    }

    /** Filled in on the spot. For death and world unload. */
    public void reset() {
        growl.stop();
    }

    @Override
    public String id() {
        return "hunger_growl";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER2_OVERLAY;
    }

    @Override
    public boolean isActive(long nowMillis) {
        return growl.visible();
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        PatternContext ctx = new PatternContext(grid, null, color, hollowColor, 0, PatternParams.EMPTY);
        return growl.render(ctx, nowMillis);
    }
}
