package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.GuardianEyePattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * The Elder Guardian: an eye in the dark, and the three seconds before it
 * fires.
 *
 * <p>Same approach as the dragon and the Naga — draw the boss, do not tint the
 * board — and this one is the best-suited of the three, because an Elder
 * Guardian already <i>is</i> a single enormous eye. See
 * {@link GuardianEyePattern} for the drawing.
 *
 * <h2>The laser is the whole point</h2>
 * An Elder Guardian's beam takes 60 ticks to charge, which is three full
 * seconds of wind-up — the longest tell in vanilla, and the one thing a player
 * fighting one is actually watching for, because it is the window to break
 * line of sight. Every part of it is synced and public: whether it has a
 * target, who that target is, and how far through the charge it is. So the
 * board can show the real wind-up rather than an animation that merely implies
 * one, and the beam on the keys is in step with the beam in the water.
 *
 * <h2>What each input actually is</h2>
 * <ul>
 *   <li><b>Charge</b> — {@code getAttackAnimationScale}, which is
 *       {@code clientSideAttackTime / getAttackDuration()} and caps at one.
 *       Only read while {@code hasActiveAttackTarget()}: outside that window
 *       the numerator is stale rather than zero.</li>
 *   <li><b>Spikes</b> — {@code getSpikesAnimation}, which eases toward 1 while
 *       the thing hovers and snaps back toward 0 while it swims. It is the
 *       mob's own tell for going aggressive, and it is smoothed again here
 *       because out of water vanilla drives it from {@code random.nextFloat()}
 *       every tick; a beached Guardian would otherwise strobe the crown of
 *       spikes at 20Hz.</li>
 *   <li><b>The curse</b> — Mining Fatigue, which an Elder Guardian applies to
 *       everyone within 50 blocks once a minute for five minutes. It does not
 *       change the picture, it slows it down: the water stops shimmering and
 *       starts heaving.</li>
 * </ul>
 *
 * <h2>The curse follows the Guardian, not the debuff</h2>
 * This layer is only on screen while one is nearby, so the heave stops when
 * you swim away even though Mining Fatigue has four minutes left to run. That
 * is deliberate: the layer is about the fight. A debuff that outlives the fight
 * by minutes belongs to whatever eventually shows player state, not to the
 * boss that handed it out — putting it here would mean a boss layer holding
 * the board long after its boss was out of sight.
 */
public final class ElderGuardianEffect implements EffectController {

    /** How long the beam's landing flash takes to decay, in millis. */
    private static final double BEAM_FLASH_MILLIS = 280;
    /**
     * And the curse's. Much slower, because the two are not the same kind of
     * event: the beam is a hit, the curse is a five-minute sentence.
     */
    private static final double CURSE_FLASH_MILLIS = 900;

    /**
     * Per-frame smoothing for the spikes, as a fraction closed each frame.
     *
     * <p>Frame-rate dependent, and fine: it is a cosmetic ease on a value that
     * vanilla already eases, and the SDK thread runs at a steady rate. The
     * reason it exists is the out-of-water case above, where the input is
     * per-tick noise and any amount of smoothing is better than none.
     */
    private static final double SPIKE_SMOOTHING = 0.12;

    private final int priority;
    private final GuardianEyePattern eye = new GuardianEyePattern();

    private volatile boolean present = false;
    /** When present last flipped, and the fade level at that instant. */
    private volatile long changedAtMillis = Long.MIN_VALUE;
    private volatile double presenceAtChange = 0;
    private volatile double fadeInSeconds = 0.6;
    private volatile double fadeOutSeconds = 1.6;

    private volatile double targetSpikes = 1.0;
    private volatile double charge = 0;
    private volatile boolean aimedAtYou = false;
    private volatile boolean cursed = false;
    private volatile long beamFiredAtMillis = Long.MIN_VALUE;
    private volatile long curseLandedAtMillis = Long.MIN_VALUE;

    private volatile RGBColor waterColor = ColorPalette.GUARDIAN_PRISMARINE;
    private volatile RGBColor eyeColor = ColorPalette.GUARDIAN_EYE;
    private volatile RGBColor beamColor = ColorPalette.GUARDIAN_BEAM;

    /** Smoothed spikes. Render-thread only, so it needs no volatile. */
    private double shownSpikes = 1.0;

    public ElderGuardianEffect(int priority) {
        this.priority = priority;
    }

    public void setColors(RGBColor water, RGBColor eye, RGBColor beam) {
        this.waterColor = water;
        this.eyeColor = eye;
        this.beamColor = beam;
    }

    public void setFadeTimes(double fadeInSeconds, double fadeOutSeconds) {
        this.fadeInSeconds = Math.max(0.05, fadeInSeconds);
        this.fadeOutSeconds = Math.max(0.05, fadeOutSeconds);
    }

