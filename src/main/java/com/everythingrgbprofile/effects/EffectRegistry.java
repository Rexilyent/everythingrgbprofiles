package com.everythingrgbprofile.effects;

import com.everythingrgbprofile.RGBProfileMod;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.ColorRamp;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.config.Feature;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.effects.support.*;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.gating.PackDetection;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.*;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;
import com.everythingrgbprofile.profile.BiomeProfile;
import com.everythingrgbprofile.profile.BossProfile;
import com.everythingrgbprofile.profile.DimensionProfile;
import com.everythingrgbprofile.sdk.BackendHealth;

import java.util.List;
import java.util.Map;

/**
 * Where every effect gets built, wired up and handed a priority. If the
 * question is "what effects exist and how do they rank against each other",
 * this is the file. It is the closest thing this mod has to a table of
 * contents.
 *
 * <p>Everything here is constructed exactly once, on the SDK worker thread,
 * immediately after the keyboard's real (or synthetic) {@link KeyGrid} is
 * known. It has to happen after, because several effects resolve config key
 * labels against actual hardware and therefore need to know what physically
 * exists before they can be built.
 *
 * <p>The fields are public and typed rather than looked up by string, which is
 * deliberate. {@code ClientEventHandlers} writes
 * {@code effects.portalCharge.setActive(...)} and gets a compile error the
 * moment that is wrong, instead of a map lookup quietly returning null at
 * runtime because somebody typo'd an id eighteen months ago.
 */
public final class EffectRegistry {

    private final EffectManager manager;

    // Loaded once at startup, and pointedly NOT hot-reloaded per frame.
    // Re-reading three JSON files sixty times a second would certainly be a
    // decision somebody could make. A future "recheck" command could reload
    // them properly; until that exists, editing a profile means restarting.
    public Map<String, BiomeProfile> biomeProfiles;
    public Map<String, BossProfile> bossProfiles;
    public Map<String, DimensionProfile> dimensionProfiles;

    // --- Tier 1: the base layer. Exactly one of these is visible at a time.
    public BiomeColorEffect biomeColor;
    public BossEncounterEffect bossEncounter;
    public SustainedOverlayEffect wardenActive;
    public RaidEffect raid;
    public SustainedOverlayEffect menuTheme;
    public SustainedOverlayEffect deathState;
    public EnderDragonEffect enderDragon;
    public ElderGuardianEffect elderGuardian;
    public NagaEffect naga;
    public SliderEffect slider;
    public SunSpiritEffect sunSpirit;
    public ValkyrieQueenEffect valkyrieQueen;
    public WitherEffect wither;

    // --- Tier 2: overlays. All active ones draw at once, no competition.
    public NightIndicatorEffect nightIndicator;
    public SustainedOverlayEffect healthFlash;
    public SustainedOverlayEffect hungerWarning;
    public SustainedOverlayEffect rainCascade;
    public SustainedOverlayEffect snowfall;
    public PortalChargeEffect portalCharge;
    public SustainedOverlayEffect thirstWarning;
    public SustainedOverlayEffect temperatureWarning;
    public DrowningEffect drowning;
    public BurningEffect burning;

    // --- Tier 3: interrupts. Highest priority wins, subject to suppression.
    public MomentaryFlashEffect deathFlash;
    public MomentaryFlashEffect levelUpFlash;
    public MomentaryFlashEffect advancementFlash;
    public PortalTransitionEffect portalTransitionFlash;
    public MomentaryFlashEffect sculkSensorPing;
    public MomentaryFlashEffect shriekerAlert;
    public MomentaryFlashEffect sleepWakeFlash;
    public MomentaryFlashEffect lightningFlash;
    public WardenEmergenceEffect wardenEmergenceFlash;

    /**
     * Shared shriek counter. Final and initialised inline because it depends
     * only on config, not on the KeyGrid — so unlike everything else here it
     * doesn't need to wait for hardware detection.
     */
    public final SculkEscalationTracker shriekerEscalation = new SculkEscalationTracker(
            RGBProfileConfig.SCULK_SHRIEKER_MAX_ESCALATION_LEVEL.get(),
            RGBProfileConfig.SCULK_SHRIEKER_ESCALATION_WINDOW_MILLIS.get()
    );

    public EffectRegistry(EffectManager manager) {
        this.manager = manager;
    }

