package com.everythingrgbprofile.pattern;

import com.everythingrgbprofile.pattern.patterns.BloodBatsPattern;
import com.everythingrgbprofile.pattern.patterns.CanopyDapplePattern;
import com.everythingrgbprofile.pattern.patterns.DesertDunesPattern;
import com.everythingrgbprofile.pattern.patterns.DriftParticlePattern;
import com.everythingrgbprofile.pattern.patterns.GlowMotePattern;
import com.everythingrgbprofile.pattern.patterns.PulsePattern;
import com.everythingrgbprofile.pattern.patterns.ShimmerPattern;
import com.everythingrgbprofile.pattern.patterns.ShimmerTwinklePattern;
import com.everythingrgbprofile.pattern.patterns.SunflowerFieldPattern;
import com.everythingrgbprofile.pattern.patterns.TwinkleParticlePattern;
import com.everythingrgbprofile.pattern.patterns.WindsweptRidgePattern;

/**
 * Turns the {@code pattern}/{@code preset} strings from
 * {@code biome_profiles.json} (and anywhere else a profile names a pattern in
 * text) into an actual {@link Pattern} object.
 *
 * <p>It's a switch statement. It lives here so it's <i>one</i> switch
 * statement, rather than the same string-matching logic quietly diverging
 * across the biome, boss, and dimension effects until "shimmer" means three
 * different things depending on who asked.
 *
 * <h2>The one interesting rule</h2>
 * Every fallback lands on an <b>animated</b> pattern, never a solid fill.
 * This is not an accident and it is not laziness — a static colour is
 * indistinguishable from "the mod crashed", so an unrecognised pattern name
 * gives you a gentle shimmer that says "I'm alive, your config has a typo"
 * instead of a dead-looking board that says nothing at all.
 */
public final class PatternFactory {

    public static Pattern fromNameAndPreset(String patternName, String preset) {
        if (patternName == null) return new ShimmerPattern();
        // Lowercased so "Shimmer", "SHIMMER" and "shimmer" all work. Nobody
        // should lose twenty minutes to a capital letter in a JSON file.
        return switch (patternName.toLowerCase()) {
            case "shimmer" -> new ShimmerPattern();
            // Pulse speeds are separate names rather than a speed parameter
            // because that's how the profile JSON spells them, and changing
            // the names would break every existing config.
            case "pulse-slow" -> new PulsePattern(PulsePattern.Speed.SLOW);
            case "pulse-medium" -> new PulsePattern(PulsePattern.Speed.MEDIUM);
            case "pulse-fast" -> new PulsePattern(PulsePattern.Speed.FAST);
            case "drift-particle" -> driftPreset(preset);
            case "twinkle-particle" -> twinklePreset(preset);
            // Wash + glints. For biomes too dark to carry twinkle-particle on
            // their own — see ShimmerTwinklePattern for what goes wrong there.
            case "shimmer-twinkle" -> shimmerTwinklePreset(preset);
            // Blood running down the board with bats cut out of it. No preset
            // argument: unlike the drift and twinkle engines this is not a
            // parameterised family, it is one specific scene. If a second
            // blood-and-something biome ever turns up, give it a preset then.
            case "blood-bats" -> BloodBatsPattern.vampireForest();
            // Sun through a canopy, stirred by wind. Unlike blood-bats this IS
            // a family: the three jungles are the same engine with the cover
            // thinned out or stretched vertically.
            case "canopy-dapple" -> canopyPreset(preset);
            // Motes drifting past glow pools. This started as one scene and
            // became a family the moment lush caves wanted the same thing
            // falling instead of rising, so it takes a preset now.
            //
            // "rising-motes" is kept as an alias for the name it shipped under.
            // An unrecognised name silently becomes a shimmer, so dropping it
            // would turn any config already carrying it into a plain shimmer
            // with nothing to say why.
            case "glow-motes", "rising-motes" -> glowMotePreset(preset);
            // Dune ridges with gusts of sand over them. One scene, no preset.
            case "desert-dunes" -> DesertDunesPattern.desert();
            // Heads over grass with the wind running through them. One scene,
            // no preset — the same call blood-bats makes. If a second crop biome
            // ever wants this, the head count and the wave are the knobs.
            case "sunflower-field" -> SunflowerFieldPattern.sunflowerPlains();
            // Eroded ground in terraces. A family, because the four windswept
            // biomes are the same hillside with different amounts of soil left
            // on it — see the preset docs for which way each one leans.
            case "windswept-ridge" -> windsweptPreset(preset);
            default -> new ShimmerPattern(); // animated fallback, never solid — see class doc
        };
    }

