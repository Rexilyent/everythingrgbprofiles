package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.ColorRamp;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.Map;

/**
 * The Terraria Celestial Pillar field — not vibed, not eyeballed,
 * <b>fitted</b> to the four reference animations frame by frame.
 *
 * <p>This is the most carefully measured pattern in the mod, and the one whose
 * design changed the most along the way. Read the whole doc before you touch a
 * default; almost every "obviously wrong-looking" value here is wrong-looking
 * because the measurement said so.
 *
 * <h2>What the reference actually does</h2>
 * Each pillar GIF is a 133-key board on a square 8px key pitch, 71 frames at
 * 20ms (1.42s per cycle). Decomposing every key's brightness over time gives
 * one clean answer:
 *
 * <pre>
 *   phase(led, t) = 2*pi*t/period  +  k_r * r  +  arms * theta  +  phase0
 *   level(led, t) = clamp( W(r) * (DC + AMP * cos(phase)), 0, 1 )
 *   W(r)          = clamp((r - coreRadius) / (falloffEnd - coreRadius), 0, 1) ^ falloffPower
 * </pre>
 *
 * with r and theta in <b>key widths</b> from a fixed centre. Fit that to the
 * Nebula pillar and it reproduces all 71 frames × 133 keys at <b>r = 0.992</b>
 * (RMSE 0.036 on 0..1 brightness). Vortex fits the same geometry at r = 0.986
 * with only the contrast changed.
 *
 * <p>To be clear about what that means: this isn't an approximation of the
 * reference. Within GIF quantisation it <i>is</i> the reference.
 *
 * <h2>Three things earlier versions got wrong</h2>
 *
 * <p><b>1. The core does not move.</b> On all three 71-frame pillars the same
 * seven keys — {@code 5 6 R T Y F G} — sit at the black level in <i>every
 * single frame</i>, per-key standard deviation exactly zero. Not "nearly
 * zero". Zero. It's a static disc of radius ~1.29 key widths.
 *
 * <p>But you can SEE it wobble, so what gives? The wobble is real and it is
 * <b>emergent</b>. With one arm, the lit annulus is bright on one side and dim
 * on the other, and that asymmetry rotates. Track the brightness-weighted
 * centroid of the dark region and you get a circle of radius 0.46 key widths,
 * exactly one revolution per cycle. A wobble produced entirely by a core that
 * never moves. Adding {@code coreOrbit} or {@code coreWobble} on top
 * double-counts it and smears the geometry — which is why both default to 0.
 *
 * <p><b>2. It's a spiral, not a wave, and definitely not a random field.</b>
 * An earlier version cross-correlated each frame's <i>column</i> brightness
 * against the previous frame, found a dominant horizontal shift of zero, and
 * concluded nothing travels — so per-key phases got rolled from an RNG.
 *
 * <p>Here's the trap: <b>a rotating spiral has zero net horizontal
 * displacement by construction.</b> Testing horizontally literally cannot see
 * it. The measurement wasn't wrong, it was answering a different question.
 * Test radially and angularly instead and it's obvious — predicting each key's
 * measured phase from {@code k_r*r + theta} alone leaves 22° of residual
 * scatter, versus the 104° a random field would give (circular concentration
 * 0.934). The phases are geometric. Nothing about them is random.
 *
 * <p><b>3. There's no emitter, and the crest is a pure cosine.</b> 99.3% of
 * the temporal power sits in the fundamental, so cranking
 * {@code armSharpness = 3.5} was manufacturing harmonics that do not exist in
 * the source. And no bright point rides the rim — adding one measurably
 * <i>lowers</i> the fit. Both default off.
 *
 * <h2>Archimedean or logarithmic: why the default is Archimedean</h2>
 * Once it was clear the field was a spiral, the next question was which kind,
 * and it was a genuine toss-up on paper. The two candidates:
 *
 * <ul>
 *   <li><b>Logarithmic</b> (equiangular): crests spaced further apart the
 *       further out they are, phase linear in {@code log r}. The shape of
 *       galaxies and nautilus shells, and the golden spiral is one of them.
 *       It is the spiral most people picture, and the one that would
 *       have been chosen by eye.</li>
 *   <li><b>Archimedean</b>: crests evenly spaced, phase linear in {@code r}.
 *       Less romantic, and the shape of a coiled rope or a record groove.</li>
 * </ul>
 *
 * <p>The measurement decided it. Crests satisfy {@code theta = -k_r*r + const}:
 * each key's fitted phase lies on a straight line against r, with one arm at a
 * radial wavelength of 4.92 key widths. A straight line against r is
 * necessarily a curve against {@code log r}, so the reference is Archimedean,
 * and only that setting reproduces it.
 *
 * <p>There is a keyboard-specific reason as well. A logarithmic spiral packs
 * its tightest winding into the few keys nearest the core — exactly where this
 * field is black — and spreads out geometrically beyond, so across a board
 * only about six keys tall most of its character lands where there are no
 * LEDs to show it. {@link Curve#LOGARITHMIC} is kept as an option, with
 * {@link #GOLDEN_TURNS_PER_EFOLD} for a true golden spiral, but it is a
 * stylistic choice, not a correction.
 *
 * <p>Motion, for the curious: hold r fixed and advance t and it rotates; hold
 * theta fixed and advance t and the crest moves <b>outward</b>. The wave
 * propagates out while the arm winds round, which is precisely why it reads as
 * "a wave... until you look at the core and realise it's spinning".
 */