    public void registerAll(KeyGrid grid) {
        biomeProfiles = BiomeProfile.loadAll();
        bossProfiles = BossProfile.loadAll();
        dimensionProfiles = DimensionProfile.loadAll();

        // ============ Tier 1 ============
        // Priorities here are a strict "who owns the board" ladder:
        //   0      biome      — the resting state
        //   1      menu       — title screen, only active when there's no world
        //   10     boss       — the generic layer; a fight outranks scenery
        //   11     warden     — the Warden outranks the boss (correct, frankly)
        //   20     raid
        //   22-28  dedicated boss layers, which all beat the raid and the
        //          generic boss layer they replace (see each one below)
        //   100    death      — above everything, and exclusive

        biomeColor = new BiomeColorEffect(biomeProfiles);
        manager.register(biomeColor);

        bossEncounter = new BossEncounterEffect();
        manager.register(bossEncounter);

        // shimmer-twinkle, not twinkle-particle. This effect outranks the biome
        // layer at priority 11, and twinkle-particle writes ONLY the keys
        // currently twinkling — so a Warden wandering within range replaced a
        // fully-lit Deep Dark with a black board carrying two near-black dots.
        // Walking in and out of its 24-block radius looked exactly like the
        // background randomly disappearing, because it was.
        wardenActive = new SustainedOverlayEffect("warden_active", EffectTier.TIER1_OPAQUE_BASE, 11, null,
                ShimmerTwinklePattern.wardenPresence(
                        RGBProfileConfig.WARDEN_ACTIVE_TWINKLE_COUNT.get(),
                        RGBProfileConfig.WARDEN_ACTIVE_TWINKLE_INTERVAL_MILLIS.get()),
                RGBColor.fromHexOrDefault(RGBProfileConfig.WARDEN_PRESENCE_COLOR.get(), ColorPalette.SCULK_AMBIENT))
                .withAccentColor(RGBColor.fromHexOrDefault(
                        RGBProfileConfig.WARDEN_PRESENCE_GLINT_COLOR.get(), ColorPalette.SCULK_WARDEN_GLINT));
        // Both of these own the board as a scene, so the moon stands down while
        // they run. Survival warnings still draw — see EffectController#ambient.
        wardenActive.withSuppressAmbientOverlays(true);
        manager.register(wardenActive);

        raid = new RaidEffect(20);
        raid.setColors(
                RGBColor.fromHexOrDefault(RGBProfileConfig.RAID_COLOR.get(), ColorPalette.RAID_WARNING_RED),
                RGBColor.fromHexOrDefault(RGBProfileConfig.RAID_RAIDER_COLOR.get(), ColorPalette.RAID_RAIDER),
                RGBColor.fromHexOrDefault(RGBProfileConfig.RAID_VICTORY_COLOR.get(), ColorPalette.RAID_VICTORY));
        manager.register(raid);

        // Priority 1: above the biome, below everything else. It only ever
        // activates when no world is loaded, so it never actually competes
        // with anything — the 1 just means the biome layer doesn't outrank it
        // on the title screen.
        menuTheme = new SustainedOverlayEffect("menu_theme", EffectTier.TIER1_OPAQUE_BASE, 1, null,
                menuPattern(),
                menuBaseColor())
                .withAccentColor(menuAccentColor());
        manager.register(menuTheme);

        // Priority 100, above everything else in the tier by a wide margin, and
        // the only effect in the mod that declares exclusivity. Being dead is
        // not a variation on being somewhere — it stops the board reporting the
        // world at all. The suppression floor of 100 lets the death flash
        // itself (also 100) punch through on the same frame and blocks
        // everything below it, so nothing interrupts the blood.
        deathState = new SustainedOverlayEffect("death_state", EffectTier.TIER1_OPAQUE_BASE, 100, null,
                new BloodDripPattern(
                        RGBProfileConfig.DEATH_BLOOD_DRIP_COUNT.get(),
                        RGBProfileConfig.DEATH_BLOOD_DRIP_INTERVAL_MILLIS.get()),
                RGBColor.fromHexOrDefault(RGBProfileConfig.DEATH_BLOOD_SOAK_COLOR.get(), ColorPalette.DEATH_BLOOD_SOAK))
                .withAccentColor(RGBColor.fromHexOrDefault(
                        RGBProfileConfig.DEATH_BLOOD_COLOR.get(), ColorPalette.DEATH_BLOOD))
                .withExclusive(true)
                .withTier3SuppressionFloor(100);
        manager.register(deathState);

        // Priority 23: same bracket as the other dedicated boss layers, all of
        // which sit above the raid (20) and the generic pulse (10). Which of
        // them outranks which barely matters — you are not fighting a Wither
        // and a Naga at once — but they must all beat the generic layer they
        // replace.
        wither = new WitherEffect(23);
        wither.setColors(
                RGBColor.fromHexOrDefault(RGBProfileConfig.WITHER_BONE_COLOR.get(), ColorPalette.WITHER_BONE),
                RGBColor.fromHexOrDefault(RGBProfileConfig.WITHER_EYE_COLOR.get(), ColorPalette.WITHER_EYE),
                RGBColor.fromHexOrDefault(RGBProfileConfig.WITHER_POWERED_COLOR.get(), ColorPalette.WITHER_POWERED));
        manager.register(wither);

        // Priority 24: above the raid (20) and the generic boss layer (10),
        // just under the dragon. Only one of the two can realistically be on
        // screen, so the exact gap matters less than both outranking the
        // generic pulse they replace.
        naga = new NagaEffect(24);
        naga.setColors(
                RGBColor.fromHexOrDefault(RGBProfileConfig.NAGA_SCALE_COLOR.get(), ColorPalette.NAGA_SCALE_GREEN),
                RGBColor.fromHexOrDefault(RGBProfileConfig.NAGA_HIGHLIGHT_COLOR.get(), ColorPalette.NAGA_HIGHLIGHT));
        naga.setFadeTimes(0.5, RGBProfileConfig.NAGA_FADE_MILLIS.get() / 1000.0);
        manager.register(naga);

        // Priority 22: the bottom of the dedicated-boss bracket, so it beats
        // the generic pulse (10) that would otherwise claim the same mob, and
        // the raid warning (20) — a raid can genuinely be running while you
        // are in a monument, and of the two, the thing charging a laser at you
        // is the one worth the board.
        elderGuardian = new ElderGuardianEffect(22);
        elderGuardian.setColors(
                RGBColor.fromHexOrDefault(RGBProfileConfig.ELDER_GUARDIAN_WATER_COLOR.get(),
                        ColorPalette.GUARDIAN_PRISMARINE),
                RGBColor.fromHexOrDefault(RGBProfileConfig.ELDER_GUARDIAN_EYE_COLOR.get(),
                        ColorPalette.GUARDIAN_EYE),
                RGBColor.fromHexOrDefault(RGBProfileConfig.ELDER_GUARDIAN_BEAM_COLOR.get(),
                        ColorPalette.GUARDIAN_BEAM));
        elderGuardian.setFadeTimes(0.6, RGBProfileConfig.ELDER_GUARDIAN_FADE_MILLIS.get() / 1000.0);
        manager.register(elderGuardian);

        // Priority 25: above the raid (20) because you cannot have a raid in
        // the End, and comfortably above the generic boss layer (10) so the
        // dedicated phase-aware version wins for the dragon even where the
        // generic detector also picks it up.
        enderDragon = new EnderDragonEffect(25);
        enderDragon.setColors(
                RGBColor.fromHexOrDefault(RGBProfileConfig.END_VOID_COLOR.get(), ColorPalette.END_VOID_PURPLE),
                RGBColor.fromHexOrDefault(RGBProfileConfig.END_ACCENT_COLOR.get(), ColorPalette.END_DRAGON_MAGENTA));
        enderDragon.setBreathFireColor(RGBColor.fromHexOrDefault(
                RGBProfileConfig.END_BREATH_FIRE_COLOR.get(), ColorPalette.END_BREATH_FIRE));
        manager.register(enderDragon);

        // Priority 26, the first of the three Aether bosses. The Slider never
        // leaves the Aether, so it cannot share a board with any of the
        // non-Aether ones; what matters is that it beats the generic pulse
        // (10) that also claims it through c:bosses.
        slider = new SliderEffect(26);
        slider.setColors(
                RGBColor.fromHexOrDefault(RGBProfileConfig.SLIDER_STONE_COLOR.get(), ColorPalette.SLIDER_STONE),
                RGBColor.fromHexOrDefault(RGBProfileConfig.SLIDER_RUNE_COLOR.get(), ColorPalette.SLIDER_RUNE),
                RGBColor.fromHexOrDefault(RGBProfileConfig.SLIDER_CRITICAL_COLOR.get(), ColorPalette.SLIDER_CRITICAL));
        slider.setFadeTimes(0.5, RGBProfileConfig.SLIDER_FADE_MILLIS.get() / 1000.0);
        manager.register(slider);

        // Priority 27, next to the Slider for the same reason: it never leaves
        // its dungeon, so all it has to beat is the generic pulse (10).
        sunSpirit = new SunSpiritEffect(27);
        sunSpirit.setColors(
                RGBColor.fromHexOrDefault(RGBProfileConfig.SUN_SPIRIT_SUN_COLOR.get(), ColorPalette.SUN_SPIRIT_SUN),
                RGBColor.fromHexOrDefault(RGBProfileConfig.SUN_SPIRIT_FLAME_COLOR.get(), ColorPalette.SUN_SPIRIT_FLAME),
                RGBColor.fromHexOrDefault(RGBProfileConfig.SUN_SPIRIT_ICE_COLOR.get(), ColorPalette.SUN_SPIRIT_ICE));
        sunSpirit.setFadeTimes(0.5, RGBProfileConfig.SUN_SPIRIT_FADE_MILLIS.get() / 1000.0);
        manager.register(sunSpirit);

        // Priority 28, with the other two Aether bosses and for the same reason.
        valkyrieQueen = new ValkyrieQueenEffect(28);
        valkyrieQueen.setColors(
                RGBColor.fromHexOrDefault(RGBProfileConfig.VALKYRIE_QUEEN_SILVER_COLOR.get(),
                        ColorPalette.VALKYRIE_QUEEN_SILVER),
                RGBColor.fromHexOrDefault(RGBProfileConfig.VALKYRIE_QUEEN_GOLD_COLOR.get(),
                        ColorPalette.VALKYRIE_QUEEN_GOLD),
                RGBColor.fromHexOrDefault(RGBProfileConfig.VALKYRIE_QUEEN_LIGHTNING_COLOR.get(),
                        ColorPalette.VALKYRIE_QUEEN_LIGHTNING));
        valkyrieQueen.setFadeTimes(0.5, RGBProfileConfig.VALKYRIE_QUEEN_FADE_MILLIS.get() / 1000.0);
        manager.register(valkyrieQueen);

        // ============ Tier 2 ============
        // Priority is meaningless here (everyone draws), hence the 0s. The
        // few that carry another number (the portal pair, the Warden
        // emergence) say why where they're built. The interesting distinction
        // is scope: single-key warnings vs whole-board overlays.
        //
        // Registration order is NOT arbitrary in this tier, though. There is no
        // priority contest, so overlays composite in the order they are
        // registered and later ones land on top. Anything that has to stay
        // legible through the others goes last.

        healthFlash = new SustainedOverlayEffect("health_flash", EffectTier.TIER2_OVERLAY, 0,
                resolveKey(grid, RGBProfileConfig.HEALTH_FLASH_KEY.get(), "health_flash"),
                new PulsePattern(PulsePattern.Speed.FAST), ColorPalette.HEALTH_RED);
        manager.register(healthFlash);

        // Note: health is FAST, hunger/thirst/temperature are SLOW. Dying is
        // urgent; being peckish is not. The pulse rate is doing the triage.
        hungerWarning = new SustainedOverlayEffect("hunger_warning", EffectTier.TIER2_OVERLAY, 0,
                resolveKey(grid, RGBProfileConfig.HUNGER_FLASH_KEY.get(), "hunger_warning"),
                new PulsePattern(PulsePattern.Speed.SLOW),
                RGBColor.fromHexOrDefault(RGBProfileConfig.HUNGER_COLOR.get(), ColorPalette.HUNGER_AMBER));
        manager.register(hungerWarning);

        // Whole board (null targets) — rain falls on everything. This is the
        // effect that was erasing the biome layer before LayerPixel existed.
        rainCascade = new SustainedOverlayEffect("rain_cascade", EffectTier.TIER2_OVERLAY, 0, null,
                DriftParticlePattern.rainDrift(
                        RGBProfileConfig.RAIN_PARTICLE_COUNT.get(),
                        RGBProfileConfig.RAIN_PARTICLE_LIFESPAN_MILLIS.get()),
                RGBColor.fromHexOrDefault(RGBProfileConfig.RAIN_COLOR.get(), ColorPalette.RAIN_BLUE_GRAY))
                .withLayerOpacity(RGBProfileConfig.RAIN_LAYER_OPACITY.get());
        manager.register(rainCascade);

        // Rain's cold-biome twin, and the only snowfall in the mod. The snowy
        // biomes used to draw snow as their base pattern, which meant it was
        // snowing on you forever under a clear sky. Now the biomes draw the
        // land and this draws the weather, only while it's actually snowing.
        // Never on at the same time as the rain: one position gets one kind
        // of precipitation, and pollWeather asks per position.
        snowfall = new SustainedOverlayEffect("snowfall", EffectTier.TIER2_OVERLAY, 0, null,
                DriftParticlePattern.snowDrift(),
                RGBColor.fromHexOrDefault(RGBProfileConfig.SNOW_COLOR.get(), ColorPalette.SNOWFALL))
                .withLayerOpacity(RGBProfileConfig.RAIN_LAYER_OPACITY.get());
        manager.register(snowfall);

        // After the rain, deliberately. The moon is the one Tier 2 overlay you
        // read rather than just notice, and rain drawing over it would hide it
        // during exactly the weather where "how long until dawn" matters most.
        nightIndicator = new NightIndicatorEffect();
        nightIndicator.setColor(RGBColor.fromHexOrDefault(
                RGBProfileConfig.NIGHT_INDICATOR_COLOR.get(), ColorPalette.NIGHT_MOONLIGHT));
        nightIndicator.setStandDownForEncounters(RGBProfileConfig.NIGHT_STAND_DOWN_FOR_ENCOUNTERS.get());
        manager.register(nightIndicator);

        // Portal dwell. Runs the fitted pillar spiral, easing up from nothing
        // so the biome layer visibly dissolves underneath it.
        // It used to be pulse-fast in a flat colour, which is why standing in a
        // portal read as the board blinking rather than as a portal opening.
        //
        // The magenta ramp is a placeholder for the first frame only — the
        // real per-dimension palette is pushed in by setPalette() the moment
        // you actually touch a portal.
        portalCharge = new PortalChargeEffect(5,
                ColorRamp.derived(RGBColor.fromHex("#8B008B"), RGBColor.fromHex("#E77BE7")));
        manager.register(portalCharge);

        thirstWarning = new SustainedOverlayEffect("thirst_warning", EffectTier.TIER2_OVERLAY, 0,
                resolveKey(grid, RGBProfileConfig.THIRST_FLASH_KEY.get(), "thirst_warning"),
                new PulsePattern(PulsePattern.Speed.SLOW),
                RGBColor.fromHexOrDefault(RGBProfileConfig.THIRST_COLOR.get(), ColorPalette.THIRST_CYAN));
        manager.register(thirstWarning);

        // One effect for both hot and cold — the colour gets swapped at
        // runtime between OVERHEATING_ORANGE and FREEZING_ICE. You cannot be
        // overheating and freezing simultaneously, so two controllers would
        // just be one controller with extra steps.
        temperatureWarning = new SustainedOverlayEffect("temperature_warning", EffectTier.TIER2_OVERLAY, 0,
                resolveKey(grid, RGBProfileConfig.TEMPERATURE_FLASH_KEY.get(), "temperature_warning"),
                new PulsePattern(PulsePattern.Speed.SLOW), ColorPalette.OVERHEATING_ORANGE);
        manager.register(temperatureWarning);

        // Level-driven rather than on/off, so it gets its own small class
        // instead of a SustainedOverlayEffect. Priority is meaningless in
        // Tier 2; it draws alongside everything else, which is right — rain
        // above the waterline and water below it is a perfectly sensible
        // thing to be looking at.
        drowning = new DrowningEffect(
                RGBProfileConfig.DROWNING_PANIC_THRESHOLD.get(),
                RGBColor.fromHexOrDefault(RGBProfileConfig.DROWNING_DEEP_COLOR.get(), ColorPalette.DROWNING_DEEP),
                RGBColor.fromHexOrDefault(RGBProfileConfig.DROWNING_SURFACE_COLOR.get(), ColorPalette.DROWNING_SURFACE));
        manager.register(drowning);

        // Straight after the water, its sibling. The two cannot both be up for
        // long — water puts the fire out — so their order is only a tiebreak.
        burning = new BurningEffect(
                RGBProfileConfig.BURNING_PANIC_THRESHOLD.get(),
                RGBColor.fromHexOrDefault(RGBProfileConfig.BURNING_CORE_COLOR.get(), ColorPalette.BURNING_CORE),
                RGBColor.fromHexOrDefault(RGBProfileConfig.BURNING_TIP_COLOR.get(), ColorPalette.BURNING_TIP));
        manager.register(burning);

        // ============ Tier 3 ============
        // THE PRIORITY LADDER. This is the most consequential list of numbers
        // in the mod — it decides what you see when two things happen at once,
        // and it's what the suppression floors are measured against:
        //
        //   100  death              — nothing outranks dying
        //    90  shrieker alert
        //    85  lightning
        //    40  level up / advancement   (the "confetti" tier)
        //    20  sculk sensor ping
        //    10  sleep/wake
        //
        // Warden emergence (95) and the portal transition (92) used to sit in
        // this list too. Both are Tier 2 overlays now, so neither competes
        // here: each draws alongside whatever is showing and, while it runs,
        // holds off every flash below 96 — which is everything except death.
        // They're still registered down here because they play the part of
        // an interrupt.

        deathFlash = new MomentaryFlashEffect("death_flash", 100);
        manager.register(deathFlash);

        // Registered among the interrupts because it plays the same part as
        // one, but it is a Tier 2 overlay — see WardenEmergenceEffect for why,
        // and for why its length follows the Warden's pose instead of a
        // stopwatch. The 95 is kept from its Tier 3 days; Tier 2 ignores
        // priority, so the number now only records where it would rank.
        wardenEmergenceFlash = new WardenEmergenceEffect(95);
        wardenEmergenceFlash.setSuppressionFloor(RGBProfileConfig.WARDEN_EMERGENCE_SUPPRESSION_FLOOR.get());
        manager.register(wardenEmergenceFlash);

        shriekerAlert = new MomentaryFlashEffect("shrieker_alert", 90);
        manager.register(shriekerAlert);

        lightningFlash = new MomentaryFlashEffect("lightning_flash", 85);
        manager.register(lightningFlash);

        // Tier 2, like the Warden emergence, so the 92 is another number that
        // only records where it ranked back when this was a Tier 3 flash. What
        // actually protects the arrival now is its suppression floor (the
        // portal suppression floor in the config, 96 by default, pushed in
        // when the portal fires): lightning (85) and a shrieker
        // (90) are held off for the whole sequence, and only death gets
        // through. Without it you'd walk through a portal and see nothing,
        // because it rained.
        portalTransitionFlash = new PortalTransitionEffect(92);
        manager.register(portalTransitionFlash);

        // Both at 40: these are the "nice thing happened" flashes and
        // none of them is more important than the others. Equal priority means
        // ties break on registration order, which is arbitrary but stable, and
        // frankly it does not matter which confetti wins.
        levelUpFlash = new MomentaryFlashEffect("level_up", 40);
        manager.register(levelUpFlash);
        advancementFlash = new MomentaryFlashEffect("advancement", 40);
        manager.register(advancementFlash);

        sculkSensorPing = new MomentaryFlashEffect("sculk_sensor_ping", 20);
        manager.register(sculkSensorPing);

        // Bottom of the ladder. Going to sleep is not an emergency.
        sleepWakeFlash = new MomentaryFlashEffect("sleep_wake", 10);
        manager.register(sleepWakeFlash);

        verifyEverythingWired();
    }

