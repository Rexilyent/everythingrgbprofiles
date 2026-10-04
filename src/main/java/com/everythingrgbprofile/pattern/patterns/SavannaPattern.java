package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Savanna: flat-topped acacias standing in gold grass, under a sky that's
 * been cooking all afternoon.
 *
 * <p>Replaces a plain {@code shimmer} in a sort of khaki, which is less a
 * savanna and more the colour of a filing cabinet. The savanna has one of the
 * most recognisable silhouettes in the whole game, and it was spending it on
 * beige.
 *
 * <h2>What's on the board</h2>
 * <ul>
 *   <li><b>Acacias.</b> Wide flat canopies up on thin trunks that fork just
 *       under the crown. That shape reads at keyboard resolution when almost
 *       nothing else does: a wide bar held up on a little Y. A few of them,
 *       well spaced, because a savanna is mostly the gaps between trees.
 *       The top edge of each canopy catches the light.</li>
 *   <li><b>Grass.</b> The bottom rows, gold, with gusts rolling through as
 *       brighter streaks. Kept deliberately secondary. Wind through a field
 *       is the sunflower plains' whole act, and this doesn't need to steal
 *       it.</li>
 *   <li><b>Sky.</b> Warm all the way up: pale gold at the horizon deepening to
 *       burnt orange overhead. No blue anywhere, partly because it's late
 *       afternoon and partly because blue blended into orange goes through
 *       grey on the way, and we've just spent a whole biome family getting
 *       rid of grey.</li>
 *   <li><b>Heat haze.</b> The band just over the horizon wavers in brightness,
 *       and the horizon itself wobbles a fraction of a row. Only there, never
 *       up in the sky, so it reads as hot ground and not as yet another
 *       shimmer. Which would be ironic, given what this replaced.</li>
 * </ul>
 *
 * <p>No sun on purpose. The badlands already has the sun crossing the board,
 * and two biomes doing the same trick makes it a template.
 *
 * <h2>The plateau</h2>
 * Same scene, camera moved up the hill. The bottom row is the plateau's own
 * grassy rim, right in front of you, where the wind's stronger. Past it the
 * plain drops away, hazed toward the sky colour, with small far-off acacias
 * standing on it. Smaller trees, more of them, more haze: that's distance, and
 * it's the whole difference between standing on the savanna and looking out
 * over it.
 *
 * <h2>Coordinates</h2>
 * Keys across, rows up from just under the bottom row, same as the alpine
 * scenes. It's terrain, so it's measured from the ground.
 */
public final class SavannaPattern implements Pattern {

    private static final double TAU = 2 * Math.PI;

    // --- presets -------------------------------------------------------

    /** Savanna: you're standing in it. Big trees, horizon up past the grass. */
    public static SavannaPattern savanna() {
        return new SavannaPattern(false, 2.1, 3, 1.0, 1.0, 5);
    }

    /** Savanna plateau: you're on top of it, looking out. Small far trees, low horizon, a rim of grass up front. */
    public static SavannaPattern savannaPlateau() {
        return new SavannaPattern(true, 2.3, 5, 0.45, 1.5, 19);
    }

    /** True for the plateau: grassy rim in front, the plain far below behind it. */
    private final boolean plateau;
    /** Horizon height in rows. */
    private final double horizon;
    private final int treeCount;
    /** Tree size multiplier. Under 1 is far away. */
    private final double treeScale;
    /** Gust strength multiplier. The plateau's exposed, so it gets more. */
    private final double windiness;
    private final int seed;

    private SavannaPattern(boolean plateau, double horizon, int treeCount,
                           double treeScale, double windiness, int seed) {
        this.plateau = plateau;
        this.horizon = horizon;
        this.treeCount = treeCount;
        this.treeScale = treeScale;
        this.windiness = windiness;
        this.seed = seed;
    }

    // --- levels --------------------------------------------------------