public final class CoreEmitterPattern implements Pattern {

    /** 71 frames at 20ms. Identical across all four reference pillars. */
    public static final double REFERENCE_PERIOD_MILLIS = 71 * 20.0;

    /** Radial wavelength in key widths: fitted k_r = -0.1595 rad/px at 8px pitch. */
    public static final double REFERENCE_WAVELENGTH_KEYS = 4.924;

    /** Core radius in key widths. Inside this the board is hard black. */
    public static final double REFERENCE_CORE_RADIUS_KEYS = 1.290;

    /** Radius in key widths where the field reaches full strength. */
    public static final double REFERENCE_FALLOFF_END_KEYS = 3.116;

    /**
     * Turns per e-fold of radius for a true golden spiral.
     *
     * <p>A golden spiral grows by phi every quarter turn, so one full turn
     * spans {@code 4 ln phi} e-folds; the winding rate is the reciprocal.
     * Purely stylistic — the reference is Archimedean — but a golden spiral
     * is the obvious thing to want to try, and this makes trying it one
     * setting rather than a derivation.
     */
    public static final double GOLDEN_TURNS_PER_EFOLD = 1.0 / (4.0 * Math.log(1.6180339887));

    public enum Emission { SPIRAL, RING, SPOKES, PULSE, NONE }

    /**
     * How phase accumulates with radius.
     *
     * <p>{@code ARCHIMEDEAN} — phase linear in r, crests evenly spaced. What
     * the reference measures as, and the only setting that reproduces it.
     *
     * <p>{@code LOGARITHMIC} — phase linear in log r, spacing grows
     * geometrically. Available as a stylistic choice, with the caveat that at
     * keyboard resolution most of its winding crams into the handful of keys
     * nearest the core, so it's subtler than it sounds.
     */
    public enum Curve { ARCHIMEDEAN, LOGARITHMIC }

    /**
     * Mutable settings bag. Public fields, no builder, no validation.
     *
     * <p>Before anyone files a code-style complaint: this is tuned live from
     * the pattern studio in {@code tuning/}, which rebuilds it every frame
     * while someone drags sliders. A builder would be twenty methods of
     * ceremony around what is genuinely just a struct. {@link #copy()} is what
     * keeps it safe at the boundary.
     */
    public static final class Settings {
        public ColorRamp ramp;
        public double periodMillis = REFERENCE_PERIOD_MILLIS;

