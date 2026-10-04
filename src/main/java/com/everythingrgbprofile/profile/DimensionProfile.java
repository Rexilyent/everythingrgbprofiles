package com.everythingrgbprofile.profile;

import com.everythingrgbprofile.RGBProfileFiles;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.pattern.patterns.SpiralInPattern;

import java.util.Map;

/**
 * One entry in {@code dimension_profiles.json}, which is what drives the
 * portal effect's pillar field.
 *
 * <p>Every field except {@code color} is optional. Anything left out falls
 * back to the geometry fitted from the four Terraria Celestial Pillar
 * reference animations, so a completely valid entry is
 * {@code {"color": "#8B008B"}} and literally nothing else. The other thirty
 * fields are there for when you want to go deep, not because anyone is making
 * you.
 *
 * <h2>Two things that WILL bite you when editing this JSON</h2>
 *
 * <p><b>1. {@code gradient} is a HUE ramp, not a brightness ramp.</b> This is
 * the single most common mistake and the symptom is that the pattern looks
 * broken.
 *
 * <p>{@link com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern}
 * emits colour with <i>alpha equal to the field level</i>, because every
 * reference pillar's palette is one peak hue scaled linearly toward black.
 * The darkness is already coming from the alpha. So every stop wants to be a
 * <b>saturated</b> colour. Drop a near-black first stop in there and it gets
 * darkened twice, which washes the arm out to nothing and looks like a bug.
 *
 * <p>Stops that shift <i>hue</i> as they brighten are exactly right. That is
 * what the Solar pillar's dark-red to vivid-red to amber ramp is doing, and it
 * is the whole reason a two-point interpolation cannot reproduce it.
 *
 * <p><b>2. Leave {@code spiralCenterX} and {@code spiralCenterY} alone.</b>
 * Omit them and the pattern auto-anchors on the {@code T} key, which is where
 * the reference pillar sits to within 0.13 key widths, and which is the same
 * physical spot on a full-size board, a TKL and a 60%. A hardcoded 0.5/0.5 is
 * none of those things and will drift around between layouts.
 *
 * <p>Same reasoning behind the radii ({@code coreRadiusKeys},
 * {@code falloffEndKeys}, {@code radialWavelengthKeys}). They are in <b>key
 * widths</b> rather than normalised units, so they mean the same physical size
 * on every board.
 */
public final class DimensionProfile {
    public String color;
    public String accentColor;

    // --- legacy spiral-in fields ---------------------------------------
    // These belong to SpiralInPattern, which the portal stopped using a while
    // back (see that class). They are kept so that old hand-written profiles
    // still load without throwing. All but one get read and then do nothing,
    // because the current pillar field has no use for them. The exception is
    // spiralArmCount, which toSettings() still applies as the pillar field's
    // arm count.
    public Double spiralRotations;
    public Integer spiralArmCount;
    public Integer trailLength;
    public Double radiusWobbleAmplitude;
    public String arrivalFlashStyle; // "sharp" | "bloom"

    /**
     * Multi-stop colour ramp, e.g.
     * {@code ["#3D0E00","#FD0500","#FB3F00","#FE8200","#FEA200"]}.
     *
     * <p>Optional. Without one, the dimension gets a ramp derived from its
     * base and accent instead. It is a LIST rather than a pair because that is
     * what the measurements demanded: a two-point interpolation genuinely
     * cannot reproduce the references' saturated midtones. See
     * {@link com.everythingrgbprofile.color.ColorRamp} for the actual numbers.
     * Extracted by sampling every frame of the originals.
     */
    public java.util.List<String> gradient;

    /** Wave period in ms. Defaults to the measured 71 frames at 20ms. */
    public Double pillarPeriodMillis;

    /** Spiral centre, normalised 0..1. Best left unset; the class doc explains why. */
    public Double spiralCenterX;
    public Double spiralCenterY;

    /** Dark core radius, 0..1 of the board half-extent. (Superseded by {@code coreRadiusKeys}.) */
    public Double spiralCoreRadius;

    /** 1 = smooth ripple (as measured), higher = a distinct narrow arm. */
    public Double spiralArmSharpness;

    /** Core's own orbit radius as a fraction of the core radius. Measured value: 0. */
    public Double spiralCoreOrbit;

    /** true for a logarithmic (golden-style) spiral; default Archimedean, as measured. */
    public Boolean spiralLogarithmic;

    /** Brightness of an emitter riding the core rim, 0..1. Not in the reference; default off. */
    public Double spiralEmitterGlow;

    /** How much a wave dims as it travels outward, 0..1. Measured: 0. */
    public Double spiralWaveFalloff;

    public RGBColor resolvedColor(RGBColor fallback) {
        return color != null ? RGBColor.fromHexOrDefault(color, fallback) : fallback;
    }

    /** Explicit accent, or the base lightened 60% — same default as PatternContext. */
    public RGBColor resolvedAccentColor(RGBColor baseColor) {
        return accentColor != null ? RGBColor.fromHexOrDefault(accentColor, null) : baseColor.lightened(0.6);
    }

    /** Hand-authored gradient if there is one, otherwise a derived five-stop ramp. */
    public com.everythingrgbprofile.color.ColorRamp resolvedRamp(RGBColor base, RGBColor accent) {
        return com.everythingrgbprofile.color.ColorRamp.fromHex(gradient,
                com.everythingrgbprofile.color.ColorRamp.derived(base, accent));
    }

    /** Emission mode name: spiral | ring | spokes | pulse | none. */
    public String emissionMode;

    /** Curve name: archimedean | logarithmic. Supersedes {@code spiralLogarithmic}. */
    public String spiralCurve;

