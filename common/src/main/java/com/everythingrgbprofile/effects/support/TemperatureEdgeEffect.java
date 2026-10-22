package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.EdgeCreepPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.HashMap;
import java.util.Map;

/**
 * Tough As Nails' freezing and overheating, as frost or heat closing in from
 * the edges of the board. See {@link EdgeCreepPattern} for what it looks like.
 *
 * <p>Built like {@link BurningEffect}, level in and the pattern eases toward
 * it, and Tier 2 for the same reason: it's information laid over wherever you
 * are, and the biome left showing in the middle is what makes the level
 * readable at all.
 *
 * <p>Two patterns rather than one recoloured, because frost and heat aren't
 * the same animation in a different colour (see the pattern's notes), and
 * because each keeps its own eased level. Going straight from ICY to HOT can't
 * happen in TAN, but if it ever did, the frost would thaw out while the heat
 * crept in, instead of one ring changing colour on the spot.
 */
public final class TemperatureEdgeEffect implements EffectController {

    private final EdgeCreepPattern frost = EdgeCreepPattern.frost();
    private final EdgeCreepPattern heat = EdgeCreepPattern.heat();

    private volatile RGBColor frostFront;
    private volatile RGBColor frostRim;
    private volatile RGBColor heatFront;
    private volatile RGBColor heatRim;

    public TemperatureEdgeEffect(RGBColor frostFront, RGBColor frostRim, RGBColor heatFront, RGBColor heatRim) {
        this.frostFront = frostFront;
        this.frostRim = frostRim;
        this.heatFront = heatFront;
        this.heatRim = heatRim;
    }

    /**
     * @param cold 0 = fine, 1 = frozen solid. The pattern eases toward this.
     * @param hot  0 = fine, 1 = full heatstroke. Same.
     */
    public void setLevels(double cold, double hot) {
        frost.setLevel(cold);
        heat.setLevel(hot);
    }

    public void setFrostColors(RGBColor front, RGBColor rim) {
        this.frostFront = front;
        this.frostRim = rim;
    }

    public void setHeatColors(RGBColor front, RGBColor rim) {
        this.heatFront = front;
        this.heatRim = rim;
    }

    /** Gone immediately, no thawing. For world unload. */
    public void reset() {
        frost.clear();
        heat.clear();
    }

    @Override
    public String id() {
        return "temperature_edge";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER2_OVERLAY;
    }

    @Override
    public boolean isActive(long nowMillis) {
        // Asks the patterns, not the targets, so the edge gets to retreat
        // after you've warmed up or cooled off.
        return frost.visible() || heat.visible();
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        // Both rendered every frame even though one of them is nearly always
        // empty, because "nearly always empty" is exactly what an empty render
        // costs: one check and an early return.
        Map<KeyGrid.LedRef, LayerPixel> cold = frost.render(
                new PatternContext(grid, null, frostFront, frostRim, 0, PatternParams.EMPTY), nowMillis);
        Map<KeyGrid.LedRef, LayerPixel> hot = heat.render(
                new PatternContext(grid, null, heatFront, heatRim, 0, PatternParams.EMPTY), nowMillis);
        if (hot.isEmpty()) return cold;
        if (cold.isEmpty()) return hot;
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>(cold);
        out.putAll(hot);
        return out;
    }
}