        // --- emission ------------------------------------------------------
        public Emission emission = Emission.SPIRAL;
        /** Arms. Measured: 1. Yes, one. */
        public double arms = 1.0;
        /** Radial wavelength in key widths. Measured: 4.924. */
        public double radialWavelengthKeys = REFERENCE_WAVELENGTH_KEYS;
        /** Scales the wavelength; >1 loosens the winding. */
        public double waveSpeed = 1.0;
        /** True = crests travel outward, as measured. */
        public boolean waveOutward = true;
        public Curve curve = Curve.ARCHIMEDEAN;
        /**
         * LOGARITHMIC only: turns per e-fold of radius.
         *
         * <p>Separate from {@link #radialWavelengthKeys} because a logarithmic
         * spiral <i>has</i> no fixed radial wavelength — its spacing grows
         * geometrically, so the concept doesn't apply. Defaults to golden.
         */
        public double logTurnsPerEfold = GOLDEN_TURNS_PER_EFOLD;
        /**
         * 1 = pure cosine, as measured. Higher narrows the crest and adds
         * harmonics the reference does not contain. Leave it alone unless you
         * are deliberately going off-reference.
         */
        public double armSharpness = 1.0;
        /** Amplitude loss per key width travelled. Measured: 0 beyond the ramp. */
        public double waveFalloff = 0.0;
        /** PULSE only: fraction of each period actually emitting. */
        public double pulseDuty = 0.35;

        // --- levels --------------------------------------------------------
        // These two are the entire difference between the pillars. Same
        // geometry, different contrast. That's it. That's the variation.
        /** Mean level of the lit field. Measured: 0.680 (Nebula), 0.474 (Vortex). */
        public double dcLevel = 0.680;
        /** Oscillation amplitude. Measured: 0.311 (Nebula), 0.512 (Vortex). */
        public double amplitude = 0.311;

        // --- core ----------------------------------------------------------
        /** Hard-black core radius, in KEY WIDTHS — not normalised units. Read that again. */
        public double coreRadius = REFERENCE_CORE_RADIUS_KEYS;
        /** Radius in key widths where the field reaches full strength. */
        public double falloffEndKeys = REFERENCE_FALLOFF_END_KEYS;
        /** Shape of the core->full ramp. Measured: 1.85. */
        public double falloffPower = 1.85;

        /**
         * Core orbit radius as a fraction of {@link #coreRadius}.
         *
         * <p><b>Measured value: 0.</b> The core is static; its apparent wobble
         * falls out of the rotating single arm (see the class doc). Setting
         * this non-zero is a deliberate stylisation, and it will double-count
         * a wobble that is already there.
         */
        public double coreOrbit = 0.0;
        /** Core orbits per period. Only meaningful when coreOrbit > 0. */
        public double coreSpeed = 1.0;
        public boolean coreClockwise = true;
        /** Irregular secondary core motion, 0..1. Measured: 0. */
        public double coreWobble = 0.0;

        // --- emitter (not present in the reference at all) -------------------
        // Kept because it was in earlier versions and looks fun on its own
        // terms. Adding it measurably WORSENS the fit, so: off.
        public boolean emitterEnabled = false;
        public double emitterGlow = 0.0;
        /** Field rotations per period. Measured: 1. */
        public double emitterSpeed = 1.0;
        /** Measured direction: counter-clockwise on screen. */
        public boolean emitterClockwise = false;

        // --- placement -----------------------------------------------------
        /**
         * Centre in normalised board coordinates. Leave NaN (the default) to
         * auto-anchor.
         *
         * <p>NaN-as-sentinel is deliberate rather than lazy: 0.0 is a perfectly
         * valid centre (top-left), so it can't double as "unset", and a boxed
         * Double would add null-checks to a hot loop.
         *
         * <p>Auto-anchor targets the 'T' key. The reference pillar sits on 'T'
         * to within 0.13 key widths, and anchoring to a real key reproduces it
         * on <i>any</i> layout — where a hardcoded fraction drifts noticeably
         * between a full-size board and a TKL.
         */
        public double centerX = Double.NaN;
        public double centerY = Double.NaN;

        /** Extra phase, radians. Measured: -1.61. Don't ask, it's just what it fitted to. */
        public double phaseOffset = -1.61;

