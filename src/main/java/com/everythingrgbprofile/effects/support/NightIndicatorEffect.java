package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.MoonArcPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * Night indicator: a moon crossing the board, showing how much night is left.
 * Genuinely useful, which is rare for RGB lighting.
 *
 * <h2>A moon, not a progress bar</h2>
 * This used to sweep a bar along the function row. It worked, and it read as a
 * loading bar for the night — accurate, and completely uninteresting.
 * {@link MoonArcPattern} puts the moon on the arc it actually travels: up out
 * of the left edge, across the top rows, down the right at dawn. Same
 * information, except now you read it the way you read the sky, by glancing at
 * where the thing is.
 *
 * <p>Tier 2, so the biome layer keeps showing underneath — you should be able
 * to check how long until sunrise without your scenery having to get out of
 * the way. It is deliberately registered <b>after</b> the rain cascade so that
 * it draws on top: Tier 2 has no priority contest, it just composites in
 * registration order, and a moon that rain can paint over is a moon you cannot
 * read during exactly the weather where you most want to know how long is
 * left.
 *
 * <h2>Two clocks, on purpose</h2>
 * Night progress only needs recomputing on the ambient poll — night moves
 * slowly, and asking sixty times a second would be silly. The moon's position
 * is then a pure function of that value, so it renders at the full animation
 * frame rate regardless. Coarse state in, smooth output.
 */
public final class NightIndicatorEffect implements EffectController {

    private final MoonArcPattern moon = new MoonArcPattern();
    private volatile boolean night = false;
    private volatile boolean standDownForEncounters = true;
    private volatile RGBColor glowColor = ColorPalette.NIGHT_MOONLIGHT;
    private volatile RGBColor coreColor = ColorPalette.NIGHT_MOONLIGHT.lightened(0.45);

    /** @param progress 0 = night just started, 1 = about to end. */
    public void setNightProgress(boolean isNight, double progress) {
        this.night = isNight;
        moon.setProgress(progress);
    }

    /** The halo colour; the moon's face is derived a little brighter than it. */
    public void setColor(RGBColor color) {
        this.glowColor = color;
        this.coreColor = color.lightened(0.45);
    }

    /** Config hook: false keeps the moon up even during a boss fight. */
    public void setStandDownForEncounters(boolean standDown) {
        this.standDownForEncounters = standDown;
    }

    @Override
    public boolean ambient() {
        return standDownForEncounters;
    }

    @Override
    public String id() {
        return "night_indicator";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER2_OVERLAY;
    }

    @Override
    public boolean isActive(long nowMillis) {
        return night; // the entire activation condition. it's night or it isn't
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        if (!night) return Map.of();
        // The moon draws from its pushed-in progress and ignores the timestamp
        // entirely, so raw wall-clock is fine here. Swap in a pattern that
        // DOES use elapsed time and this needs an activation stamp, the way
        // SustainedOverlayEffect keeps one.
        PatternContext ctx = new PatternContext(grid, null, glowColor, coreColor, 0, PatternParams.EMPTY);
        return moon.render(ctx, nowMillis);
    }
}