    /**
     * Fails loudly if any effect field was left unassigned.
     *
     * <p>This method is over thirty assignments long, every one of them
     * {@code field = new Something(...)} followed by a register call, and the
     * fields are declared a hundred lines away from where they are filled in.
     * Drop one — an edit that lands on the wrong line, a merge that eats a
     * block — and the code still compiles perfectly: the field is simply null.
     *
     * <p>What happens then is the worst kind of failure. The effect never
     * draws, and every poll that touches it throws an NPE onto the SDK worker,
     * which catches and logs and carries on by design. So the symptom is one
     * feature quietly missing plus a warning nobody reads, rather than
     * anything that points at the cause.
     *
     * <p>Reflection over the public fields rather than a hand-written list,
     * because a hand-written list is the same kind of thing that goes stale.
     */
    private void verifyEverythingWired() {
        StringBuilder missing = new StringBuilder();
        for (java.lang.reflect.Field field : EffectRegistry.class.getDeclaredFields()) {
            if (!EffectController.class.isAssignableFrom(field.getType())) continue;
            try {
                if (field.get(this) == null) {
                    if (missing.length() > 0) missing.append(", ");
                    missing.append(field.getName());
                }
            } catch (IllegalAccessException ignored) {
                // Every one of these is public; if that ever changes this
                // check quietly covers less, which beats it throwing.
            }
        }
        if (missing.length() > 0) {
            RGBProfileMod.LOGGER.error(
                    "RGB Profile: effects were never constructed and will not work: {}. "
                            + "This is a bug in registerAll — the field is declared but nothing "
                            + "assigns it.", missing);
            BackendHealth.bug("Effects", "These effects were never set up and will not work: " + missing + ".", null);
        }
    }

