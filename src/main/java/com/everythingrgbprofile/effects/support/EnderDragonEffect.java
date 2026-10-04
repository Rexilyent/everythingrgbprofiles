package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.DragonBreathFirePattern;
import com.everythingrgbprofile.pattern.patterns.DragonSilhouettePattern;
import com.everythingrgbprofile.pattern.patterns.EndRitualPattern;
import com.everythingrgbprofile.pattern.patterns.RingExpandPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * The End's headline event: the dragon summoned, fought, and killed.
 *
 * <p>Three states, all driven by things the client can genuinely see:
 *
 * <ul>
 *   <li><b>RITUAL</b> — {@link EndRitualPattern}, fitted to a traced respawn.</li>
 *   <li><b>FIGHT</b> — {@link DragonSilhouettePattern}: the dragon itself,
 *       posed from its synced phase.</li>
 *   <li><b>DYING</b> — rings firing outward across the 200-tick death.</li>
 * </ul>
 *
 * <h2>Why the fight draws a dragon instead of tinting the board</h2>
 * A phase-tinted pulse was the first attempt, and what it actually read as was
 * "purple, and now slightly different purple". The board never once said
 * <i>dragon</i>. It said <i>something</i>, at best.
 *
 * <p>Terraria's RGB solves this by drawing the boss outright: the entire point
 * of its Moon Lord effect is that the keyboard becomes an eye. Posing a real
 * silhouette makes the phase legible as BEHAVIOUR — it is gliding, it
 * is lunging at you, it has landed and is breathing — instead of as a colour
 * you would have to learn.
 */
public final class EnderDragonEffect implements EffectController {

    public enum State { IDLE, RITUAL, FIGHT, DYING }

    /** What the dragon is doing, collapsed from the eleven vanilla phases. */
    public enum Pose { GLIDE, LUNGE, LANDING, PERCHED, BREATHING, HOVER }

    /**
     * Where a landed dragon sits. Below the middle, because it is on the
     * ground now and the board has a ground.
     */
    private static final double PERCH_Y = 0.62;
    /** Wings pulled in, so a perched dragon stops spanning the entire board. */
    private static final double PERCH_SCALE = 0.62;
    /** Wingtips held up over the back. Without this, a perch is a flat bar. */
    private static final double PERCH_ARCH = 0.75;
    /** How long the drop onto the portal takes to draw. */
    private static final double LANDING_SECONDS = 1.3;
    /** Catch-up time when a perch arrives without a landing in front of it. */
    private static final double SETTLE_SECONDS = 0.8;

    private final int priority;
    private final EndRitualPattern ritual = new EndRitualPattern();
    private final DragonSilhouettePattern silhouette = new DragonSilhouettePattern();
    private final DragonBreathFirePattern breathFire = new DragonBreathFirePattern();
    private final RingExpandPattern deathRings = new RingExpandPattern(2.0);

    private volatile State state = State.IDLE;
    private volatile Pose pose = Pose.HOVER;
    private volatile RGBColor baseColor = ColorPalette.END_VOID_PURPLE;
    private volatile RGBColor accentColor = ColorPalette.END_DRAGON_MAGENTA;
    private volatile RGBColor breathFireColor = ColorPalette.END_BREATH_FIRE;
    private volatile double healthFraction = 1.0;
    private volatile double deathProgress = 0;
    private volatile long surgeAtMillis = Long.MIN_VALUE;
    private volatile boolean breathBurning = false;
    private volatile double breathOriginX = 0.5;
    private volatile double breathReach = 1.0;

    // --- pose transition state, touched only from render() ------------
    /** Geometry as last drawn, so a pose change has somewhere to ease out of. */
    private double drawnX = 0.5, drawnY = 0.5, drawnScale = 1.0, drawnArch = 0;
    /** The same, frozen at the moment the pose changed. */
    private double fromX = 0.5, fromY = 0.5, fromScale = 1.0, fromArch = 0;
    private Pose renderedPose = Pose.HOVER;
    private long poseSinceMillis = Long.MIN_VALUE;

    public EnderDragonEffect(int priority) {
        this.priority = priority;
    }

    public void setColors(RGBColor base, RGBColor accent) {
        this.baseColor = base;
        this.accentColor = accent;
    }

    public void setBreathFireColor(RGBColor color) {
        this.breathFireColor = color;
    }