    /**
     * Mote presets: same engine, opposite directions and different palettes.
     * See the factory methods on the pattern for what each biome is doing.
     */
    private static Pattern glowMotePreset(String preset) {
        if (preset == null) return GlowMotePattern.enchantedTangle();
        return switch (preset.toLowerCase()) {
            case "lush-caves" -> GlowMotePattern.lushCaves();
            case "enchanted-tangle" -> GlowMotePattern.enchantedTangle();
            default -> GlowMotePattern.enchantedTangle();
        };
    }

    /**
     * Canopy presets. All three differ only in how much light gets through and
     * what shape it arrives in — see the factory methods for which knob does
     * what, particularly the vertical stretch that turns leaf dapple into the
     * slotted light of a bamboo stand.
     */
    private static Pattern canopyPreset(String preset) {
        if (preset == null) return CanopyDapplePattern.jungle();
        return switch (preset.toLowerCase()) {
            case "jungle" -> CanopyDapplePattern.jungle();
            case "sparse-jungle" -> CanopyDapplePattern.sparseJungle();
            case "bamboo-jungle" -> CanopyDapplePattern.bambooJungle();
            case "dark-forest" -> CanopyDapplePattern.darkForest();
            default -> CanopyDapplePattern.jungle();
        };
    }

    /**
     * The drift presets: same particle engine, eight different sets of
     * physics. This is that "parameterised engine, not per-entry subclasses"
     * idea actually paying rent — falling petals and rising embers are the
     * same code with the gravity sign flipped.
     */
    private static Pattern driftPreset(String preset) {
        if (preset == null) return DriftParticlePattern.petalDrift();
        return switch (preset.toLowerCase()) {
            case "petal-drift" -> DriftParticlePattern.petalDrift();
            case "snow-drift" -> DriftParticlePattern.snowDrift();
            case "sand-drift" -> DriftParticlePattern.sandDrift();
            case "bubble-rise" -> DriftParticlePattern.bubbleRise();
            case "ember-rise" -> DriftParticlePattern.emberRise();
            case "spore-drift" -> DriftParticlePattern.sporeDrift();
            case "drip-fall" -> DriftParticlePattern.dripFall();
            case "rain-drift" -> DriftParticlePattern.rainDrift();
            default -> DriftParticlePattern.petalDrift();
        };
    }

    /**
     * Windswept presets. The two knobs that actually separate them are how many
     * terraces the ground is cut into and how much of the board is bare rock;
     * gravelly hills is the one where those go far enough that the accent
     * becomes the grass rather than the stone.
     */
    private static Pattern windsweptPreset(String preset) {
        if (preset == null) return WindsweptRidgePattern.windsweptHills();
        return switch (preset.toLowerCase()) {
            case "windswept-hills" -> WindsweptRidgePattern.windsweptHills();
            case "windswept-gravelly-hills" -> WindsweptRidgePattern.windsweptGravellyHills();
            case "windswept-forest" -> WindsweptRidgePattern.windsweptForest();
            case "windswept-savanna" -> WindsweptRidgePattern.windsweptSavanna();
            default -> WindsweptRidgePattern.windsweptHills();
        };
    }

    private static Pattern shimmerTwinklePreset(String preset) {
        if (preset == null) return ShimmerTwinklePattern.gentle();
        return switch (preset.toLowerCase()) {
            case "sculk", "sculk-veins" -> ShimmerTwinklePattern.sculkVeins();
            default -> ShimmerTwinklePattern.gentle();
        };
    }

    private static Pattern twinklePreset(String preset) {
        if (preset == null) return TwinkleParticlePattern.fireflyGlow();
        return switch (preset.toLowerCase()) {
            case "firefly-glow" -> TwinkleParticlePattern.fireflyGlow();
            case "sculk-shimmer" -> TwinkleParticlePattern.sculkShimmer();
            case "starfield-twinkle" -> TwinkleParticlePattern.starfieldTwinkle();
            default -> TwinkleParticlePattern.fireflyGlow();
        };
    }

    private PatternFactory() {
    }
}
