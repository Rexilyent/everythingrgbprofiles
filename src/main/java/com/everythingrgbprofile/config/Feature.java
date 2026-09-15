package com.everythingrgbprofile.config;

import com.everythingrgbprofile.RGBProfileMod;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/**
 * Every lighting feature the mod has, and whether this build is allowed to run
 * it.
 *
 * <p>A feature is either {@link Stage#RELEASED} or {@link Stage#IN_DEVELOPMENT}.
 * Released features follow their config switches as they always have. Features
 * in development are hard off in a release build: {@link #isOn()} returns false
 * whatever the config says, so a published jar can only ever light up what has
 * been finished and tested, and no edited config file can bring the rest back.
 * The config entries themselves are still written, so a config carries over
 * unchanged to the version where the feature ships.
 *
 * <h2>Which build is which</h2>
 * The switch is baked into the jar at build time, not read from the config.
 * A plain {@code gradlew build} makes a release build. A development build
 * needs {@code experimental_features=true}, either as {@code -P} on the command
 * line or in the gitignored {@code local.properties}, and it stamps
 * {@code -experimental} onto the version, which shows in the jar's name and in
 * the game's mod list. So a jar with in-development features in it cannot pass
 * for a release. See {@code build.gradle}.
 *
 * <p>If the flag cannot be read at all — the resource is missing, as in an IDE
 * run that skipped Gradle's resource processing, or was never expanded — the
 * build counts as a release. Failing closed means the worst case is a developer
 * wondering where a feature went, never a player being handed one.
 *
 * <h2>Moving a feature</h2>
 * Change its stage here; that is the whole job. Every check for a lighting
 * feature goes through this enum rather than reading its config switch
 * directly, so there is no second list to keep in step.
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
    /** The generic boss-bar pulse, which also covers modded bosses through {@code c:bosses}. */
    BOSS_ENCOUNTER(Stage.RELEASED, () -> RGBProfileConfig.BOSS_EVENTS_ENABLED.get()
            && RGBProfileConfig.BOSS_PROXIMITY_ENABLED.get()),
    WITHER(Stage.RELEASED, () -> RGBProfileConfig.WITHER_ENABLED.get()),
    ELDER_GUARDIAN(Stage.RELEASED, () -> RGBProfileConfig.ELDER_GUARDIAN_ENABLED.get()),
    END_DRAGON(Stage.RELEASED, () -> RGBProfileConfig.END_DRAGON_ENABLED.get()),
    END_RITUAL(Stage.RELEASED, () -> RGBProfileConfig.END_RITUAL_ENABLED.get()),

    // --- Other mods -------------------------------------------------------
    // With any of these held back, its boss still gets the generic boss-bar
    // pulse, so nothing goes dark: it just loses its dedicated room.
    NAGA(Stage.IN_DEVELOPMENT, () -> RGBProfileConfig.NAGA_ENABLED.get()),
    SLIDER(Stage.IN_DEVELOPMENT, () -> RGBProfileConfig.SLIDER_ENABLED.get()),
    SUN_SPIRIT(Stage.IN_DEVELOPMENT, () -> RGBProfileConfig.SUN_SPIRIT_ENABLED.get()),
    VALKYRIE_QUEEN(Stage.IN_DEVELOPMENT, () -> RGBProfileConfig.VALKYRIE_QUEEN_ENABLED.get()),
    /** Its reading of thirst and temperature is still a placeholder; see {@code ToughAsNailsCompat}. */
    TOUGH_AS_NAILS(Stage.IN_DEVELOPMENT, () -> RGBProfileConfig.TOUGH_AS_NAILS_ENABLED.get());

    public enum Stage {
        /** Finished and tested; follows its config switch. */
        RELEASED,
        /** Runs only in a development build; hard off in a release. */
        IN_DEVELOPMENT
    }

    /** Where the build flag lives inside the jar. Written by Gradle's resource processing. */
    private static final String BUILD_PROPERTIES = "/everythingrgbprofiles/build.properties";

    private final Stage stage;
    /**
     * Read through a lambda rather than holding the config value itself.
     * {@code RGBProfileConfig} builds its spec in a static initialiser that
     * refers back to this enum, and holding its fields here would make each
     * class's initialisation depend on the other having finished first.
     */
    private final BooleanSupplier configSwitch;

    Feature(Stage stage, BooleanSupplier configSwitch) {
        this.stage = stage;
        this.configSwitch = configSwitch;
    }

    public Stage stage() {
        return stage;
    }

    /** Whether this build can run the feature at all, whatever the config says. */
    public boolean isAvailable() {
        return stage == Stage.RELEASED || Build.EXPERIMENTAL;
    }

    /** Whether the feature should run right now: available in this build and switched on in the config. */
    public boolean isOn() {
        return isAvailable() && configSwitch.getAsBoolean();
    }

    /**
     * A config comment, with a note in front of it when this build will not
     * run the feature, so nobody reading the file wonders why a switch set to
     * true does nothing.
     */
    public String[] configComment(String comment) {
        if (isAvailable()) return new String[]{comment};
        return new String[]{
                "Not available in this version: this switch does nothing yet, and is kept so your "
                        + "config carries over to the version where it ships.",
                comment};
    }

    /** True when in-development features were built in. */
    public static boolean experimentalBuild() {
        return Build.EXPERIMENTAL;
    }

    /** Says in the log which kind of build this is, and what it is holding back. */
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

    /** The build flag, read once, in its own holder so it is loaded on first use. */
    private static final class Build {
        static final boolean EXPERIMENTAL = read();

        private static boolean read() {
            try (InputStream in = Feature.class.getResourceAsStream(BUILD_PROPERTIES)) {
                if (in == null) return false;
                Properties properties = new Properties();
                properties.load(in);
                // parseBoolean is false for anything but "true", which covers
                // a file Gradle never expanded as well as a missing key.
                return Boolean.parseBoolean(properties.getProperty("experimental_features", "false").trim());
            } catch (IOException e) {
                return false;
            }
        }
    }
}
