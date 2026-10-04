package com.everythingrgbprofile.config;

import com.everythingrgbprofile.RGBProfileMod;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/**
 * Every lighting feature this mod has, and whether the build you are holding
 * is actually allowed to run it.
 *
 * <p>A feature is either {@link Stage#RELEASED} or
 * {@link Stage#IN_DEVELOPMENT}. Released features follow their config switch
 * like normal. In-development features are hard off in a release build:
 * {@link #isOn()} returns false no matter what the config says, so a published
 * jar can only ever light up the things that are finished and tested, and no
 * amount of creative config editing brings the rest back.
 *
 * <p>The config entries still get written either way, which is deliberate.
 * Somebody's config carries over untouched to the version where the feature
 * actually ships, instead of their settings evaporating on upgrade.
 *
 * <h2>Which build is which</h2>
 * The switch is baked into the jar at build time and is not read from the
 * config, because a switch users can flip is not a gate.
 *
 * <p>A plain {@code gradlew build} produces a release build. A development
 * build needs {@code experimental_features=true}, passed either as {@code -P}
 * on the command line or set in the gitignored {@code local.properties}, and
 * it stamps {@code -experimental} onto the version. That shows up in the jar's
 * filename AND in the game's mod list, so a jar carrying in-development
 * features cannot quietly pass itself off as a release. (The one exception is
 * the copy {@code copyToModpack} drops into a modpack, which is renamed
 * without the stamp on purpose; the mod list still shows it.) See
 * {@code build.gradle}.
 *
 * <p>If the flag cannot be read at all, which happens when the resource is
 * missing or was never expanded (an IDE run that skipped Gradle's resource
 * processing, for instance), the build counts as a release. Failing closed
 * means the worst case is a developer briefly wondering where their feature
 * went. Failing open means the worst case is a player being handed unfinished
 * lighting, and those are not the same size of problem.
 *
 * <h2>Moving a feature between stages</h2>
 * Change its stage here. That is the entire job. Every check for a lighting
 * feature anywhere in this mod goes through this enum instead of reading a
 * config switch directly, specifically so there is never a second list
 * somewhere that has to be kept in step with this one.
 */
public enum Feature {

    // --- Vanilla ----------------------------------------------------------
    BIOME_COLORS(Stage.RELEASED, () -> RGBProfileConfig.BIOME_COLORS_ENABLED.get()),
    NIGHT_INDICATOR(Stage.RELEASED, () -> RGBProfileConfig.NIGHT_INDICATOR_ENABLED.get()),
    RAIN_THUNDERSTORM(Stage.RELEASED, () -> RGBProfileConfig.RAIN_THUNDERSTORM_ENABLED.get()),
    MENU_THEME(Stage.RELEASED, () -> RGBProfileConfig.MENU_THEME_ENABLED.get()),
    HEALTH_FLASH(Stage.RELEASED, () -> RGBProfileConfig.HEALTH_FLASH_ENABLED.get()),
    HUNGER_WARNING(Stage.RELEASED, () -> RGBProfileConfig.HUNGER_WARNING_ENABLED.get()),
    DROWNING(Stage.RELEASED, () -> RGBProfileConfig.DROWNING_ENABLED.get()),
    BURNING(Stage.RELEASED, () -> RGBProfileConfig.BURNING_ENABLED.get()),
    DEATH_FLASH(Stage.RELEASED, () -> RGBProfileConfig.DEATH_FLASH_ENABLED.get()),
    DEATH_BLOOD(Stage.RELEASED, () -> RGBProfileConfig.DEATH_BLOOD_ENABLED.get()),
    LEVEL_UP(Stage.RELEASED, () -> RGBProfileConfig.PROGRESSION_EFFECTS_ENABLED.get()
            && RGBProfileConfig.LEVEL_UP_ENABLED.get()),
    ADVANCEMENT(Stage.RELEASED, () -> RGBProfileConfig.PROGRESSION_EFFECTS_ENABLED.get()
            && RGBProfileConfig.ADVANCEMENT_ENABLED.get()),
    SLEEP_WAKE(Stage.RELEASED, () -> RGBProfileConfig.SLEEP_WAKE_ENABLED.get()),
    PORTAL_TRANSITION(Stage.RELEASED, () -> RGBProfileConfig.PORTAL_TRANSITION_ENABLED.get()),
    PORTAL_CHARGE_UP(Stage.RELEASED, () -> RGBProfileConfig.PORTAL_TRANSITION_ENABLED.get()
            && RGBProfileConfig.PORTAL_CHARGE_UP_ENABLED.get()),
    RAID_WARNING(Stage.RELEASED, () -> RGBProfileConfig.RAID_WARNING_ENABLED.get()),
    SCULK_SENSOR(Stage.RELEASED, () -> RGBProfileConfig.SCULK_ALERT_ENABLED.get()
            && RGBProfileConfig.SCULK_SENSOR_ENABLED.get()),
    SCULK_SHRIEKER(Stage.RELEASED, () -> RGBProfileConfig.SCULK_ALERT_ENABLED.get()
            && RGBProfileConfig.SCULK_SHRIEKER_ENABLED.get()),
    WARDEN_ENCOUNTER(Stage.RELEASED, () -> RGBProfileConfig.WARDEN_ENCOUNTER_ENABLED.get()),
    /**
     * The generic boss pulse, which also covers modded bosses through
     * {@code c:bosses}. Not driven by boss bars despite the history; see
     * ClientEventHandlers.pollBossEncounter.
     */
    BOSS_ENCOUNTER(Stage.RELEASED, () -> RGBProfileConfig.BOSS_EVENTS_ENABLED.get()
            && RGBProfileConfig.BOSS_PROXIMITY_ENABLED.get()),
    WITHER(Stage.RELEASED, () -> RGBProfileConfig.WITHER_ENABLED.get()),
    ELDER_GUARDIAN(Stage.RELEASED, () -> RGBProfileConfig.ELDER_GUARDIAN_ENABLED.get()),
    END_DRAGON(Stage.RELEASED, () -> RGBProfileConfig.END_DRAGON_ENABLED.get()),
    END_RITUAL(Stage.RELEASED, () -> RGBProfileConfig.END_RITUAL_ENABLED.get()),

