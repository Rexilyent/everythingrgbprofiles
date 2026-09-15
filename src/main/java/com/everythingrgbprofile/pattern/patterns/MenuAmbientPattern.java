package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What your keyboard does on the title screen: a mineshaft face, lit by
 * torches, with something cutting into the rock.
 *
 * <p>The look being matched is the Forge Everything pack's title background: a
 * Create drill contraption grinding away deep in a cave, painted in FTB-style
 * chiaroscuro. Heavy tech and magic, not kitchen-sink. Something growing in
 * the dark crevices.
 *
 * <h2>Four layers</h2>
 * <ol>
 *   <li><b>Stone and torch pools.</b> Cold dark rock everywhere, with warm
 *       pools around the torches the drill has left behind it. The pools
 *       breathe on an irregular flicker rather than a clean sine, because a
 *       torch is a flame and a sine is a dimmer knob.</li>
 *   <li><b>The drill.</b> One localised spot of harsh, fast, high-contrast
 *       flicker — the bit meeting rock. It works in bursts with pauses
 *       between them, and it chews its way across the board.</li>
 *   <li><b>Sparks.</b> Thrown from the drill point while it is actually
 *       cutting, flying outward on a gravity arc, cooling from white-hot to
 *       dull red as they go.</li>
 *   <li><b>Ore glints.</b> Rare, brief, and in a spread of ore colours rather
 *       than one violet. Weighted so redstone and gold are common and diamond
 *       is not, which is what makes it read as ore rather than as confetti.</li>
 * </ol>
 *
 * <p>Composited <b>here</b> rather than handed to
 * {@link com.everythingrgbprofile.priority.Compositor}, because these are one
 * Tier 1 base effect built from four parts — not four effects that happen to
 * co-occur. Registering them separately would let the priority system make
 * decisions about them independently, which is exactly what we don't want.
 *
 * <h2>Everything is additive light</h2>
 * Layers accumulate into an RGB light budget per key and are normalised once
 * at the end, instead of each layer overwriting the last with {@code put()}.
 * That is what lets a spark cross a torch pool and come out brighter, and a
 * glint sit <i>in</i> the rock rather than punching a hole through it. An
 * earlier version replaced outright, so whichever layer ran last won the key
 * whether or not it was the brighter thing.
 *
 * <h2>It digs a tunnel, and it lights it as it goes</h2>
 * The drill used to teleport to a random key every sixteen seconds and the
 * torches were scattered once at startup and then never moved. Both were
 * static ideas dressed up with animation: nothing on the board was going
 * anywhere, so the scene had no direction and nothing ever changed except
 * which pixels happened to be flickering.
 *
 * <p>Now the drill bores a corridor across the board, and every few keys of
 * progress it drops a torch behind itself. Torches fade up as they are placed,
 * burn while the drill is still nearby, and gutter out once the trail gets too
 * long — so what you are looking at is a lit corridor receding into the dark
 * behind a machine, which is the scene the title art actually shows.
 *
 * <p>When the drill runs off one edge it comes back along the other in the
 * opposite direction, a couple of rows over, the way anybody actually strip
 * mines. It never jumps: the turn happens off-board, in the margin.
 *
 * <h2>None of that motion is stored anywhere</h2>
 * Distance drilled is a closed-form function of elapsed time, and everything
 * else — where the drill is, where each torch was planted, how bright it is
 * now — is derived from that one number. So the whole traversal survives a
 * clock restart for free, with no reseeding and no state to go stale, which is
 * the same reason the earlier teleporting drill computed its position from a
 * slot index rather than remembering it.
 *
 * <p>Result: dark, uneven, painted-looking. If it ever reads as a clean bright
 * gamer-RGB menu, it has stopped matching the art it was made for.
 */
public final class MenuAmbientPattern implements Pattern {

    // --- Geometry, all authored in key-widths so it survives a move from a
    // --- full-size board to a tenkeyless without re-tuning.
    private static final double TORCH_RADIUS_KEYS = 3.0;
    private static final double DRILL_CORE_KEYS = 1.2;
    private static final double DRILL_HALO_KEYS = 3.0;

    /** Dark rock is dim but never black — you should always see the cave. */
    private static final double STONE_FLOOR = 0.42;

    // --- The drill's working rhythm. Continuous grinding reads as a fault
    // --- light; bursts with pauses read as someone operating a machine.
    private static final double DRILL_BURST_SECONDS = 2.4;
    private static final double DRILL_PAUSE_SECONDS = 1.2;

