package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.WitherHeadsPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.Map;

/**
 * The Wither: summoned, fought, and armoured.
 *
 * <p>Three states, all read straight off public synced API — no reflection and
 * no mod imports, because this one is vanilla:
 *
 * <ul>
 *   <li><b>SUMMONING</b> — {@code getInvulnerableTicks()} counts 220 down to
 *       zero over eleven seconds while it hangs there charging. The board
 *       charges with it and the heads assemble out of the glare, so the
 *       explosion at the end lands as a release rather than a surprise.</li>
 *   <li><b>FIGHT</b> — three heads, each lit when it has locked onto
 *       something, spitting as real skulls appear.</li>
 *   <li><b>POWERED</b> — {@code isPowered()} is health at or below half, where
 *       it armours up and starts diving. The board reddens and speeds up.</li>
 * </ul>
 *
 * <h2>Why the summon gets its own state</h2>
 * Eleven seconds is a long time to stand there, and it is the one part of the
 * fight with a guaranteed script: you know exactly how long it lasts and
 * exactly what happens at the end. That makes it the easiest thing in the mod
 * to build tension against, and wasting it on the same three heads you are
 * about to look at for five minutes would be a shame.
 */
public final class WitherEffect implements EffectController {

    public enum State { IDLE, SUMMONING, FIGHT }

    /** Vanilla's {@code WitherBoss.INVULNERABLE_TICKS}. */
    private static final int SUMMON_TICKS = 220;

    private final int priority;
    private final WitherHeadsPattern heads = new WitherHeadsPattern();

    private volatile State state = State.IDLE;
    private volatile double summonProgress = 0;
    private volatile double healthFraction = 1.0;
    private volatile boolean poweredPhase = false;
    private volatile boolean[] locked = {false, false, false};

    private volatile RGBColor boneColor = ColorPalette.WITHER_BONE;
    private volatile RGBColor eyeColor = ColorPalette.WITHER_EYE;
    private volatile RGBColor poweredColor = ColorPalette.WITHER_POWERED;

    public WitherEffect(int priority) {
        this.priority = priority;
    }

    public void setColors(RGBColor bone, RGBColor eye, RGBColor powered) {
        this.boneColor = bone;
        this.eyeColor = eye;
        this.poweredColor = powered;
    }

    public void setIdle() {
        this.state = State.IDLE;
    }

    /** @param invulnerableTicks straight from {@code getInvulnerableTicks()}. */
    public void setSummoning(int invulnerableTicks) {
        // Counts DOWN, so invert it: 0 at the start of the charge, 1 at the bang.
        this.summonProgress = Math.max(0, Math.min(1,
                1.0 - invulnerableTicks / (double) SUMMON_TICKS));
        this.state = State.SUMMONING;
    }

    public void setFight(double healthFraction, boolean powered, boolean[] locked) {
        this.healthFraction = Math.max(0, Math.min(1, healthFraction));
        this.poweredPhase = powered;
        if (locked != null && locked.length == 3) this.locked = locked;
        this.state = State.FIGHT;
    }

    /** A real wither skull just spawned; throw one from a head. */
    public void fireSkull(int head, long nowMillis) {
        heads.fire(head, nowMillis);
    }

    @Override
    public String id() {
        return "wither";
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
        return state != State.IDLE;
    }

    @Override
    public int tier3SuppressionFloor(long nowMillis) {
        // The summon ends in an explosion that hands out advancements. Letting
        // confetti blank the moment it is celebrating would be the usual
        // mistake.
        return state == State.SUMMONING ? 90 : 0;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        State current = state;
        if (current == State.IDLE) return Map.of();
        double seconds = nowMillis / 1000.0;

        if (current == State.SUMMONING) {
            double p = summonProgress;
            // Everything accelerates together: the pulse rate roughly triples,
            // the whole board brightens, and the colour runs from the eye
            // glow to white. Nothing here is subtle on purpose — it is a
            // countdown to an explosion.
            double rate = 0.9 + 4.5 * p * p;
            double pulse = 0.5 + 0.5 * Math.sin(2 * Math.PI * rate * seconds);
            double wash = (0.10 + 0.55 * p) * (0.55 + 0.45 * pulse);
            RGBColor chargeColor = eyeColor.lerp(RGBColor.WHITE, Math.min(1.0, p * 1.2));

            LightBudget budget = new LightBudget();
            for (KeyGrid.LedPosition key : grid.allKeys()) {
                budget.add(key.ref(), chargeColor, wash);
            }
            // The heads fade in over the back half, so it assembles out of the
            // glare rather than switching on when the bang lands.
            heads.setAssembled(Math.max(0, (p - 0.45) / 0.55));
            heads.setHeads(new boolean[]{false, false, false}, 0, 0.7 + 0.6 * p);
            PatternContext ctx = new PatternContext(grid, null, boneColor, chargeColor, 0, PatternParams.EMPTY);
            budget.addLayer(heads.render(ctx, nowMillis), 1.0);
            return budget.resolve();
        }

        // --- FIGHT -----------------------------------------------------
        double hurt = 1.0 - healthFraction;
        // Armour and rage. The colour shift is the readable signal that it has
        // crossed half health and started diving at you.
        RGBColor bone = poweredPhase ? boneColor.lerp(poweredColor, 0.55) : boneColor;
        RGBColor eye = poweredPhase ? eyeColor.lerp(poweredColor, 0.35) : eyeColor;
        double glow = (poweredPhase ? 1.15 : 0.9) + 0.25 * hurt;

        heads.setAssembled(1.0);
        heads.setHeads(locked, poweredPhase ? 1 : 0, glow);
        PatternContext ctx = new PatternContext(grid, null, bone, eye, 0, PatternParams.EMPTY);
        return heads.render(ctx, nowMillis);
    }
}