    public void setIdle() {
        this.state = State.IDLE;
    }

    /**
     * @param lockedBearings radians, one per tower already lit
     * @param sweepBearing   radians, where the central crystals are aiming
     * @param hasSweep       false while they point straight up instead
     * @param completion     0..1 across the ritual
     */
    public void setRitual(double[] lockedBearings, double sweepBearing, boolean hasSweep,
                          boolean converging, double completion) {
        ritual.setState(lockedBearings, sweepBearing, hasSweep, converging, completion);
        this.state = State.RITUAL;
    }

    /** Called when the sweep jumps to a new tower, so the change lands with a kick. */
    public void pulseSurge(long nowMillis) {
        this.surgeAtMillis = nowMillis;
    }

    public void setFight(double healthFraction, Pose pose) {
        this.healthFraction = Math.max(0, Math.min(1, healthFraction));
        this.pose = pose;
        this.state = State.FIGHT;
    }

    /**
     * Dragon's breath burning on the ground.
     *
     * <p>Driven by the real {@code AreaEffectCloud}, not by the dragon's
     * phase. Both of its fire attacks end in the same cloud — the perched
     * flame makes one directly, and a fireball makes one where it bursts — so
     * watching for the cloud covers both, and covers them at the moment the
     * fire actually exists rather than at the moment the animation starts.
     *
     * @param originX 0..1, where the pool sits relative to where you are looking
     * @param reach   0..1, from the cloud's radius
     */
    public void setBreathFire(boolean burning, double originX, double reach) {
        this.breathBurning = burning;
        this.breathOriginX = originX;
        this.breathReach = reach;
    }

    public void setDying(double progress) {
        this.deathProgress = Math.max(0, Math.min(1, progress));
        this.state = State.DYING;
    }

    @Override
    public String id() {
        return "ender_dragon";
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
        return state != State.IDLE;
    }

    @Override
    public boolean suppressesAmbientOverlays(long nowMillis) {
        // A moon tracking sunset across a dragon is the exact thing this is for.
        return state != State.IDLE;
    }