    // --- Mod paths --------------------------------------------------------
    // One switch per dimension mod with dedicated support, covering
    // everything this mod does for it: the portal effect for its dimensions
    // and whatever boss rooms it has. Turning a path off has to reach all of
    // that, which is why the per-boss switches below read their path's switch
    // as well as their own, and why the portal looks its dimension up through
    // dimensionPath() rather than carrying a list of its own.
    //
    // Vanilla is deliberately absent here. The Overworld, the Nether and the
    // End are not optional mod support and are not gated by any of this; they
    // follow portalTransitionEnabled as they always have.
    AETHER(Stage.RELEASED, () -> RGBProfileConfig.AETHER_ENABLED.get()),
    TWILIGHT_FOREST(Stage.RELEASED, () -> RGBProfileConfig.TWILIGHT_FOREST_ENABLED.get()),
    BUMBLEZONE(Stage.RELEASED, () -> RGBProfileConfig.BUMBLEZONE_ENABLED.get()),
    UNDERGARDEN(Stage.RELEASED, () -> RGBProfileConfig.UNDERGARDEN_ENABLED.get()),

    // --- Other mods -------------------------------------------------------
    // With any of these held back, its boss still gets the generic boss
    // pulse, so nothing goes dark: it just loses its dedicated room. Each one
    // is also under its mod's path switch above, so turning the Aether off
    // takes all three of its rooms with it without three separate edits.
    NAGA(Stage.IN_DEVELOPMENT, () -> TWILIGHT_FOREST.isOn() && RGBProfileConfig.NAGA_ENABLED.get()),
    SLIDER(Stage.IN_DEVELOPMENT, () -> AETHER.isOn() && RGBProfileConfig.SLIDER_ENABLED.get()),
    SUN_SPIRIT(Stage.IN_DEVELOPMENT, () -> AETHER.isOn() && RGBProfileConfig.SUN_SPIRIT_ENABLED.get()),
    VALKYRIE_QUEEN(Stage.IN_DEVELOPMENT, () -> AETHER.isOn() && RGBProfileConfig.VALKYRIE_QUEEN_ENABLED.get()),
    /** Its reading of thirst and temperature is still a placeholder; see {@code ToughAsNailsCompat}. */
    TOUGH_AS_NAILS(Stage.IN_DEVELOPMENT, () -> RGBProfileConfig.TOUGH_AS_NAILS_ENABLED.get()),

    // --- Upcoming vanilla content -----------------------------------------
    /**
     * The Sift, a dimension announced for a future Minecraft release: its
     * portal colours and its title-screen theme. Built from the promotional
     * screenshots ahead of time and held back until the dimension ships,
     * because its id and its real textures do not exist yet. No switch of its
     * own; the portal and menu switches it lives under already cover it.
     * Flip to RELEASED, and move its portal profile into
     * dimension_profiles.json, once the real id is known.
     */
    SIFT(Stage.IN_DEVELOPMENT, () -> true);

    public enum Stage {
        /** Finished and tested. Follows its config switch like a normal feature. */
        RELEASED,
        /** Runs only in a development build. Hard off in a release, config or no config. */
        IN_DEVELOPMENT
    }

    /** Where the build flag lives inside the jar. Gradle's resource processing writes it. */
    private static final String BUILD_PROPERTIES = "/everythingrgbprofiles/build.properties";

