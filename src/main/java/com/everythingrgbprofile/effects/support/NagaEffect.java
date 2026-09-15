package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.SnakeSlitherPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * The Twilight Forest Naga: a snake winding round the keyboard while you fight
 * it, getting shorter as you cut it down.
 *
 * <p>Same idea as {@link EnderDragonEffect} — draw the boss rather than tint
 * the board — applied to a boss whose whole identity is being long.
 *
 * <h2>The body length is the health bar</h2>
 * The Naga sheds segments as it takes damage; that is its actual mechanic, not
 * a flourish. So the snake on the keyboard sheds them too, and how much of the
 * board it covers tells you how the fight is going without a number anywhere.
 * A full-length serpent filling the board means you have barely started; three
 * segments skittering around means it is nearly done.
 *
 * <h2>Read entirely off things the client already knows</h2>
 * Health is synced, and so is velocity — that is the whole input. The Naga's
 * internal movement state (CIRCLE, CHARGE, INTIMIDATE, DAZE and friends) lives
 * in Twilight Forest's own mob-behaviour classes (Minecraft calls these "AI
 * goals"). It does sync a {@code DATA_CHARGE} flag, but that field is private
 * to a Twilight Forest class, and this mod never imports another mod's classes
 * (see {@code ModCompatRegistry} for why). Reaching for it with reflection
 * would break the first time Twilight Forest renamed it, all for something the
 * entity's own speed already tells you: a Naga bolting at you moves several
 * times faster than one circling, and speed is vanilla public API.
 */
public final class NagaEffect implements EffectController {

    /**
     * Segments shown at full health, and the floor as it dies — head
     * included, so a full-health Naga is a head and ten body segments and a
     * nearly-dead one is a head and two.
     *
     * <p>Eight steps across the health bar, so about every 12% of its health
     * visibly costs it one segment. An earlier version ran 14 down to 3, which
     * sounds like finer feedback and was in fact none at all: fourteen
     * segments only fit on a keyboard at well under a key apart, so the body
     * was a smear whose length nobody could read, and the whole top half of
     * the health bar looked identical. Ten steps you cannot see are worth less
     * than eight you can, and the count is capped here by how many segments
     * the board can show as separate things — see {@code DESIGN_SEGMENTS} in
     * {@code SnakeSlitherPattern}, which must not be lower than this.
     */
    private static final int MAX_SEGMENTS = 11;
    private static final int MIN_SEGMENTS = 3;

    /** Horizontal speed, in blocks/tick, at which it reads as charging. */
    private static final double CHARGE_SPEED = 0.42;

    private final int priority;
    private final SnakeSlitherPattern snake = new SnakeSlitherPattern();

    private volatile boolean present = false;
    /** When present last flipped, and what the fade level was at that instant. */
    private volatile long changedAtMillis = Long.MIN_VALUE;
    private volatile double presenceAtChange = 0;
    private volatile double fadeInSeconds = 0.5;
    private volatile double fadeOutSeconds = 1.4;
    private volatile RGBColor scaleColor = ColorPalette.NAGA_SCALE_GREEN;
    private volatile RGBColor highlightColor = ColorPalette.NAGA_HIGHLIGHT;

    public NagaEffect(int priority) {
        this.priority = priority;
    }

    public void setColors(RGBColor scale, RGBColor highlight) {
        this.scaleColor = scale;
        this.highlightColor = highlight;
    }

    public void setFadeTimes(double fadeInSeconds, double fadeOutSeconds) {
        this.fadeInSeconds = Math.max(0.05, fadeInSeconds);
        this.fadeOutSeconds = Math.max(0.05, fadeOutSeconds);
    }

    public void setGone(long nowMillis) {
        flip(false, nowMillis);
    }

    /**
     * How much of the snake is currently on screen, 0 to 1.
     *
     * <p>Computed from the clock rather than stepped per frame, so it needs no
     * dt and cannot drift: remember the level at the moment the state last
     * flipped, then ramp from there. Flipping mid-fade picks up from wherever
     * the fade had reached, so walking out of range and straight back in does
     * not jump.
     */
    private double presence(long nowMillis) {
        if (changedAtMillis == Long.MIN_VALUE) return present ? 1 : 0;
        double elapsed = Math.max(0, (nowMillis - changedAtMillis) / 1000.0);
        return present
                ? Math.min(1.0, presenceAtChange + elapsed / fadeInSeconds)
                : Math.max(0.0, presenceAtChange - elapsed / fadeOutSeconds);
    }

    private void flip(boolean nowPresent, long nowMillis) {
        if (nowPresent == present && changedAtMillis != Long.MIN_VALUE) return;
        presenceAtChange = presence(nowMillis);
        changedAtMillis = nowMillis;
        present = nowPresent;
    }

    /**
     * @param healthFraction 0..1, which sets how much snake is left
     * @param speed          horizontal blocks per tick, which separates a
     *                       charge from a circle
     */
    public void setNaga(double healthFraction, double speed, long nowMillis) {
        double health = Math.max(0, Math.min(1, healthFraction));
        double charge = Math.max(0, Math.min(1, speed / CHARGE_SPEED));

        int segments = MIN_SEGMENTS + (int) Math.round((MAX_SEGMENTS - MIN_SEGMENTS) * health);
        // A shorter snake weaves tighter — it has less board to work with, and
        // a long lazy wave on a three-segment stub just looks like a dash.
        double amplitude = 0.14 + 0.14 * health;
        snake.setBody(segments, amplitude);

        // Circling is a slow prowl. Charging is roughly four times that, and
        // the pattern straightens the weave at the same time, so the two read
        // as different behaviours rather than the same one at two speeds.
        snake.setMotion(0.13 + 0.42 * charge, charge);
        // Brighter as it gets hurt: an angry Naga, and a legible one at the
        // point where it is a short fast smear rather than a long body.
        snake.setGlow(0.85 + 0.4 * (1 - health));
        flip(true, nowMillis);
    }

    @Override
    public String id() {
        return "naga";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER1_OPAQUE_BASE;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isActive(long nowMillis) {
        // Stays active through the fade-out, or there would be nothing left to
        // fade. The compositor draws the biome underneath while this is
        // partially transparent — see EffectController#layerOpacity.
        return presence(nowMillis) > 0.004;
    }

    @Override
    public boolean suppressesAmbientOverlays(long nowMillis) {
        return presence(nowMillis) > 0.004;
    }

    @Override
    public double layerOpacity(long nowMillis) {
        return presence(nowMillis);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        if (presence(nowMillis) <= 0.004) return Map.of();
        PatternContext ctx = new PatternContext(grid, null, scaleColor, highlightColor, 0, PatternParams.EMPTY);
        return snake.render(ctx, nowMillis);
    }
}
