import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.effects.support.BurningEffect;
import com.everythingrgbprofile.effects.support.DrowningEffect;
import com.everythingrgbprofile.effects.support.MomentaryFlashEffect;
import com.everythingrgbprofile.effects.support.NightIndicatorEffect;
import com.everythingrgbprofile.effects.support.RaidEffect;
import com.everythingrgbprofile.pattern.patterns.RaidHordePattern;
import com.everythingrgbprofile.effects.support.SustainedOverlayEffect;
import com.everythingrgbprofile.pattern.patterns.BloodDripPattern;
import com.everythingrgbprofile.pattern.patterns.DriftParticlePattern;
import com.everythingrgbprofile.pattern.patterns.LevelUpPattern;
import com.everythingrgbprofile.pattern.patterns.ShimmerTwinklePattern;
import com.everythingrgbprofile.pattern.patterns.VanillaMenuPattern;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The non-boss effects, as things {@code ColorTuner} can select, colour and
 * export.
 *
 * <p>Built on exactly the same machinery as {@link BossThemes}, because they
 * are the same kind of thing: an {@code EffectController} that has to be told
 * what state the game is in before it draws, plus colours that live in the
 * TOML config. Each theme here is the mod's real effect class, a script from
 * the progress slider to the calls the event layer makes, and its colour
 * slots with their config keys. Everything {@code BossThemes} says about
 * colours being read live rather than baked in applies unchanged.
 *
 * <p>What progress means differs per effect, and each status line says so:
 * how much air is gone for the water, how bad the burning is for the fire,
 * how far through the night for the moon. The ones with no state to be in —
 * rain, the blood, the sculk, the menu — ignore it.
 *
 * <h2>Overlays are drawn over a biome</h2>
 * Burning, drowning, rain and the moon are Tier 2 overlays: in game they are
 * always laid over whatever the base layer shows, and much of what makes each
 * one work is what it lets through. Over black, rain is a few dots and the
 * water's line is just where the colour stops. So {@link #isOverlay} marks
 * them, and the tuner draws them over a biome animated by its real pattern —
 * the last one selected, or black on request. See
 * {@code ColorTuner.backdropBiome}.
 *
 * <h2>Only the colours are live</h2>
 * Counts, intervals and thresholds — the number of raindrops, how often blood
 * drips, where the water starts to panic — are built from the shipped config
 * defaults, not read from the live config. The tuner tunes colour; those are
 * the values the effect looks like out of the box.
 *
 * <p>The mineshaft menu theme is not here. It takes its ember and amethyst
 * colours once, when it is built, so the only way to recolour it would be to
 * rebuild it on every slider movement, which restarts its drill and sparks
 * each time — the thing {@code BossThemes} explains these adapters exist to
 * avoid.
 */
final class EffectThemes {

    private EffectThemes() {
    }

    /** Shipped config defaults the scripts build with. See the class notes. */
    private static final double DROWNING_PANIC_THRESHOLD = 0.85;
    private static final double BURNING_PANIC_THRESHOLD = 0.9;
    private static final int RAIN_PARTICLES = 12;
    private static final int RAIN_LIFESPAN_MILLIS = 2800;
    private static final double RAIN_OPACITY = 0.78;
    private static final int BLOOD_DRIPS = 7;
    private static final double BLOOD_DRIP_INTERVAL_MILLIS = 900;
    private static final int WARDEN_TWINKLES = 7;
    private static final double WARDEN_TWINKLE_INTERVAL_MILLIS = 1100;
    private static final int MENU_CLOUDS = 4;
    /** How often the level-up replays: its own length plus a moment of dark between plays. */
    private static final long LEVEL_UP_REPLAY_MILLIS = 2400;
    private static final LevelUpPattern LEVEL_UP_PATTERN = new LevelUpPattern();

    /** The raid, as shares of the slider: the omen, the raid, victory, then defeat. */
    private static final double RAID_OMEN_END = 0.25;
    private static final double RAID_END = 0.7;
    private static final double RAID_VICTORY_END = 0.85;
    /** How often the horn blows while the slider sits on the raid. */
    private static final long RAID_HORN_REPLAY_MILLIS = 4000;