    /**
     * Dimension namespaces that belong to a mod path, and the switch covering
     * them.
     *
     * <p>Keyed by namespace rather than by full dimension id, so a mod that
     * adds a second dimension is covered without anyone remembering to add a
     * line here, and so this cannot drift out of step with
     * {@code dimension_profiles.json}.
     *
     * <p>Everything not listed is ungated: vanilla's three, and every
     * dimension from a mod with no dedicated support, which keeps working off
     * its profile or the derived fallback exactly as before.
     */
    private static final Map<String, Feature> DIMENSION_PATHS = Map.of(
            "aether", AETHER,
            "twilightforest", TWILIGHT_FOREST,
            "the_bumblezone", BUMBLEZONE,
            "undergarden", UNDERGARDEN);

    private final Stage stage;
    /**
     * Read through a lambda instead of holding the config value directly, and
     * this is not a style preference. {@code RGBProfileConfig} builds its whole
     * spec inside a static initialiser that refers back to this enum, so
     * holding its fields here would make each class's initialisation depend on
     * the other one having already finished. Deferring the read through a
     * lambda breaks that circle.
     */
    private final BooleanSupplier configSwitch;

    Feature(Stage stage, BooleanSupplier configSwitch) {
        this.stage = stage;
        this.configSwitch = configSwitch;
    }

    public Stage stage() {
        return stage;
    }

    /** Whether this build can run the feature at all, regardless of what the config says. */
    public boolean isAvailable() {
        return stage == Stage.RELEASED || Build.EXPERIMENTAL;
    }

    /** Whether the feature should run right now: available in this build AND switched on in the config. */
    public boolean isOn() {
        return isAvailable() && configSwitch.getAsBoolean();
    }

    /**
     * A config comment, with a warning bolted onto the front of it when this
     * build is never going to run the feature. Otherwise somebody reads their
     * config file, sees a switch sitting there set to true, and reasonably
     * concludes the mod is broken.
     */
    public String[] configComment(String comment) {
        if (isAvailable()) return new String[]{comment};
        return new String[]{
                "Not available in this version: this switch does nothing yet, and is kept so your "
                        + "config carries over to the version where it ships.",
                comment};
    }

    /**
     * The mod path a dimension belongs to, or null for one this mod does not
     * gate.
     *
     * @param dimensionId a dimension's resource location as a string, e.g.
     *                    {@code aether:the_aether}.
     */
    public static Feature dimensionPath(String dimensionId) {
        if (dimensionId == null) return null;
        // colon > 0 for the same reason ProfileResolver uses it: a leading
        // colon is a malformed id with an empty namespace, not a match.
        int colon = dimensionId.indexOf(':');
        if (colon <= 0) return null;
        return DIMENSION_PATHS.get(dimensionId.substring(0, colon));
    }

    /**
     * Whether a dimension is allowed to light the board at all.
     *
     * <p>True for anything ungated, and true for a null id. The dwell reads
     * the dimension it is leaving from a field that is not necessarily filled
     * in yet — the first portal of a session can beat the first dimension
     * observation — and "not known yet" must not quietly mean "switched off".
     */
    public static boolean dimensionPathIsOn(String dimensionId) {
        Feature path = dimensionPath(dimensionId);
        return path == null || path.isOn();
    }

    /** True when in-development features were actually compiled into this jar. */
    public static boolean experimentalBuild() {
        return Build.EXPERIMENTAL;
    }

    /** Says in the log which kind of build this is and what it is holding back. */
    public static void logBuildStatus() {
        List<String> held = new ArrayList<>();
        for (Feature feature : values()) {
            if (feature.stage == Stage.IN_DEVELOPMENT) held.add(feature.name().toLowerCase(Locale.ROOT));
        }
        if (Build.EXPERIMENTAL) {
            RGBProfileMod.LOGGER.warn("RGB Profile: EXPERIMENTAL build — in-development features are "
                    + "running: {}. Do not publish this jar.", String.join(", ", held));
        } else if (!held.isEmpty()) {
            RGBProfileMod.LOGGER.info("RGB Profile: release build. Held back until they are finished: {}.",
                    String.join(", ", held));
        }
    }

    /**
     * The build flag, read exactly once, parked in its own holder class so the
     * JVM loads it on first use rather than during this enum's own
     * initialisation.
     */
    private static final class Build {
        static final boolean EXPERIMENTAL = read();

        private static boolean read() {
            try (InputStream in = Feature.class.getResourceAsStream(BUILD_PROPERTIES)) {
                if (in == null) return false;
                Properties properties = new Properties();
                properties.load(in);
                // parseBoolean returns false for literally anything that isn't
                // "true", which conveniently covers both a missing key and a
                // file Gradle never expanded the placeholder in.
                return Boolean.parseBoolean(properties.getProperty("experimental_features", "false").trim());
            } catch (IOException e) {
                return false;
            }
        }
    }
}