    /** The three title-screen themes, so the pattern and its two colours cannot disagree. */
    private enum MenuStyle {
        /** A sulfur cave, matching Minecraft 26.2's own panorama. The default. */
        SULFUR,
        /** Sky over grass, matching the panorama the game had through 1.21.x. */
        VANILLA,
        /** The Forge Everything pack's title art. */
        MINESHAFT,
        /** The Sift: coral mesas under an aurora. Development builds only; see {@code Feature.SIFT}. */
        SIFT
    }

    /**
     * Picks the title-screen theme.
     *
     * <p>Default is the sulfur cave, because that is what Minecraft 26.2 puts
     * on the title screen: matching the panorama is not a decorating decision
     * the way the mineshaft theme is, it is the board agreeing with the screen
     * it sits under. The mineshaft theme is drawn for one pack's title art and
     * looks like a mistake anywhere else, so it stays opt-in — either by naming
     * it outright, or by {@code auto} recognising the pack.
     *
     * <p>The sky-over-grass theme is still here and still worth having. Anyone
     * running a resource pack that restores the old panorama wants it, and it
     * is the only theme that is about the overworld rather than about a cave.
     */
    private static Pattern menuPattern() {
        return switch (menuStyle()) {
            case MINESHAFT -> new MenuAmbientPattern(
                    RGBColor.fromHexOrDefault(RGBProfileConfig.MENU_EMBER_COLOR.get(), ColorPalette.MENU_EMBER),
                    RGBColor.fromHexOrDefault(RGBProfileConfig.MENU_ARCANE_COLOR.get(), ColorPalette.MENU_ARCANE),
                    RGBProfileConfig.MENU_SPARK_COUNT.get(),
                    RGBProfileConfig.MENU_SPARK_INTERVAL_MILLIS.get(),
                    RGBProfileConfig.MENU_ORE_GLINT_COUNT.get(),
                    RGBProfileConfig.MENU_ORE_GLINT_INTERVAL_MILLIS.get(),
                    RGBProfileConfig.MENU_TORCH_COUNT.get());
            case VANILLA -> new VanillaMenuPattern(RGBProfileConfig.MENU_VANILLA_CLOUD_COUNT.get());
            case SULFUR -> SulfurCavePattern.menu();
            case SIFT -> SiftPattern.menu();
        };
    }