    private static RaidHordePattern.Phase raidPhase(double progress) {
        if (progress < RAID_OMEN_END) return RaidHordePattern.Phase.OMEN;
        if (progress < RAID_END) return RaidHordePattern.Phase.RAID;
        if (progress < RAID_VICTORY_END) return RaidHordePattern.Phase.VICTORY;
        return RaidHordePattern.Phase.DEFEAT;
    }

    /**
     * Burning, as bands of the slider: not burning, then the four heights the
     * event layer chooses between, from least to most dangerous. Bands rather
     * than a ramp because in game the height only ever takes these values;
     * anything between them is the flames easing from one to the next.
     */
    private static final double[] BURNING_BAND_ENDS = {0.1, 0.3, 0.55, 0.8};
    private static final double[] BURNING_HEATS = {0, 0.3, 0.5, 0.75, 1.0};
    private static final String[] BURNING_STATES = {
            "not burning - the flames die down",
            "burning, with Fire Resistance protecting you",
            "burning",
            "standing in fire",
            "in lava - the whole board, surging",
    };

    private static final Set<BossThemes.Theme> OVERLAYS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static List<BossThemes.Theme> themes;

    /** Every effect theme, built once. */
    static synchronized List<BossThemes.Theme> all() {
        if (themes != null) return themes;
        List<BossThemes.Theme> out = new ArrayList<>();

        out.add(overlay(new BossThemes.Theme("burning", "burning",
                List.of(new BossThemes.Slot("core", "coreColor", ColorPalette.BURNING_CORE),
                        new BossThemes.Slot("tips", "tipColor", ColorPalette.BURNING_TIP)),
                colors -> new BurningEffect(BURNING_PANIC_THRESHOLD, ColorPalette.BURNING_CORE,
                        ColorPalette.BURNING_TIP),
                (controller, memo, progress, elapsed) -> {
                    BurningEffect b = (BurningEffect) controller;
                    b.setColors(memo.colors.get(0), memo.colors.get(1));
                    b.setHeat(BURNING_HEATS[burningBand(progress)]);
                },
                progress -> "burning - " + BURNING_STATES[burningBand(progress)])
                .withPhases(
                        new BossThemes.Phase("not burning", 0, BURNING_BAND_ENDS[0]),
                        new BossThemes.Phase("protected", BURNING_BAND_ENDS[0], BURNING_BAND_ENDS[1]),
                        new BossThemes.Phase("burning", BURNING_BAND_ENDS[1], BURNING_BAND_ENDS[2]),
                        new BossThemes.Phase("in fire", BURNING_BAND_ENDS[2], BURNING_BAND_ENDS[3]),
                        new BossThemes.Phase("in lava", BURNING_BAND_ENDS[3], 1))));

        out.add(overlay(new BossThemes.Theme("drowning", "drowning",
                List.of(new BossThemes.Slot("deep", "deepColor", ColorPalette.DROWNING_DEEP),
                        new BossThemes.Slot("surface", "surfaceColor", ColorPalette.DROWNING_SURFACE)),
                colors -> new DrowningEffect(DROWNING_PANIC_THRESHOLD, ColorPalette.DROWNING_DEEP,
                        ColorPalette.DROWNING_SURFACE),
                (controller, memo, progress, elapsed) -> {
                    DrowningEffect d = (DrowningEffect) controller;
                    d.setColors(memo.colors.get(0), memo.colors.get(1));
                    d.setSubmersion(progress);
                },
                progress -> String.format("drowning - %.0f%% of your air gone%s", progress * 100,
                        progress > DROWNING_PANIC_THRESHOLD ? ", and the water pulsing" : ""))
                // The describer's test is > rather than <, so the threshold
                // itself is still calm; Phase keeps a loop clear of it either way.
                .withPhases(
                        new BossThemes.Phase("rising", 0, DROWNING_PANIC_THRESHOLD),
                        new BossThemes.Phase("panic", DROWNING_PANIC_THRESHOLD, 1))));

        out.add(overlay(new BossThemes.Theme("rain", "rainThunderstorm",
                List.of(new BossThemes.Slot("rain", "rainColor", ColorPalette.RAIN_BLUE_GRAY)),
                colors -> new SustainedOverlayEffect("rain_cascade", EffectTier.TIER2_OVERLAY, 0, null,
                        DriftParticlePattern.rainDrift(RAIN_PARTICLES, RAIN_LIFESPAN_MILLIS),
                        ColorPalette.RAIN_BLUE_GRAY)
                        .withLayerOpacity(RAIN_OPACITY),
                (controller, memo, progress, elapsed) -> {
                    SustainedOverlayEffect r = (SustainedOverlayEffect) controller;
                    r.setColor(memo.colors.get(0));
                    r.setActive(true, elapsed);
                },
                progress -> "rain - falling over plains; progress does nothing here")));

        out.add(overlay(new BossThemes.Theme("night moon", "nightIndicator",
                List.of(new BossThemes.Slot("moonlight", "color", ColorPalette.NIGHT_MOONLIGHT)),
                colors -> new NightIndicatorEffect(),
                (controller, memo, progress, elapsed) -> {
                    NightIndicatorEffect n = (NightIndicatorEffect) controller;
                    n.setColor(memo.colors.get(0));
                    n.setNightProgress(true, progress);
                },
                progress -> String.format("night moon - %.0f%% of the way through the night", progress * 100))));

        out.add(new BossThemes.Theme("death blood", "deathFlash",
                List.of(new BossThemes.Slot("soak", "bloodSoakColor", ColorPalette.DEATH_BLOOD_SOAK),
                        new BossThemes.Slot("blood", "bloodColor", ColorPalette.DEATH_BLOOD)),
                colors -> new SustainedOverlayEffect("death_state", EffectTier.TIER1_OPAQUE_BASE, 100, null,
                        new BloodDripPattern(BLOOD_DRIPS, BLOOD_DRIP_INTERVAL_MILLIS),
                        ColorPalette.DEATH_BLOOD_SOAK),
                (controller, memo, progress, elapsed) -> {
                    SustainedOverlayEffect d = (SustainedOverlayEffect) controller;
                    d.setColor(memo.colors.get(0));
                    d.withAccentColor(memo.colors.get(1));
                    d.setActive(true, elapsed);
                },
                progress -> "death blood - the death screen; progress does nothing here"));

        out.add(new BossThemes.Theme("warden presence", "wardenEncounter",
                List.of(new BossThemes.Slot("sculk", "presenceColor", ColorPalette.SCULK_AMBIENT),
                        new BossThemes.Slot("glint", "presenceGlintColor", ColorPalette.SCULK_WARDEN_GLINT)),
                colors -> new SustainedOverlayEffect("warden_active", EffectTier.TIER1_OPAQUE_BASE, 11, null,
                        ShimmerTwinklePattern.wardenPresence(WARDEN_TWINKLES, WARDEN_TWINKLE_INTERVAL_MILLIS),
                        ColorPalette.SCULK_AMBIENT),
                (controller, memo, progress, elapsed) -> {
                    SustainedOverlayEffect w = (SustainedOverlayEffect) controller;
                    w.setColor(memo.colors.get(0));
                    w.withAccentColor(memo.colors.get(1));
                    w.setActive(true, elapsed);
                },
                progress -> "warden presence - a Warden nearby; progress does nothing here"));

        out.add(new BossThemes.Theme("raid", "raidWarning",
                List.of(new BossThemes.Slot("horde", "color", ColorPalette.RAID_WARNING_RED),
                        new BossThemes.Slot("raiders", "raiderColor", ColorPalette.RAID_RAIDER),
                        new BossThemes.Slot("victory", "victoryColor", ColorPalette.RAID_VICTORY)),
                colors -> new RaidEffect(20),
                (controller, memo, progress, elapsed) -> {
                    RaidEffect r = (RaidEffect) controller;
                    r.setColors(memo.colors.get(0), memo.colors.get(1), memo.colors.get(2));
                    RaidHordePattern.Phase phase = raidPhase(progress);
                    double omen = phase == RaidHordePattern.Phase.OMEN ? progress / RAID_OMEN_END : 0;
                    r.setPhase(phase, omen, elapsed);
                    // The horn on a loop while the raid is on, fired on the
                    // change of beat so it plays once per beat, not per frame.
                    if (phase == RaidHordePattern.Phase.RAID) {
                        int beat = (int) Math.floorDiv(elapsed, RAID_HORN_REPLAY_MILLIS);
                        if (beat != memo.count) {
                            memo.count = beat;
                            r.horn(elapsed);
                        }
                    }
                },
                progress -> switch (raidPhase(progress)) {
                    case OMEN -> String.format("raid - Raid Omen counting down, %.0f%% of the way to the raid",
                            progress / RAID_OMEN_END * 100);
                    case RAID -> "raid - the army marching, the horn every "
                            + RAID_HORN_REPLAY_MILLIS / 1000.0 + "s";
                    case VICTORY -> "raid - won: the army routs, then fireworks";
                    case DEFEAT -> "raid - lost: the army celebrates";
                }).withPhases(
                new BossThemes.Phase("omen", 0, RAID_OMEN_END),
                new BossThemes.Phase("raid", RAID_OMEN_END, RAID_END),
                new BossThemes.Phase("victory", RAID_END, RAID_VICTORY_END),
                new BossThemes.Phase("defeat", RAID_VICTORY_END, 1)));

        out.add(new BossThemes.Theme("level up", "progressionEffects",
                List.of(new BossThemes.Slot("burst", "levelUpColor", ColorPalette.LEVEL_UP_GOLD),
                        new BossThemes.Slot("bar", "levelUpBarColor", ColorPalette.LEVEL_UP_XP_GREEN)),
                colors -> new MomentaryFlashEffect("level_up", 40),
                (controller, memo, progress, elapsed) -> {
                    // A one-shot, replayed on a loop so a parked slider still
                    // shows the whole animation, the way BossThemes replays the
                    // Elder Guardian's beam. Triggered on the change of beat and
                    // never per frame, or it would restart every frame and
                    // never get past its first.
                    int beat = (int) Math.floorDiv(elapsed, LEVEL_UP_REPLAY_MILLIS);
                    if (beat != memo.count) {
                        memo.count = beat;
                        ((MomentaryFlashEffect) controller).trigger(beat * LEVEL_UP_REPLAY_MILLIS,
                                memo.colors.get(0), memo.colors.get(1), LevelUpPattern.DURATION_MILLIS,
                                null, LEVEL_UP_PATTERN, null);
                    }
                },
                progress -> "level up - the bar fills, bursts and sparkles, replayed every "
                        + LEVEL_UP_REPLAY_MILLIS / 1000.0 + "s; progress does nothing here"));

        out.add(new BossThemes.Theme("menu vanilla", "menuTheme",
                List.of(new BossThemes.Slot("grass", "vanillaGrassColor", ColorPalette.MENU_VANILLA_GRASS),
                        new BossThemes.Slot("sky", "vanillaSkyColor", ColorPalette.MENU_VANILLA_SKY)),
                colors -> new SustainedOverlayEffect("menu_theme", EffectTier.TIER1_OPAQUE_BASE, 1, null,
                        new VanillaMenuPattern(MENU_CLOUDS), ColorPalette.MENU_VANILLA_GRASS),
                (controller, memo, progress, elapsed) -> {
                    SustainedOverlayEffect m = (SustainedOverlayEffect) controller;
                    m.setColor(memo.colors.get(0));
                    m.withAccentColor(memo.colors.get(1));
                    m.setActive(true, elapsed);
                },
                progress -> "menu vanilla - the title screen; progress does nothing here"));

        // The Sift's theme, on branches that have it (main, for now). The tuner is
        // one copy shared by every version branch, so it looks the Sift up by name
        // rather than linking against it; linking directly broke the tuner on
        // every branch that does not carry the pattern yet.
        siftTheme().ifPresent(out::add);
        // The sulfur cave, on 26.2 and later only. Gated on the branch's game
        // version first and the class second; see GameVersion for why both.
        sulfurTheme().ifPresent(out::add);

        themes = List.copyOf(out);
        return themes;
    }