    /**
     * How fast the bit advances, in normalised x per second <b>of actual
     * cutting</b> — it does not creep forward during the pauses between
     * passes. Rock does not move when nothing is grinding it, and a drill that
     * slid along during its own downtime would read as a light being panned
     * rather than a machine making progress.
     *
     * <p>0.065 works out to roughly twenty-seven seconds of wall clock per
     * crossing once the pauses are counted, which is slow enough to read as
     * heavy machinery and quick enough that you can see it move while the menu
     * music is still on its first loop.
     */
    private static final double DRILL_SPEED = 0.065;
    /**
     * How far past each edge the corridor runs. The drill turns around out
     * here, so the change of direction and the step to a new row both happen
     * where nobody can see them.
     */
    private static final double EDGE_MARGIN = 0.08;
    private static final double CORRIDOR_LENGTH = 1.0 + 2 * EDGE_MARGIN;
    /** Height wander within one corridor, so the tunnel is dug rather than ruled. */
    private static final double CORRIDOR_WAVE = 0.055;
    /** Keeps the corridor off the very top and bottom edges of the board. */
    private static final double CORRIDOR_Y_MIN = 0.08;
    private static final double CORRIDOR_Y_SPAN = 0.84;
    /** Gap between planted torches, in key widths. */
    private static final double TORCH_SPACING_KEYS = 4.0;

    // --- Spark ballistics, in x-normalised units per second.
    private static final double SPARK_SPEED_MIN = 0.18;
    private static final double SPARK_SPEED_RANGE = 0.34;
    private static final double SPARK_GRAVITY = 0.55;
    private static final double SPARK_LIFE_MIN = 0.35;
    private static final double SPARK_LIFE_RANGE = 0.45;

    private static final double GLINT_HALO_KEYS = 1.5;

    /**
     * The ore table. Weights are deliberately lopsided: you hit redstone and
     * gold constantly and diamond almost never, and reproducing that ratio is
     * the entire difference between "ores glinting in the wall" and "coloured
     * lights". Amethyst is supplied by config, so the arcane accent the pack
     * brief asks for is still in the mix — just no longer the only thing here.
     */
    private static final RGBColor ORE_REDSTONE = RGBColor.fromHex("#FF2A2A");
    private static final RGBColor ORE_GOLD = RGBColor.fromHex("#FCEE4B");
    private static final RGBColor ORE_LAPIS = RGBColor.fromHex("#3D6ED9");
    private static final RGBColor ORE_COPPER = RGBColor.fromHex("#E0734D");
    private static final RGBColor ORE_EMERALD = RGBColor.fromHex("#17DD62");
    private static final RGBColor ORE_DIAMOND = RGBColor.fromHex("#4AEDD9");

    /**
     * One warm pool of torchlight on the rock.
     *
     * <p>These are recomputed from the drill's progress every frame rather
     * than stored, so the objects are reused in place and only ever hold the
     * answer for the current frame. Nothing here is remembered between frames.
     */
    private static final class Torch {
        double x, y, phase, strength;
    }

    /** One thrown spark. Recycled in place; never escapes this class. */
    private static final class Spark {
        double x0, y0, vx, vy;
        double startSeconds;
        /** <= 0 means "parked": the slot is waiting for the drill to cut again. */
        double lifeSeconds;
    }

    /** One ore facet catching the light. */
    private static final class Glint {
        KeyGrid.LedPosition pos;
        double startMillis, durationMillis;
        RGBColor color;
    }

    private final RGBColor emberColor;
    private final RGBColor torchColor;
    private final RGBColor drillCoreColor;
    private final RGBColor sparkHotColor;
    private final RGBColor sparkCoolColor;
    private final RGBColor[] oreColors;
    private final double[] oreWeights;
    private final double oreWeightTotal;

    private final int sparkCount;
    private final double sparkIntervalMillis;
    private final int glintCount;
    private final double glintIntervalMillis;
    private final int torchCount;

    private final Random random = new Random();

    private boolean initialized = false;
    private double lastElapsedMillis = 0;

    /** Reused every frame; {@link #liveTorches} says how many entries are valid. */
    private Torch[] torches;
    private int liveTorches;
    private List<Spark> sparks;
    private List<Glint> glints;
    private Map<KeyGrid.LedRef, Double> stonePhase;