    private static final double SKY_LOW_LEVEL = 0.80, SKY_HIGH_LEVEL = 0.65;
    private static final double GRASS_LEVEL = 0.85;
    private static final double GUST_LEVEL = 0.40;
    private static final double CANOPY_LEVEL = 0.80;
    /** The lit top edge of every canopy. */
    private static final double CANOPY_RIM_LEVEL = 0.28;
    private static final double TRUNK_LEVEL = 0.70;
    /** How far toward the sky colour the plateau's distant plain is hazed. */
    private static final double FAR_HAZE = 0.22;

    // --- heat haze -----------------------------------------------------

    /** How far above the horizon the haze reaches, in rows. */
    private static final double HAZE_HEIGHT = 1.4;
    private static final double HAZE_DEPTH = 0.22;
    /** How much the horizon line itself wobbles, in rows. Tiny, or it stops being heat and starts being an earthquake. */
    private static final double HORIZON_WOBBLE = 0.12;

    // --- wind ----------------------------------------------------------

    /** Gust speed through the grass, keys per second. */
    private static final double GUST_SPEED = 2.2;
    /** How far a canopy leans in a full gust, in keys. Acacias are stiff; this is barely anything. */
    private static final double CANOPY_SWAY = 0.15;

    private KeyGrid layoutFor;
    /** {x, groundElev, canopyBottom, canopyTop, halfWidth, phase} */
    private final List<double[]> trees = new ArrayList<>();

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        double t = elapsedMillis / 1000.0;
        KeyGrid grid = ctx.grid();
        double keyWidth = Math.max(1e-6, grid.keyWidthNormalised());
        double rowStep = keyWidth / Math.max(0.05, grid.aspectRatio());
        RGBColor grass = ctx.baseColor();
        RGBColor light = ctx.resolvedAccentColor();

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxX = 0;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            minY = Math.min(minY, key.y());
            maxY = Math.max(maxY, key.y());
            maxX = Math.max(maxX, key.x());
        }
        double lastRow = Math.max(1, (maxY - minY) / rowStep);
        double widthKeys = maxX / keyWidth;
        double boardHeight = lastRow + 1;
        if (layoutFor != grid) rollLayout(grid, widthKeys, boardHeight);

        // One gust envelope for the whole scene, so the grass streaks and the
        // canopies lean on the same breath of wind.
        double gust = windiness * (0.55 + 0.45 * Math.sin(TAU * t / 8.3) * Math.sin(TAU * t / 3.7 + 0.6));

        RGBColor farGrass = grass.lerp(ColorPalette.SAVANNA_HAZE, FAR_HAZE);
        RGBColor farLeaves = ColorPalette.ACACIA_LEAVES.lerp(ColorPalette.SAVANNA_HAZE, FAR_HAZE * 0.5);
        RGBColor farBark = ColorPalette.ACACIA_BARK.lerp(ColorPalette.SAVANNA_HAZE, FAR_HAZE * 0.8);

        LightBudget budget = new LightBudget();

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double col = key.x() / keyWidth;
            double rowF = (key.y() - minY) / rowStep;
            double elev = lastRow + 0.5 - rowF;
            KeyGrid.LedRef ref = key.ref();

            double skyline = horizon + 0.25 * (valueNoise(col * 0.3 + seed, 1.7) - 0.5)
                    + HORIZON_WOBBLE * Math.sin(TAU * (col * 0.21 + t * 0.23)) * Math.sin(TAU * t / 2.9 + col * 0.7);
            double rim = plateau ? 1.05 + 0.3 * (valueNoise(col * 0.5 + seed, 9.1) - 0.5) : -1;

            // --- trees -------------------------------------------------
            double leaves = 0, bark = 0, crown = 0;
            for (double[] tr : trees) {
                double lean = CANOPY_SWAY * treeScale * gust * Math.sin(TAU * t / 2.3 + tr[5]);
                double hw = tr[4];
                double dx = col - tr[0];
                if (Math.abs(dx) > hw + 1.0) continue;
                // Canopy: flat on top, slightly domed underneath, a little
                // lumpy along both edges so it's foliage and not a plank.
                double cdx = (dx - lean) / hw;
                if (Math.abs(cdx) < 1.3) {
                    double thick = tr[3] - tr[2];
                    double top = tr[3] - 0.15 * thick * Math.pow(Math.abs(cdx), 4)
                            + 0.18 * treeScale * (valueNoise(col * 1.4 + tr[5] * 7, 2.2) - 0.5);
                    double bottom = tr[2] + 0.35 * thick * cdx * cdx;
                    double soft = 0.3 * Math.max(0.6, treeScale);
                    double c = smoothstep(top + soft, top - soft, elev)
                            * smoothstep(bottom - soft, bottom + soft, elev)
                            * smoothstep(1.15, 0.9, Math.abs(cdx));
                    if (c > leaves) {
                        leaves = c;
                        crown = smoothstep(top - 0.35, top + 0.1, elev);
                    }
                }
                // Trunk: one stem, forking into a Y for the top 45%.
                if (elev > tr[1] - 0.3 && elev < tr[2] + 0.2) {
                    double f = (elev - tr[1]) / Math.max(0.1, tr[2] - tr[1]);
                    double fork = Math.max(0, (f - 0.55) / 0.45);
                    double spread = 0.45 * hw * fork;
                    double off = lean * Math.max(0, f);
                    double nearest = Math.min(Math.abs(dx - off - spread), Math.abs(dx - off + spread));
                    double width = 0.2 + 0.1 * treeScale;
                    bark = Math.max(bark, smoothstep(width + 0.3, width - 0.05, nearest));
                }
            }
            bark *= 1 - leaves;
            double treeCover = leaves + bark;

            // --- ground or sky -----------------------------------------
            double open = 1 - treeCover;
            if (open > 0) {
                if (plateau && elev < rim + 0.3) {
                    // The rim, up close. Full grass, strong gusts.
                    double c = smoothstep(rim + 0.3, rim - 0.3, elev);
                    addGrass(budget, ref, grass, light, col, elev, t, gust * 1.2, open * c);
                    if (c < 1) addFarPlain(budget, ref, farGrass, col, elev, t, open * (1 - c));
                } else if (elev < skyline) {
                    double ground = smoothstep(skyline + 0.3, skyline - 0.3, elev);
                    if (plateau) addFarPlain(budget, ref, farGrass, col, elev, t, open * ground);
                    else addGrass(budget, ref, grass, light, col, elev, t, gust, open * ground);
                    if (ground < 1) addSky(budget, ref, elev, skyline, boardHeight, col, t, open * (1 - ground));
                } else {
                    addSky(budget, ref, elev, skyline, boardHeight, col, t, open);
                }
            }

            // --- the trees themselves ----------------------------------
            boolean far = treeScale < 1;
            if (leaves > 0) {
                budget.add(ref, far ? farLeaves : ColorPalette.ACACIA_LEAVES, leaves * CANOPY_LEVEL);
                if (crown > 0) budget.add(ref, light, leaves * crown * CANOPY_RIM_LEVEL * (far ? 0.6 : 1));
            }
            if (bark > 0) budget.add(ref, far ? farBark : ColorPalette.ACACIA_BARK, bark * TRUNK_LEVEL);
        }

        return budget.resolve();
    }

    /** Near grass: gold, with vertical blade texture and gust streaks running through it. */
    private void addGrass(LightBudget budget, KeyGrid.LedRef ref, RGBColor grass, RGBColor light,
                          double col, double elev, double t, double gust, double amount) {
        if (amount <= 0) return;
        double blades = 0.85 + 0.15 * (2 * valueNoise(col * 1.6 + seed, elev * 0.6) - 1);
        budget.add(ref, grass, amount * GRASS_LEVEL * blades);
        double streak = smoothstep(0.55, 0.85, valueNoise((col - GUST_SPEED * t) * 0.28 + seed, elev * 0.9 + 4.1));
        if (streak > 0) budget.add(ref, light, amount * GUST_LEVEL * streak * gust);
    }

    /** The plain seen from the plateau: hazed, flatter, faint streaks of distance in it. */
    private void addFarPlain(LightBudget budget, KeyGrid.LedRef ref, RGBColor farGrass,
                             double col, double elev, double t, double amount) {
        if (amount <= 0) return;
        double bands = 0.88 + 0.12 * Math.sin(TAU * (elev * 1.1 + 0.6 * valueNoise(col * 0.2 + seed, 3.3)));
        budget.add(ref, farGrass, amount * GRASS_LEVEL * 0.70 * bands);
    }

    /** Sky: gold at the horizon to burnt orange overhead, with the haze wavering just above the ground. */
    private void addSky(LightBudget budget, KeyGrid.LedRef ref, double elev, double skyline,
                        double boardHeight, double col, double t, double amount) {
        if (amount <= 0) return;
        double up = smoothstep(skyline, boardHeight, elev);
        RGBColor colour = ColorPalette.SAVANNA_HAZE.lerp(ColorPalette.SAVANNA_SKY_HIGH, up);
        double level = SKY_LOW_LEVEL + (SKY_HIGH_LEVEL - SKY_LOW_LEVEL) * up;
        double above = elev - skyline;
        if (above < HAZE_HEIGHT) {
            // Two waves at odd speeds plus a slow noise, so the haze never
            // settles into a ripple you could count.
            double waver = Math.sin(TAU * (col * 0.45 + t * 0.7)) * 0.6
                    + Math.sin(TAU * (col * 0.83 - t * 0.43) + 1.9) * 0.4;
            waver *= 0.6 + 0.4 * valueNoise(col * 0.3 + seed, t * 0.5);
            level *= 1 + HAZE_DEPTH * waver * smoothstep(HAZE_HEIGHT, 0.0, above);
        }
        budget.add(ref, colour, amount * level);
    }

    /**
     * Plants the acacias across the board, evenly-ish: the board's split into
     * slots and each tree lands somewhere in the middle of its slot. A
     * savanna's trees are spread out because they're competing for water,
     * and random placement kept clumping two together, which is not how
     * anything competing for water ends up.
     */
    private void rollLayout(KeyGrid grid, double widthKeys, double boardHeight) {
        trees.clear();
        double slot = (widthKeys + 2) / Math.max(1, treeCount);
        for (int i = 0; i < treeCount; i++) {
            double x = -1 + slot * (i + 0.25 + 0.5 * hash(i, 10));
            double groundElev;
            double canopyBottom, canopyTop, halfWidth;
            if (plateau) {
                // Standing on the far plain, between the rim and the horizon.
                groundElev = lerp(1.5, horizon - 0.1, hash(i, 11));
                canopyBottom = groundElev + lerp(0.8, 1.2, hash(i, 12));
                canopyTop = canopyBottom + lerp(0.55, 0.75, hash(i, 13));
                halfWidth = lerp(1.0, 1.5, hash(i, 14));
            } else {
                groundElev = lerp(0.6, 1.4, hash(i, 11));
                canopyBottom = Math.min(boardHeight - 2.6, groundElev + lerp(1.6, 2.2, hash(i, 12)));
                canopyTop = canopyBottom + lerp(1.1, 1.4, hash(i, 13));
                halfWidth = lerp(2.2, 3.0, hash(i, 14));
            }
            trees.add(new double[]{x, groundElev, canopyBottom, canopyTop, halfWidth, TAU * hash(i, 15)});
        }
        layoutFor = grid;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private double hash(int n, int salt) {
        return latticeHash(n * 7919 + seed * 131, salt * 104729 + 17);
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double v = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        return v * v * (3 - 2 * v);
    }

    /** Same lattice hash the canopy and the swamp use; see CanopyDapplePattern for the notes. */
    private static double latticeHash(int x, int y) {
        int n = x * 374761393 + y * 668265263;
        n = (n ^ (n >> 13)) * 1274126177;
        return ((n ^ (n >> 16)) & 0x7fffffff) / (double) 0x7fffffff;
    }

    private static double valueNoise(double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        double sx = fx * fx * (3 - 2 * fx);
        double sy = fy * fy * (3 - 2 * fy);
        double top = lerp(latticeHash(x0, y0), latticeHash(x0 + 1, y0), sx);
        double bottom = lerp(latticeHash(x0, y0 + 1), latticeHash(x0 + 1, y0 + 1), sx);
        return lerp(top, bottom, sy);
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }
}
