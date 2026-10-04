package com.everythingrgbprofile.pattern;

import com.everythingrgbprofile.pattern.patterns.AlpinePattern;
import com.everythingrgbprofile.pattern.patterns.BasaltDeltasPattern;
import com.everythingrgbprofile.pattern.patterns.BloodBatsPattern;
import com.everythingrgbprofile.pattern.patterns.CanopyDapplePattern;
import com.everythingrgbprofile.pattern.patterns.DesertDunesPattern;
import com.everythingrgbprofile.pattern.patterns.DriftParticlePattern;
import com.everythingrgbprofile.pattern.patterns.DripstonePattern;
import com.everythingrgbprofile.pattern.patterns.EndAuroraPattern;
import com.everythingrgbprofile.pattern.patterns.GlowMotePattern;
import com.everythingrgbprofile.pattern.patterns.GrasslandPattern;
import com.everythingrgbprofile.pattern.patterns.IceFloePattern;
import com.everythingrgbprofile.pattern.patterns.NetherFungusPattern;
import com.everythingrgbprofile.pattern.patterns.NetherWastesPattern;
import com.everythingrgbprofile.pattern.patterns.OpenSeaPattern;
import com.everythingrgbprofile.pattern.patterns.PulsePattern;
import com.everythingrgbprofile.pattern.patterns.SavannaPattern;
import com.everythingrgbprofile.pattern.patterns.ShimmerPattern;
import com.everythingrgbprofile.pattern.patterns.ShimmerTwinklePattern;
import com.everythingrgbprofile.pattern.patterns.ShoreBreakPattern;
import com.everythingrgbprofile.pattern.patterns.SnowForestPattern;
import com.everythingrgbprofile.pattern.patterns.StrataPattern;
import com.everythingrgbprofile.pattern.patterns.SoulHazePattern;
import com.everythingrgbprofile.pattern.patterns.SulfurCavePattern;
import com.everythingrgbprofile.pattern.patterns.SunflowerFieldPattern;
import com.everythingrgbprofile.pattern.patterns.SwampBogPattern;
import com.everythingrgbprofile.pattern.patterns.TwinkleParticlePattern;
import com.everythingrgbprofile.pattern.patterns.WindsweptRidgePattern;

