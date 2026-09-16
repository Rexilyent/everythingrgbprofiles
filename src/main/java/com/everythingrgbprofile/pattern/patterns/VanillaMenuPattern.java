package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * The default title-screen theme: sky over grass, with clouds drifting across.
 *
 * <p>Deliberately the most obvious thing in the world — a horizon, blue above
 * it, green below, a few slow clouds — because a theme that ships on by default
 * should look like the game rather than like somebody's taste.
 *
 * <p>This was that default for as long as the game's own title panorama was a
 * landscape. Minecraft 26.2 replaced it with a sulfur cave, so the theme that
 * now looks like the game is {@link SulfurCavePattern#menu()} and this one is
 * opt-in — still the right answer with a resource pack that restores the old
 * panorama, and still the only theme that is about the overworld rather than
 * about a cave.
 *
 * <p>{@link MenuAmbientPattern} is the third: a mineshaft with a drill in it,
 * built for a specific pack's title art. Excellent there, presumptuous
 * anywhere else, which is why it is gated behind pack detection and neither of
 * the other two is.
 *
 * <h2>Restraint is the design</h2>
 * A menu runs for as long as somebody leaves the game sitting there, which can
 * be hours. Everything here is slow and shallow on purpose: the horizon breathes
 * at 0.05Hz, clouds take the better part of a minute to cross, and nothing ever
 * flashes. It should be pleasant to have in your peripheral vision and
 * completely ignorable, which is a harder brief than "look impressive".
 */
public final class VanillaMenuPattern implements Pattern {

    /** Where the horizon sits, as a fraction down the board. */
    private static final double HORIZON = 0.58;
    /** How far the horizon drifts up and down. */
    private static final double HORIZON_DRIFT = 0.05;
    /** Softness of the sky/grass boundary, in board heights. */
    private static final double HORIZON_BLEND = 0.16;

    private static final double CLOUD_SPEED = 0.022;
    private static final double CLOUD_RADIUS_KEYS = 3.2;

    private static final class Cloud {
        double x, y, scale, alpha;
    }

    private final int cloudCount;
    private final Random random = new Random();

    private boolean initialised = false;
    private Cloud[] clouds;
    private Map<KeyGrid.LedRef, Double> shimmerPhase;

    public VanillaMenuPattern(int cloudCount) {
        this.cloudCount = Math.max(0, Math.min(12, cloudCount));
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        initIfNeeded(ctx);
        double seconds = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double cloudRadius = CLOUD_RADIUS_KEYS * grid.keyWidthNormalised();

        RGBColor grass = ctx.baseColor();
        RGBColor sky = ctx.resolvedAccentColor();
        // Clouds are the sky lightened rather than a third configured colour,
        // so recolouring the sky keeps them in the same family instead of
        // leaving white smudges on a sunset.
        RGBColor cloud = sky.lightened(0.55);

        // The horizon rises and falls very slightly, which stops the two bands
        // reading as a static split down the board.
        double horizon = HORIZON + HORIZON_DRIFT * Math.sin(2 * Math.PI * 0.05 * seconds);

        LightBudget budget = new LightBudget();
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            // Blend across the horizon rather than cutting at it: a hard line
            // reads as two separate effects that happen to be adjacent.
            double t = (key.y() - horizon) / HORIZON_BLEND;
            double grassness = t <= -1 ? 0.0 : (t >= 1 ? 1.0 : (t + 1) / 2.0);
            RGBColor base = sky.lerp(grass, grassness);

            double phase = shimmerPhase.getOrDefault(key.ref(), 0.0);
            double breathe = 0.86 + 0.14 * Math.sin(2 * Math.PI * 0.09 * seconds + phase);
            budget.add(key.ref(), base, 0.62 * breathe);
        }

        // --- clouds, sky only -------------------------------------------
        for (Cloud c : clouds) {
            double x = c.x + seconds * CLOUD_SPEED * c.scale;
            x -= Math.floor(x);
            double radius = cloudRadius * c.scale;
            for (KeyGrid.LedPosition key : grid.allKeys()) {
                // Clouds sit above the horizon; a cloud in the grass is a
                // sheep, and this pattern is not that ambitious.
                if (key.y() > horizon + HORIZON_BLEND * 0.5) continue;
                double dx = key.x() - x;
                if (dx > 0.5) dx -= 1.0;
                if (dx < -0.5) dx += 1.0;
                double dy = (key.y() - c.y) * aspect;
                double d = Math.hypot(dx, dy);
                if (d > radius) continue;
                double f = 1.0 - d / radius;
                budget.add(key.ref(), cloud, f * f * c.alpha);
            }
        }
        return budget.resolve();
    }

    private void initIfNeeded(PatternContext ctx) {
        if (initialised) return;
        shimmerPhase = new HashMap<>();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            shimmerPhase.put(key.ref(), random.nextDouble() * 2 * Math.PI);
        }
        clouds = new Cloud[cloudCount];
        for (int i = 0; i < cloudCount; i++) {
            Cloud c = new Cloud();
            c.x = random.nextDouble();
            // Kept in the upper part of the sky band so they read as sky
            // rather than as fog sitting on the horizon.
            c.y = 0.06 + random.nextDouble() * (HORIZON - 0.20);
            // Varied size and speed, so they do not move as one sheet.
            c.scale = 0.7 + random.nextDouble() * 0.7;
            c.alpha = 0.30 + random.nextDouble() * 0.25;
            clouds[i] = c;
        }
        initialised = true;
    }
}