    /**
     * The base colour the chosen pattern paints on: cave wall, unlit rock, or
     * grass.
     *
     * <p>All three themes take their second colour from the context's accent,
     * so this pairs with {@code menuAccentColor()} and the two must agree about
     * which theme is running — which is what the enum is for. An earlier
     * version asked a {@code useMineshaftMenu()} boolean three separate times,
     * which was fine while there were two themes and would have needed two
     * booleans that could contradict each other once there were three.
     */
    private static RGBColor menuBaseColor() {
        return switch (menuStyle()) {
            case MINESHAFT ->
                    RGBColor.fromHexOrDefault(RGBProfileConfig.MENU_BASE_COLOR.get(), ColorPalette.MENU_STONE_BASE);
            case VANILLA ->
                    RGBColor.fromHexOrDefault(RGBProfileConfig.MENU_VANILLA_GRASS_COLOR.get(), ColorPalette.MENU_VANILLA_GRASS);
            case SULFUR ->
                    RGBColor.fromHexOrDefault(RGBProfileConfig.MENU_SULFUR_ROCK_COLOR.get(), ColorPalette.SULFUR_ROCK);
            case SIFT ->
                    RGBColor.fromHexOrDefault(RGBProfileConfig.MENU_SIFT_MESA_COLOR.get(), ColorPalette.SIFT_MESA);
        };
    }