        /**
         * Field-by-field copy. Called on every {@code trigger}, which is what
         * lets a caller keep tweaking its own Settings afterwards without
         * mutating a pattern that's mid-render on another thread.
         */
        public Settings copy() {
            Settings s = new Settings();
            s.ramp = ramp; s.periodMillis = periodMillis;
            s.emission = emission; s.arms = arms;
            s.radialWavelengthKeys = radialWavelengthKeys; s.waveSpeed = waveSpeed;
            s.waveOutward = waveOutward; s.curve = curve; s.armSharpness = armSharpness;
            s.logTurnsPerEfold = logTurnsPerEfold;
            s.waveFalloff = waveFalloff; s.pulseDuty = pulseDuty;
            s.dcLevel = dcLevel; s.amplitude = amplitude;
            s.coreRadius = coreRadius; s.falloffEndKeys = falloffEndKeys;
            s.falloffPower = falloffPower;
            s.coreOrbit = coreOrbit; s.coreSpeed = coreSpeed;
            s.coreClockwise = coreClockwise; s.coreWobble = coreWobble;
            s.emitterEnabled = emitterEnabled; s.emitterGlow = emitterGlow;
            s.emitterSpeed = emitterSpeed; s.emitterClockwise = emitterClockwise;
            s.centerX = centerX; s.centerY = centerY; s.phaseOffset = phaseOffset;
            return s;
        }

        /**
         * How many turns one arm makes between the core rim and a given radius.
         * Read-only introspection for the tuning studio's readouts.
         *
         * <p>Dividing by arm count isn't arbitrary: a crest satisfies
         * {@code arms*theta = -radialPhase}, so the angular sweep available to
         * each arm is split between them. More arms, less winding each.
         */
        public double turnsWithin(double radiusKeys) {
            double rMin = Math.max(1e-3, coreRadius);
            if (radiusKeys <= rMin) return 0;
            double n = Math.max(1e-6, arms);
            if (curve == Curve.LOGARITHMIC) {
                return logTurnsPerEfold * Math.log(radiusKeys / rMin) / n;
            }
            double lambda = Math.max(1e-6, radialWavelengthKeys * waveSpeed);
            return (radiusKeys - rMin) / (lambda * n);
        }

        /** What a golden spiral would do over the same span, for comparison in the studio. */
        public double goldenTurns(double radiusKeys) {
            double rMin = Math.max(1e-3, coreRadius);
            if (radiusKeys <= rMin) return 0;
            return GOLDEN_TURNS_PER_EFOLD * Math.log(radiusKeys / rMin);
        }

        /**
         * Switch to a true golden spiral.
         *
         * <p>Under the fitted model, winding is set <i>directly</i> rather than
         * emerging from an interaction of emitter and wave speeds — so this
         * assigns the winding rate instead of solving for a velocity, which is
         * what the pre-fit version had to do.
         */
        public void makeGolden() {
            curve = Curve.LOGARITHMIC;
            logTurnsPerEfold = GOLDEN_TURNS_PER_EFOLD;
        }

        /** The measured Nebula / Solar / Stardust contrast: softer, higher mean. */
        public static Settings referenceSoft() {
            Settings s = new Settings();
            s.dcLevel = 0.680; s.amplitude = 0.311;
            return s;
        }

        /** The measured Vortex contrast: darker mean, nearly double the swing. */
        public static Settings referenceHard() {
            Settings s = new Settings();
            s.dcLevel = 0.474; s.amplitude = 0.512;
            s.coreRadius = 1.315; s.falloffEndKeys = 3.112; s.falloffPower = 1.95;
            return s;
        }
    }

    private final Settings cfg;
    private volatile double intensity = 1.0;

    /** Copies the settings, so the caller can keep mutating theirs. */
    public CoreEmitterPattern(Settings settings) {
        this.cfg = settings.copy();
    }