    public Double waveSpeed;
    public Double emitterSpeed;
    public Boolean emitterClockwise;
    public Boolean coreClockwise;
    public Double coreSpeed;
    public Double coreWobble;
    public Boolean emitterEnabled;

    // --- measured pillar geometry (see CoreEmitterPattern) ----------------
    // All radii in KEY WIDTHS, so they mean the same thing on a full-size
    // board and a TKL. Leave unset to take the reference values, which were
    // fitted frame by frame to the Terraria pillar animations.

    /** Radial wavelength in key widths. Reference: 4.924. */
    public Double radialWavelengthKeys;
    /** Hard-black core radius in key widths. Reference: 1.290. */
    public Double coreRadiusKeys;
    /** Radius in key widths at which the field reaches full strength. Reference: 3.116. */
    public Double falloffEndKeys;
    /** Shape of the core-to-full ramp. Reference: 1.75 (soft) to 1.95 (hard). */
    public Double falloffPower;
    /** Mean level of the lit field. Reference: 0.680 soft, 0.474 hard. */
    public Double pillarDcLevel;
    /** Oscillation amplitude. Reference: 0.311 soft, 0.512 hard. */
    public Double pillarAmplitude;
    /** Extra phase in radians. Reference: -1.61. */
    public Double pillarPhaseOffset;

    /**
     * Folds this profile's overrides onto the reference defaults, one field at
     * a time.
     *
     * <p>Yes, it is two dozen consecutive {@code if (x != null)} lines. Yes, that
     * looks like something a generic merge should handle. The repetition is
     * doing real work though: every field is a boxed type specifically so that
     * null can mean "nobody specified this, keep the measured default".
     * Reflection would be shorter and would throw away the compile-time
     * checking that stops a renamed JSON field from quietly becoming a no-op
     * nobody notices for three months.
     */
    public com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern.Settings toSettings() {
        var s = new com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern.Settings();
        if (emissionMode != null) {
            try {
                s.emission = com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern.Emission
                        .valueOf(emissionMode.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // A typo'd mode name falls back to the default (SPIRAL)
                // instead of throwing during profile load and taking the
                // entire mod's startup down with it. That's the "a malformed
                // user edit must never crash the mod" rule, applied to enums.
            }
        }
        if (spiralArmCount != null) s.arms = spiralArmCount;
        // spiralCurve wins. spiralLogarithmic is the older spelling of the
        // same idea, kept around so existing profiles don't break overnight.
        // Anything new should be using spiralCurve.
        if (spiralCurve != null) {
            try {
                s.curve = com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern.Curve
                        .valueOf(spiralCurve.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Same deal as above: keep the default, don't take startup out
                // over a typo.
            }
        } else if (spiralLogarithmic != null && spiralLogarithmic) {
            s.curve = com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern.Curve.LOGARITHMIC;
        }
        if (waveSpeed != null) s.waveSpeed = waveSpeed;
        if (spiralArmSharpness != null) s.armSharpness = spiralArmSharpness;
        if (spiralWaveFalloff != null) s.waveFalloff = spiralWaveFalloff;
        if (emitterEnabled != null) s.emitterEnabled = emitterEnabled;
        if (spiralEmitterGlow != null) s.emitterGlow = spiralEmitterGlow;
        if (emitterSpeed != null) s.emitterSpeed = emitterSpeed;
        if (emitterClockwise != null) s.emitterClockwise = emitterClockwise;
        if (spiralCoreRadius != null) s.coreRadius = spiralCoreRadius;
        if (spiralCoreOrbit != null) s.coreOrbit = spiralCoreOrbit;
        if (coreSpeed != null) s.coreSpeed = coreSpeed;
        if (coreClockwise != null) s.coreClockwise = coreClockwise;
        if (coreWobble != null) s.coreWobble = coreWobble;
        if (spiralCenterX != null) s.centerX = spiralCenterX;
        if (spiralCenterY != null) s.centerY = spiralCenterY;
        if (pillarPeriodMillis != null) s.periodMillis = pillarPeriodMillis;

        // This block comes LAST on purpose. coreRadiusKeys has to be able to
        // override the older normalised spiralCoreRadius that got applied
        // further up, because the newer and more precise unit should win.
        // Moving this block earlier is how you would introduce a genuinely
        // baffling bug that only shows up on profiles setting both.
        if (radialWavelengthKeys != null) s.radialWavelengthKeys = radialWavelengthKeys;
        if (coreRadiusKeys != null) s.coreRadius = coreRadiusKeys;
        if (falloffEndKeys != null) s.falloffEndKeys = falloffEndKeys;
        if (falloffPower != null) s.falloffPower = falloffPower;
        if (pillarDcLevel != null) s.dcLevel = pillarDcLevel;
        if (pillarAmplitude != null) s.amplitude = pillarAmplitude;
        if (pillarPhaseOffset != null) s.phaseOffset = pillarPhaseOffset;
        return s;
    }

    /** Legacy, for {@link SpiralInPattern}. BLOOM unless somebody explicitly said "sharp". */
    public SpiralInPattern.ArrivalStyle resolvedArrivalStyle() {
        return "sharp".equalsIgnoreCase(arrivalFlashStyle) ? SpiralInPattern.ArrivalStyle.SHARP : SpiralInPattern.ArrivalStyle.BLOOM;
    }

    public static Map<String, DimensionProfile> loadAll() {
        return JsonProfileLoader.load("/everythingrgbprofile_defaults/dimension_profiles.json",
                RGBProfileFiles.dimensionProfilesFile(), DimensionProfile.class);
    }
}