    /**
     * The second colour, which each theme reads differently: the vanilla theme
     * takes it as the sky, the sulfur theme as the acid pool, and the mineshaft
     * theme ignores it entirely because it derives everything warm from its own
     * ember colour instead.
     *
     * <p>Set unconditionally rather than only for the themes that read it,
     * because an accent nobody reads costs nothing and a null one would have
     * {@code resolvedAccentColor()} quietly hand the shading code a lightened
     * version of the base.
     */
    private static RGBColor menuAccentColor() {
        return switch (menuStyle()) {
            case SULFUR ->
                    RGBColor.fromHexOrDefault(RGBProfileConfig.MENU_SULFUR_POOL_COLOR.get(), ColorPalette.SULFUR_ACID_POOL);
            case SIFT ->
                    RGBColor.fromHexOrDefault(RGBProfileConfig.MENU_SIFT_SKY_COLOR.get(), ColorPalette.SIFT_SKY);
            case VANILLA, MINESHAFT ->
                    RGBColor.fromHexOrDefault(RGBProfileConfig.MENU_VANILLA_SKY_COLOR.get(), ColorPalette.MENU_VANILLA_SKY);
        };
    }

    /** Resolved once at registration, because the theme does not change mid-session. */
    private static MenuStyle menuStyle() {
        String style = RGBProfileConfig.MENU_STYLE.get();
        if (style == null) return MenuStyle.SULFUR;
        return switch (style.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "mineshaft" -> MenuStyle.MINESHAFT;
            case "vanilla" -> MenuStyle.VANILLA;
            case "sulfur" -> MenuStyle.SULFUR;
            // Only in a build that carries the Sift. In a release it falls
            // through to the default below, the same as any unknown value.
            case "sift" -> Feature.SIFT.isOn() ? MenuStyle.SIFT : MenuStyle.SULFUR;
            // Anything unrecognised behaves as auto rather than throwing,
            // because a typo in a config file does not get to decide whether
            // the mod starts. Note this is the only path that reaches
            // PackDetection at all: the default of "vanilla" returns above
            // without ever touching the disk.
            default -> PackDetection.matches(RGBProfileConfig.MENU_PACK_PATTERNS.get())
                    ? MenuStyle.MINESHAFT
                    : MenuStyle.SULFUR;
        };
    }