    /**
     * @param spikes     0 swimming, 1 hovering with the crown out
     * @param charge     0 to 1 through the beam's wind-up, 0 when not locked on
     * @param aimedAtYou whether the thing it has locked onto is you
     * @param cursed     whether Mining Fatigue is on you right now
     */
    public void setGuardian(double spikes, double charge, boolean aimedAtYou,
                            boolean cursed, long nowMillis) {
        this.targetSpikes = Math.max(0, Math.min(1, spikes));
        this.charge = Math.max(0, Math.min(1, charge));
        this.aimedAtYou = aimedAtYou;
        this.cursed = cursed;
        flip(true, nowMillis);
    }

    /** The wind-up completed: the beam is on you. */
    public void fireBeam(long nowMillis) {
        this.beamFiredAtMillis = nowMillis;
    }

    /** Mining Fatigue just landed — the once-a-minute curse. */
    public void curseLanded(long nowMillis) {
        this.curseLandedAtMillis = nowMillis;
    }

    public void setGone(long nowMillis) {
        flip(false, nowMillis);
    }

    /**
     * How much of the layer is on screen, 0 to 1.
     *
     * <p>Computed off the clock rather than stepped per frame, so it cannot
     * drift and needs no dt — the same approach as {@code NagaEffect}, and for
     * the same reason: a Guardian circling a monument pillar leaves and
     * re-enters range constantly, and a fade that resumes from wherever it had
     * got to does not jump when that happens.
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

    private static double decay(long sinceMillis, long nowMillis, double lengthMillis) {
        if (sinceMillis == Long.MIN_VALUE) return 0;
        double age = (nowMillis - sinceMillis) / lengthMillis;
        return age < 0 || age >= 1 ? 0 : 1 - age;
    }

    @Override
    public String id() {
        return "elder_guardian";
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
        // fade; the compositor draws the biome underneath while this is
        // partially transparent. See EffectController#layerOpacity.
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
    public int tier3SuppressionFloor(long nowMillis) {
        // Nothing gets to blank the board during the wind-up. Three seconds of
        // charge is the window to break line of sight, and an advancement
        // popping over the top of it would cost you the one thing the layer is
        // for. Only while it is genuinely aimed at you.
        return aimedAtYou && charge > 0.15 ? 95 : 0;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        if (presence(nowMillis) <= 0.004) return Map.of();

        shownSpikes += (targetSpikes - shownSpikes) * SPIKE_SMOOTHING;

        // A beam charging at somebody else is worth seeing — it tells you
        // where the thing is looking — but it is not worth the full glare,
        // and it must not constrict the pupil as if you were the target.
        double aimed = aimedAtYou ? 1.0 : 0.35;
        double shownCharge = charge * aimed;

        double curse = cursed ? 1.0 : 0.0;
        double curseFlash = decay(curseLandedAtMillis, nowMillis, CURSE_FLASH_MILLIS);

        // Cursed water goes sick: toward the olive of the Mining Fatigue
        // icon, and darker with it. Derived from the configured water colour
        // rather than replacing it, so a retuned prismarine stays recognisable
        // when it turns.
        RGBColor water = curse <= 0 ? waterColor
                : waterColor.lerp(ColorPalette.GUARDIAN_CURSE, 0.55).scaled(0.85);

        // The eye runs from its own amber through the beam colour and into
        // white as the charge tops out, which is what the real beam does. The
        // pattern only carries two colours, so this is where the third one
        // gets in.
        RGBColor lit = eyeColor.lerp(beamColor, Math.min(1.0, shownCharge * 1.3));
        if (shownCharge > 0.75) {
            lit = lit.lerp(RGBColor.WHITE, (shownCharge - 0.75) / 0.25 * 0.6);
        }

        eye.setBody(shownSpikes, 0.95 + 0.25 * shownCharge);
        eye.setBeam(shownCharge, decay(beamFiredAtMillis, nowMillis, BEAM_FLASH_MILLIS));
        eye.setCurse(curse);

        PatternContext ctx = new PatternContext(grid, null, water, lit, 0, PatternParams.EMPTY);
        Map<KeyGrid.LedRef, LayerPixel> drawn = eye.render(ctx, nowMillis);
        if (curseFlash <= 0.001) return drawn;

        // The curse landing, as its own wash under the eye. Bright rather than
        // dark, which sounds backwards for a debuff and is what vanilla does:
        // the moment it lands is a pale face and a very loud noise, not a
        // dimming.
        LightBudget budget = new LightBudget();
        RGBColor sick = ColorPalette.GUARDIAN_CURSE.lightened(0.25);
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            budget.add(key.ref(), sick, curseFlash * 0.85);
        }
        budget.addLayer(drawn, 1.0);
        return budget.resolve();
    }
}
