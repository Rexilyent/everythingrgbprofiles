package com.everythingrgbprofile.profile;

import com.everythingrgbprofile.RGBProfileFiles;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.pattern.patterns.SpiralInPattern;

import java.util.Map;

/**
 * One entry in {@code dimension_profiles.json} — this is what drives the
 * portal effect's pillar field.
 *
 * <p>Every field except {@code color} is optional. Anything you leave out
 * falls back to the geometry fitted from the four Terraria Celestial Pillar
 * reference animations, so a perfectly good entry is:
 * {@code {"color": "#8B008B"}} and nothing else. The other thirty fields are
 * there for when you want to go deep, not because you have to.
 *
 * <h2>Two things that WILL bite you when editing the JSON</h2>
 *
 * <p><b>1. {@code gradient} is a HUE ramp, not a brightness ramp.</b> This is
 * the single most common mistake and it looks like the pattern is broken.
 *
 * <p>{@link com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern}
 * emits colour with <i>alpha equal to the field level</i>, because every
 * reference pillar's palette is one peak hue scaled linearly toward black.
 * Darkness already comes from the alpha. So every stop should be a
 * <b>saturated</b> colour — put a near-black first stop in and it darkens
 * twice, washing the arm out to nothing.
 *
 * <p>Stops that shift <i>hue</i> as they brighten are exactly right: that's
 * what the Solar pillar's dark-red → vivid-red → amber ramp does, and it's the
 * whole reason a two-point interpolation can't reproduce it.
 *
 * <p><b>2. Leave {@code spiralCenterX}/{@code spiralCenterY} unset.</b>
 * Omitted, the pattern auto-anchors on the {@code T} key — where the reference
 * pillar sits to within 0.13 key widths, and which is the same physical spot
 * on a full-size board, a TKL, and a 60%. A hardcoded 0.5/0.5 is none of those
 * things and will drift between layouts.
 *
 * <p>Same reasoning for the radii ({@code coreRadiusKeys},
 * {@code falloffEndKeys}, {@code radialWavelengthKeys}): they're in <b>key
 * widths</b>, not normalised units, so they mean the same physical size
 * everywhere.
 */
public final class DimensionProfile {
    public String color;
    public String accentColor;

    // --- legacy spiral-in fields ---------------------------------------
    // These belong to SpiralInPattern, which the portal no longer uses (see
    // that class). Kept so old hand-written profiles still load without
    // errors; they're read but have no effect on the current pillar field.
    public Double spiralRotations;
    public Integer spiralArmCount;
    public Integer trailLength;
    public Double radiusWobbleAmplitude;
    public String arrivalFlashStyle; // "sharp" | "bloom"

    /**
     * Multi-stop colour ramp, e.g.
     * {@code ["#3D0E00","#FD0500","#FB3F00","#FE8200","#FEA200"]}.
     *
     * <p>Optional; without one the dimension gets a ramp derived from its base
     * and accent. It's a LIST and not a pair because that's what the
     * measurements demanded — a two-point interpolation cannot reproduce the
     * references' saturated midtones (see {@link com.everythingrgbprofile.color.ColorRamp}
     * for the numbers). Extracted by sampling every frame of the originals.
     */
    public java.util.List<String> gradient;

    /** Wave period in ms. Defaults to the measured 71 frames at 20ms. */
    public Double pillarPeriodMillis;

    /** Spiral centre, normalised 0..1. Best left unset — see the class doc for why. */
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
     * Folds this profile's overrides onto the reference defaults, field by
     * field.
     *
     * <p>Yes, it's thirty {@code if (x != null)} lines. That repetition is
     * doing real work: every field is a boxed type precisely so null can mean
     * "user didn't specify, keep the measured default". Reflection or a
     * generic merge would be shorter and would lose the compile-time checking
     * that keeps a renamed JSON field from silently becoming a no-op.
     */
    public com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern.Settings toSettings() {
        var s = new com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern.Settings();
        if (emissionMode != null) {
            try {
                s.emission = com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern.Emission
                        .valueOf(emissionMode.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // A typo'd mode name keeps the default (SPIRAL) instead of
                // throwing during profile load and taking the whole mod's
                // startup with it. The "a malformed user edit must never crash
                // the mod" rule, applied to enums.
            }
        }
        if (spiralArmCount != null) s.arms = spiralArmCount;
        // spiralCurve wins; spiralLogarithmic is the older spelling, kept so
        // existing profiles don't break. New profiles should use spiralCurve.
        if (spiralCurve != null) {
            try {
                s.curve = com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern.Curve
                        .valueOf(spiralCurve.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Same deal as above.
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

        // These come LAST on purpose: coreRadiusKeys must be able to override
        // the older normalised spiralCoreRadius applied above. Newer, more
        // precise units win. Reordering this block is how you'd introduce a
        // very confusing bug.
        if (radialWavelengthKeys != null) s.radialWavelengthKeys = radialWavelengthKeys;
        if (coreRadiusKeys != null) s.coreRadius = coreRadiusKeys;
        if (falloffEndKeys != null) s.falloffEndKeys = falloffEndKeys;
        if (falloffPower != null) s.falloffPower = falloffPower;
        if (pillarDcLevel != null) s.dcLevel = pillarDcLevel;
        if (pillarAmplitude != null) s.amplitude = pillarAmplitude;
        if (pillarPhaseOffset != null) s.phaseOffset = pillarPhaseOffset;
        return s;
    }

    /** Legacy, for {@link SpiralInPattern}. BLOOM unless explicitly "sharp". */
    public SpiralInPattern.ArrivalStyle resolvedArrivalStyle() {
        return "sharp".equalsIgnoreCase(arrivalFlashStyle) ? SpiralInPattern.ArrivalStyle.SHARP : SpiralInPattern.ArrivalStyle.BLOOM;
    }

    public static Map<String, DimensionProfile> loadAll() {
        return JsonProfileLoader.load("/everythingrgbprofile_defaults/dimension_profiles.json",
                RGBProfileFiles.dimensionProfilesFile(), DimensionProfile.class);
    }
}