    public void setIntensity(double intensity) {
        this.intensity = Math.max(0.0, Math.min(1.0, intensity));
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
        ColorRamp ramp = cfg.ramp;
        if (ramp == null || grid.isEmpty()) return out;

        double t = elapsedMillis / Math.max(1e-6, cfg.periodMillis); // cycles, not seconds

        // THE ASPECT RATIO CORRECTION. Do not remove it.
        //
        // Normalised coordinates run 0..1 on both axes, but a keyboard is
        // roughly 409x122mm. So one unit of normalised y is about 3.3x
        // "longer" in real space than one unit of x. Compute a radius without
        // correcting for that and your perfect circle comes out as a wide flat
        // ellipse, the spiral geometry stops matching the fit, and every
        // measurement in the class doc quietly stops applying.
        //
        // kw = one key width in normalised x; ar converts normalised y into
        // those same units. Together they give a true circular radius.
        double kw = Math.max(1e-6, grid.keyWidthNormalised());
        double ar = grid.aspectRatio();

        double[] centre = resolveCentre(grid);
        double cx = centre[0];
        double cy = centre[1];

        // Static by default (measured coreOrbit = 0). This block only runs if
        // someone deliberately opted into stylised core motion.
        if (cfg.coreOrbit > 0) {
            double dir = cfg.coreClockwise ? 1 : -1;
            double a = 2 * Math.PI * cfg.coreSpeed * dir * t;
            double orbitKeys = cfg.coreRadius * cfg.coreOrbit;
            if (cfg.coreWobble > 0) {
                // Two sines at an irrational ratio again — same anti-repetition
                // trick as the spiral's wobble. Random per frame would jitter.
                orbitKeys *= 1 + cfg.coreWobble * 0.5
                        * (Math.sin(t * 7.3) + 0.5 * Math.sin(t * 11.7 + 1.3));
            }
            cx += orbitKeys * kw * Math.cos(a);
            cy += orbitKeys * kw / Math.max(1e-6, ar) * Math.sin(a);
        }

        double lambda = Math.max(1e-6, cfg.radialWavelengthKeys * cfg.waveSpeed);
        // Negative k_r = crests travel OUTWARD, matching the measured sign.
        double kR = (cfg.waveOutward ? -1 : 1) * (2 * Math.PI / lambda);
        double rotDir = cfg.emitterClockwise ? -1 : 1;
        double span = Math.max(1e-6, cfg.falloffEndKeys - cfg.coreRadius);

        for (KeyGrid.LedPosition key : grid.allKeys()) {
            // Both deltas converted into key widths, so r is a true circular
            // radius rather than a stretched one. See the kw/ar note above.
            double dx = (key.x() - cx) / kw;
            double dy = (key.y() - cy) * ar / kw;
            double r = Math.hypot(dx, dy);

            // W(r): 0 inside the core, ramping to 1 at falloffEnd.
            double w = Math.min(1.0, Math.max(0.0, (r - cfg.coreRadius) / span));
            if (w <= 0) {
                // Inside the core. Emitted as alpha 0 rather than black,
                // deliberately — alpha 0 lets whatever the Compositor has
                // underneath show through untouched, while black would paint
                // over it. (PortalTransitionEffect actually wants the opposite
                // and flattens this against black itself before compositing;
                // see its render() for why.)
                out.put(key.ref(), new LayerPixel(ramp.sample(0), 0.0));
                continue;
            }
            if (cfg.falloffPower != 1.0) w = Math.pow(w, cfg.falloffPower);

            // Radial phase, in radians.
            //
            // The LOGARITHMIC branch deliberately does NOT route through kR.
            // If it did, its radial term would carry a factor of lambda that
            // cancels kR's 1/lambda exactly — leaving the curve completely
            // deaf to waveSpeed, silently, with no error. It gets its own
            // winding control instead, which is honest anyway since a
            // logarithmic spiral has no fixed radial wavelength to scale.
            double radialPhase;
            if (cfg.curve == Curve.LOGARITHMIC) {
                double rMin = Math.max(1e-3, cfg.coreRadius);
                radialPhase = (cfg.waveOutward ? -1 : 1) * 2 * Math.PI * cfg.arms
                        * cfg.logTurnsPerEfold * Math.log(Math.max(r, rMin) / rMin);
            } else {
                radialPhase = kR * r;
            }

            double theta = Math.atan2(dy, dx);
            double spin = 2 * Math.PI * cfg.emitterSpeed * rotDir * t;

            // The emission modes are just which phase terms you keep:
            //   SPIRAL = spin + radial + angular  (both -> it winds)
            //   RING   = spin + radial           (no angular -> concentric)
            //   SPOKES = spin + angular          (no radial -> rigid rotation)
            // Three visually distinct effects, one formula, terms omitted.
            double phase;
            switch (cfg.emission) {
                case SPIRAL -> phase = spin + radialPhase + cfg.arms * theta + cfg.phaseOffset;
                case RING   -> phase = spin + radialPhase + cfg.phaseOffset;
                case SPOKES -> phase = spin + cfg.arms * theta + cfg.phaseOffset;
                // NaN is fine: neither of these branches reads `phase`.
                case PULSE  -> phase = Double.NaN;
                default     -> phase = Double.NaN;
            }

            double level;
            if (cfg.emission == Emission.NONE) {
                level = cfg.dcLevel; // static glow, no oscillation
            } else if (cfg.emission == Emission.PULSE) {
                // Duty-cycled burst rather than a continuous wave: emit for
                // pulseDuty of the cycle, dark for the rest. The radial term
                // still offsets the cycle position, so the pulse ripples
                // outward instead of the whole board blinking at once.
                double cyclePos = (t + radialPhase / (2 * Math.PI)) % 1.0;
                if (cyclePos < 0) cyclePos += 1.0; // Java % keeps the sign of the dividend
                double burst = cyclePos < cfg.pulseDuty
                        ? 0.5 + 0.5 * Math.cos(2 * Math.PI * cyclePos / Math.max(1e-6, cfg.pulseDuty))
                        : 0.0;
                level = cfg.dcLevel - cfg.amplitude + 2 * cfg.amplitude * burst;
            } else {
                // Pure cosine at armSharpness 1 — which is what was measured,
                // and the whole point of finding 99.3% of the power in the
                // fundamental.
                double waveform = 0.5 + 0.5 * Math.cos(phase);
                if (cfg.armSharpness != 1.0) waveform = Math.pow(waveform, cfg.armSharpness);
                double amp = cfg.amplitude;
                if (cfg.waveFalloff > 0) {
                    amp *= Math.max(0, 1.0 - cfg.waveFalloff * (r - cfg.coreRadius) / span);
                }
                // (2*waveform - 1) remaps 0..1 back to -1..1 so amplitude
                // swings symmetrically either side of dcLevel.
                level = cfg.dcLevel + amp * (2 * waveform - 1);
            }

            double v = Math.max(0, Math.min(1, w * level));

            // Off-reference stylisation, default disabled. max() rather than
            // add, so the glow can only brighten and never blows out the field.
            if (cfg.emitterEnabled && cfg.emitterGlow > 0) {
                double ex = cx + cfg.coreRadius * kw * Math.cos(spin);
                double ey = cy + cfg.coreRadius * kw / Math.max(1e-6, ar) * Math.sin(spin);
                double de = Math.hypot((key.x() - ex) / kw, (key.y() - ey) * ar / kw);
                double glow = Math.max(0, 1.0 - de / Math.max(1e-6, cfg.coreRadius));
                v = Math.max(v, glow * glow * cfg.emitterGlow); // squared = softer falloff
            }

            v *= intensity;

            // The neat bit: v is used TWICE, as both the ramp position and the
            // alpha. Compositing (colour, v) source-over black gives
            // colour * v — and that is exactly how the reference palettes
            // behave. Every pillar's RGB is its peak colour scaled linearly
            // toward black with the hue held constant. So one variable
            // reproduces both the colour walk and the brightness envelope,
            // because in the source material they were never separate things.
            out.put(key.ref(), new LayerPixel(ramp.sample(v), v));
        }
        return out;
    }

    /**
     * Centre resolution: explicit setting, else the 'T' key, else the surface
     * centroid.
     *
     * <p>'T' because the reference pillar sits on it to within 0.13 key
     * widths, and because anchoring to a physical key means the effect lands
     * in the same visual spot on a full-size board, a TKL, and a 60% — where a
     * fixed fraction would drift. The centroid fallback covers devices with no
     * 'T' at all, like a mousepad.
     */
    private double[] resolveCentre(KeyGrid grid) {
        if (!Double.isNaN(cfg.centerX) && !Double.isNaN(cfg.centerY)) {
            return new double[] { cfg.centerX, cfg.centerY };
        }
        KeyGrid.LedRef anchor = grid.namedKey("T");
        if (anchor != null) {
            KeyGrid.LedPosition p = grid.position(anchor);
            if (p != null) return new double[] { p.x(), p.y() };
        }
        return new double[] { grid.centerX(), grid.centerY() };
    }
}