/**
 * Turns the {@code pattern}/{@code preset} strings from
 * {@code biome_profiles.json} into an actual {@link Pattern} object. Biome
 * profiles are the only caller today: boss profiles carry a {@code pattern}
 * too, but nothing reads it (see BossProfile), and dimensions get their
 * portal field straight from {@code DimensionProfile.toSettings}.
 *
 * <p>It is a switch statement. It lives in here specifically so that it is
 * <i>one</i> switch statement, so that if bosses or anything else ever do
 * start naming patterns in text, "shimmer" can't end up meaning subtly
 * different things depending on which one you asked.
 *
 * <h2>The one genuinely interesting rule</h2>
 * Every fallback lands on an <b>animated</b> pattern. Never a solid fill.
 *
 * <p>That is neither an accident nor laziness. A static colour is completely
 * indistinguishable from "the mod has crashed", so an unrecognised pattern
 * name gets you a gentle shimmer that says "I am alive and your config has a
 * typo in it", instead of a dead-looking board that says nothing whatsoever
 * and sends somebody hunting through logs.
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
            // a family: the jungles and forests are the same engine with the cover
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
            // The End sky as a violet aurora. One scene, no preset: the five
            // End biomes are all standing under the same sky, and nothing
            // about which island you are on changes what is above it.
            case "end-aurora" -> EndAuroraPattern.theEnd();
            // Sand with the sea running up it. A family of three now: the
            // snowy beach is the same shore frozen, with snow for sand, ice
            // glazing the swash zone and slush in the water, and the stony
            // shore is the same tide throwing spray up rock.
            case "shore-break" -> shorePreset(preset);
            // Heads over grass with the wind running through them. One scene,
            // no preset — the same call blood-bats makes. If a second crop biome
            // ever wants this, the head count and the wave are the knobs.
            case "sunflower-field" -> SunflowerFieldPattern.sunflowerPlains();
            // Open grass from overhead, with cloud shadows crossing it. A
            // family of two: the plains get the heavier weather, the meadow
            // gets more flowers and the bees.
            case "grassland" -> preset != null && preset.equalsIgnoreCase("meadow")
                    ? GrasslandPattern.meadow()
                    : GrasslandPattern.plains();
            // Eroded ground in terraces with wind streaking across every row. A
            // family, because the four windswept biomes are the same hillside
            // with different amounts of soil left on it and different amounts
            // of wind over it — see the preset docs for which way each one leans.
            case "windswept-ridge" -> windsweptPreset(preset);
						// Level bands of terracotta with the sun crossing them. A family,
            // because the three badlands biomes are the same rock with one
            // thing different, and this mirrors that: a skyline of spires for
            // the eroded one, trees for the wooded one, nothing for the plain
            // one. (In vanilla the eroded spires come from terrain shaping, not
            // the biome definition, which plain and eroded share exactly.)
            case "strata" -> strataPreset(preset);
            // Fog, soul fire and wisps. One scene, no preset: nothing else in
            // the game is fog lit from underneath by blue fire.
            case "soul-haze" -> SoulHazePattern.soulSandValley();
            // Murky water, lily pads, vines, and gas bubbling up through it. A
            // family of two: the mangrove swamp is the same bog with roots
            // standing in it and less room for everything else.
            case "swamp-bog" -> swampPreset(preset);
            // Ice drifting on dark water. A family of three: the deep ocean is
            // fewer, bigger slabs, and the river pins shelf ice to both banks
            // and runs the chunks down a channel between them.
            case "ice-floe" -> iceFloePreset(preset);
            // Every ocean that isn't frozen. A family, and a loose one: the
            // presets share an engine but switch on different layers (swell,
            // kelp, caustics, coral), so they look like different seas rather
            // than one sea in five tints.
            case "open-sea" -> openSeaPreset(preset);
            // The cold, treeless biomes: peaks, slopes, ice spikes, snowy
            // plains. A family on one terrain engine, each preset picking its
            // own land (skyline, slope, spikes, flat) and its own wind.
            // Snowfall is not in here on purpose. That's the weather
            // overlay's job, and it only runs when it's actually snowing.
            case "alpine" -> alpinePreset(preset);
            // Spruces. A family of three: the grove is packed on a slope, the
            // snowy taiga is spread out on the flat, and the plain taiga is the
            // snowy taiga with the snow off and a fox in it.
            case "snow-forest" -> snowForestPreset(preset);
            // Stalactites with water beading and dropping off them. One
            // scene, no preset.
            case "dripstone" -> DripstonePattern.dripstoneCaves();
            // Glowstone ceiling over a lava sea. One scene, no preset.
            case "nether-wastes" -> NetherWastesPattern.netherWastes();
            // Huge fungi with shroomlights and vines. A family of two that
            // mirror each other: crimson hangs its vines, warped grows them
            // up from the floor, and warped gets the endermen.
            case "nether-fungus" -> preset != null && preset.equalsIgnoreCase("warped-forest")
                    ? NetherFungusPattern.warpedForest()
                    : NetherFungusPattern.crimsonForest();
            // Basalt columns, magma floor, ash falling. One scene, no preset.
            case "basalt-deltas" -> BasaltDeltasPattern.basaltDeltas();
            // Acacias in gold grass under a hot sky. A family of two: the
            // plateau is the same scene from up the hill, looking out.
            case "savanna" -> preset != null && preset.equalsIgnoreCase("savanna-plateau")
                    ? SavannaPattern.savannaPlateau()
                    : SavannaPattern.savanna();
						// An acid pool bubbling under a spiked ceiling. Shared with the
            // title-screen theme, since Minecraft 26.2's own panorama is a
            // sulfur cave — which is why it takes a preset despite there being
            // one biome: the menu wants the same scene at a slower pace.
            case "sulfur-cave" -> sulfurPreset(preset);
            default -> new ShimmerPattern(); // animated fallback, never solid — see class doc
        };
    }

    /** Shore presets. Anything unrecognised gets the sandy beach. */
    private static Pattern shorePreset(String preset) {
        if (preset == null) return ShoreBreakPattern.beach();
        return switch (preset.toLowerCase()) {
            case "snowy-beach" -> ShoreBreakPattern.snowyBeach();
            case "stony-shore" -> ShoreBreakPattern.stonyShore();
            default -> ShoreBreakPattern.beach();
        };
    }

    /** Ice presets. Anything unrecognised gets the frozen ocean. */
    private static Pattern iceFloePreset(String preset) {
        if (preset == null) return IceFloePattern.frozenOcean();
        return switch (preset.toLowerCase()) {
            case "deep-frozen-ocean" -> IceFloePattern.deepFrozenOcean();
            case "frozen-river" -> IceFloePattern.frozenRiver();
            default -> IceFloePattern.frozenOcean();
        };
    }

    /** Open-sea presets. Anything unrecognised gets the plain ocean. */
    private static Pattern openSeaPreset(String preset) {
        if (preset == null) return OpenSeaPattern.ocean();
        return switch (preset.toLowerCase()) {
            case "deep-ocean" -> OpenSeaPattern.deepOcean();
            case "cold-ocean" -> OpenSeaPattern.coldOcean();
            case "warm-ocean" -> OpenSeaPattern.warmOcean();
            case "lukewarm-ocean" -> OpenSeaPattern.lukewarmOcean();
            default -> OpenSeaPattern.ocean();
        };
    }

    /** Alpine presets. Anything unrecognised gets the jagged peaks. */
    private static Pattern alpinePreset(String preset) {
        if (preset == null) return AlpinePattern.jaggedPeaks();
        return switch (preset.toLowerCase()) {
            case "frozen-peaks" -> AlpinePattern.frozenPeaks();
            case "stony-peaks" -> AlpinePattern.stonyPeaks();
            case "snowy-slopes" -> AlpinePattern.snowySlopes();
            case "ice-spikes" -> AlpinePattern.iceSpikes();
            case "snowy-plains" -> AlpinePattern.snowyPlains();
            default -> AlpinePattern.jaggedPeaks();
        };
    }

    /**
     * Snow-forest presets. Anything unrecognised gets the grove. "taiga" is in
     * here even though it has no snow, because it's the same trees on the same
     * engine; the class doc says what gets swapped out.
     */
    private static Pattern snowForestPreset(String preset) {
        if (preset == null) return SnowForestPattern.grove();
        return switch (preset.toLowerCase()) {
            case "snowy-taiga" -> SnowForestPattern.snowyTaiga();
            case "taiga" -> SnowForestPattern.taiga();
            default -> SnowForestPattern.grove();
        };
    }

    /** Swamp presets. Anything unrecognised gets the plain swamp. */
    private static Pattern swampPreset(String preset) {
        if (preset != null && preset.equalsIgnoreCase("mangrove-swamp")) return SwampBogPattern.mangroveSwamp();
        return SwampBogPattern.swamp();
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
     * Canopy presets. All of them differ only in how much light gets through and
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
            case "forest" -> CanopyDapplePattern.forest();
            case "flower-forest" -> CanopyDapplePattern.flowerForest();
            case "birch-forest" -> CanopyDapplePattern.birchForest();
            case "old-growth-birch-forest" -> CanopyDapplePattern.oldGrowthBirchForest();
            case "spruce-forest" -> CanopyDapplePattern.spruceForest();
            case "old-growth-pine-taiga" -> CanopyDapplePattern.oldGrowthPineTaiga();
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

		/**
     * Strata presets. All three share one ladder and one palette; what separates
     * them is what has happened to the rock since it was laid down.
     */
    private static Pattern strataPreset(String preset) {
        if (preset == null) return StrataPattern.badlands();
        return switch (preset.toLowerCase()) {
            case "badlands" -> StrataPattern.badlands();
            case "eroded-badlands" -> StrataPattern.erodedBadlands();
            case "wooded-badlands" -> StrataPattern.woodedBadlands();
            default -> StrataPattern.badlands();
        };
    }

		/**
     * Sulfur presets. The biome one is busier and sits lower in the water; the
     * menu one is slower and shows more of the cave. See the factory methods
     * for what each is framed against.
     */
    private static Pattern sulfurPreset(String preset) {
        if (preset == null) return SulfurCavePattern.sulfurCaves();
        return switch (preset.toLowerCase()) {
            case "sulfur-caves" -> SulfurCavePattern.sulfurCaves();
            case "menu" -> SulfurCavePattern.menu();
            default -> SulfurCavePattern.sulfurCaves();
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