    /** Aspect-corrected radii, resolved once the grid's real geometry is known. */
    private double torchRadius, drillCore, drillHalo, glintHalo, aspect;
    /** Gap between torches in normalised units, from {@link #TORCH_SPACING_KEYS}. */
    private double torchSpacing;

    private double drillX, drillY;
    /** True while the bit is actually in the rock — gates spark emission. */
    private boolean drilling = false;
    /** +1 while the drill is heading right, -1 while it is heading left. */
    private double drillFacing = 1;

    public MenuAmbientPattern(RGBColor emberColor, RGBColor arcaneColor,
                              int sparkCount, double sparkIntervalMillis,
                              int glintCount, double glintIntervalMillis,
                              int torchCount) {
        this.emberColor = emberColor;
        // Everything warm is derived from the one configured ember colour, so
        // recolouring the menu in config still produces a coherent scene
        // rather than a torch and a drill that disagree about what fire is.
        this.torchColor = emberColor;
        this.drillCoreColor = emberColor.lightened(0.75);
        this.sparkHotColor = emberColor.lightened(0.6);
        this.sparkCoolColor = emberColor.scaled(0.35);
        this.oreColors = new RGBColor[]{
                ORE_REDSTONE, ORE_GOLD, ORE_LAPIS, ORE_COPPER, arcaneColor, ORE_EMERALD, ORE_DIAMOND
        };
        this.oreWeights = new double[]{0.22, 0.18, 0.16, 0.13, 0.14, 0.10, 0.07};
        double total = 0;
        for (double w : oreWeights) total += w;
        this.oreWeightTotal = total;

        this.sparkCount = Math.max(1, sparkCount);
        this.sparkIntervalMillis = Math.max(40, sparkIntervalMillis);
        this.glintCount = Math.max(1, glintCount);
        this.glintIntervalMillis = Math.max(120, glintIntervalMillis);
        this.torchCount = Math.max(1, torchCount);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        initIfNeeded(ctx, elapsedMillis);

        // Same clock-restart guard as the twinkle engine, and for the same
        // reason: this effect's elapsed time resets to zero every time the
        // menu comes back, while sparks and glints hold absolute schedules.
        // Without this they would all sit in the "not started yet" branch for
        // as long as the menu ran the previous time.
        if (elapsedMillis < lastElapsedMillis) {
            reseed(ctx, elapsedMillis);
        }
        lastElapsedMillis = elapsedMillis;

        double seconds = elapsedMillis / 1000.0;
        LightBudget budget = new LightBudget();

        // One number drives the whole scene: how far the bit has cut. The
        // drill's position, the torches behind it and where the sparks come
        // from are all read off this, which is why none of them need storing.
        double travelled = distanceDrilled(seconds);
        drillX = tunnelX(travelled);
        drillY = tunnelY(travelled);
        drillFacing = corridorDirection(travelled);
        updateTorches(travelled);

        renderStoneAndTorches(ctx, seconds, budget);
        renderDrill(ctx, seconds, budget);
        renderSparks(ctx, seconds, budget);
        renderGlints(ctx, elapsedMillis, budget);

        return budget.resolve();
    }

    // ---------------------------------------------------------------
    // Where the drill has got to
    // ---------------------------------------------------------------

    /**
     * Total distance cut by time {@code seconds}, in normalised x units.
     *
     * <p>The bit only advances while it is actually cutting, so this is the
     * integral of the burst cycle rather than plain {@code speed * time}. That
     * integral has a closed form because the cycle is periodic: every complete
     * cycle contributes exactly one burst's worth of travel, and the current
     * partial cycle contributes however much of its burst has elapsed. No
     * accumulator, so no drift, and no dependence on frame timing.
     */
    private static double distanceDrilled(double seconds) {
        double cycle = DRILL_BURST_SECONDS + DRILL_PAUSE_SECONDS;
        double completed = Math.floor(seconds / cycle);
        double intoCycle = seconds - completed * cycle;
        double cutting = completed * DRILL_BURST_SECONDS + Math.min(intoCycle, DRILL_BURST_SECONDS);
        return cutting * DRILL_SPEED;
    }

    private static int corridorIndex(double travelled) {
        return (int) Math.floor(travelled / CORRIDOR_LENGTH);
    }

    /** 0 at the start of the current corridor, 1 at its far end. */
    private static double corridorProgress(double travelled) {
        return (travelled - corridorIndex(travelled) * CORRIDOR_LENGTH) / CORRIDOR_LENGTH;
    }

