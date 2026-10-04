package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * Boss Encounter Lighting. Your keyboard develops a heart rate, and it climbs
 * as the boss goes down.
 *
 * <p>Deliberately mod-agnostic, so any boss gets lighting for free with zero
 * per-mod code anywhere. The Forge Everything pack this was built against runs
 * somewhere around 600 mods, so per-mod integrations were never once a
 * workable plan and it would have been a waste of everyone's time to pretend
 * otherwise.
 *
 * <p>Detection started life as a vanilla boss-bar hook. It now looks for the
 * boss itself near the player, and counts it only if it's in the
 * {@code c:bosses} entity tag or has an exact entry in
 * {@code boss_profiles.json}. See {@code LegendaryMonstersCompat} for the full
 * story of why the boss-bar idea did not survive contact with reality. This
 * class does not care in the slightest which of them found the boss.
 *
 * <p>Profile resolution (exact entity id, then mod wildcard, then a colour
 * invented from the entity id) all happens up in the event layer, where the
 * registry access lives. By the time anything arrives here the colours have
 * already been decided and this class only renders.
 *
 * <h2>Known v1 simplification, stated rather than hidden</h2>
 * Shows exactly one boss at a time. When several qualify at once, which is
 * rare but which some boss mods absolutely do, the detector picks one: a
 * tagged boss beats one named in {@code boss_profiles.json}, and ties go to
 * the higher max health. Flagging it here as a
 * conscious v1 choice rather than letting it be a silent gap someone discovers
 * mid-fight.
 */
public final class BossEncounterEffect implements EffectController {

    private volatile boolean active = false;
    private volatile long startMillis = 0;
    private volatile RGBColor baseColor = RGBColor.fromHex("#7F00FF");
    private volatile RGBColor enrageColor = null;
    private volatile int enrageThresholdPercent = -1; // -1 = no enrage phase configured
    private volatile double currentPercent = 100.0;
    private volatile boolean enrageIntensityScaling = true;

    public void startBoss(RGBColor baseColor, RGBColor enrageColor, int enrageThresholdPercent, long nowMillis) {
        this.baseColor = baseColor;
        this.enrageColor = enrageColor;
        this.enrageThresholdPercent = enrageThresholdPercent;
        this.currentPercent = 100.0;
        this.startMillis = nowMillis;
        this.active = true;
    }

    /** The boss's health changed. Read off the entity by the polling layer; no timestamp needed. */
    public void updatePercent(double percent) {
        this.currentPercent = percent;
    }

    public void endBoss() {
        this.active = false;
    }

    public void setEnrageIntensityScaling(boolean enabled) {
        this.enrageIntensityScaling = enabled;
    }

    @Override
    public String id() {
        return "boss_encounter";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER1_OPAQUE_BASE;
    }

    @Override
    public int priority() {
        // Tier 1 order: above Biome Color (0), below Warden Active (11) and
        // Raid Warning (20). So a boss beats scenery, and the Warden beats the
        // boss: a Warden nearby is the more urgent thing to know about.
        return 10;
    }

    @Override
    public boolean isActive(long nowMillis) {
        return active;
    }

    @Override
    public boolean suppressesAmbientOverlays(long nowMillis) {
        return active;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        double healthFraction = Math.max(0.0, Math.min(1.0, currentPercent / 100.0));
        // 0.4Hz at full health climbing to 2.0Hz at zero — a five-fold speedup.
        // Slow and ominous at the start, frantic at the end. Your pulse and the
        // board's converge, which is the entire trick and it costs one lerp.
        //
        // With scaling off it sits flat at 0.6Hz: present, not dramatic.
        double frequencyHz = enrageIntensityScaling ? (0.4 + (1.0 - healthFraction) * 1.6) : 0.6;

        RGBColor effectiveColor = baseColor;
        if (enrageColor != null && enrageThresholdPercent >= 0 && currentPercent <= enrageThresholdPercent) {
            // Renormalise below the threshold so the shift into enrage colour
            // spans the remaining health rather than jumping. t = 0 exactly at
            // the threshold, 1 at zero health. Crossing into enrage is a
            // gradient, not a costume change.
            double t = 1.0 - (currentPercent / Math.max(1, enrageThresholdPercent));
            effectiveColor = baseColor.lerp(enrageColor, Math.max(0, Math.min(1, t)));
        }

        double elapsedSeconds = (nowMillis - startMillis) / 1000.0;
        // 0.15 floor: never fully dark. A boss fight where the keyboard blacks
        // out on every downbeat is exhausting to look at, and you want the
        // colour continuously present as a "this is still happening" signal.
        double brightness = 0.15 + 0.85 * (0.5 + 0.5 * Math.sin(2 * Math.PI * frequencyHz * elapsedSeconds));
        LayerPixel pixel = new LayerPixel(effectiveColor, brightness);

        // Whole board, synchronised. Same reasoning as PulsePattern: this is
        // urgent information and unified beats textured.
        Map<KeyGrid.LedRef, LayerPixel> out = new java.util.HashMap<>();
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            out.put(key.ref(), pixel);
        }
        return out;
    }
}
