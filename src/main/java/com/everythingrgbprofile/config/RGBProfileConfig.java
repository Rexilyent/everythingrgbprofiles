package com.everythingrgbprofile.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Every knob, in one long static initialiser. This is what generates
 * {@code everythingrgbprofiles-common.toml}, section by section.
 *
 * <h2>Why COMMON and not CLIENT</h2>
 * Because a couple of toggles ({@code sculkAlertEnabled}) are read by the
 * optional server-side relay to decide whether listening is worth the
 * bother. Everything SDK- and lighting-related remains strictly client-only in
 * <i>behaviour</i>; it's only the config VALUES that are shared. A server
 * reading {@code portalDurationMillis} and doing nothing with it is harmless.
 *
 * <h2>Reading this file</h2>
 * Field declarations at the top, all the actual definitions in one static
 * block below. That's the shape {@code ModConfigSpec} imposes — the builder's
 * {@code push}/{@code pop} calls are what create the TOML section headers, so
 * the definitions must run in document order and every {@code push} needs its
 * matching {@code pop} or the sections nest in ways nobody intended.
 *
 * <h2>The comment strings are the actual documentation</h2>
 * The {@code b.comment(...)} text gets written into the generated TOML, which
 * is the only documentation most users will ever see. That's why several of
 * them run to a dozen lines and read like essays — a config comment that says
 * "sets the portal duration" is worthless next to one that says what the
 * number interacts with and what breaks if you get it wrong. If you add a
 * setting here, write the comment for the person editing the file at midnight,
 * not for the person who already knows the answer.
 *
 * <p>Every numeric setting uses {@code defineInRange}, so an out-of-range
 * value is clamped and reported by NeoForge rather than silently producing
 * something absurd.
 */
public final class RGBProfileConfig {


    // --- Device classes -----------------------------------------------------
    public static final ModConfigSpec.BooleanValue DEVICE_KEYBOARD_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_MOUSE_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_MOUSEMAT_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_HEADSET_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_MEMORY_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_COOLING_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_MOTHERBOARD_ENABLED;
    public static final ModConfigSpec.BooleanValue DEVICE_OTHER_ENABLED;

    public static final ModConfigSpec.BooleanValue PORTAL_CHARGE_UP_ENABLED;
    public static final ModConfigSpec.IntValue PORTAL_CHARGE_RAMP_MILLIS;
    public static final ModConfigSpec.IntValue PORTAL_SUPPRESSION_FLOOR;
    public static final ModConfigSpec.DoubleValue PORTAL_CHARGE_INTENSITY_FLOOR;
    public static final ModConfigSpec.BooleanValue PORTAL_DEBUG_LOGGING;
    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;
    public static final ModConfigSpec.BooleanValue DEBUG_LOG_TRANSITIONS;
    public static final ModConfigSpec.IntValue DEBUG_HEALTH_INTERVAL_SECONDS;
    public static final ModConfigSpec.BooleanValue DEBUG_CHAT_NOTICES;

    public static final ModConfigSpec SPEC;

    // [general]
    public static final ModConfigSpec.BooleanValue GENERAL_ENABLED;

    // [features]
    public static final ModConfigSpec.BooleanValue HEALTH_FLASH_ENABLED;
    public static final ModConfigSpec.BooleanValue NIGHT_INDICATOR_ENABLED;
    public static final ModConfigSpec.BooleanValue BIOME_COLORS_ENABLED;
    public static final ModConfigSpec.BooleanValue BOSS_EVENTS_ENABLED;
    public static final ModConfigSpec.BooleanValue HUNGER_WARNING_ENABLED;
    public static final ModConfigSpec.BooleanValue DEATH_FLASH_ENABLED;
    public static final ModConfigSpec.BooleanValue PROGRESSION_EFFECTS_ENABLED;
    public static final ModConfigSpec.BooleanValue RAIN_THUNDERSTORM_ENABLED;
    public static final ModConfigSpec.BooleanValue TOUGH_AS_NAILS_ENABLED;
    public static final ModConfigSpec.BooleanValue PORTAL_TRANSITION_ENABLED;
    public static final ModConfigSpec.BooleanValue RAID_WARNING_ENABLED;
    public static final ModConfigSpec.BooleanValue SCULK_ALERT_ENABLED;
    public static final ModConfigSpec.BooleanValue SLEEP_WAKE_ENABLED;
    public static final ModConfigSpec.BooleanValue WARDEN_ENCOUNTER_ENABLED;

    // [healthFlash]
    public static final ModConfigSpec.IntValue HEALTH_THRESHOLD_PERCENT;
    public static final ModConfigSpec.ConfigValue<String> HEALTH_FLASH_KEY;

    // [hungerWarning]
    public static final ModConfigSpec.IntValue HUNGER_THRESHOLD_PERCENT;
    public static final ModConfigSpec.ConfigValue<String> HUNGER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> HUNGER_FLASH_KEY;

    // [deathFlash]
    public static final ModConfigSpec.IntValue DEATH_FLASH_DURATION_MILLIS;
    public static final ModConfigSpec.ConfigValue<String> DEATH_FLASH_COLOR;
    public static final ModConfigSpec.BooleanValue DEATH_BLOOD_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> DEATH_BLOOD_SOAK_COLOR;
    public static final ModConfigSpec.ConfigValue<String> DEATH_BLOOD_COLOR;
    public static final ModConfigSpec.IntValue DEATH_BLOOD_DRIP_COUNT;
    public static final ModConfigSpec.IntValue DEATH_BLOOD_DRIP_INTERVAL_MILLIS;

    // [nightIndicator]
    public static final ModConfigSpec.ConfigValue<String> LIGHTING_BACKEND;
    public static final ModConfigSpec.ConfigValue<String> OPENRGB_HOST;
    public static final ModConfigSpec.IntValue OPENRGB_PORT;
    public static final ModConfigSpec.BooleanValue WITHER_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> WITHER_BONE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> WITHER_EYE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> WITHER_POWERED_COLOR;
    public static final ModConfigSpec.IntValue WITHER_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue WITHER_GRACE_MILLIS;
    public static final ModConfigSpec.BooleanValue NAGA_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> NAGA_SCALE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> NAGA_HIGHLIGHT_COLOR;
    public static final ModConfigSpec.IntValue NAGA_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue NAGA_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue NAGA_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue SLIDER_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SLIDER_STONE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SLIDER_RUNE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SLIDER_CRITICAL_COLOR;
    public static final ModConfigSpec.IntValue SLIDER_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue SLIDER_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue SLIDER_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue SUN_SPIRIT_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SUN_SPIRIT_SUN_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SUN_SPIRIT_FLAME_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SUN_SPIRIT_ICE_COLOR;
    public static final ModConfigSpec.IntValue SUN_SPIRIT_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue SUN_SPIRIT_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue SUN_SPIRIT_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue VALKYRIE_QUEEN_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> VALKYRIE_QUEEN_SILVER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> VALKYRIE_QUEEN_GOLD_COLOR;
    public static final ModConfigSpec.ConfigValue<String> VALKYRIE_QUEEN_LIGHTNING_COLOR;
    public static final ModConfigSpec.IntValue VALKYRIE_QUEEN_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue VALKYRIE_QUEEN_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue VALKYRIE_QUEEN_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue ELDER_GUARDIAN_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> ELDER_GUARDIAN_WATER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> ELDER_GUARDIAN_EYE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> ELDER_GUARDIAN_BEAM_COLOR;
    public static final ModConfigSpec.IntValue ELDER_GUARDIAN_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue ELDER_GUARDIAN_GRACE_MILLIS;
    public static final ModConfigSpec.IntValue ELDER_GUARDIAN_FADE_MILLIS;
    public static final ModConfigSpec.BooleanValue END_DRAGON_ENABLED;
    public static final ModConfigSpec.BooleanValue END_RITUAL_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> END_VOID_COLOR;
    public static final ModConfigSpec.ConfigValue<String> END_ACCENT_COLOR;
    public static final ModConfigSpec.ConfigValue<String> END_BREATH_FIRE_COLOR;
    public static final ModConfigSpec.BooleanValue END_TRACE_RITUAL;
    public static final ModConfigSpec.BooleanValue DROWNING_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> DROWNING_DEEP_COLOR;
    public static final ModConfigSpec.ConfigValue<String> DROWNING_SURFACE_COLOR;
    public static final ModConfigSpec.DoubleValue DROWNING_PANIC_THRESHOLD;
    public static final ModConfigSpec.BooleanValue BURNING_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> BURNING_CORE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> BURNING_TIP_COLOR;
    public static final ModConfigSpec.DoubleValue BURNING_PANIC_THRESHOLD;
    public static final ModConfigSpec.ConfigValue<String> NIGHT_INDICATOR_COLOR;
    public static final ModConfigSpec.BooleanValue NIGHT_STAND_DOWN_FOR_ENCOUNTERS;