    /** +1 for corridors dug left to right, -1 for the return passes. */
    private static double corridorDirection(double travelled) {
        return (corridorIndex(travelled) & 1) == 0 ? 1 : -1;
    }

    private static double tunnelX(double travelled) {
        double along = corridorProgress(travelled);
        // Odd corridors run backwards, so the drill turns around at the edge
        // and digs home rather than snapping back to where it started.
        double u = (corridorIndex(travelled) & 1) == 0 ? along : 1.0 - along;
        return -EDGE_MARGIN + u * CORRIDOR_LENGTH;
    }

    private static double tunnelY(double travelled) {
        int corridor = corridorIndex(travelled);
        double wave = CORRIDOR_WAVE
                * Math.sin(2 * Math.PI * 1.7 * corridorProgress(travelled) + corridor * 2.4);
        return corridorY(corridor) + wave;
    }

    /**
     * The row each corridor is cut at.
     *
     * <p>Stepping by the golden ratio's fractional part is the cheap trick for
     * spreading successive values evenly over a range without them ever
     * repeating or clumping. A tidier step like 0.25 would cycle through four
     * rows forever and the board would visibly loop; this one keeps finding
     * gaps, so consecutive passes stay a comfortable distance apart while the
     * long run still covers everything.
     */
    private static double corridorY(int corridor) {
        double v = 0.5 + corridor * 0.3819660112501051;
        return CORRIDOR_Y_MIN + CORRIDOR_Y_SPAN * (v - Math.floor(v));
    }