    /** The Sift menu theme, or empty on a branch without {@code SiftPattern}. */
    private static java.util.Optional<BossThemes.Theme> siftTheme() {
        try {
            Class<?> type = Class.forName("com.everythingrgbprofile.pattern.patterns.SiftPattern");
            java.lang.reflect.Method menu = type.getMethod("menu");
            com.everythingrgbprofile.color.RGBColor mesa =
                    (com.everythingrgbprofile.color.RGBColor) ColorPalette.class.getField("SIFT_MESA").get(null);
            com.everythingrgbprofile.color.RGBColor sky =
                    (com.everythingrgbprofile.color.RGBColor) ColorPalette.class.getField("SIFT_SKY").get(null);
            return java.util.Optional.of(new BossThemes.Theme("menu sift", "menuTheme",
                    List.of(new BossThemes.Slot("mesa", "siftMesaColor", mesa),
                            new BossThemes.Slot("sky", "siftSkyColor", sky)),
                    colors -> {
                        try {
                            return new SustainedOverlayEffect("menu_theme", EffectTier.TIER1_OPAQUE_BASE, 1, null,
                                    (com.everythingrgbprofile.pattern.Pattern) menu.invoke(null), mesa);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("SiftPattern.menu() failed", e);
                        }
                    },
                    (controller, memo, progress, elapsed) -> {
                        SustainedOverlayEffect m = (SustainedOverlayEffect) controller;
                        m.setColor(memo.colors.get(0));
                        m.withAccentColor(memo.colors.get(1));
                        m.setActive(true, elapsed);
                    },
                    progress -> "menu sift - the title screen for the upcoming dimension; progress does nothing here"));
        } catch (ReflectiveOperationException | LinkageError e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * The sulfur cave menu theme, or empty on a branch for a Minecraft older
     * than 26.2. Same shape as the Sift's: the pattern and both shipped
     * colours looked up by name, because on 1.21.1 none of them exist and
     * a direct reference would stop this file compiling there.
     */
    private static java.util.Optional<BossThemes.Theme> sulfurTheme() {
        if (!GameVersion.atLeast(GameVersion.SULFUR)) return java.util.Optional.empty();
        Class<?> type = GameVersion.required("com.everythingrgbprofile.pattern.patterns.SulfurCavePattern",
                "menu sulfur");
        if (type == null) return java.util.Optional.empty();
        try {
            java.lang.reflect.Method menu = type.getMethod("menu");
            com.everythingrgbprofile.color.RGBColor rock =
                    (com.everythingrgbprofile.color.RGBColor) ColorPalette.class.getField("SULFUR_ROCK").get(null);
            com.everythingrgbprofile.color.RGBColor pool =
                    (com.everythingrgbprofile.color.RGBColor) ColorPalette.class.getField("SULFUR_ACID_POOL").get(null);
            return java.util.Optional.of(new BossThemes.Theme("menu sulfur", "menuTheme",
                    List.of(new BossThemes.Slot("rock", "sulfurRockColor", rock),
                            new BossThemes.Slot("pool", "sulfurPoolColor", pool)),
                    colors -> {
                        try {
                            return new SustainedOverlayEffect("menu_theme", EffectTier.TIER1_OPAQUE_BASE, 1, null,
                                    (com.everythingrgbprofile.pattern.Pattern) menu.invoke(null), rock);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("SulfurCavePattern.menu() failed", e);
                        }
                    },
                    (controller, memo, progress, elapsed) -> {
                        SustainedOverlayEffect m = (SustainedOverlayEffect) controller;
                        m.setColor(memo.colors.get(0));
                        m.withAccentColor(memo.colors.get(1));
                        m.setActive(true, elapsed);
                    },
                    progress -> "menu sulfur - the 26.2 title screen, an acid pool under a spiked ceiling; "
                            + "progress does nothing here"));
        } catch (ReflectiveOperationException | LinkageError e) {
            // The class is there but not the shape this expects: a renamed
            // palette constant, say. Worth hearing about, not worth a crash.
            System.err.println("menu sulfur left out: " + e);
            return java.util.Optional.empty();
        }
    }

    /** The themes drawn over a biome rather than over black. See the class notes. */
    static boolean isOverlay(BossThemes.Theme theme) {
        all();
        return OVERLAYS.contains(theme);
    }

    private static BossThemes.Theme overlay(BossThemes.Theme theme) {
        OVERLAYS.add(theme);
        return theme;
    }

    private static int burningBand(double progress) {
        for (int i = 0; i < BURNING_BAND_ENDS.length; i++) {
            if (progress < BURNING_BAND_ENDS[i]) return i;
        }
        return BURNING_BAND_ENDS.length;
    }
}