    // [progressionEffects]
    public static final ModConfigSpec.BooleanValue LEVEL_UP_ENABLED;
    public static final ModConfigSpec.BooleanValue ADVANCEMENT_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> LEVEL_UP_COLOR;
    public static final ModConfigSpec.ConfigValue<String> LEVEL_UP_BAR_COLOR;
    public static final ModConfigSpec.ConfigValue<String> ADVANCEMENT_COLOR;

    // [portalTransition]
    public static final ModConfigSpec.BooleanValue PORTAL_USE_CUSTOM_JSON;
    public static final ModConfigSpec.BooleanValue PORTAL_FALLBACK_TO_DERIVED_COLOR;
    public static final ModConfigSpec.IntValue PORTAL_DURATION_MILLIS;
    public static final ModConfigSpec.DoubleValue PORTAL_HOLD_FRACTION;
    public static final ModConfigSpec.BooleanValue PORTAL_HOLD_UNTIL_WORLD_READY;
    public static final ModConfigSpec.IntValue PORTAL_POST_ARRIVAL_HOLD_MILLIS;
    public static final ModConfigSpec.IntValue PORTAL_MAX_DURATION_MILLIS;
    public static final ModConfigSpec.DoubleValue PORTAL_SPIRAL_ROTATIONS;

    // [raidWarning]
    public static final ModConfigSpec.ConfigValue<String> RAID_COLOR;
    public static final ModConfigSpec.ConfigValue<String> RAID_RAIDER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> RAID_VICTORY_COLOR;

    // [sculkAlert]
    public static final ModConfigSpec.BooleanValue SCULK_SENSOR_ENABLED;
    public static final ModConfigSpec.BooleanValue SCULK_SHRIEKER_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SCULK_SENSOR_COLOR;
    public static final ModConfigSpec.IntValue SCULK_SENSOR_DURATION_MILLIS;
    public static final ModConfigSpec.DoubleValue SCULK_SENSOR_RING_THICKNESS_KEYS;
    public static final ModConfigSpec.ConfigValue<String> SCULK_SHRIEKER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> SCULK_SHRIEKER_PEAK_COLOR;
    public static final ModConfigSpec.IntValue SCULK_SHRIEKER_DURATION_MILLIS;
    public static final ModConfigSpec.DoubleValue SCULK_SHRIEKER_RING_THICKNESS_KEYS;
    public static final ModConfigSpec.IntValue SCULK_SHRIEKER_MAX_ESCALATION_LEVEL;
    public static final ModConfigSpec.IntValue SCULK_SHRIEKER_ESCALATION_WINDOW_MILLIS;
    public static final ModConfigSpec.IntValue SCULK_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue SCULK_SCAN_INTERVAL_TICKS;

    // [sleepWake]
    public static final ModConfigSpec.ConfigValue<String> SLEEP_WAKE_COLOR;
    public static final ModConfigSpec.IntValue SLEEP_WAKE_DURATION_MILLIS;

    // [biomeColors]
    public static final ModConfigSpec.BooleanValue BIOME_USE_CUSTOM_JSON;
    public static final ModConfigSpec.BooleanValue BIOME_FALLBACK_TO_DERIVED_COLOR;

    // [bossEvents]
    public static final ModConfigSpec.BooleanValue BOSS_USE_CUSTOM_JSON;
    public static final ModConfigSpec.BooleanValue BOSS_FALLBACK_TO_BOSSBAR_COLOR;
    public static final ModConfigSpec.BooleanValue BOSS_ENRAGE_INTENSITY_SCALING;
    public static final ModConfigSpec.BooleanValue BOSS_LEGENDARY_MONSTERS_INTEGRATION;
    public static final ModConfigSpec.BooleanValue BOSS_PROXIMITY_ENABLED;
    public static final ModConfigSpec.BooleanValue BOSS_USE_BOSSES_TAG;
    public static final ModConfigSpec.IntValue BOSS_DETECTION_RADIUS;
    public static final ModConfigSpec.DoubleValue BOSS_MIN_MAX_HEALTH;
    public static final ModConfigSpec.IntValue BOSS_GRACE_MILLIS;
    public static final ModConfigSpec.ConfigValue<String> BOSS_EXCLUDED;

    // [rainThunderstorm]
    public static final ModConfigSpec.ConfigValue<String> RAIN_COLOR;
    public static final ModConfigSpec.IntValue RAIN_PARTICLE_COUNT;
    public static final ModConfigSpec.IntValue RAIN_PARTICLE_LIFESPAN_MILLIS;
    public static final ModConfigSpec.DoubleValue RAIN_LAYER_OPACITY;
    public static final ModConfigSpec.IntValue RAIN_SHELTER_GRACE_MILLIS;
    public static final ModConfigSpec.BooleanValue RAIN_REQUIRE_SKY_EXPOSURE;
    public static final ModConfigSpec.ConfigValue<String> LIGHTNING_COLOR;
    public static final ModConfigSpec.IntValue LIGHTNING_FLASH_DURATION_MILLIS;
    public static final ModConfigSpec.IntValue LIGHTNING_DETECTION_RADIUS;
    public static final ModConfigSpec.IntValue LIGHTNING_FALLBACK_INTERVAL_MILLIS;

    // [toughAsNails]
    public static final ModConfigSpec.IntValue THIRST_THRESHOLD_PERCENT;
    public static final ModConfigSpec.ConfigValue<String> THIRST_COLOR;
    public static final ModConfigSpec.ConfigValue<String> THIRST_FLASH_KEY;
    public static final ModConfigSpec.ConfigValue<String> TEMPERATURE_FLASH_KEY;
    public static final ModConfigSpec.ConfigValue<String> OVERHEATING_COLOR;
    public static final ModConfigSpec.ConfigValue<String> FREEZING_COLOR;

    // [toughAsNails.seasonalTint]
    public static final ModConfigSpec.BooleanValue SEASONAL_TINT_ENABLED;
    public static final ModConfigSpec.DoubleValue SEASONAL_TINT_INTENSITY;

    // [wardenEncounter]
    public static final ModConfigSpec.IntValue WARDEN_DETECTION_RADIUS;
    public static final ModConfigSpec.ConfigValue<String> WARDEN_EMERGENCE_COLOR;
    public static final ModConfigSpec.BooleanValue WARDEN_EMERGENCE_HOLD_UNTIL_RISEN;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_PULSE_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_MIN_HOLD_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_FADE_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_MAX_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_NOMINAL_MILLIS;
    public static final ModConfigSpec.IntValue WARDEN_EMERGENCE_SUPPRESSION_FLOOR;
    public static final ModConfigSpec.ConfigValue<String> WARDEN_PRESENCE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> WARDEN_PRESENCE_GLINT_COLOR;
    public static final ModConfigSpec.IntValue WARDEN_ACTIVE_TWINKLE_COUNT;
    public static final ModConfigSpec.IntValue WARDEN_ACTIVE_TWINKLE_INTERVAL_MILLIS;

    // [menuTheme]
    public static final ModConfigSpec.BooleanValue MENU_THEME_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> MENU_STYLE;
    public static final ModConfigSpec.ConfigValue<String> MENU_PACK_PATTERNS;
    public static final ModConfigSpec.ConfigValue<String> MENU_VANILLA_SKY_COLOR;
    public static final ModConfigSpec.ConfigValue<String> MENU_VANILLA_GRASS_COLOR;
    public static final ModConfigSpec.IntValue MENU_VANILLA_CLOUD_COUNT;
    public static final ModConfigSpec.ConfigValue<String> MENU_SULFUR_ROCK_COLOR;
    public static final ModConfigSpec.ConfigValue<String> MENU_SULFUR_POOL_COLOR;
    public static final ModConfigSpec.ConfigValue<String> MENU_BASE_COLOR;
    public static final ModConfigSpec.ConfigValue<String> MENU_EMBER_COLOR;
    public static final ModConfigSpec.ConfigValue<String> MENU_ARCANE_COLOR;
    public static final ModConfigSpec.IntValue MENU_SPARK_COUNT;
    public static final ModConfigSpec.IntValue MENU_SPARK_INTERVAL_MILLIS;
    public static final ModConfigSpec.IntValue MENU_ORE_GLINT_COUNT;
    public static final ModConfigSpec.IntValue MENU_ORE_GLINT_INTERVAL_MILLIS;
    public static final ModConfigSpec.IntValue MENU_TORCH_COUNT;