    /**
     * Turns a config key label such as {@code "H"} into a real LED, using the
     * named-key table the connected backend handed over.
     *
     * <p>On Corsair that table comes from
     * {@code CorsairGetLedLuidForKeyName}, which respects the user's actual
     * keyboard layout (checked on a K70 RGB RAPIDFIRE: H→47, F→45, T→33,
     * K→49). Razer's comes from its documented key table. Logitech and
     * SteelSeries document no key positions whatsoever, so they have no table
     * at all and every label falls back as described below.
     *
     * <p>Note also that {@code KeyGrid.namedKey} only reads the first
     * character of the label, so {@code "F5"} resolves to F rather than to F5
     * or to null.
     *
     * <p>Returns null, meaning "the whole primary surface", when the key isn't
     * present — after logging a warning naming both the effect AND the key, so
     * the message is something a person can act on.
     *
     * <p><b>Why any of this exists:</b> an earlier version passed the raw
     * string straight down to the SDK layer, where an unrecognised label
     * resolved to LED id 0. Which is a real, valid, perfectly good LED. So a
     * typo'd config key did not error, it silently lit up some unrelated key
     * and kept doing it forever.
     *
     * <p>Failing loudly to the whole board is enormously easier to diagnose
     * than one mystery key nobody can explain.
     */
    private static List<KeyGrid.LedRef> resolveKey(KeyGrid grid, String label, String effectId) {
        KeyGrid.LedRef ref = grid.namedKey(label);
        if (ref == null) {
            RGBProfileMod.LOGGER.warn(
                    "RGB Profile: {} is configured for key '{}', which isn't on any connected keyboard "
                            + "— falling back to the whole board.", effectId, label);
            BackendHealth.problem("Config", effectId + " is set to light the '" + label + "' key, which was not "
                    + "found on the connected keyboard, so it lights the whole board instead. Either the setting "
                    + "has a typo, or the lighting software cannot say where keys are (Logitech and SteelSeries "
                    + "cannot).", null);
            return null;
        }
        return List.of(ref);
    }

}
