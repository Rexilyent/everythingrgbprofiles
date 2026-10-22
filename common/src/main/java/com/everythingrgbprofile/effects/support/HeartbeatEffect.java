package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.HeartbeatPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * Low health, as a heartbeat thumping out from the middle of the board. See
 * {@link HeartbeatPattern} for what it looks like.
 *
 * <p>Level-driven like {@link BurningEffect}. The event layer decides whether
 * you're under the health threshold at all and how far under, and sends 0 the
 * rest of the time, so at full health this is never so much as asked to
 * render.
 *
 * <p>Base is the red the beat runs in, accent is the paler crest at its peak.
 */
public final class HeartbeatEffect implements EffectController {

    private final HeartbeatPattern heartbeat = new HeartbeatPattern();
    private volatile RGBColor color;
    private volatile RGBColor crestColor;

    public HeartbeatEffect(RGBColor color, RGBColor crestColor) {
        this.color = color;
        this.crestColor = crestColor;
    }

    /** @param level 0 = healthy enough not to beat, 1 = nearly dead. */
    public void setLevel(double level) {
        heartbeat.setLevel(level);
    }

    public void setColors(RGBColor color, RGBColor crest) {
        this.color = color;
        this.crestColor = crest;
    }

    /** Stopped dead, rings and all. For death and world unload. */
    public void reset() {
        heartbeat.stop();
    }

    @Override
    public String id() {
        return "heartbeat";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER2_OVERLAY;
    }

    @Override
    public boolean isActive(long nowMillis) {
        return heartbeat.visible();
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        PatternContext ctx = new PatternContext(grid, null, color, crestColor, 0, PatternParams.EMPTY);
        return heartbeat.render(ctx, nowMillis);
    }
}