    // [advanced]
    public static final ModConfigSpec.IntValue UPDATE_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue ANIMATION_FRAME_RATE_HZ;
    public static final ModConfigSpec.IntValue DEBOUNCE_MILLIS;
    public static final ModConfigSpec.IntValue BIOME_SAMPLE_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue BIOME_CROSSFADE_MILLIS;

    static {
        // ONE builder, threaded through every section in order. push() opens a
        // TOML table, pop() closes it — miss a pop and everything after it
        // ends up nested inside the previous section, which the game will load
        // perfectly happily and which will confuse everyone forever.
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("general");
        GENERAL_ENABLED = b.comment("Master kill switch for the whole mod.").define("enabled", true);
        b.pop();

        // Per-feature switches. All default true: someone who installs a
        // reactive-lighting mod wants the reactive lighting. These exist so a
        // specific effect that clashes with someone's setup can be turned off
        // without losing the rest.
        b.push("features");
        HEALTH_FLASH_ENABLED = b.define("healthFlashEnabled", true);
        NIGHT_INDICATOR_ENABLED = b.define("nightIndicatorEnabled", true);
        BIOME_COLORS_ENABLED = b.define("biomeColorsEnabled", true);
        BOSS_EVENTS_ENABLED = b.define("bossEventsEnabled", true);
        HUNGER_WARNING_ENABLED = b.define("hungerWarningEnabled", true);
        DEATH_FLASH_ENABLED = b.define("deathFlashEnabled", true);
        PROGRESSION_EFFECTS_ENABLED = b.define("progressionEffectsEnabled", true);
        RAIN_THUNDERSTORM_ENABLED = b.define("rainThunderstormEnabled", true);
        TOUGH_AS_NAILS_ENABLED = b.comment(Feature.TOUGH_AS_NAILS.configComment(
                        "Master switch for the Tough As Nails thirst/temperature effects; no-op if that mod isn't installed."))
                .define("toughAsNailsEnabled", true);
        PORTAL_TRANSITION_ENABLED = b.define("portalTransitionEnabled", true);
        RAID_WARNING_ENABLED = b.define("raidWarningEnabled", true);
        SCULK_ALERT_ENABLED = b.define("sculkAlertEnabled", true);
        SLEEP_WAKE_ENABLED = b.define("sleepWakeEnabled", true);
        WARDEN_ENCOUNTER_ENABLED = b.define("wardenEncounterEnabled", true);
        b.pop();

        b.push("healthFlash");
        HEALTH_THRESHOLD_PERCENT = b.defineInRange("thresholdPercent", 25, 1, 99);
        HEALTH_FLASH_KEY = b.define("flashKey", "H");
        b.pop();

        b.push("hungerWarning");
        HUNGER_THRESHOLD_PERCENT = b.defineInRange("thresholdPercent", 30, 1, 99);
        HUNGER_COLOR = b.define("color", "#FFA500");
        HUNGER_FLASH_KEY = b.define("flashKey", "F");
        b.pop();

        b.push("deathFlash");
        DEATH_FLASH_DURATION_MILLIS = b.defineInRange("durationMillis", 800, 50, 10000);
        DEATH_FLASH_COLOR = b.comment("The momentary whole-board flash at the moment of death.")
                .define("flashColor", "#FF1A1A");
        DEATH_BLOOD_ENABLED = b.comment(
                        "After the flash, hold blood running down the board until you respawn or quit. "
                                + "While it is up nothing else draws at all -- not the biome, not rain, "
                                + "not the moon -- because a moon calmly tracking across a death "
                                + "screen is absurd.")
                .define("bloodDripEnabled", true);
        DEATH_BLOOD_SOAK_COLOR = b.comment("The soaked board under the drips.")
                .define("bloodSoakColor", "#2E0709");
        DEATH_BLOOD_COLOR = b.comment("The drips themselves, and the streaks they leave behind.")
                .define("bloodColor", "#C81020");
        DEATH_BLOOD_DRIP_COUNT = b.comment("Drips running at once.")
                .defineInRange("bloodDripCount", 7, 1, 48);
        DEATH_BLOOD_DRIP_INTERVAL_MILLIS = b.comment("Average gap before a spent drip slot starts another.")
                .defineInRange("bloodDripIntervalMillis", 900, 80, 10000);
        b.pop();

        b.push("hardware");
        LIGHTING_BACKEND = b.comment(
                        "Which lighting software to drive. Nothing extra needs installing: each option "
                                + "uses the software that already came with your hardware.",
                        "  auto        - connect to everything that answers (default). The first one "
                                + "with a keyboard leads; the rest show the same effects.",
                        "  corsair     - Corsair iCUE",
                        "  razer       - Razer Synapse, with its Chroma module installed",
                        "  logitech    - Logitech G HUB",
                        "  steelseries - SteelSeries GG (GameSense)",
                        "  openrgb     - OpenRGB's SDK server, for hardware none of the above cover "
                                + "(ASUS, MSI, and a long tail of others).",
                        "Pick one explicitly if auto chooses the wrong device. An unrecognised value "
                                + "falls back to auto with a warning rather than refusing to start.")
                .define("lightingBackend", "auto");
        OPENRGB_HOST = b.comment("Where the OpenRGB SDK server is listening. Enable it in OpenRGB under SDK Server.")
                .define("openRgbHost", "127.0.0.1");
        OPENRGB_PORT = b.comment("OpenRGB SDK server port.")
                .defineInRange("openRgbPort", 6742, 1, 65535);
        b.pop();

        b.push("wither");
        WITHER_ENABLED = b.comment(
                        "Three skulls over a spine, tracking separately and spitting. Includes the "
                                + "eleven-second summon charge, and reddens when it armours up below "
                                + "half health.")
                .define("enabled", true);
        WITHER_BONE_COLOR = b.comment("Skull and spine.").define("boneColor", "#4A4652");
        WITHER_EYE_COLOR = b.comment("Eyes, the summon charge, and the skulls it throws.")
                .define("eyeColor", "#A8DCF5");
        WITHER_POWERED_COLOR = b.comment("Blended in below half health, when the armour goes on.")
                .define("poweredColor", "#B02436");
        WITHER_DETECTION_RADIUS = b.comment("Blocks. It flies, and it retreats when hurt.")
                .defineInRange("detectionRadius", 96, 16, 256);
        WITHER_GRACE_MILLIS = b.comment("How long the board is held after it goes out of sight.")
                .defineInRange("graceMillis", 5000, 0, 60000);
        b.pop();

        b.push("naga");
        NAGA_ENABLED = b.comment(Feature.NAGA.configComment(
                        "Twilight Forest's Naga gets a serpent drawn on the keys instead of a generic "
                                + "boss pulse, and it sheds body segments as you damage it — the same "
                                + "way the real one does. Costs nothing when Twilight Forest is absent."))
                .define("enabled", true);
        NAGA_SCALE_COLOR = b.comment("The body.").define("scaleColor", "#2F8F3A");
        NAGA_HIGHLIGHT_COLOR = b.comment("The head, so you can tell which end is coming at you.")
                .define("highlightColor", "#B6F04A");
        NAGA_DETECTION_RADIUS = b.comment(
                        "How far the Naga can be and still own the board. Its courtyard is large and it "
                                + "roams the whole thing, so a tight radius drops the effect while you "
                                + "are very much still in the fight. 128 is about the limit of what the "
                                + "client tracks anyway.")
                .defineInRange("detectionRadius", 128, 16, 256);
        NAGA_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after the Naga stops being visible. Covers it "
                                + "ducking behind the hedge or briefly leaving entity tracking, neither "
                                + "of which means the fight is over.")
                .defineInRange("graceMillis", 5000, 0, 60000);
        NAGA_FADE_MILLIS = b.comment("How long the snake takes to dissolve back into the biome once it really is gone.")
                .defineInRange("fadeOutMillis", 1400, 100, 10000);
        b.pop();

