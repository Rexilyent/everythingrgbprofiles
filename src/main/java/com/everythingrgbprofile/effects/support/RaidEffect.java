package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.RaidHordePattern;
import com.everythingrgbprofile.pattern.patterns.RaidHordePattern.Phase;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * A raid on the village you are in, from the Raid Omen counting down to the
 * last firework or the last jeer. See {@link RaidHordePattern} for the drawing.
 *
 * <p>The event layer reports which phase the raid is in and when the horn
 * blows; this class remembers when each phase began, which the victory and
 * defeat animations are timed from, and eases the horde's glow from one phase
 * to the next so it builds and drains rather than stepping.
 */
public final class RaidEffect implements EffectController {

    private static final double BAND_SMOOTHING_SECONDS = 0.6;

    private final int priority;
    private final RaidHordePattern horde = new RaidHordePattern();

    private volatile boolean present = false;
    private volatile long changedAtMillis = Long.MIN_VALUE;
    private volatile double presenceAtChange = 0;
    private volatile double fadeInSeconds = 0.6;
    private volatile double fadeOutSeconds = 1.5;

    private volatile RGBColor hordeColor = ColorPalette.RAID_WARNING_RED;
    private volatile RGBColor raiderColor = ColorPalette.RAID_RAIDER;
    private volatile RGBColor victoryColor = ColorPalette.RAID_VICTORY;

    // Worker thread only.
    private Phase phase = null;
    private long phaseStartedAtMillis = Long.MIN_VALUE;
    private double omenProgress = 0;
    private double shownBand = 0;
    private long lastRenderMillis = Long.MIN_VALUE;

    public RaidEffect(int priority) {
        this.priority = priority;
    }

    public void setColors(RGBColor horde, RGBColor raider, RGBColor victory) {
        this.hordeColor = horde;
        this.raiderColor = raider;
        this.victoryColor = victory;
    }

    /**
     * Which phase the raid is in this tick.
     *
     * @param omenProgress 0 to 1 through the Raid Omen's countdown; ignored in other phases
     */
    public void setPhase(Phase phase, double omenProgress, long nowMillis) {
        if (phase != this.phase) {
            this.phase = phase;
            this.phaseStartedAtMillis = nowMillis;
        }
        this.omenProgress = omenProgress;
        flip(true, nowMillis);
    }

    /** The raid horn blew. Drawn only while a raid is on the board. */
    public void horn(long nowMillis) {
        horde.horn(nowMillis);
    }

    /** The raid is over or out of range. */
    public void setGone(long nowMillis) {
        flip(false, nowMillis);
    }

    /** Off immediately, for the world going away. */
    public void clear() {
        present = false;
        changedAtMillis = Long.MIN_VALUE;
        presenceAtChange = 0;
        phase = null;
        phaseStartedAtMillis = Long.MIN_VALUE;
        shownBand = 0;
    }

    private double bandTarget() {
        if (phase == null) return 0;
        return switch (phase) {
            case OMEN -> 0.1 + 0.5 * omenProgress;
            case RAID, DEFEAT -> 1;
            case VICTORY -> 0;
        };
    }

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
        // A raid seen afresh is measured from now, not from whatever the last
        // one ended on.
        if (nowPresent && presenceAtChange <= 0.004) {
            shownBand = 0;
            lastRenderMillis = Long.MIN_VALUE;
        }
    }

    @Override
    public String id() {
        return "raid";
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
        double dt = lastRenderMillis == Long.MIN_VALUE || nowMillis < lastRenderMillis
                ? 0 : Math.min(0.25, (nowMillis - lastRenderMillis) / 1000.0);
        lastRenderMillis = nowMillis;
        shownBand += (bandTarget() - shownBand) * (1 - Math.exp(-dt / BAND_SMOOTHING_SECONDS));

        horde.setState(phase == null ? Phase.RAID : phase, omenProgress, shownBand, phaseStartedAtMillis);
        horde.setVictoryColor(victoryColor);
        PatternContext ctx = new PatternContext(grid, null, hordeColor, raiderColor, 0, PatternParams.EMPTY);
        return horde.render(ctx, nowMillis);
    }
}