    @Override
    public int tier3SuppressionFloor(long nowMillis) {
        // Killing the dragon showers you in advancements and XP, every one of
        // which would blank the finale it is celebrating.
        return state == State.DYING ? 90 : 0;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        State current = state;
        if (current == State.IDLE) return Map.of();
        double seconds = nowMillis / 1000.0;

        if (current == State.RITUAL) {
            double surge = 0;
            if (surgeAtMillis != Long.MIN_VALUE) {
                double age = (nowMillis - surgeAtMillis) / 700.0;
                if (age >= 0 && age < 1) surge = 1 - age;
            }
            ritual.setSurge(surge);
            PatternContext ctx = new PatternContext(grid, null, baseColor, accentColor, 0, PatternParams.EMPTY);
            return ritual.render(ctx, nowMillis);
        }

        if (current == State.DYING) {
            double period = 1600 - 900 * deathProgress;
            long phase = (long) (nowMillis % Math.max(200, period));
            RGBColor color = accentColor.lerp(RGBColor.WHITE, 0.35 * deathProgress);
            PatternContext ctx = new PatternContext(grid, null, color, accentColor,
                    (long) Math.max(200, period), PatternParams.EMPTY);
            return deathRings.render(ctx, phase);
        }

        // --- FIGHT: pose the silhouette -------------------------------
        // Health raises the wingbeat and the glow rather than changing the
        // shape: a wounded dragon should look frantic, not different.
        double hurt = 1.0 - healthFraction;
        double glowScale = 0.85 + 0.45 * hurt;
        RGBColor accent = accentColor.lerp(ColorPalette.END_DRAGON_FLAME, 0.55 * hurt);

        // Note where it was the instant the pose changes. Cutting straight
        // from a full-span glide to a folded perch moved the dragon, resized
        // it and reshaped it all on the same frame, which looked like the
        // effect had been swapped out rather than like anything had landed.
        Pose currentPose = pose;
        if (currentPose != renderedPose || poseSinceMillis == Long.MIN_VALUE) {
            fromX = drawnX;
            fromY = drawnY;
            fromScale = drawnScale;
            fromArch = drawnArch;
            renderedPose = currentPose;
            poseSinceMillis = nowMillis;
        }
        double sincePose = Math.max(0, (nowMillis - poseSinceMillis) / 1000.0);

        switch (currentPose) {
            case GLIDE -> {
                // Crosses the board and wraps, so it reads as circling
                // overhead rather than sitting still and flapping.
                double cycle = 9.0;
                double t = (seconds % cycle) / cycle;
                // Out past the edge at both ends, so it enters and leaves
                // rather than popping into existence mid-board.
                place(-0.35 + t * 1.7, 0.5, 1.0, 0);
                silhouette.setWings(0.75 + 0.5 * hurt, 1.0);
                silhouette.setGlow(0.9 * glowScale);
            }
            case LUNGE -> {
                // Straight at you: it swells in place and beats hard.
                double swell = 0.5 + 0.5 * Math.sin(2 * Math.PI * 0.9 * seconds);
                place(0.5, 0.5, 1.0 + 0.30 * swell, 0);
                silhouette.setWings(1.7, 1.0);
                silhouette.setGlow((1.15 + 0.35 * swell) * glowScale);
            }
            case LANDING -> {
                // Drops from wherever it was last drawn and slides to the
                // middle, because the portal it lands on is dead centre of the
                // island whichever End you are standing in. Braking beats on
                // the way down, easing off as it settles.
                double t = ease(sincePose / LANDING_SECONDS);
                settleToward(t, PERCH_ARCH);
                silhouette.setWings(2.3 - 1.5 * t, 1.0 - 0.6 * t);
                silhouette.setGlow((1.05 - 0.25 * t) * glowScale);
            }
            case PERCHED -> {
                // Down on the portal with its wings folded up over its back.
                // This is the window where you can actually reach its head, so
                // it is the calmest the board gets during the fight: the shape
                // shuffles rather than beats.
                settleToward(ease(sincePose / SETTLE_SECONDS), PERCH_ARCH);
                silhouette.setWings(0.55, 0.30);
                silhouette.setGlow(0.8 * glowScale);
            }
            case BREATHING -> {
                // Same posture, wings clamped in and holding. The movement
                // drops away on purpose so the head is the only thing doing
                // anything, which is where the fire is coming from.
                settleToward(ease(sincePose / SETTLE_SECONDS), PERCH_ARCH * 1.15);
                silhouette.setWings(0.9, 0.16);
                silhouette.setGlow(1.05 * glowScale);
            }
            case HOVER -> {
                place(0.5, 0.5, 1.0, 0);
                silhouette.setWings(0.5 + 0.3 * hurt, 0.8);
                silhouette.setGlow(0.85 * glowScale);
            }
        }

        // The fire is told whether it is burning, not whether to exist: it
        // spreads on ignition and dies down over about a second afterwards, so
        // it has to keep rendering past the end of whatever lit it.
        breathFire.setBurning(breathBurning, breathOriginX, breathReach);

        PatternContext ctx = new PatternContext(grid, null, baseColor, accent, 0, PatternParams.EMPTY);
        if (!breathFire.visible()) {
            return silhouette.render(ctx, nowMillis);
        }

        // Summed rather than layered on top: the fire is light thrown across
        // the board, so where it crosses a wing the two add instead of one
        // erasing the other.
        LightBudget budget = new LightBudget();
        budget.addLayer(silhouette.render(ctx, nowMillis), 1.0);
        PatternContext fireCtx = new PatternContext(grid, null, breathFireColor,
                breathFireColor.lightened(0.62), 0, PatternParams.EMPTY);
        budget.addLayer(breathFire.render(fireCtx, nowMillis), 1.0);
        return budget.resolve();
    }

    /**
     * Puts the silhouette somewhere and remembers where, so whatever pose
     * comes next has a starting point to ease out of.
     */
    private void place(double x, double y, double scale, double arch) {
        drawnX = x;
        drawnY = y;
        drawnScale = scale;
        drawnArch = arch;
        silhouette.setPose(x, y, scale, 1.0);
        silhouette.setWingArch(arch);
    }

    /** Eases from wherever the pose change caught it toward the perched geometry. */
    private void settleToward(double t, double arch) {
        place(lerp(fromX, 0.5, t), lerp(fromY, PERCH_Y, t),
                lerp(fromScale, PERCH_SCALE, t), lerp(fromArch, arch, t));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Smoothstep, clamped. A linear landing looks dropped rather than flown. */
    private static double ease(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }
}