        b.push("slider");
        SLIDER_ENABLED = b.comment(Feature.SLIDER.configComment(
                        "The Aether's Slider gets its room drawn on the keys instead of a generic boss "
                                + "pulse: the board is the boss room seen from above with north at the "
                                + "top, the cube slides and slams where the real one does, and a gold "
                                + "dot marks where you are standing. Costs nothing when the Aether is absent."))
                .define("enabled", true);
        SLIDER_STONE_COLOR = b.comment("The cube, the dust it throws up, and the dungeon floor.")
                .define("stoneColor", "#6F7B8C");
        SLIDER_RUNE_COLOR = b.comment("The runes on the cube while it is awake.")
                .define("runeColor", "#3A86FF");
        SLIDER_CRITICAL_COLOR = b.comment("The runes below a quarter health, where the real one turns red and speeds up.")
                .define("criticalColor", "#FF3308");
        SLIDER_DETECTION_RADIUS = b.comment(
                        "Blocks. The same as the generic boss detector's radius by default, so that "
                                + "anywhere a sleeping Slider would start the generic pulse, this "
                                + "takes the board instead.")
                .defineInRange("detectionRadius", 48, 8, 128);
        SLIDER_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after the Slider drops out of range. Short, "
                                + "because the Slider never leaves its room: losing sight of it means "
                                + "you left, not that it did.")
                .defineInRange("graceMillis", 3000, 0, 60000);
        SLIDER_FADE_MILLIS = b.comment("How long the room takes to dissolve back into the biome once it is gone.")
                .defineInRange("fadeOutMillis", 1200, 100, 10000);
        b.pop();

        b.push("sunSpirit");
        SUN_SPIRIT_ENABLED = b.comment(Feature.SUN_SPIRIT.configComment(
                        "The Aether's Sun Spirit gets its boss room drawn on the keys instead of a generic "
                                + "boss pulse: the board is the room seen from above with north at the top, "
                                + "the spirit is a burning sun where the real one flies, and the fire on the "
                                + "floor and the crystals it throws are drawn where they really are. When "
                                + "an ice crystal freezes it, it turns blue and a ring counts down the time "
                                + "you have to hit it. Costs nothing when the Aether is absent."))
                .define("enabled", true);
        SUN_SPIRIT_SUN_COLOR = b.comment("The spirit's body.").define("sunColor", "#FFA012");
        SUN_SPIRIT_FLAME_COLOR = b.comment("Its corona, the fire on the floor, the fire crystals, and the room's glow.")
                .define("flameColor", "#E0460F");
        SUN_SPIRIT_ICE_COLOR = b.comment("Frozen, the countdown ring, and the ice crystals.")
                .define("iceColor", "#3FB4F0");
        SUN_SPIRIT_DETECTION_RADIUS = b.comment(
                        "Blocks. The same as the generic boss detector's radius by default, so this "
                                + "always takes the board where the generic pulse would have.")
                .defineInRange("detectionRadius", 48, 8, 128);
        SUN_SPIRIT_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after the spirit drops out of range. Short, because "
                                + "it never leaves its room.")
                .defineInRange("graceMillis", 3000, 0, 60000);
        SUN_SPIRIT_FADE_MILLIS = b.comment("How long the room takes to dissolve back into the biome once it is gone.")
                .defineInRange("fadeOutMillis", 1500, 100, 10000);
        b.pop();

        b.push("valkyrieQueen");
        VALKYRIE_QUEEN_ENABLED = b.comment(Feature.VALKYRIE_QUEEN.configComment(
                        "The Aether's Valkyrie Queen gets her throne room drawn on the keys instead of a "
                                + "generic boss pulse: the board is the room seen from above with north at the "
                                + "top, and she is a winged figure where the real one is, turned toward you, "
                                + "with her wings folded, open or swept into a lunge. Her teleports, the "
                                + "thunder crystals she throws and the lightning they become are drawn where "
                                + "they happen, and each crystal's crackle speeds up as its fuse runs out. "
                                + "Costs nothing when the Aether is absent."))
                .define("enabled", true);
        VALKYRIE_QUEEN_SILVER_COLOR = b.comment("Her body and wings, and the puff she leaves when she teleports.")
                .define("silverColor", "#B8C4FF");
        VALKYRIE_QUEEN_GOLD_COLOR = b.comment("The room's glow, the fight starting, and the room opening when she is beaten.")
                .define("goldColor", "#FFB12E");
        VALKYRIE_QUEEN_LIGHTNING_COLOR = b.comment("Thunder crystals, the reach of their lightning, and the strikes themselves.")
                .define("lightningColor", "#1FD8FF");
        VALKYRIE_QUEEN_DETECTION_RADIUS = b.comment(
                        "Blocks. The same as the generic boss detector's radius by default, so this "
                                + "always takes the board where the generic pulse would have.")
                .defineInRange("detectionRadius", 48, 8, 128);
        VALKYRIE_QUEEN_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after she drops out of range. Short, because she "
                                + "never leaves her room.")
                .defineInRange("graceMillis", 3000, 0, 60000);
        VALKYRIE_QUEEN_FADE_MILLIS = b.comment("How long the room takes to dissolve back into the biome once she is gone.")
                .defineInRange("fadeOutMillis", 1500, 100, 10000);
        b.pop();

        b.push("elderGuardian");
        ELDER_GUARDIAN_ENABLED = b.comment(
                        "Elder Guardians get an eye drawn on the keys instead of a generic boss pulse: "
                                + "the body with its crown of spikes, and the laser winding up. The "
                                + "wind-up is the useful part — it runs for a full three seconds, which "
                                + "is your window to break line of sight, and the board is in step with "
                                + "the real one rather than guessing.")
                .define("enabled", true);
        ELDER_GUARDIAN_WATER_COLOR = b.comment("The body, its spikes, and the monument water behind them.")
                .define("waterColor", "#2E8F87");
        ELDER_GUARDIAN_EYE_COLOR = b.comment("The eye at rest.").define("eyeColor", "#F2A23C");
        ELDER_GUARDIAN_BEAM_COLOR = b.comment(
                        "The laser. The eye runs from its own colour into this one and then into white "
                                + "across the wind-up, so this is what 'about to fire' looks like.")
                .define("beamColor", "#B453E8");
        ELDER_GUARDIAN_DETECTION_RADIUS = b.comment(
                        "How far one can be and still own the board. 48 covers a monument room and "
                                + "roughly matches the 50 blocks from which it curses you, so the board "
                                + "is showing a Guardian whenever one can actually reach you.")
                .defineInRange("detectionRadius", 48, 8, 128);
        ELDER_GUARDIAN_GRACE_MILLIS = b.comment(
                        "How long it keeps the board after the last one drops out of range. Monuments "
                                + "are full of walls, and swimming behind one does not end the fight.")
                .defineInRange("graceMillis", 4000, 0, 60000);
        ELDER_GUARDIAN_FADE_MILLIS = b.comment("How long the eye takes to dissolve back into the biome once they really are gone.")
                .defineInRange("fadeOutMillis", 1600, 100, 10000);
        b.pop();

        b.push("enderDragon");
        END_DRAGON_ENABLED = b.comment(
                        "Lighting for the Ender Dragon: the summoning ritual, the fight itself (phase "
                                + "aware, not just health), and the death sequence.")
                .define("enabled", true);
        END_RITUAL_ENABLED = b.comment(
                        "Show the summoning ritual as rays converging on the middle of the board, one "
                                + "per end crystal currently firing a beam. Works for vanilla's "
                                + "four-crystal respawn and for YUNG's Better End Island's staged "
                                + "pillar ritual alike, because both are made of the same beams.")
                .define("ritualEnabled", true);
        END_VOID_COLOR = b.comment("Resting colour for the whole End sequence.")
                .define("voidColor", "#5B2C8F");
        END_ACCENT_COLOR = b.comment("Beams, breath and the death rings.")
                .define("accentColor", "#D34DFF");
        END_BREATH_FIRE_COLOR = b.comment(
                        "Dragon's breath burning along the bottom of the board. This is the colour at "
                                + "the flame TIPS; the base is a lightened version of it, because fire "
                                + "cools as it rises and that gradient is what makes the ragged top edge "
                                + "read as tongues.")
                .define("breathFireColor", "#8B2FE0");
        END_TRACE_RITUAL = b.comment(
                        "Writes a transcript of the summoning ritual to the log: every end crystal, "
                                + "where it is (distance and bearing from origin, which identifies which "
                                + "pillar), when its beam turns on and off, and where the beam points. "
                                + "The ritual lighting was fitted to transcripts like this one; turn it "
                                + "on for one respawn if the rays look out of step with what you see, "
                                + "and include the log when reporting it. Also follows the master "
                                + "debug switch. Off by default: it is noisy on purpose.")
                .define("traceRitual", false);
        b.pop();

        b.push("drowning");
        DROWNING_ENABLED = b.comment(
                        "Floods the keyboard from the bottom up as your breath runs out, and drains it "
                                + "again when you surface. Overlays the biome rather than replacing it, "
                                + "so the waterline reads as a level.")
                .define("enabled", true);
        DROWNING_DEEP_COLOR = b.comment("Deep water, at the bottom of the board.")
                .define("deepColor", "#0E3FA8");
        DROWNING_SURFACE_COLOR = b.comment("The waterline itself, and the shallows just under it.")
                .define("surfaceColor", "#5FC8E8");
        DROWNING_PANIC_THRESHOLD = b.comment(
                        "How full the board gets before the water starts pulsing. 0.85 means the last "
                                + "15% of your air. Set to 1.0 for no pulse at all.")
                .defineInRange("panicThreshold", 0.85, 0.0, 1.0);
        b.pop();

        b.push("burning");
        BURNING_ENABLED = b.comment(Feature.BURNING.configComment(
                        "Sets the keyboard alight from the bottom up while you are on fire, and lets it die "
                                + "down when the fire goes out. The flames are low if Fire Resistance is "
                                + "protecting you, about half the board while you burn, most of it while you "
                                + "stand in fire, and all of it in lava. Overlays the biome rather than "
                                + "replacing it, like the drowning water."))
                .define("enabled", true);
        BURNING_CORE_COLOR = b.comment("The base of the flames, where they are hottest.")
                .define("coreColor", "#FFB01F");
        BURNING_TIP_COLOR = b.comment("The tips of the flames.")
                .define("tipColor", "#E8350C");
        BURNING_PANIC_THRESHOLD = b.comment(
                        "How high the flames get before the whole fire starts surging. The default is "
                                + "only reached in lava. Set to 1.0 for no surge at all.")
                .defineInRange("panicThreshold", 0.9, 0.0, 1.0);
        b.pop();

        b.push("nightIndicator");
        NIGHT_STAND_DOWN_FOR_ENCOUNTERS = b.comment(
                        "Hide the moon while a boss or encounter layer owns the board. On by default: "
                                + "those layers draw a whole scene, and a moon calmly tracking sunset "
                                + "across a dragon reads as a bug rather than as a second layer.",
                        "Health, hunger and drowning warnings are unaffected — a boss fight is exactly "
                                + "when you want those.")
                .define("standDownForEncounters", true);
        NIGHT_INDICATOR_COLOR = b.define("color", "#C8D6FF");
        b.pop();

        b.push("progressionEffects");
        LEVEL_UP_ENABLED = b.define("levelUpEnabled", true);
        ADVANCEMENT_ENABLED = b.define("advancementEnabled", true);
        LEVEL_UP_COLOR = b.comment("The level-up burst: the gold wave and the sparkles.")
                .define("levelUpColor", "#FFD700");
        LEVEL_UP_BAR_COLOR = b.comment("The experience bar that fills along the bottom of the board before it bursts.")
                .define("levelUpBarColor", "#80FF20");
        ADVANCEMENT_COLOR = b.define("advancementColor", "#00E5FF");
        b.pop();

        b.push("portalTransition");
        PORTAL_USE_CUSTOM_JSON = b.define("useCustomJsonProfiles", true);
        PORTAL_FALLBACK_TO_DERIVED_COLOR = b.define("fallbackToDerivedColor", true);
        // One full rotation of the fitted portal field is 1420ms. The arrival
        // no longer contains a flash of any kind: it holds the spinning field
        // and then crossfades down into the biome layer. With
        // holdUntilWorldReady on (the default) durationMillis/holdFraction
        // only set the MINIMUM hold and the fade length -- the fade itself is
        // anchored to the end of the terrain load, not to a wall clock.
        PORTAL_DURATION_MILLIS = b.comment(
                        "Baseline arrival length, minimum hold plus fade-out. One rotation of the portal",
                        "spiral is 1420ms, so values below ~1500 will not show a complete turn.",
                        "With holdUntilWorldReady = true this is a floor, not a total: the hold is",
                        "extended for as long as the terrain load actually takes.")
                .defineInRange("durationMillis", 4200, 100, 30000);
        PORTAL_HOLD_FRACTION = b.comment(
                        "Split of durationMillis between hold and fade. At the default 4200ms and 0.5",
                        "that is a 2100ms minimum hold (about 1.5 rotations) and a 2100ms dissolve.",
                        "Raise this to shorten the dissolve, lower it to lengthen it.",
                        "Replaces the old spiralPhaseFraction, which gated an arrival flash that no",
                        "longer exists -- delete that key from your config, it is ignored.")
                .defineInRange("holdFraction", 0.5, 0.0, 0.95);
        PORTAL_HOLD_UNTIL_WORLD_READY = b.comment(
                        "Keep the field at full until the destination has actually finished loading,",
                        "instead of running a fixed stopwatch from the moment of transfer.",
                        "",
                        "The dimension change is detected as early as it can be seen, which is before",
                        "the client spends one to several seconds rebuilding chunks and shaders. The",
                        "lighting runs on its own thread, so a fixed budget was being spent entirely",
                        "inside that stall: the board was already dark by the time \"Downloading terrain\"",
                        "cleared and the player could see anything. There is no good fixed number for",
                        "this -- the load is however long the pack makes it.",
                        "",
                        "On means: hold through the load, then hold postArrivalHoldMillis longer once",
                        "you are in the world, then dissolve into the biome layer. Off restores the old",
                        "fixed-stopwatch behaviour.")
                .define("holdUntilWorldReady", true);
        PORTAL_POST_ARRIVAL_HOLD_MILLIS = b.comment(
                        "How long the field stays at full AFTER the terrain screen clears, before the",
                        "dissolve starts. This is the part of the arrival the player actually watches,",
                        "so it is worth more than it looks. Ignored when holdUntilWorldReady is false.")
                .defineInRange("postArrivalHoldMillis", 1200, 0, 15000);
        PORTAL_MAX_DURATION_MILLIS = b.comment(
                        "Hard ceiling on the whole arrival, so a load that never completes cannot leave",
                        "the spiral spinning forever. Only reached if the terrain screen never clears;",
                        "30000 matches vanilla's own timeout on that screen. The fade is never",
                        "truncated by this -- the hold is shortened to make room for it.")
                .defineInRange("maxDurationMillis", 30000, 1000, 120000);
        PORTAL_CHARGE_UP_ENABLED = b.comment(
                        "Light the board while you stand IN a portal, before the transfer completes.",
                        "Without this you only get the arrival, once the transfer completes.",
                        "Set false for arrival-only behaviour.")
                .define("chargeUpEnabled", true);
        PORTAL_CHARGE_RAMP_MILLIS = b.comment(
                        "How long the spiral takes to ease from chargeIntensityFloor up to full strength",
                        "while you stand in a portal. Keep this well under the shortest portal dwell you",
                        "care about: a nether portal gives about 4000ms, but an Aether portal was measured",
                        "at 382ms, and anything slower than the dwell means the spiral is still fading in",
                        "when the dimension changes.")
                .defineInRange("chargeRampMillis", 300, 50, 20000);
        PORTAL_CHARGE_INTENSITY_FLOOR = b.comment(
                        "Field strength on the very first frame of the dwell, before the ramp has done",
                        "anything. This also sets how much of the board the dwell owns on that frame:",
                        "below it the biome layer reads through. 0.35 was too low to be legible on the",
                        "short transfers that most modded portals actually have -- the Aether and",
                        "similar were measured at 160-420ms from hitbox contact to dimension change,",
                        "so the ramp never got anywhere and walking in looked like nothing happened.",
                        "Set 0 for a pure fade from the biome; the trade-off is exactly that.")
                .defineInRange("chargeIntensityFloor", 0.6, 0.0, 1.0);
        PORTAL_SUPPRESSION_FLOOR = b.comment(
                        "For the whole portal sequence -- dwell, arrival and fade-out -- Tier 3 flashes",
                        "below this priority are held off instead of blanking the board. Reference",
                        "priorities: death 100, shrieker 90, lightning 85, level up / advancement 40,",
                        "sculk ping 20, sleep 10. The default of 96 lets nothing but",
                        "death interrupt a portal; 86 would also let shrieker alerts through. Set 0",
                        "to let every flash interrupt. A Warden emerging is drawn as an overlay, not",
                        "a flash, so it shows alongside the portal whatever this is set to.")
                .defineInRange("tier3SuppressionFloor", 96, 0, 100);
        PORTAL_DEBUG_LOGGING = b.comment(
                        "Log a timestamped line when portal intersection is detected and when the dimension",
                        "actually changes. The gap between the two tells you whether the effect is firing late",
                        "or firing on time and being drawn over.")
                .define("debugLogging", false);
        PORTAL_SPIRAL_ROTATIONS = b.defineInRange("spiralRotations", 1.75, 0.25, 10.0);
        b.pop();

        b.push("debug");
        DEBUG_CHAT_NOTICES = b.comment(
                        "Say in chat, once, when no lighting connected, when a connection drops mid-game, or",
                        "when the mod hits an error, naming the command that explains it. Whatever this is set",
                        "to, /rgbprofiles status explains the lighting, /rgbprofiles test checks the keyboard",
                        "shows the right colours in the right places, and /rgbprofiles report writes a file",
                        "with everything needed to help with a problem.")
                .define("chatNotices", true);
        DEBUG_ENABLED = b.comment(
                        "Master switch for diagnostic logging. Off costs one boolean read per frame.",
                        "For effect problems rather than hardware ones: it logs every effect activation with",
                        "who owns the board at that moment, which distinguishes 'my effect never fired' from",
                        "'my effect fired and something drew over it'. Hardware problems are always logged,",
                        "and /rgbprofiles report covers them without this.")
                .define("enabled", false);
        DEBUG_LOG_TRANSITIONS = b.comment(
                        "Log a line whenever any effect becomes active or inactive.")
                .define("logEffectTransitions", true);
        DEBUG_HEALTH_INTERVAL_SECONDS = b.comment(
                        "How often to log render-thread health: achieved fps, frame cost, job queue depth.",
                        "The worker runs independently of the client tick, so this shows whether animation",
                        "is actually stalling during world generation or only appearing to.")
                .defineInRange("healthIntervalSeconds", 10, 1, 600);
        b.pop();

        b.push("raidWarning");
        RAID_COLOR = b.comment(
                        "A raid, drawn like a Terraria invasion: while the Raid Omen counts down, a "
                                + "quickening heartbeat and an army marching onto the board; during the raid, "
                                + "the horde glowing along the bottom rows with the army marching across it "
                                + "and a flash each time the raid horn blows; fireworks when it is won, and "
                                + "the army jumping in celebration when it is lost.",
                        "This colour is the horde, its glow, and the raiders' bodies.")
                .define("color", "#B22222");
        RAID_RAIDER_COLOR = b.comment("The raiders' heads.").define("raiderColor", "#B8D0A0");
        RAID_VICTORY_COLOR = b.comment("The victory fireworks.").define("victoryColor", "#44FF44");
        b.pop();

        b.push("sculkAlert");
        SCULK_SENSOR_ENABLED = b.define("sensorEnabled", true);
        SCULK_SHRIEKER_ENABLED = b.define("shriekerEnabled", true);
        SCULK_SENSOR_COLOR = b.define("sensorColor", "#4FC3C3");
        SCULK_SENSOR_DURATION_MILLIS = b.defineInRange("sensorDurationMillis", 700, 50, 10000);
        SCULK_SENSOR_RING_THICKNESS_KEYS = b.defineInRange("sensorRingThicknessKeys", 1.0, 0.25, 5.0);
        SCULK_SHRIEKER_COLOR = b.define("shriekerColor", "#1F8C7A");
        SCULK_SHRIEKER_PEAK_COLOR = b.comment(
                        "Colour at maximum escalation. Wants to be BRIGHTER than shriekerColor, not "
                                + "darker — the ramp used to end near black, so the more shrieks you "
                                + "set off the less you could see the warning.")
                .define("shriekerPeakColor", "#5FF5DC");
        SCULK_SHRIEKER_DURATION_MILLIS = b.defineInRange("shriekerDurationMillis", 900, 50, 10000);
        SCULK_SHRIEKER_RING_THICKNESS_KEYS = b.defineInRange("shriekerRingThicknessKeys", 1.0, 0.25, 5.0);
        SCULK_SHRIEKER_MAX_ESCALATION_LEVEL = b.defineInRange("shriekerMaxEscalationLevel", 3, 0, 10);
        SCULK_SHRIEKER_ESCALATION_WINDOW_MILLIS = b.comment(
                        "Gap within which a repeat shriek escalates rather than resetting. " +
                        "Placeholder default — not verified against vanilla's real " +
                        "shriek-accumulation window yet.")
                .defineInRange("shriekerEscalationWindowMillis", 20000, 1000, 120000);
        SCULK_DETECTION_RADIUS = b.comment(
                        "How far to watch for sensors and shriekers firing. Cost scales with the "
                                + "number of chunk sections in range that actually contain sculk, not "
                                + "with the radius cubed — see SculkBlockWatcher.")
                .defineInRange("detectionRadius", 16, 1, 128);
        SCULK_SCAN_INTERVAL_TICKS = b.comment(
                        "Ticks between activation scans. A sensor stays ACTIVE for 40 ticks and a "
                                + "shrieker shrieks for 90, so anything up to about 30 catches every "
                                + "event; lower it only if you want tighter timing.")
                .defineInRange("scanIntervalTicks", 5, 1, 40);
        b.pop();

        b.push("sleepWake");
        SLEEP_WAKE_COLOR = b.define("color", "#3355AA");
        SLEEP_WAKE_DURATION_MILLIS = b.defineInRange("durationMillis", 1000, 50, 10000);
        b.pop();

        b.push("biomeColors");
        BIOME_USE_CUSTOM_JSON = b.define("useCustomJsonProfiles", true);
        BIOME_FALLBACK_TO_DERIVED_COLOR = b.define("fallbackToDerivedColor", true);
        b.pop();

        b.push("bossEvents");
        BOSS_USE_CUSTOM_JSON = b.define("useCustomJsonProfiles", true);
        BOSS_FALLBACK_TO_BOSSBAR_COLOR = b.define("fallbackToBossBarColor", true);
        BOSS_ENRAGE_INTENSITY_SCALING = b.define("enrageIntensityScaling", true);
        BOSS_LEGENDARY_MONSTERS_INTEGRATION = b.define("legendaryMonstersIntegration", true);
        BOSS_PROXIMITY_ENABLED = b.comment(
                        "Detect bosses by standing near them rather than by their boss bar. Needed for "
                                + "every boss that is a boss the way the Warden is one — dangerous, "
                                + "encounter-defining, and with no bar above the screen. Several "
                                + "Legendary Monsters mobs are exactly this.")
                .define("proximityDetectionEnabled", true);
        BOSS_USE_BOSSES_TAG = b.comment(
                        "Treat anything in the 'c:bosses' convention tag as a boss, skipping the health "
                                + "test. This is the mod author's own declaration of what a boss is, so "
                                + "it beats any heuristic we could invent. Eleven mods in the Forge "
                                + "Everything pack populate it, Legendary Monsters included.")
                .define("useBossesTag", true);
        BOSS_DETECTION_RADIUS = b.comment("Blocks. Generous, because bosses are large and fight at range.")
                .defineInRange("proximityDetectionRadius", 48, 4, 128);
        BOSS_MIN_MAX_HEALTH = b.comment(
                        "Max health a mob needs before proximity counts it as a boss. This is the "
                                + "mod-agnostic half: 150 clears the Wither (300), Ender Dragon (200) "
                                + "and Warden (500) while leaving Ravagers and Iron Golems (100 each) "
                                + "alone. A mob with its own hand-written entry in boss_profiles.json "
                                + "skips this test entirely — an Elder Guardian is a boss at 80 health "
                                + "because somebody said so in the file.")
                .defineInRange("proximityMinMaxHealth", 150.0, 1.0, 10000.0);
        BOSS_GRACE_MILLIS = b.comment(
                        "How long the encounter lighting survives the boss leaving range, so a dragon "
                                + "circling out and back does not restart the effect each pass.")
                .defineInRange("proximityGraceMillis", 5000, 0, 60000);
        BOSS_EXCLUDED = b.comment(
                        "Comma-separated list of things proximity detection must ignore. Accepts exact "
                                + "entity ids ('minecraft:warden') and mod-wide wildcards "
                                + "('mutantmonsters:*'). Checked before everything else, so it "
                                + "overrides the c:bosses tag and a hand-written profile alike.",
                        "Defaults explained: the Warden has its own dedicated presence layer that "
                                + "outranks the boss layer anyway. Mutant Monsters is excluded wholesale "
                                + "because its mutants carry boss-sized health pools while being "
                                + "ordinary roaming mobs — exactly the case a health threshold gets "
                                + "wrong, and it declares no c:bosses tag to correct it.",
                        "Ice and Fire's fire, ice and lightning dragons are the same case: no c:bosses "
                                + "tag, health that grows past the threshold as they age, and whole "
                                + "regions where several roam at once. Each one nearby took the board "
                                + "for a pulse of its own, which buried the biome, weather and time of "
                                + "day under a mess of colours. Only the dragons are listed, not "
                                + "'iceandfire:*', so the mod's other large creatures still count.")
                .define("proximityExcluded",
                        "minecraft:warden, minecraft:ender_dragon, minecraft:wither, "
                                + "twilightforest:naga, mutantmonsters:*, "
                                + "iceandfire:fire_dragon, iceandfire:ice_dragon, iceandfire:lightning_dragon");
        b.pop();

        b.push("rainThunderstorm");
        RAIN_COLOR = b.comment("Pale blue-grey. Wants to stay brighter than the biome colours it falls over.")
                .define("rainColor", "#AECDE8");
        RAIN_PARTICLE_COUNT = b.comment("Drops in flight. Some are off-board at any moment, so on-board density is lower than this.")
                .defineInRange("rainParticleCount", 12, 1, 64);
        RAIN_PARTICLE_LIFESPAN_MILLIS = b.comment(
                        "Must exceed the time a slow drop needs to cross the board (~2900ms) or drops "
                                + "die partway down and the bottom rows stay dry.")
                .defineInRange("rainParticleLifespanMillis", 2800, 100, 10000);
        RAIN_LAYER_OPACITY = b.comment(
                        "How much of the biome underneath survives a drop. 1.0 replaces the key "
                                + "outright, which on a dark biome reads as the biome having vanished. "
                                + "Lower this if rain is drowning out the scenery.")
                .defineInRange("rainOpacity", 0.78, 0.05, 1.0);
        RAIN_REQUIRE_SKY_EXPOSURE = b.comment(
                        "Only show rain where the player is genuinely being rained on, rather than "
                                + "whenever it happens to be raining somewhere in the world. Uses the "
                                + "game's own per-position test, so a ravine or any other opening to the "
                                + "sky counts as outdoors while a cave under it does not.")
                .define("requireSkyExposure", true);
        RAIN_SHELTER_GRACE_MILLIS = b.comment(
                        "How long rain keeps running after you duck out of it. Without a grace period "
                                + "the overlay snaps off and on as you walk under trees, since leaves "
                                + "block sky just as effectively as stone does.")
                .defineInRange("shelterGraceMillis", 4000, 0, 60000);
        LIGHTNING_COLOR = b.define("lightningColor", "#F0F5FF");
        LIGHTNING_FLASH_DURATION_MILLIS = b.defineInRange("lightningFlashDurationMillis", 400, 50, 5000);
        LIGHTNING_DETECTION_RADIUS = b.defineInRange("lightningDetectionRadius", 64, 1, 256);
        LIGHTNING_FALLBACK_INTERVAL_MILLIS = b.defineInRange("lightningFallbackIntervalMillis", 8000, 500, 60000);
        b.pop();

        b.push("toughAsNails");
        THIRST_THRESHOLD_PERCENT = b.defineInRange("thirstThresholdPercent", 30, 1, 99);
        THIRST_COLOR = b.define("thirstColor", "#3FBFD4");
        THIRST_FLASH_KEY = b.define("thirstFlashKey", "T");
        TEMPERATURE_FLASH_KEY = b.define("temperatureFlashKey", "K");
        OVERHEATING_COLOR = b.define("overheatingColor", "#FF6B35");
        FREEZING_COLOR = b.define("freezingColor", "#A8E0F0");
        b.push("seasonalTint");
        SEASONAL_TINT_ENABLED = b.define("enabled", true);
        SEASONAL_TINT_INTENSITY = b.defineInRange("intensity", 0.25, 0.0, 1.0);
        b.pop();
        b.pop();

        b.push("wardenEncounter");
        WARDEN_DETECTION_RADIUS = b.defineInRange("detectionRadius", 24, 1, 128);
        WARDEN_EMERGENCE_COLOR = b.comment(
                        "The rings that close in while a Warden climbs out of the ground. They are "
                                + "drawn over the dim Warden presence wash, so a dark colour here "
                                + "disappears into it at the worst possible moment. Keep it bright.")
                .define("emergenceColor", "#2EE0C8");
        WARDEN_EMERGENCE_HOLD_UNTIL_RISEN = b.comment(
                        "Hold the emergence effect until the Warden actually finishes climbing out, "
                                + "rather than for a fixed length. Vanilla's emergence runs 134 ticks "
                                + "(6.7s); the effect used to borrow the shrieker's 900ms and so ended "
                                + "while the Warden was still in the ground. Turn off for a fixed "
                                + "minHold + fade.")
                .define("emergenceHoldUntilRisen", true);
        WARDEN_EMERGENCE_PULSE_MILLIS = b.comment(
                        "One ring contraction. It repeats for the whole emergence, so this is the "
                                + "heartbeat rate, not the total length.")
                .defineInRange("emergencePulseMillis", 1100, 120, 10000);
        WARDEN_EMERGENCE_MIN_HOLD_MILLIS = b.comment("Floor on the hold, so a fast release still registers.")
                .defineInRange("emergenceMinHoldMillis", 2000, 0, 30000);
        WARDEN_EMERGENCE_FADE_MILLIS = b.comment("Dissolve back into the Warden presence layer.")
                .defineInRange("emergenceFadeMillis", 1200, 50, 10000);
        WARDEN_EMERGENCE_MAX_MILLIS = b.comment(
                        "Hard cap. A Warden that somehow never leaves its emerging pose costs a long "
                                + "effect rather than a stuck one.")
                .defineInRange("emergenceMaxDurationMillis", 14000, 1000, 60000);
        WARDEN_EMERGENCE_NOMINAL_MILLIS = b.comment(
                        "Roughly how long an emergence runs. Shapes the build-up only — the ending is "
                                + "driven by the real pose, not by this.")
                .defineInRange("emergenceNominalMillis", 6700, 500, 60000);
        WARDEN_EMERGENCE_SUPPRESSION_FLOOR = b.comment(
                        "Tier 3 flashes below this priority are held off while a Warden is emerging. "
                                + "Below death (100) on purpose: dying during an emergence is still "
                                + "information you need.")
                .defineInRange("emergenceSuppressionFloor", 96, 0, 100);
        WARDEN_PRESENCE_COLOR = b.comment(
                        "Wash colour held on the board for as long as a Warden is in range. Replaces "
                                + "the biome layer, so it wants to be dim but genuinely lit.")
                .define("presenceColor", "#0C2A33");
        WARDEN_PRESENCE_GLINT_COLOR = b.comment("Glints over the presence wash.")
                .define("presenceGlintColor", "#24C8B8");
        WARDEN_ACTIVE_TWINKLE_COUNT = b.defineInRange("activeTwinkleCount", 7, 1, 64);
        WARDEN_ACTIVE_TWINKLE_INTERVAL_MILLIS = b.defineInRange("activeTwinkleIntervalMillis", 1100, 50, 5000);
        b.pop();

        b.push("menuTheme");
        MENU_THEME_ENABLED = b.comment(
                        "Ambient lighting while no world is loaded — title screen, server list, mod "
                                + "list and so on. Turn off for a dark keyboard at the menu. Which "
                                + "theme you get is 'style' below.")
                .define("enabled", true);
        MENU_STYLE = b.comment(
                        "Which title-screen theme to use.",
                        "  sulfur    - a sulfur cave: an acid pool bubbling and venting below a",
                        "              spiked ceiling. The default, because from Minecraft 26.2 the",
                        "              game's own title panorama IS a sulfur cave, so this is the",
                        "              theme that matches the screen it is sitting behind rather",
                        "              than somebody's taste.",
                        "  vanilla   - sky over grass with drifting clouds. What the panorama was",
                        "              through 1.21.x, and what this mod shipped with. Still the",
                        "              right answer with a resource pack that restores the old art.",
                        "  mineshaft - the dark cave face with torches, a drill and ore glints. Built",
                        "              for the Forge Everything pack's title screen specifically.",
                        "  auto      - mineshaft if this looks like one of menuPackNames below,",
                        "              sulfur otherwise.",
                        "Pack maintainers: set this outright in defaultconfigs/ rather than relying on",
                        "auto. Detection is a convenience and cannot see inside every launcher.")
                .define("style", "sulfur");
        MENU_PACK_PATTERNS = b.comment(
                        "Comma-separated. Under 'auto', the mineshaft theme is used when the detected "
                                + "pack name contains any of these. Matched as a case-insensitive "
                                + "substring, so version suffixes do not break it.")
                .define("menuPackNames", "Forge Everything");
        MENU_SULFUR_ROCK_COLOR = b.comment(
                        "Sulfur theme: the cave walls above the waterline, and the light everything "
                                + "in the rock is shaded from.")
                .define("sulfurRockColor", "#C4A917");
        MENU_SULFUR_POOL_COLOR = b.comment(
                        "Sulfur theme: the acid pool along the bottom. The deep water under it is "
                                + "derived from this, so recolouring the pool moves its whole depth "
                                + "gradient with it.")
                .define("sulfurPoolColor", "#19D98C");
        MENU_VANILLA_SKY_COLOR = b.comment("Default theme: the sky above the horizon.")
                .define("vanillaSkyColor", "#5B93E8");
        MENU_VANILLA_GRASS_COLOR = b.comment("Default theme: the grass below it.")
                .define("vanillaGrassColor", "#4BA83C");
        MENU_VANILLA_CLOUD_COUNT = b.comment("Default theme: clouds drifting across the sky. 0 for a clear day.")
                .defineInRange("vanillaCloudCount", 4, 0, 12);
        MENU_BASE_COLOR = b.comment("Cold unlit rock. Everything else is light falling on this.")
                .define("baseColor", "#241B16");
        MENU_EMBER_COLOR = b.comment(
                        "The one warm colour in the scene. Torchlight, the drill's hot core, and the " +
                        "sparks are all derived from it, so recolouring this keeps the fire coherent.")
                .define("emberColor", "#FF7A29");
        MENU_ARCANE_COLOR = b.comment(
                        "The 'something magical in the dark crevices' accent. It is one entry in the " +
                        "ore glint table (the amethyst), not the only colour glinting.")
                .define("arcaneColor", "#9B5FFF");
        MENU_TORCH_COUNT = b.comment(
                        "How many torches stay lit behind the drill. The drill plants one every "
                                + "few keys as it cuts across the board and the oldest gutters out, so "
                                + "this is really the length of the lit corridor trailing it.",
                        "Renamed from 'torchCount', which meant something else: torches used to be "
                                + "scattered once at startup and never moved. The rename is deliberate -- "
                                + "it is what lets the new default reach anyone who already has a "
                                + "config file, since existing keys are kept as they are.")
                .defineInRange("torchTrailLength", 5, 1, 12);
        MENU_SPARK_COUNT = b.comment("Sparks in flight off the drill bit. They only spawn while the drill is actually cutting.")
                .defineInRange("sparkCount", 14, 1, 64);
        MENU_SPARK_INTERVAL_MILLIS = b.comment("Average gap before a spent spark slot throws another. Lower = a denser spray.")
                .defineInRange("sparkIntervalMillis", 260, 40, 5000);
        MENU_ORE_GLINT_COUNT = b.comment("Ore facets catching the light at once. Kept low so a glint stays an event.")
                .defineInRange("oreGlintCount", 5, 1, 32);
        MENU_ORE_GLINT_INTERVAL_MILLIS = b.comment("Average gap between one slot's glints. Raise for a quieter, rarer wall.")
                .defineInRange("oreGlintIntervalMillis", 1500, 120, 8000);
        b.pop();

        b.push("advanced");
        UPDATE_INTERVAL_TICKS = b.comment("Polling cadence for biome/time STATE checks. Unrelated to animation frame rate.")
                .defineInRange("updateIntervalTicks", 20, 1, 1200);
        ANIMATION_FRAME_RATE_HZ = b.comment("How often the SDK worker thread advances any active animated pattern.")
                .defineInRange("animationFrameRateHz", 30, 1, 60);
        DEBOUNCE_MILLIS = b.comment(
                        "How long a new biome must persist before it commits. Only meaningful if it is "
                                + "LONGER than biomeSampleIntervalTicks -- otherwise every change "
                                + "commits on the second consecutive sample and the debounce filters "
                                + "nothing while still costing a full sample interval of delay.")
                .defineInRange("debounceMillis", 250, 0, 5000);
        BIOME_SAMPLE_INTERVAL_TICKS = b.comment(
                        "How often to check which biome you are standing in, independently of "
                                + "updateIntervalTicks. Biome lookup is a cheap chunk-local query, and "
                                + "sharing the one-second ambient cadence meant a change took between "
                                + "one and two seconds to appear -- long enough to feel disconnected "
                                + "from walking across the border.")
                .defineInRange("biomeSampleIntervalTicks", 4, 1, 40);
        BIOME_CROSSFADE_MILLIS = b.comment(
                        "How long one biome takes to dissolve into the next. Raise for a gentler "
                                + "transition; the fade always starts from whatever is currently on "
                                + "screen, so crossing a border mid-fade stays smooth.")
                .defineInRange("biomeCrossfadeMillis", 1400, 50, 10000);
        b.pop();

        b.comment(
                 "Which classes of lighting hardware this mod is allowed to drive.",
                 "Only the keyboard is on by default: a fresh install should never",
                 "start flashing someone's motherboard or RAM unasked. Modpack",
                 "authors can ship different defaults, and users can switch any",
                 "class off without disabling the mod.",
                 "Corsair and OpenRGB report every device they control, so all of",
                 "these apply to them. Razer, Logitech and SteelSeries only ever",
                 "drive a keyboard, so for those only keyboardEnabled matters.",
                 "Disabled classes are skipped during device enumeration, so they",
                 "cost nothing at all -- no LEDs read, no buffers allocated.")
         .push("devices");
        DEVICE_KEYBOARD_ENABLED = b.define("keyboardEnabled", true);
        DEVICE_MOUSE_ENABLED = b.define("mouseEnabled", false);
        DEVICE_MOUSEMAT_ENABLED = b.define("mousematEnabled", false);
        // Everything except the keyboard defaults to FALSE. Installing a
        // Minecraft mod and having your RAM and motherboard start pulsing at
        // you unprompted is a jump-scare, not a feature. Opt in.
        DEVICE_HEADSET_ENABLED = b.define("headsetEnabled", false);
        DEVICE_MEMORY_ENABLED = b.define("memoryEnabled", false);
        DEVICE_COOLING_ENABLED = b.define("coolingEnabled", false);
        DEVICE_MOTHERBOARD_ENABLED = b.define("motherboardEnabled", false);
        DEVICE_OTHER_ENABLED = b.define("otherDevicesEnabled", false);
        b.pop();

        // build() locks the spec. Nothing may be defined after this, which is
        // why every section lives in this one static block rather than being
        // spread across lazily-initialised helpers.
        SPEC = b.build();
    }

    private RGBProfileConfig() {
    }
}
