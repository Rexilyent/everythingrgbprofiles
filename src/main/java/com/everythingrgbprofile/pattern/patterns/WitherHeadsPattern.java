package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.Map;

/**
 * Three skulls hanging over a spine, tracking separately and spitting.
 *
 * <p>The Wither's silhouette is three heads in a row, which lands well on a
 * board that is three times wider than it is tall. Same principle as the
 * dragon's wingspan and the Naga's body: the shape is the thing you recognise,
 * so draw the shape.
 *
 * <h2>The heads move independently because they do</h2>
 * A Wither's three heads pick their own targets and swing separately — that is
 * a real mechanic, not a rendering flourish, and it is the single detail that
 * makes it read as a Wither rather than as three dots. Each head bobs on its
 * own frequency, and a head that has locked onto something is drawn brighter
 * with its eye lit.
 *
 * <h2>The skulls</h2>
 * Each shot is a mote leaving a head and running out to the edge of the board.
 * They are launched by the effect on the frame a real skull appears in the
 * world, so what you see is the actual rate of fire rather than a decorative
 * loop guessing at one.
 */
public final class WitherHeadsPattern implements Pattern {

    /** Horizontal offset of the side heads from centre, in board widths. */
    private static final double SIDE_HEAD_OFFSET = 0.26;
    private static final double HEAD_Y = 0.42;
    /**
     * Head radii, in key widths. Sized up from an earlier 2.0/1.5, which
     * looked reasonable on paper and rendered as four dim smudges: at that
     * size the eye core came out smaller than the gap between keys, so it
     * frequently landed between LEDs and lit nothing at all. A shape on a
     * keyboard has to be at least a few keys across before it is a shape.
     */
    private static final double CENTRE_HEAD_KEYS = 3.2;
    private static final double SIDE_HEAD_KEYS = 2.4;
    /** Eye core, as a fraction of the head radius. */
    private static final double EYE_FRACTION = 0.60;
    /** How far the heads bob, in board heights. */
    private static final double BOB = 0.075;
    /** Deliberately unrelated rates, so the three never swing in time. */
    private static final double[] BOB_HZ = {0.23, 0.31, 0.19};

    private static final double SPINE_HALF_WIDTH_KEYS = 1.0;
    private static final double SKULL_RADIUS_KEYS = 1.0;
    private static final double SKULL_SPEED = 0.55;
    private static final double SKULL_LIFE_SECONDS = 1.6;

    /** One skull in flight. Recycled; a spent slot is simply free again. */
    private static final class Skull {
        double x0, y0, vx, vy;
        double firedAtSeconds = Double.NEGATIVE_INFINITY;
    }

    private final Skull[] skulls = new Skull[8];

    private volatile boolean[] locked = {false, false, false};
    private volatile double powered = 0;
    private volatile double glow = 1.0;
    /** 0 = still assembling during the summon, 1 = fully present. */
    private volatile double assembled = 1.0;

    public WitherHeadsPattern() {
        for (int i = 0; i < skulls.length; i++) skulls[i] = new Skull();
    }

    /** @param locked one flag per head: is it tracking something? */
    public void setHeads(boolean[] locked, double powered, double glow) {
        if (locked != null && locked.length == 3) this.locked = locked;
        this.powered = Math.max(0, Math.min(1, powered));
        this.glow = Math.max(0, glow);
    }

    /** 0 hides the heads entirely, 1 draws them fully. Used by the summon. */
    public void setAssembled(double assembled) {
        this.assembled = Math.max(0, Math.min(1, assembled));
    }