    // ---------------------------------------------------------------
    // Layer 1 — stone, and the torches burning on it
    // ---------------------------------------------------------------
    private void renderStoneAndTorches(PatternContext ctx, double seconds, LightBudget budget) {
        RGBColor stone = ctx.baseColor();
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            // Per-key phase, so the unlit rock is a surface breathing rather
            // than one big synchronised lamp. Same reasoning as ShimmerPattern,
            // which this layer replaces.
            double phase = stonePhase.getOrDefault(key.ref(), 0.0);
            double breath = 0.84 + 0.16 * Math.sin(2 * Math.PI * 0.06 * seconds + phase);
            budget.add(key.ref(), stone, STONE_FLOOR * breath);

            // Brightest torch wins rather than summing, so three overlapping
            // pools do not blow out into one flat bright region.
            double warm = 0;
            for (int i = 0; i < liveTorches; i++) {
                Torch torch = torches[i];
                double d = distance(key.x(), key.y(), torch.x, torch.y);
                if (d >= torchRadius) continue;
                double falloff = 1.0 - d / torchRadius;
                falloff *= falloff; // quadratic: tight bright core, long soft edge
                warm = Math.max(warm, falloff * torch.strength * torchFlicker(seconds, torch.phase));
            }
            if (warm > 0.001) {
                budget.add(key.ref(), torchColor, warm);
            }
        }
    }

    /**
     * Works out which torches are currently lit, and how brightly.
     *
     * <p>A torch is planted every {@link #TORCH_SPACING_KEYS} of progress, so
     * torch number <i>n</i> was planted at distance {@code n * spacing} and
     * its position is just the tunnel evaluated there. That means the whole
     * trail is recoverable from the current distance alone — there is no list
     * of placed torches anywhere, because there does not need to be one.
     *
     * <p>Age is measured in <b>distance behind the bit</b> rather than in
     * seconds, which is what makes the trail hold its shape: the drill pauses
     * between passes, and a torch timed in seconds would keep ageing during
     * the pause and gutter out early while the drill sat still next to it.
     */
    private void updateTorches(double travelled) {
        liveTorches = 0;
        if (torchSpacing <= 0) return;
        int newest = (int) Math.floor(travelled / torchSpacing);
        double trailLength = torches.length * torchSpacing;
        // Fade a torch up over roughly a third of a gap, so it reads as being
        // lit rather than switching on, and let the last one gutter out over
        // most of a gap so the tail of the trail dissolves instead of popping.
        double fadeIn = torchSpacing * 0.35;
        double fadeOut = torchSpacing * 0.90;

        for (int i = 0; i < torches.length; i++) {
            int index = newest - i;
            if (index < 0) continue;
            double planted = index * torchSpacing;
            double behind = travelled - planted;
            if (behind < 0 || behind > trailLength) continue;

            double strength;
            if (behind < fadeIn) {
                strength = behind / fadeIn;
            } else if (behind > trailLength - fadeOut) {
                strength = (trailLength - behind) / fadeOut;
            } else {
                strength = 1.0;
            }
            if (strength <= 0.02) continue;

            Torch torch = torches[liveTorches++];
            torch.x = tunnelX(planted);
            torch.y = tunnelY(planted);
            // Phase from the torch's own index, so each flame has its own
            // rhythm and keeps it for as long as that torch is burning.
            torch.phase = (index * 2.399963) % (2 * Math.PI);
            // Uneven brightness, also derived from the index rather than
            // rolled, so a torch does not change character frame to frame.
            torch.strength = strength * (0.72 + 0.28 * fractionalHash(index));
        }
    }

    /** Deterministic 0..1 from an integer. Cheap, and good enough for jitter. */
    private static double fractionalHash(int n) {
        double v = Math.sin(n * 12.9898) * 43758.5453;
        return v - Math.floor(v);
    }

    /**
     * Three incommensurate sines summed. The frequencies do not divide into
     * each other, so the result never repeats on a period the eye can latch
     * onto — which is the whole difference between "a flame" and "a pulsing
     * light". Kept under ~5Hz because the SDK worker renders at about 29fps
     * and anything past 14Hz aliases into a slow phantom beat instead of a
     * flicker.
     */
    private static double torchFlicker(double seconds, double phase) {
        double n = 0.55 * Math.sin(2 * Math.PI * 1.3 * seconds + phase)
                 + 0.30 * Math.sin(2 * Math.PI * 2.9 * seconds + phase * 2.3)
                 + 0.15 * Math.sin(2 * Math.PI * 5.1 * seconds + phase * 4.1);
        return 0.80 + 0.20 * n; // 0.60 .. 1.00 — a glow that moves, not a strobe
    }

    // ---------------------------------------------------------------
    // Layer 2 — the drill
    // ---------------------------------------------------------------
    private void renderDrill(PatternContext ctx, double seconds, LightBudget budget) {
        // Position was already resolved in render() from the distance cut, so
        // there is nothing to place here — only the question of how hard the
        // bit is biting this instant.
        double cycle = DRILL_BURST_SECONDS + DRILL_PAUSE_SECONDS;
        double phase = seconds % cycle;
        double burst;
        if (phase >= DRILL_BURST_SECONDS) {
            burst = 0; // between passes
        } else {
            // Ramp in fast and out a little slower, so a burst starts with a
            // bite and trails off, instead of two hard square edges.
            double p = phase / DRILL_BURST_SECONDS;
            burst = Math.max(0, Math.min(1.0, Math.min(p / 0.06, (1.0 - p) / 0.14)));
        }
        drilling = burst > 0.2;
        if (burst <= 0) return;

        double flick = drillFlicker(seconds);
        for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
            double d = distance(key.x(), key.y(), drillX, drillY);
            if (d >= drillHalo) continue;
            double falloff = 1.0 - d / drillHalo;
            falloff *= falloff;
            boolean core = d < drillCore;
            // The core takes the flicker at full contrast — that is the harsh
            // part, and it wants to slam between near-dark and white-hot. The
            // surrounding rock only catches the light, so its copy is damped:
            // stone does not strobe, it is merely lit by something that does.
            double intensity = burst * falloff * (core ? flick : 0.4 + 0.6 * flick);
            if (intensity <= 0.02) continue;
            budget.add(key.ref(), core ? drillCoreColor : emberColor, intensity);
        }
    }

    /**
     * Deliberately harsher than the torch: faster, and squared so it spends
     * most of its time low with sharp excursions up, rather than sitting
     * around the middle. That asymmetry is what reads as cutting.
     */
    private static double drillFlicker(double seconds) {
        double n = 0.5 * Math.sin(2 * Math.PI * 7.7 * seconds)
                 + 0.3 * Math.sin(2 * Math.PI * 11.3 * seconds + 1.7)
                 + 0.2 * Math.sin(2 * Math.PI * 4.9 * seconds + 3.1);
        double v = 0.5 + 0.5 * n;   // 0..1
        return 0.25 + 0.75 * v * v; // biased dark, spiky bright, never fully out
    }

    // ---------------------------------------------------------------
    // Layer 3 — sparks thrown off the bit
    // ---------------------------------------------------------------
    private void renderSparks(PatternContext ctx, double seconds, LightBudget budget) {
        for (int i = 0; i < sparks.size(); i++) {
            Spark spark = sparks.get(i);
            double age = seconds - spark.startSeconds;
            if (age < 0) continue;                       // scheduled, not thrown yet
            if (spark.lifeSeconds <= 0 || age >= spark.lifeSeconds) {
                respawnSpark(spark, seconds);
                continue;
            }

            double p = age / spark.lifeSeconds;
            double x = spark.x0 + spark.vx * age;
            // Gravity only on the y term, and in normalised-y units — see
            // spawnSpark for why the conversion is a division by aspect.
            double y = spark.y0 + spark.vy * age + 0.5 * (SPARK_GRAVITY / aspect) * age * age;
            if (x < -0.05 || x > 1.05 || y < -0.05 || y > 1.05) {
                respawnSpark(spark, seconds);
                continue;
            }

            KeyGrid.LedPosition nearest = nearestKey(ctx.grid(), x, y);
            if (nearest == null) continue;
            // Squared falloff: a spark is bright for the first third of its
            // flight and then winks out, rather than dimming evenly the whole
            // way, which would read as a slow-moving dot.
            double fade = (1.0 - p) * (1.0 - p);
            RGBColor color = sparkHotColor.lerp(sparkCoolColor, Math.min(1.0, p * 1.3));
            budget.add(nearest.ref(), color, fade);
        }
    }

    private void respawnSpark(Spark spark, double seconds) {
        if (!drilling) {
            // Park the slot and look again shortly. Sparks come from the bit
            // meeting rock, so they must not keep flying during the pause
            // between passes — that is the detail that makes the drill read as
            // a machine doing work rather than a permanent fountain.
            spark.startSeconds = seconds + 0.1;
            spark.lifeSeconds = -1;
            return;
        }
        spawnSpark(spark, seconds + random.nextDouble() * (sparkIntervalMillis / 1000.0));
    }

    private void spawnSpark(Spark spark, double startSeconds) {
        spark.x0 = drillX;
        spark.y0 = drillY;
        double angle = random.nextDouble() * 2 * Math.PI;
        double speed = SPARK_SPEED_MIN + random.nextDouble() * SPARK_SPEED_RANGE;
        // Biased against the direction of travel. Sparks come off the back of
        // a cutting head, and a moving drill throwing them evenly in all
        // directions reads as a stationary sprinkler that happens to be
        // sliding along.
        spark.vx = Math.cos(angle) * speed - drillFacing * speed * 0.35;
        // Normalised y is compressed relative to normalised x by the board's
        // aspect ratio, so an equal PHYSICAL speed needs a larger number here.
        // Skip the division and sparks fly out in a flat sideways fan, which
        // is exactly the mistake the rain preset was making.
        spark.vy = Math.sin(angle) * speed / aspect;
        spark.startSeconds = startSeconds;
        spark.lifeSeconds = SPARK_LIFE_MIN + random.nextDouble() * SPARK_LIFE_RANGE;
    }

    // ---------------------------------------------------------------
    // Layer 4 — ore glinting in the rock
    // ---------------------------------------------------------------
    private void renderGlints(PatternContext ctx, double elapsedMillis, LightBudget budget) {
        for (int i = 0; i < glints.size(); i++) {
            Glint glint = glints.get(i);
            double age = elapsedMillis - glint.startMillis;
            if (age < 0) continue;
            if (age > glint.durationMillis) {
                glints.set(i, newGlint(ctx, elapsedMillis + random.nextDouble() * glintIntervalMillis));
                continue;
            }
            double p = age / glint.durationMillis;
            // Sharp attack then a long tail, multiplied by a small oscillation
            // so the highlight moves across the facet. A plain fade up and down
            // reads as a lamp; the wobble is what makes it read as light
            // catching a crystal face as you shift your head.
            double envelope = p < 0.10 ? p / 0.10 : Math.pow(1.0 - (p - 0.10) / 0.90, 1.7);
            double sparkle = 0.78 + 0.22 * Math.sin(p * Math.PI * 5.0);
            double brightness = Math.max(0, envelope) * sparkle;

            budget.add(glint.pos.ref(), glint.color, brightness * 0.9);

            // A faint halo on the immediate neighbours, so the ore sits IN the
            // wall and lights the rock around it instead of being one isolated
            // coloured pixel floating in the dark.
            for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
                if (key.ref().equals(glint.pos.ref())) continue;
                double d = distance(key.x(), key.y(), glint.pos.x(), glint.pos.y());
                if (d >= glintHalo) continue;
                double falloff = 1.0 - d / glintHalo;
                budget.add(key.ref(), glint.color, brightness * falloff * falloff * 0.22);
            }
        }
    }

    private Glint newGlint(PatternContext ctx, double startMillis) {
        List<KeyGrid.LedPosition> keys = ctx.grid().allKeys();
        Glint glint = new Glint();
        glint.pos = keys.get(random.nextInt(keys.size()));
        glint.startMillis = startMillis;
        // Short and sharp relative to the gap between glints, so they stay
        // events rather than merging into a permanent coloured haze.
        glint.durationMillis = glintIntervalMillis * (0.22 + random.nextDouble() * 0.24);
        glint.color = pickOre();
        return glint;
    }

    private RGBColor pickOre() {
        double roll = random.nextDouble() * oreWeightTotal;
        for (int i = 0; i < oreColors.length; i++) {
            roll -= oreWeights[i];
            if (roll <= 0) return oreColors[i];
        }
        return oreColors[oreColors.length - 1];
    }

    // ---------------------------------------------------------------
    // Setup and shared helpers
    // ---------------------------------------------------------------
    private void initIfNeeded(PatternContext ctx, long elapsedMillis) {
        if (initialized) return;
        KeyGrid grid = ctx.grid();
        // aspectRatio() is height/width, about 0.30 on a full-size board.
        // Everything radial in here is measured through distance() so that a
        // "circle" is round to the eye rather than round in normalised space,
        // where it would be a wide flat ellipse.
        aspect = Math.max(0.05, grid.aspectRatio());
        double keyWidth = grid.keyWidthNormalised();
        torchRadius = TORCH_RADIUS_KEYS * keyWidth;
        drillCore = DRILL_CORE_KEYS * keyWidth;
        drillHalo = DRILL_HALO_KEYS * keyWidth;
        glintHalo = GLINT_HALO_KEYS * keyWidth;
        torchSpacing = TORCH_SPACING_KEYS * keyWidth;

        stonePhase = new HashMap<>();
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            stonePhase.put(key.ref(), random.nextDouble() * 2 * Math.PI);
        }

        torches = new Torch[torchCount];
        for (int i = 0; i < torchCount; i++) torches[i] = new Torch();
        sparks = new ArrayList<>(sparkCount);
        for (int i = 0; i < sparkCount; i++) sparks.add(new Spark());
        glints = new ArrayList<>(glintCount);
        for (int i = 0; i < glintCount; i++) glints.add(null);

        reseed(ctx, elapsedMillis);
        lastElapsedMillis = elapsedMillis;
        initialized = true;
    }

    /** Re-anchor every scheduled thing to a clock that has just restarted. */
    private void reseed(PatternContext ctx, double fromMillis) {
        double fromSeconds = fromMillis / 1000.0;
        for (Spark spark : sparks) {
            spark.startSeconds = fromSeconds + random.nextDouble() * (sparkIntervalMillis / 1000.0);
            spark.lifeSeconds = -1;
        }
        for (int i = 0; i < glints.size(); i++) {
            glints.set(i, newGlint(ctx, fromMillis - random.nextDouble() * glintIntervalMillis));
        }
        // The drill and its torches need nothing here: both are read straight
        // off the new clock, so a restart simply puts the tunnel back at the
        // beginning, which is exactly where a fresh menu should start it.
    }

    /**
     * Aspect-corrected distance in normalised units.
     *
     * <p>Normalised coordinates span 0..1 on both axes whatever the board's
     * real shape, so the y term has to be scaled by height/width for anything
     * that must look round. Without this a torch pool is a letterbox stripe.
     */
    private double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, (y1 - y2) * aspect);
    }

    /** Nearest LED to a normalised point. Brute force; see DriftParticlePattern for why that's fine. */
    private static KeyGrid.LedPosition nearestKey(KeyGrid grid, double nx, double ny) {
        KeyGrid.LedPosition best = null;
        double bestDist = Double.MAX_VALUE;
        for (KeyGrid.LedPosition key : grid.allKeys()) {
            double d = Math.hypot(key.x() - nx, key.y() - ny);
            if (d < bestDist) {
                bestDist = d;
                best = key;
            }
        }
        return best;
    }
}