    /** Fires one skull from the given head. Called when a real skull spawns. */
    public void fire(int head, long nowMillis) {
        double seconds = nowMillis / 1000.0;
        Skull free = null;
        for (Skull s : skulls) {
            if (seconds - s.firedAtSeconds > SKULL_LIFE_SECONDS) {
                free = s;
                break;
            }
        }
        // All eight in flight already: the board is busy enough that a ninth
        // would not read as anything. Drop it rather than stealing a slot
        // mid-flight and teleporting somebody else's skull.
        if (free == null) return;

        int index = Math.max(0, Math.min(2, head));
        free.x0 = headX(index);
        free.y0 = HEAD_Y;
        // Outward and slightly down, away from the centre — the side heads
        // throw to their own side so the three are visibly separate guns.
        double outward = index == 0 ? (Math.random() < 0.5 ? -1 : 1) : (index == 1 ? -1 : 1);
        free.vx = outward * SKULL_SPEED;
        free.vy = 0.22 * SKULL_SPEED;
        free.firedAtSeconds = seconds;
    }

    private static double headX(int index) {
        return switch (index) {
            case 1 -> 0.5 - SIDE_HEAD_OFFSET;
            case 2 -> 0.5 + SIDE_HEAD_OFFSET;
            default -> 0.5;
        };
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double seconds = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();

        RGBColor bone = ctx.baseColor();
        RGBColor eye = ctx.resolvedAccentColor();
        double present = assembled;
        if (present <= 0.01) return Map.of();

        LightBudget budget = new LightBudget();

        // --- spine, under the middle head ------------------------------
        // Faint and short. It exists so the heads read as attached to
        // something rather than as three floating lights.
        double spineHalf = SPINE_HALF_WIDTH_KEYS * keyWidth;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            if (key.y() < HEAD_Y) continue;
            double dx = Math.abs(key.x() - 0.5);
            if (dx > spineHalf) continue;
            double down = (key.y() - HEAD_Y) / Math.max(0.01, 1.0 - HEAD_Y);
            double f = (1.0 - dx / spineHalf) * (1.0 - down * 0.8);
            budget.add(key.ref(), bone, f * 0.45 * glow * present);
        }

        // --- the three heads -------------------------------------------
        for (int i = 0; i < 3; i++) {
            double hx = headX(i);
            double hy = HEAD_Y + BOB * Math.sin(2 * Math.PI * BOB_HZ[i] * seconds + i * 2.1);
            double radius = (i == 0 ? CENTRE_HEAD_KEYS : SIDE_HEAD_KEYS) * keyWidth;
            // Side heads arrive after the middle one during the summon, so the
            // Wither assembles rather than appearing whole.
            double headPresent = i == 0 ? present : Math.max(0, present * 1.6 - 0.6);
            if (headPresent <= 0.01) continue;

            boolean lit = locked[i];
            double eyeBoost = lit ? 1.0 : 0.45;

            for (KeyGrid.LedPosition key : grid.allKeys()) {
                double dx = key.x() - hx;
                double dy = (key.y() - hy) * aspect;
                double d = Math.hypot(dx, dy);
                if (d > radius) continue;
                double f = 1.0 - d / radius;
                // Skull first, then a hot eye in the middle of it. The eye is
                // what makes a head read as facing you.
                budget.add(key.ref(), bone, f * f * glow * headPresent);
                if (d < radius * EYE_FRACTION) {
                    double e = 1.0 - d / (radius * EYE_FRACTION);
                    budget.add(key.ref(), eye, e * e * 1.4 * eyeBoost * glow * headPresent);
                }
            }
        }

        // --- skulls in flight ------------------------------------------
        double skullRadius = SKULL_RADIUS_KEYS * keyWidth;
        for (Skull s : skulls) {
            double age = seconds - s.firedAtSeconds;
            if (age < 0 || age > SKULL_LIFE_SECONDS) continue;
            double x = s.x0 + s.vx * age;
            double y = s.y0 + s.vy * age / aspect;
            if (x < -0.1 || x > 1.1 || y > 1.15) continue;
            double fade = 1.0 - age / SKULL_LIFE_SECONDS;
            for (KeyGrid.LedPosition key : grid.allKeys()) {
                double dx = key.x() - x;
                double dy = (key.y() - y) * aspect;
                double d = Math.hypot(dx, dy);
                if (d > skullRadius) continue;
                double f = 1.0 - d / skullRadius;
                budget.add(key.ref(), eye, f * f * fade * 0.9 * glow);
            }
        }
        return budget.resolve();
    }
}
