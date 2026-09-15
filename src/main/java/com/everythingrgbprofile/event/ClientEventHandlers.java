package com.everythingrgbprofile.event;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.debug.Diagnostics;
import com.everythingrgbprofile.debug.EndRitualTracer;
import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.pattern.patterns.CoreEmitterPattern;
import com.everythingrgbprofile.pattern.patterns.SpiralInPattern;
import com.everythingrgbprofile.color.ColorRamp;
import com.everythingrgbprofile.compat.ModCompatRegistry;
import com.everythingrgbprofile.compat.betterendisland.BetterEndIslandCompat;
import com.everythingrgbprofile.compat.aether.AetherCompat;
import com.everythingrgbprofile.effects.support.SunSpiritEffect;
import com.everythingrgbprofile.effects.support.ValkyrieQueenEffect;
import com.everythingrgbprofile.compat.twilightforest.TwilightForestCompat;
import com.everythingrgbprofile.compat.toughasnails.ToughAsNailsCompat;
import com.everythingrgbprofile.config.Feature;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.detect.SculkBlockWatcher;
import com.everythingrgbprofile.effects.EffectRegistry;
import com.everythingrgbprofile.effects.support.EnderDragonEffect;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.FadePattern;
import com.everythingrgbprofile.pattern.patterns.FlashOncePattern;
import com.everythingrgbprofile.pattern.patterns.LevelUpPattern;
import com.everythingrgbprofile.pattern.patterns.RingContractPattern;
import com.everythingrgbprofile.pattern.patterns.RingExpandPattern;
import com.everythingrgbprofile.pattern.patterns.SpiralInPattern;
import com.everythingrgbprofile.profile.BossProfile;
import com.everythingrgbprofile.profile.DimensionProfile;
import com.everythingrgbprofile.profile.ProfileResolver;
import com.everythingrgbprofile.sdk.SdkWorkerThread;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import com.everythingrgbprofile.pattern.patterns.RaidHordePattern;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ToastAddEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where Minecraft meets the lighting. Every event subscription and every poll
 * lives here — this file is the entire boundary between "the game" and "the
 * keyboard".
 *
 * <h2>The pattern, repeated ~20 times</h2>
 * Every handler is deliberately tiny and shaped identically:
 * <b>compute, compare, enqueue</b>. Read some game state, check whether it
 * actually changed, and if so hand a small job to {@link SdkWorkerThread}.
 *
 * <p>Nothing here ever touches an effect object or SDK state directly.
 * That is not style, it's the thread-safety contract — see
 * {@code SdkWorkerThread} for what happens when two threads call into a native
 * DLL at once. Spoiler: no stack trace.
 *
 * <p>The <b>compare</b> step matters as much as the rest. Almost every poll
 * fires on a THRESHOLD CROSSING, not on the condition being true. Health
 * below 25% enqueues once when you cross it, not sixty times a second while
 * you stand there bleeding.
 *
 * <h2>What is wired and what is not</h2>
 * Read this before assuming a hook works, because a hook that silently never
 * fires is far worse than one with a comment admitting it does not.
 *
 * <p><b>Fully wired:</b> health, hunger, death, XP, advancement, biome, time
 * of day, weather, portal transition, drowning, burning, raids, sculk sensors
 * and shriekers, the Warden, bosses, and the whole Ender Dragon sequence.
 *
 * <p><b>Approximate — lightning.</b> {@link #pollLightningFallback} fires on a
 * timer during a thunderstorm rather than on actual strikes, so it is
 * atmosphere rather than information. Real strike detection wants
 * {@code EntityJoinLevelEvent} filtered to lightning bolts with a distance
 * check; there is a commented-out sketch of it near the weather polls.
 *
 * <h2>A note on where hooks are placed</h2>
 * Several detectors here deliberately listen lower in the class hierarchy than
 * you might expect — the level rather than the player, block state rather than
 * game events. That is not accidental. In a large pack the obvious high-level
 * hooks are exactly the ones every other mod has mixined into, and arriving
 * last means arriving after several seconds of shader and chunk work. See
 * {@link #onLevelLoad} for the case that made the point.
 */
@EventBusSubscriber(modid = RGBProfileMod.MODID, value = Dist.CLIENT)
public final class ClientEventHandlers {

    private static int tickCounter = 0;
    private static boolean lastHealthBelowThreshold = false;
    private static boolean lastHungerBelowThreshold = false;
    private static boolean lastThirstBelowThreshold = false;
    private static boolean lastOverheating = false;
    private static boolean lastFreezing = false;
    private static String lastBiomeId = null;
    private static String pendingBiomeId = null;
    private static long pendingBiomeSinceMillis = 0;
    private static String lastDimensionId = null;
    private static boolean lastInPortal = false;
    private static boolean lastRaining = false;
    /** When the player last stopped being exposed to the sky, for the shelter grace. */
    private static long rainShelteredSinceMillis = 0;
    /** The raid phase as of the last poll, or null for no raid. */
    private static RaidHordePattern.Phase lastRaidPhase = null;
    private static long raidLastSeenMillis = 0;
    /** The longest Raid Omen duration seen this countdown, which is where it started. */
    private static int raidOmenStartTicks = 0;
    /**
     * The {@code c:bosses} convention tag.
     *
     * <p>Built by hand rather than taken from NeoForge's {@code Tags} class,
     * which has moved package between versions — this is two lines and cannot
     * break on an update. Tags sync to clients, so this is readable here.
     */
    private static final TagKey<EntityType<?>> BOSSES_TAG =
            TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("c", "bosses"));

    /**
     * Bearings, in whole degrees, of every tower that has lit this ritual.
     *
     * <p>Remembered rather than recounted each poll. The capture shows towers
     * staying lit right up until the dragon arrives, so the live count is
     * normally correct — but a crystal that slips out of client tracking would
     * otherwise put its ray out, and a tower that has fired has fired.
     */
    private static final Set<Integer> endLockedBearings = new HashSet<>();
    /** Where the central crystals were last aiming, to catch the jump to a new tower. */
    private static int endLastSweepDegrees = Integer.MIN_VALUE;

    /** Horizontal distance from the world origin inside which something is "the middle". */
    private static final double END_CENTRE_RADIUS = 12.0;

    private static boolean witherPresent = false;
    private static long witherLastSeenMillis = 0;
    private static int witherLastSkulls = 0;
    /** Spreads consecutive shots across the three heads so they take turns. */
    private static int witherSkullRotation = 0;

    private static boolean nagaPresent = false;
    /** Last time the Naga was actually seen, for the grace period. */
    private static long nagaLastSeenMillis = 0;

    private static boolean sliderPresent = false;
    private static long sliderLastSeenMillis = 0;
    /** Latched, so a Slider lying dead for its 20-tick death animation bursts once rather than every tick. */
    private static boolean sliderDeathReported = false;

    private static boolean sunSpiritPresent = false;
    private static long sunSpiritLastSeenMillis = 0;
    private static boolean sunSpiritDeathReported = false;

    private static boolean valkyrieQueenPresent = false;
    private static long valkyrieQueenLastSeenMillis = 0;
    private static boolean valkyrieQueenDefeatReported = false;
    /** Latched once her walls have been found, so the scan stops until the next time she is found. */
    private static boolean valkyrieRoomFound = false;

    private static boolean elderGuardianPresent = false;
    private static long elderGuardianLastSeenMillis = 0;
    /**
     * Whether the current wind-up has already been reported as fired.
     *
     * <p>The charge sits at 1 for as long as the Guardian keeps its target, so
     * without a latch the landing flash would retrigger every tick for the
     * rest of the lock. Cleared when the charge drops away, which is what
     * happens the moment the beam releases or the target is lost.
     */
    private static boolean elderGuardianBeamFired = false;
    /**
     * Mining Fatigue's remaining duration as of the last poll, in ticks.
     *
     * <p>The curse landing is not an event the client is handed — vanilla
     * sends a game-event packet for the sound and the ghost, and hooking that
     * needs a mixin. It is visible in the debuff itself though: an Elder
     * Guardian applies a fresh 6000-tick Mining Fatigue every 60 seconds, so a
     * duration that has gone <i>up</i> since the last look is a curse that has
     * just landed. Ticking down is just time passing.
     */
    private static int elderGuardianCurseTicks = 0;

    /** Entity id of the boss currently owning the board, or null. */
    private static String currentBossId = null;
    private static long lastBossSeenMillis = 0;
    /** Parsed form of the exclusion config, recomputed only when the string changes. */
    private static String excludedBossRaw = null;
    private static Set<String> excludedBossIds = Set.of();
    /** Namespaces excluded wholesale by a {@code modid:*} entry. */
    private static Set<String> excludedBossMods = Set.of();

    private static boolean lastWardenPresent = false;
    /** Entity ids of Wardens we have already flashed for, so one arrival is one flash. */
    private static final java.util.Set<Integer> wardenFlashed = new java.util.HashSet<>();
    /** Client-side sculk activation detector; see SculkBlockWatcher. */
    private static final SculkBlockWatcher sculkWatcher = new SculkBlockWatcher();
    private static final SculkBlockWatcher.Listener SCULK_LISTENER = new SculkBlockWatcher.Listener() {
        @Override
        public void onSensorActivated() {
            ClientEventHandlers.onSculkSensorActivated(System.currentTimeMillis());
        }

        @Override
        public void onShriekerActivated() {
            ClientEventHandlers.onSculkShriekerActivated(System.currentTimeMillis());
        }
    };
    private static long lightningLastFallbackMillis = 0;
    private static boolean sleeping = false;
    /** True while the local player is dead and waiting on the respawn screen. */
    private static boolean dead = false;

    /** True while the current biome Holder has no unwrappable registry key. */
    private static boolean biomeUnresolved = false;

    /** Set when a player clone was seen but the new dimension was not yet readable. */
    private static volatile boolean dimensionChangePending = false;

    /** Wall-clock ms when portal intersection was last detected, for debugLogging. */
    private static long portalDetectedAtMillis = 0;

    /** True between a dimension change and the terrain screen clearing. */
    private static boolean awaitingWorldReady = false;

    /**
     * Whether a world was loaded as of the last observation. The edge from
     * true to false is the only signal that a world went away, and it is the
     * thing this file previously had no notion of at all.
     */
    private static boolean inWorld = false;

    /**
     * Last value handed to the menu theme, so the poll can run every tick and
     * still only enqueue on a genuine edge. Null = never set.
     */
    private static Boolean lastMenuActive = null;

    /** Wall-clock ms the above was armed, for debugLogging the load length. */
    private static long worldReadyArmedAtMillis = 0;

    /**
     * The heartbeat. Every polled effect is driven from here.
     *
     * <p>Two cadences, deliberately: a small set of things run EVERY tick
     * because they're discrete moments where latency is visible, and
     * everything else runs behind the {@code updateIntervalTicks} gate because
     * it's ambient state that nobody can perceive changing at 20Hz.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        EffectRegistry effects = SdkWorkerThread.effects();
        // null = gating never started the worker (no hardware, disabled, wrong
        // OS, dedicated server). Costs one null check per tick and buys total
        // silence on machines this mod is irrelevant to.
        if (effects == null) return;

        Minecraft mc = Minecraft.getInstance();

        // Ahead of the menu theme AND the null-guard below, and ungated by
        // updateIntervalTicks. It is one boolean compare; at the default
        // interval of 20 a gated version would leave the departed world's
        // lighting on the board for up to a second after the menu appeared.
        pollWorldUnload(mc, effects);

        // Menu theme has to be checked before the player/level null-guard
        // below, since "no world loaded" (title screen, server list, mod
        // list, pause-to-menu, etc.) is exactly the condition it's keyed on.
        tickCounter++;
        int interval = RGBProfileConfig.UPDATE_INTERVAL_TICKS.get();
        // Every tick now, not every updateIntervalTicks. It enqueues only on a
        // real edge (see pollMenuTheme), so running it 20x more often is
        // cheaper than the old gated version was, not dearer.
        pollMenuTheme(mc, effects, System.currentTimeMillis());

        Player player = mc.player;
        if (player == null || mc.level == null) return;

        long now = System.currentTimeMillis();

        // Dimension change and portal dwell are discrete moments, not ambient
        // state, so they run every tick rather than at updateIntervalTicks.
        // At the default interval of 20 the arrival spiral would otherwise
        // land up to a full second after the screen finished loading, which
        // reads as unrelated to the portal. Both checks are cheap: a
        // ResourceKey comparison and two block lookups.
        Diagnostics.clientTickSeen(now);

        // Fast path: dying is a discrete moment and the flash has to land on it,
        // not up to a second later.
        pollDeath(player, effects, now);
        // Every tick: the level-up is a moment, and a second late it plays
        // after the sound it belongs to.
        pollLevelUp(player, effects, now);

        pollDimension(mc, effects, now);
        pollPortalWorldReady(mc, effects, now);
        pollPortalCharge(mc, player, effects, now);
        // Every tick. Air supply changes once per tick underwater, and the
        // whole point of the effect is the value between full and empty.
        pollDrowning(player, effects);
        // Every tick too: stepping into lava should flare the board as it
        // happens, not up to a second later.
        pollBurning(player, effects);
        // Fast path: the dragon's phase changes are the beats of the fight,
        // and a beam landing is a discrete moment. Both look wrong a second late.
        pollEndDragon(player, effects, now);
        pollNaga(player, effects, now);
        // Fast path: a slide lasts a second or two and ends in a slam, and the
        // board has to move with the cube rather than a second behind it.
        pollSlider(mc, player, effects, now);
        // Fast path: a crystal crosses the room in a second or two, and a
        // freeze is a window of under nine seconds.
        pollSunSpirit(mc, player, effects, now);
        // Fast path: a teleport is over in three ticks, and a lunge in eight.
        pollValkyrieQueen(mc, player, effects, now);
        pollWither(player, effects, now);
        pollRaid(mc, player, effects, now);
        // Fast path: three seconds of laser wind-up is the whole effect, and a
        // second of that is a third of it. Cheap when there is nothing to
        // find — see pollElderGuardian for the scan cadence.
        pollElderGuardian(player, effects, now);

        pollSculkBlocks(player);

        // Biome runs on its own cadence, above the ambient gate. Sharing the
        // one-second group put a change between one and two seconds behind the
        // player: one interval to notice it, then a whole second one to satisfy
        // a debounce that was shorter than the interval and so never actually
        // filtered anything.
        if (tickCounter % Math.max(1, RGBProfileConfig.BIOME_SAMPLE_INTERVAL_TICKS.get()) == 0) {
            pollBiome(player, effects, now);
        }

        // Everything past this line is ambient state on the slow cadence.
        // Default interval is 20 ticks = once per second, which is plenty for
        // "what biome am I in" and saves ~19 of every 20 polls.
        if (tickCounter % interval != 0) return;

        pollHealth(player, effects, now);
        pollHunger(player, effects, now);
        pollTimeOfDay(player, effects);
        pollWeather(player, effects, now);
        pollBossEncounter(player, effects, now);
        pollWarden(player, effects, now);
        pollToughAsNails(player, effects, now);
        pollLightningFallback(effects, now);
        pollSleepState(player, effects, now);
    }

    // ---------------------------------------------------------------
    // World unload — the teardown half of the tick loop.
    //
    // onClientTick returns at `player == null || mc.level == null`, so when a
    // world goes away every poll below that line simply stops running. Not one
    // of them gets a final call, which means every effect they drive keeps the
    // last state it was handed, indefinitely. pollMenuTheme is the sole
    // exception — it sits above the guard — so the menu theme does switch on.
    // It just had to fight everything that never switched off:
    //
    //   - wardenActive is Tier 1 priority 11 against menuTheme's 1, so
    //     quitting near a Warden left the title screen holding sculk ambience.
    //   - rainCascade is a whole-board Tier 2 overlay, and Tier 2 has no
    //     priority contest: everyone draws. Quit in a thunderstorm and the
    //     menu theme rendered underneath a permanent grey-blue drift.
    //   - health/hunger/thirst/temperature stayed pulsing on their keys.
    //   - biomeColor's isActive is `currentBiomeId != null` — "active from
    //     the first biome we ever see, then forever". Priority 0 keeps it
    //     hidden under the menu theme, so it only surfaced with menuTheme
    //     disabled in config, and then it was the whole board: the last
    //     biome's colour and pattern, at the main menu, permanently.
    //
    // Both entry points funnel through endWorldSession so whichever observes
    // the unload first wins and the second is a no-op.
    // ---------------------------------------------------------------

    /**
     * The catch-all. Fires on the tick after {@code mc.level} goes null,
     * whatever route took it there — Disconnect, Save and Quit, a kick, a
     * connection drop, or a pack's own bespoke path back to the menu. Screen
     * subclasses are deliberately not consulted, for the same reason
     * pollMenuTheme doesn't consult them.
     */
    private static void pollWorldUnload(Minecraft mc, EffectRegistry effects) {
        if (mc.level != null) {
            inWorld = true;
            return;
        }
        endWorldSession(effects, "tick-poll");
    }

    /**
     * The prompt one. {@code LoggingOut} lands during teardown rather than a
     * tick or more after it, which is the difference between the board
     * changing as the menu appears and changing visibly afterwards.
     *
     * <p>Kept alongside the tick poll rather than replacing it: this event
     * covers a disconnect, and the poll covers everything a disconnect isn't.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null) return;
        endWorldSession(effects, "logging-out event");
    }

    /** Guarded so first observer wins, exactly like checkDimensionChange. */
    private static synchronized void endWorldSession(EffectRegistry effects, String source) {
        if (!inWorld) return;
        inWorld = false;
        resetWorldState(effects, System.currentTimeMillis(), source);
    }

    /**
     * Hands every world-driven effect its missing "off", and wipes the poll
     * caches so the next world is read fresh rather than compared against the
     * last one.
     */
    private static void resetWorldState(EffectRegistry effects, long now, String source) {
        if (portalDebug()) {
            RGBProfileMod.LOGGER.info("[rgb] world unloaded (via {}) — releasing all world-driven effects", source);
        }

        // --- Client-thread poll caches --------------------------------
        // Every threshold poll fires on a CHANGE, so a stale cache means the
        // new world gets compared against the old one's readings. Quit
        // starving and rejoin still starving and the hunger warning never
        // re-arms — the compare sees no change, because as far as it knows
        // nothing happened. Clearing these makes the first poll of a new
        // world re-establish the truth unconditionally.
        lastHealthBelowThreshold = false;
        lastHungerBelowThreshold = false;
        lastThirstBelowThreshold = false;
        lastOverheating = false;
        lastFreezing = false;
        lastRaining = false;
        rainShelteredSinceMillis = 0;
        lastRaidPhase = null;
        raidLastSeenMillis = 0;
        raidOmenStartTicks = 0;
        currentBossId = null;
        lastBossSeenMillis = 0;
        nagaPresent = false;
        nagaLastSeenMillis = 0;
        sliderPresent = false;
        sliderLastSeenMillis = 0;
        sliderDeathReported = false;
        sunSpiritPresent = false;
        sunSpiritLastSeenMillis = 0;
        sunSpiritDeathReported = false;
        valkyrieQueenPresent = false;
        valkyrieQueenLastSeenMillis = 0;
        valkyrieQueenDefeatReported = false;
        valkyrieRoomFound = false;
        elderGuardianPresent = false;
        elderGuardianLastSeenMillis = 0;
        elderGuardianBeamFired = false;
        elderGuardianCurseTicks = 0;
        witherPresent = false;
        witherLastSeenMillis = 0;
        witherLastSkulls = 0;
        endLockedBearings.clear();
        endLastSweepDegrees = Integer.MIN_VALUE;
        EndRitualTracer.reset();
        lastWardenPresent = false;
        wardenFlashed.clear();
        sculkWatcher.reset();
        lastInPortal = false;
        sleeping = false;
        dead = false;
        levelWatchedPlayer = null;
        lastExperienceLevel = 0;
        levelSettleTicks = 0;
        lastBiomeId = null;
        pendingBiomeId = null;
        pendingBiomeSinceMillis = 0;
        biomeUnresolved = false;
        lightningLastFallbackMillis = 0;
        portalDetectedAtMillis = 0;

        // lastDimensionId is a special case worth spelling out: null is the
        // value checkDimensionChange reads as "first observation this session,
        // record it and stay silent". Any other value and loading a save fires
        // an arrival for a portal nobody walked through — quit to the title
        // screen from the Nether, open an overworld save, and the id really
        // did change from nether to overworld. The check was right; the
        // premise that a session lasts forever was wrong.
        lastDimensionId = null;
        dimensionChangePending = false;
        awaitingWorldReady = false;
        worldReadyArmedAtMillis = 0;

        // The handover, not merely the teardown. Turning everything off and
        // leaving pollMenuTheme to notice on its next run cost a measured
        // 1.225s of totally black keyboard on the way back to the title
        // screen: the reset landed at 13:56:21.176 and menu_theme did not come
        // up until 13:56:22.401, because the client tick loop stalls for over
        // a second while it tears the world down and builds the menu, and the
        // poll cannot run during a stall no matter how often it is scheduled.
        // Only doing it here, on the event, closes the gap — so the last thing
        // the teardown job does is switch the menu theme on.
        boolean handOverToMenu = Feature.MENU_THEME.isOn();
        if (handOverToMenu) lastMenuActive = true;

        // --- Worker-thread effect state -------------------------------
        // One job, because these are meant to land on the same frame: turning
        // the base layer off a frame before the overlays would show a flash of
        // bare menu theme through them.
        SdkWorkerThread.enqueue(() -> {
            // Tier 1.
            effects.biomeColor.clear();
            effects.wardenActive.setActive(false, now);
            effects.raid.clear();
            effects.bossEncounter.endBoss();

            // Tier 2.
            effects.healthFlash.setActive(false, now);
            effects.hungerWarning.setActive(false, now);
            effects.thirstWarning.setActive(false, now);
            effects.temperatureWarning.setActive(false, now);
            effects.rainCascade.setActive(false, now);
            effects.nightIndicator.setNightProgress(false, 0);
            effects.drowning.reset();
            effects.burning.reset();
            effects.enderDragon.setIdle();
            // Snapped, not faded: the world is gone, so there is nothing left
            // for the snake to dissolve into.
            effects.naga.setGone(now);
            effects.slider.clear();
            effects.sunSpirit.clear();
            effects.valkyrieQueen.clear();
            effects.wither.setIdle();
            effects.portalCharge.setActive(false, now);
            effects.portalTransitionFlash.cancel();
            effects.wardenEmergenceFlash.cancel();
            // Quitting to the menu counts as leaving the death screen.
            effects.deathState.setActive(false, now);

            // Tier 3 needs nothing: momentary flashes are duration-based and
            // expire on their own inside a second or two. The escalation
            // counter is not momentary though — it is a running tally, and a
            // new world should start at zero shrieks rather than inheriting
            // whatever alert level the last one ended on.
            effects.shriekerEscalation.reset();

            // Same job, last statement: the board goes from world lighting to
            // menu lighting within one frame, with no black in between.
            if (handOverToMenu) effects.menuTheme.setActive(true, now);
        });
    }

    // ---------------------------------------------------------------
    // Main-menu ambient theme — active whenever no world is loaded.
    // ---------------------------------------------------------------
    private static void pollMenuTheme(Minecraft mc, EffectRegistry effects, long now) {
        if (!Feature.MENU_THEME.isOn()) return;
        // mc.level == null IS the "at a menu" test, and it's better than
        // checking for a Screen subclass. It covers the title screen, server
        // list, realms, mod list, and pause-after-disconnect identically —
        // including all the bespoke menu screens a big pack adds, which this
        // mod could not possibly enumerate and has no business knowing about.
        boolean atMenu = mc.level == null;
        // Edge-triggered, like every other poll in this file. Without this the
        // ungated call above would enqueue a job every single tick forever.
        if (lastMenuActive != null && lastMenuActive == atMenu) return;
        lastMenuActive = atMenu;
        SdkWorkerThread.enqueue(() -> effects.menuTheme.setActive(atMenu, now));
    }

    // ---------------------------------------------------------------
    // The Ender Dragon: summoning ritual, fight, and death.
    //
    // Everything here comes off SynchedEntityData, which is what makes it work
    // without importing anything and without a server component:
    //
    //   - EndCrystal.getBeamTarget() is synced because vanilla has to render
    //     the beam. Counting lit crystals IS the ritual's progress.
    //   - EnderDragon's phase is synced (see EnderDragon.onSyncedDataUpdated,
    //     which explicitly applies it on the client), so the fight can be
    //     phase-aware rather than health-aware.
    //   - dragonDeathTime is a public field ticked client-side across the
    //     200-tick death animation.
    //
    // YUNG's Better End Island keeps its DragonRespawnStage server-side and
    // ships no packets, so that enum is unreadable here — but it drives the
    // same crystals, so reading beams gets the sequence anyway, and the very
    // same code covers vanilla's four-crystal respawn.
    // ---------------------------------------------------------------
    private static void pollEndDragon(Player player, EffectRegistry effects, long now) {
        if (!Feature.END_DRAGON.isOn()) return;
        Level level = player.level();
        if (!(level instanceof ClientLevel clientLevel) || !Level.END.equals(level.dimension())) {
            endLockedBearings.clear();
            endLastSweepDegrees = Integer.MIN_VALUE;
            EndRitualTracer.reset();
            SdkWorkerThread.enqueue(() -> {
                effects.enderDragon.setBreathFire(false, 0.5, 1.0);
                effects.enderDragon.setIdle();
            });
            return;
        }
        boolean trace = traceEndRitual();

        // One pass over the client's tracked entities rather than an AABB
        // query: the dragon ranges over the whole island and the pillars are
        // 40+ blocks out, so any radius generous enough would be most of the
        // dimension anyway.
        EnderDragon dragon = null;
        int beaming = 0;
        // Every crystal is kept now, not just counted: the ritual needs each
        // one's position and beam target to work out which are towers and
        // where the central four are aiming. There are at most fourteen.
        List<EndCrystal> crystalsThisPoll = new ArrayList<>();
        AreaEffectCloud biggestBreath = null;
        int fireballs = 0;
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (entity instanceof EnderDragon found) {
                dragon = found;
            } else if (entity instanceof EndCrystal crystal) {
                if (crystal.getBeamTarget() != null) beaming++;
                crystalsThisPoll.add(crystal);
            } else if (entity instanceof DragonFireball) {
                fireballs++;
            } else if (entity instanceof AreaEffectCloud cloud
                    && cloud.getParticle().getType() == ParticleTypes.DRAGON_BREATH) {
                // Biggest wins: with several pools down, the one filling the
                // most ground is the one worth showing.
                if (biggestBreath == null || cloud.getRadius() > biggestBreath.getRadius()) {
                    biggestBreath = cloud;
                }
            }
        }
        pollDragonBreath(player, effects, biggestBreath);
        if (trace) {
            EndRitualTracer.observe(crystalsThisPoll, dragon, biggestBreath, fireballs, now);
        }

        if (dragon != null) {
            endLockedBearings.clear();
            endLastSweepDegrees = Integer.MIN_VALUE;
            // dragonDeathTime ticks 0 -> 200 through the death animation, and
            // is the only thing that distinguishes "dying spectacularly" from
            // "dead", since health is already zero for all of it.
            if (dragon.dragonDeathTime > 0 || dragon.getHealth() <= 0) {
                double progress = Math.min(1.0, dragon.dragonDeathTime / 200.0);
                SdkWorkerThread.enqueue(() -> effects.enderDragon.setDying(progress));
                return;
            }
            double health = dragon.getHealth() / Math.max(1.0f, dragon.getMaxHealth());
            EnderDragonEffect.Pose pose = dragonPose(dragon);
            SdkWorkerThread.enqueue(() -> effects.enderDragon.setFight(health, pose));
            return;
        }

        if (!Feature.END_RITUAL.isOn() || beaming <= 0) {
            endLockedBearings.clear();
            endLastSweepDegrees = Integer.MIN_VALUE;
            SdkWorkerThread.enqueue(effects.enderDragon::setIdle);
            return;
        }

        // Split the beams the way the ritual itself does. From a traced
        // respawn:
        //
        //   - Crystals OUT on the towers (r ~54) beam back to (0,128,0) once
        //     their tower has been activated, and stay lit. Their own bearing
        //     is the ray's angle, taken from the world rather than assumed
        //     evenly spaced — the real steps are 35-37 degrees, not a clean 36.
        //   - The four crystals in the middle (r ~8) all aim at ONE thing at a
        //     time: straight up at (0,128,0) during the opening five seconds
        //     and the closing five, and at the tower currently being activated
        //     for the twenty seconds in between. That target's bearing is the
        //     sweeping ray.
        boolean converging = false;
        boolean hasSweep = false;
        double sweepBearing = 0;
        for (EndCrystal crystal : crystalsThisPoll) {
            BlockPos target = crystal.getBeamTarget();
            if (target == null) continue;
            if (Math.hypot(crystal.getX(), crystal.getZ()) >= END_CENTRE_RADIUS) {
                endLockedBearings.add((int) Math.round(
                        Math.toDegrees(Math.atan2(crystal.getZ(), crystal.getX()))));
                continue;
            }
            if (Math.hypot(target.getX(), target.getZ()) < END_CENTRE_RADIUS) {
                converging = true;
            } else {
                hasSweep = true;
                sweepBearing = Math.atan2(target.getZ(), target.getX());
            }
        }

        // A jump to the next tower is the beat of the whole sequence, so it
        // gets a kick.
        if (hasSweep) {
            int degrees = (int) Math.round(Math.toDegrees(sweepBearing));
            if (degrees != endLastSweepDegrees) {
                endLastSweepDegrees = degrees;
                SdkWorkerThread.enqueue(() -> effects.enderDragon.pulseSurge(now));
            }
        } else {
            endLastSweepDegrees = Integer.MIN_VALUE;
        }

        double[] locked = new double[endLockedBearings.size()];
        int i = 0;
        for (Integer degrees : endLockedBearings) locked[i++] = Math.toRadians(degrees);
        double completion = Math.min(1.0,
                locked.length / (double) Math.max(1, BetterEndIslandCompat.expectedRitualSources()));

        boolean finalConverging = converging;
        boolean finalHasSweep = hasSweep;
        double finalSweep = sweepBearing;
        SdkWorkerThread.enqueue(() ->
                effects.enderDragon.setRitual(locked, finalSweep, finalHasSweep, finalConverging, completion));
    }

    /** Its own switch, or the master debug flag — same arrangement as portalDebug(). */
    private static boolean traceEndRitual() {
        return RGBProfileConfig.END_TRACE_RITUAL.get() || Diagnostics.enabled();
    }

    // ---------------------------------------------------------------
    // The Wither.
    //
    // Vanilla, so unlike the modded bosses this can use the class directly and
    // read its real state instead of inferring from velocity. All of it is
    // public and synced:
    //
    //   getInvulnerableTicks()   the 220-tick summon charge, DATA_ID_INV
    //   getAlternativeTarget(n)  what head n is tracking, DATA_TARGET_A/B/C
    //   isPowered()              health at or below half, when it armours up
    //
    // Skull count is tracked separately: a rise means one was just thrown, and
    // that is a better firing signal than any timer, because it is the real
    // rate of fire.
    // ---------------------------------------------------------------
    private static void pollWither(Player player, EffectRegistry effects, long now) {
        if (!Feature.WITHER.isOn()) return;
        if (!(player.level() instanceof ClientLevel clientLevel)) return;

        double maxDistance = RGBProfileConfig.WITHER_DETECTION_RADIUS.get();
        double maxDistanceSq = maxDistance * maxDistance;

        WitherBoss wither = null;
        int skulls = 0;
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (entity instanceof WitherBoss found) {
                if (found.isAlive() && found.distanceToSqr(player) <= maxDistanceSq) wither = found;
            } else if (entity instanceof WitherSkull) {
                skulls++;
            }
        }

        if (wither == null) {
            if (!witherPresent) return;
            if (now - witherLastSeenMillis < RGBProfileConfig.WITHER_GRACE_MILLIS.get()) return;
            witherPresent = false;
            witherLastSkulls = 0;
            SdkWorkerThread.enqueue(effects.wither::setIdle);
            return;
        }

        witherPresent = true;
        witherLastSeenMillis = now;

        // A skull appearing is the only honest "it just fired" signal — the
        // heads have no synced attack flag, only a target.
        if (skulls > witherLastSkulls) {
            int fired = skulls - witherLastSkulls;
            for (int i = 0; i < Math.min(3, fired); i++) {
                int head = (witherSkullRotation++) % 3;
                SdkWorkerThread.enqueue(() -> effects.wither.fireSkull(head, now));
            }
        }
        witherLastSkulls = skulls;

        int invulnerable = wither.getInvulnerableTicks();
        if (invulnerable > 0) {
            SdkWorkerThread.enqueue(() -> effects.wither.setSummoning(invulnerable));
            return;
        }

        double health = wither.getHealth() / Math.max(1.0f, wither.getMaxHealth());
        boolean powered = wither.isPowered();
        // A head reports 0 when it is not tracking anything.
        boolean[] locked = new boolean[3];
        for (int i = 0; i < 3; i++) locked[i] = wither.getAlternativeTarget(i) > 0;
        SdkWorkerThread.enqueue(() -> effects.wither.setFight(health, powered, locked));
    }

    // ---------------------------------------------------------------
    // The Elder Guardian.
    //
    // Vanilla again, so the real state is readable rather than inferred, and
    // all of it is synced and public:
    //
    //   hasActiveAttackTarget()    the beam is locked on, DATA_ID_ATTACK_TARGET
    //   getActiveAttackTarget()    and on whom
    //   getAttackAnimationScale()  how far through the 60-tick wind-up it is
    //   getSpikesAnimation()       the crown of spikes, out when it hovers
    //
    // Mining Fatigue comes off the player rather than the mob, because that is
    // where it lands.
    // ---------------------------------------------------------------

    /**
     * Ticks between scans while none is in range.
     *
     * <p>The other bosses poll every tick because they walk the tracked-entity
     * list they were going to walk anyway. This one wants a box query, and the
     * charge it is watching for only exists while a Guardian is already in
     * range — so it scans twice a second until it finds one and then every
     * tick, which keeps the wind-up smooth without paying for it in the
     * overwhelming majority of ticks where the nearest monument is nowhere
     * near.
     */
    private static final int ELDER_GUARDIAN_SCAN_INTERVAL_TICKS = 10;

    private static void pollElderGuardian(Player player, EffectRegistry effects, long now) {
        if (!Feature.ELDER_GUARDIAN.isOn()) return;
        if (!elderGuardianPresent && tickCounter % ELDER_GUARDIAN_SCAN_INTERVAL_TICKS != 0) return;

        double radius = RGBProfileConfig.ELDER_GUARDIAN_DETECTION_RADIUS.get();
        List<ElderGuardian> nearby = player.level().getEntitiesOfClass(
                ElderGuardian.class, player.getBoundingBox().inflate(radius));

        // The one aiming at you wins, then the nearest. In a monument room
        // with three of them, the one winding up at you is the only one whose
        // state you need, and picking the nearest instead would show you a
        // calm eye while a beam was landing.
        ElderGuardian best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (ElderGuardian g : nearby) {
            if (!g.isAlive()) continue;
            boolean atYou = g.hasActiveAttackTarget() && g.getActiveAttackTarget() == player;
            double distSq = g.distanceToSqr(player);
            boolean bestAtYou = best != null && best.hasActiveAttackTarget()
                    && best.getActiveAttackTarget() == player;
            if (best == null || (atYou && !bestAtYou) || (atYou == bestAtYou && distSq < bestDistSq)) {
                best = g;
                bestDistSq = distSq;
            }
        }

        if (best == null) {
            if (!elderGuardianPresent) return;
            // Grace before letting go: monuments are a maze of walls and a
            // Guardian behind one is still very much in the fight.
            if (now - elderGuardianLastSeenMillis
                    < RGBProfileConfig.ELDER_GUARDIAN_GRACE_MILLIS.get()) return;
            elderGuardianPresent = false;
            elderGuardianBeamFired = false;
            elderGuardianCurseTicks = 0;
            SdkWorkerThread.enqueue(() -> effects.elderGuardian.setGone(now));
            return;
        }

        elderGuardianPresent = true;
        elderGuardianLastSeenMillis = now;

        boolean locked = best.hasActiveAttackTarget();
        boolean atYou = locked && best.getActiveAttackTarget() == player;
        // Only meaningful while it has a target: clientSideAttackTime is not
        // zeroed until the target changes, so outside the lock this reads as
        // whatever the last wind-up reached.
        double charge = locked
                ? Math.max(0, Math.min(1, best.getAttackAnimationScale(1.0f))) : 0;
        double spikes = Math.max(0, Math.min(1, best.getSpikesAnimation(1.0f)));

        // The top of the wind-up is the moment it fires. Latched, because the
        // charge stays pinned at 1 for as long as it holds the target.
        if (atYou && charge >= 0.98) {
            if (!elderGuardianBeamFired) {
                elderGuardianBeamFired = true;
                SdkWorkerThread.enqueue(() -> effects.elderGuardian.fireBeam(now));
            }
        } else if (charge < 0.5) {
            elderGuardianBeamFired = false;
        }

        MobEffectInstance fatigue = player.getEffect(MobEffects.MINING_FATIGUE);
        boolean cursed = fatigue != null;
        int curseTicks = cursed ? fatigue.getDuration() : 0;
        // Gone up rather than down: a fresh curse. See the field's note for
        // why this is read off the debuff instead of the packet that announces
        // it.
        if (curseTicks > elderGuardianCurseTicks) {
            SdkWorkerThread.enqueue(() -> effects.elderGuardian.curseLanded(now));
        }
        elderGuardianCurseTicks = curseTicks;

        SdkWorkerThread.enqueue(() ->
                effects.elderGuardian.setGuardian(spikes, charge, atYou, cursed, now));
    }

    // ---------------------------------------------------------------
    // The Twilight Forest Naga.
    //
    // Gated on the modid first, so for anyone without Twilight Forest this
    // costs one boolean read per tick and never touches the world.
    //
    // Matched by registry id rather than by class, because importing anything
    // from another mod is how you get a NoClassDefFoundError the first time
    // somebody removes it. Health and velocity are both public, synced, and
    // enough: health says how much snake is left, and speed separates a charge
    // from a prowl without needing the mod's private movement-state accessor.
    // ---------------------------------------------------------------
    private static void pollNaga(Player player, EffectRegistry effects, long now) {
        if (!Feature.NAGA.isOn() || !ModCompatRegistry.isTwilightForestLoaded()) return;
        if (!(player.level() instanceof ClientLevel clientLevel)) return;

        double maxDistance = RGBProfileConfig.NAGA_DETECTION_RADIUS.get();
        double maxDistanceSq = maxDistance * maxDistance;

        // Walk the client's tracked entities rather than querying a box. A box
        // big enough to cover the courtyard spans well over a thousand chunk
        // sections, and scanning that every tick to find one snake would be
        // absurd; the tracked list is a few hundred entries and the Naga is
        // either in it or genuinely not near you.
        LivingEntity naga = null;
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            ResourceLocation loc = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
            if (loc == null || !TwilightForestCompat.NAGA_ID.equals(loc.toString())) continue;
            if (living.distanceToSqr(player) > maxDistanceSq) continue;
            naga = living;
            break;
        }

        if (naga != null) {
            nagaPresent = true;
            nagaLastSeenMillis = now;
            double health = naga.getHealth() / Math.max(1.0f, naga.getMaxHealth());
            // Horizontal only: the Naga rears and drops constantly while
            // circling, and vertical motion is not what tells you it is coming
            // for you.
            double speed = naga.getDeltaMovement().horizontalDistance();
            SdkWorkerThread.enqueue(() -> effects.naga.setNaga(health, speed, now));
            return;
        }

        if (!nagaPresent) return;
        // Grace before letting go. The Naga dips behind the hedge, burrows,
        // and wanders the far side of its courtyard constantly, and none of
        // that means you have left the fight — without this the board flickers
        // between snake and biome while you are still very much being chased.
        if (now - nagaLastSeenMillis < RGBProfileConfig.NAGA_GRACE_MILLIS.get()) return;
        nagaPresent = false;
        SdkWorkerThread.enqueue(() -> effects.naga.setGone(now));
    }

    // ---------------------------------------------------------------
    // The Aether's Slider.
    //
    // Gated on the modid first, like the Naga, and matched by registry id for
    // the same reason. Position, health and whether it is dying are vanilla
    // and synced. Whether it is awake is not something the Aether exposes
    // without importing its classes, so it comes from vanilla's boss overlay
    // instead: the Slider's bar exists on the client only while it is awake,
    // and it is a bar that plays boss music. See SliderEffect for the rest.
    // ---------------------------------------------------------------
    private static void pollSlider(Minecraft mc, Player player, EffectRegistry effects, long now) {
        if (!Feature.SLIDER.isOn() || !ModCompatRegistry.isAetherLoaded()) return;
        if (!(player.level() instanceof ClientLevel clientLevel)) return;

        double maxDistance = RGBProfileConfig.SLIDER_DETECTION_RADIUS.get();
        double maxDistanceSq = maxDistance * maxDistance;

        // Tracked-entity walk rather than a box query, as for the Naga. Dying
        // Sliders are kept, unlike the Naga's loop: the death is the one
        // moment worth drawing that a living-only filter would skip.
        LivingEntity slider = null;
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living.isRemoved()) continue;
            ResourceLocation loc = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
            if (loc == null || !AetherCompat.SLIDER_ID.equals(loc.toString())) continue;
            if (living.distanceToSqr(player) > maxDistanceSq) continue;
            slider = living;
            break;
        }

        if (slider != null) {
            sliderPresent = true;
            sliderLastSeenMillis = now;
            if (slider.isDeadOrDying()) {
                if (!sliderDeathReported) {
                    sliderDeathReported = true;
                    SdkWorkerThread.enqueue(() -> effects.slider.setDead(now));
                }
                return;
            }
            sliderDeathReported = false;
            double x = slider.getX();
            double y = slider.getY();
            double z = slider.getZ();
            double px = player.getX();
            double pz = player.getZ();
            double health = slider.getHealth() / Math.max(1.0f, slider.getMaxHealth());
            boolean bossMusic = mc.gui.getBossOverlay().shouldPlayMusic();
            SdkWorkerThread.enqueue(() -> effects.slider.setSlider(x, y, z, px, pz, health, bossMusic, now));
            return;
        }

        if (!sliderPresent) return;
        // Short grace, because the Slider never leaves its room: losing it
        // means you did. It still covers the entity briefly dropping out of
        // tracking at the edge of range.
        if (now - sliderLastSeenMillis < RGBProfileConfig.SLIDER_GRACE_MILLIS.get()) return;
        sliderPresent = false;
        sliderDeathReported = false;
        SdkWorkerThread.enqueue(() -> effects.slider.setGone(now));
    }

    // ---------------------------------------------------------------
    // The Aether's Sun Spirit.
    //
    // The same shape as the Slider's poll — modid gate, registry-id match,
    // dying spirits kept for the death — plus the two things its room is full
    // of. Crystals are entities, so they are picked up in the same walk as
    // the spirit. Fire is blocks, so it is found by looking: every
    // SUN_SPIRIT_FIRE_SCAN_TICKS, a box around the spirit is checked for fire.
    // ---------------------------------------------------------------

    /**
     * Ticks between fire scans. Vanilla fire lasts seconds, and the spirit
     * sets a new one every 35 ticks, so twice a second misses nothing anyone
     * would notice.
     */
    private static final int SUN_SPIRIT_FIRE_SCAN_TICKS = 10;
    /**
     * How far round the spirit to look for fire. It flies up to 9 blocks from
     * the centre of a room 21 blocks across, so a fire on the far wall can be
     * nearly 20 blocks from it.
     */
    private static final int SUN_SPIRIT_FIRE_SCAN_RADIUS = 20;
    /**
     * Layers scanned, from the spirit's own block down. It sets fire at the
     * first empty block with something solid under it, looking at most three
     * blocks below itself.
     */
    private static final int SUN_SPIRIT_FIRE_SCAN_DEPTH = 4;
    /** Most fires handed over at once. A room with more than this on fire is on fire. */
    private static final int SUN_SPIRIT_MAX_FIRES = 96;

    private static void pollSunSpirit(Minecraft mc, Player player, EffectRegistry effects, long now) {
        if (!Feature.SUN_SPIRIT.isOn() || !ModCompatRegistry.isAetherLoaded()) return;
        if (!(player.level() instanceof ClientLevel clientLevel)) return;

        double maxDistance = RGBProfileConfig.SUN_SPIRIT_DETECTION_RADIUS.get();
        double maxDistanceSq = maxDistance * maxDistance;

        LivingEntity spirit = null;
        List<SunSpiritEffect.Crystal> crystals = new ArrayList<>();
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (entity.isRemoved() || entity.distanceToSqr(player) > maxDistanceSq) continue;
            ResourceLocation loc = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (loc == null || !AetherCompat.NAMESPACE.equals(loc.getNamespace())) continue;
            String id = loc.toString();
            if (AetherCompat.SUN_SPIRIT_ID.equals(id) && entity instanceof LivingEntity living) {
                spirit = living;
            } else if (AetherCompat.FIRE_CRYSTAL_ID.equals(id) || AetherCompat.ICE_CRYSTAL_ID.equals(id)) {
                crystals.add(new SunSpiritEffect.Crystal(entity.getId(), entity.getX(), entity.getZ(),
                        AetherCompat.ICE_CRYSTAL_ID.equals(id)));
            }
        }

        if (spirit != null) {
            boolean firstSighting = !sunSpiritPresent;
            sunSpiritPresent = true;
            sunSpiritLastSeenMillis = now;
            if (spirit.isDeadOrDying()) {
                if (!sunSpiritDeathReported) {
                    sunSpiritDeathReported = true;
                    SdkWorkerThread.enqueue(() -> effects.sunSpirit.setDead(now));
                }
                return;
            }
            sunSpiritDeathReported = false;
            double x = spirit.getX();
            double z = spirit.getZ();
            double px = player.getX();
            double pz = player.getZ();
            double health = spirit.getHealth() / Math.max(1.0f, spirit.getMaxHealth());
            boolean bossMusic = mc.gui.getBossOverlay().shouldPlayMusic();
            SdkWorkerThread.enqueue(() -> {
                effects.sunSpirit.setSpirit(x, z, px, pz, health, bossMusic, now);
                effects.sunSpirit.setCrystals(crystals, now);
            });

            if (firstSighting || tickCounter % SUN_SPIRIT_FIRE_SCAN_TICKS == 0) {
                double[][] fires = scanFire(clientLevel, spirit.blockPosition());
                SdkWorkerThread.enqueue(() -> effects.sunSpirit.setFires(fires[0], fires[1]));
            }
            return;
        }

        if (!sunSpiritPresent) return;
        if (now - sunSpiritLastSeenMillis < RGBProfileConfig.SUN_SPIRIT_GRACE_MILLIS.get()) return;
        sunSpiritPresent = false;
        sunSpiritDeathReported = false;
        SdkWorkerThread.enqueue(() -> effects.sunSpirit.setGone(now));
    }

    /** Fire blocks near the spirit, as {xs, zs} of their centres. */
    private static double[][] scanFire(ClientLevel level, BlockPos centre) {
        double[] xs = new double[SUN_SPIRIT_MAX_FIRES];
        double[] zs = new double[SUN_SPIRIT_MAX_FIRES];
        int n = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int r = SUN_SPIRIT_FIRE_SCAN_RADIUS;
        scan:
        for (int dy = 0; dy < SUN_SPIRIT_FIRE_SCAN_DEPTH; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    pos.set(centre.getX() + dx, centre.getY() - dy, centre.getZ() + dz);
                    if (!level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.FIRE)) continue;
                    xs[n] = pos.getX() + 0.5;
                    zs[n] = pos.getZ() + 0.5;
                    if (++n == SUN_SPIRIT_MAX_FIRES) break scan;
                }
            }
        }
        return new double[][]{java.util.Arrays.copyOf(xs, n), java.util.Arrays.copyOf(zs, n)};
    }

    // ---------------------------------------------------------------
    // The Aether's Valkyrie Queen.
    //
    // The Sun Spirit's poll again — modid gate, registry-id match, a dying
    // Queen kept for the defeat, her projectiles picked up in the same walk —
    // plus lightning bolts, which are vanilla entities, and her room's walls,
    // which are looked for rather than assumed. See ValkyrieQueenEffect for
    // why the room cannot be guessed from where she stands.
    // ---------------------------------------------------------------

    /** Ticks between attempts to find her walls, until they are found. */
    private static final int VALKYRIE_ROOM_SCAN_TICKS = 20;
    /** Furthest a wall is looked for. Her room is 24 blocks at its longest. */
    private static final int VALKYRIE_ROOM_SCAN_REACH = 30;
    /**
     * Where the rays start, sideways from her and upward from her feet. Many
     * rays rather than one, because the room is not an empty box: her throne
     * is built from the same stone as the walls, and before the fight the
     * doorway is open. A handful of rays hit the throne or leave through the
     * door; the rest agree on the wall, and the wall is the distance most of
     * them agree on. The highest row clears the throne's back.
     */
    private static final int[] VALKYRIE_SCAN_SIDEWAYS = {-4, -2, 0, 2, 4};
    private static final int[] VALKYRIE_SCAN_UP = {0, 2, 6};
    /** Fewest rays that must agree on a wall. */
    private static final int VALKYRIE_SCAN_MIN_VOTES = 4;
    /** The Silver Dungeon boss room's floor, inside its walls, which can face either way. */
    private static final int VALKYRIE_ROOM_SHORT = 20;
    private static final int VALKYRIE_ROOM_LONG = 24;

    private static void pollValkyrieQueen(Minecraft mc, Player player, EffectRegistry effects, long now) {
        if (!Feature.VALKYRIE_QUEEN.isOn() || !ModCompatRegistry.isAetherLoaded()) return;
        if (!(player.level() instanceof ClientLevel clientLevel)) return;

        double maxDistance = RGBProfileConfig.VALKYRIE_QUEEN_DETECTION_RADIUS.get();
        double maxDistanceSq = maxDistance * maxDistance;

        LivingEntity queen = null;
        List<ValkyrieQueenEffect.Crystal> crystals = new ArrayList<>();
        List<ValkyrieQueenEffect.Bolt> bolts = new ArrayList<>();
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (entity.isRemoved() || entity.distanceToSqr(player) > maxDistanceSq) continue;
            if (entity instanceof LightningBolt) {
                bolts.add(new ValkyrieQueenEffect.Bolt(entity.getId(), entity.getX(), entity.getZ()));
                continue;
            }
            ResourceLocation loc = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (loc == null || !AetherCompat.NAMESPACE.equals(loc.getNamespace())) continue;
            String id = loc.toString();
            if (AetherCompat.VALKYRIE_QUEEN_ID.equals(id) && entity instanceof LivingEntity living) {
                queen = living;
            } else if (AetherCompat.THUNDER_CRYSTAL_ID.equals(id)) {
                crystals.add(new ValkyrieQueenEffect.Crystal(entity.getId(), entity.getX(), entity.getZ()));
            }
        }

        if (queen != null) {
            boolean firstSighting = !valkyrieQueenPresent;
            valkyrieQueenPresent = true;
            valkyrieQueenLastSeenMillis = now;
            if (firstSighting) valkyrieRoomFound = false;
            if (queen.isDeadOrDying()) {
                if (!valkyrieQueenDefeatReported) {
                    valkyrieQueenDefeatReported = true;
                    SdkWorkerThread.enqueue(() -> effects.valkyrieQueen.setDefeated(now));
                }
                return;
            }
            valkyrieQueenDefeatReported = false;
            double x = queen.getX();
            double y = queen.getY();
            double z = queen.getZ();
            double px = player.getX();
            double pz = player.getZ();
            double health = queen.getHealth() / Math.max(1.0f, queen.getMaxHealth());
            boolean bossMusic = mc.gui.getBossOverlay().shouldPlayMusic();
            // The room goes in the same job, after the sighting, so the first
            // crystals and bolts are measured against the real walls.
            double[] room = !valkyrieRoomFound
                    && (firstSighting || tickCounter % VALKYRIE_ROOM_SCAN_TICKS == 0)
                    ? scanValkyrieRoom(clientLevel, queen.blockPosition()) : null;
            if (room != null) valkyrieRoomFound = true;
            SdkWorkerThread.enqueue(() -> {
                effects.valkyrieQueen.setQueen(x, y, z, px, pz, health, bossMusic, now);
                if (room != null) effects.valkyrieQueen.setRoom(room[0], room[1], room[2], room[3]);
                effects.valkyrieQueen.setCrystals(crystals, now);
                effects.valkyrieQueen.setBolts(bolts, now);
            });
            return;
        }

        if (!valkyrieQueenPresent) return;
        if (now - valkyrieQueenLastSeenMillis < RGBProfileConfig.VALKYRIE_QUEEN_GRACE_MILLIS.get()) return;
        valkyrieQueenPresent = false;
        valkyrieQueenDefeatReported = false;
        valkyrieRoomFound = false;
        SdkWorkerThread.enqueue(() -> effects.valkyrieQueen.setGone(now));
    }

    /**
     * The inside of the room she is standing in, as {minX, minZ, maxX, maxZ}
     * at the faces of its walls, or null when the walls found do not measure
     * as her room — she is mid-air above the scan, or not in a dungeon.
     */
    private static double[] scanValkyrieRoom(ClientLevel level, BlockPos feet) {
        int west = valkyrieWallDistance(level, feet, -1, 0);
        int east = valkyrieWallDistance(level, feet, 1, 0);
        int north = valkyrieWallDistance(level, feet, 0, -1);
        int south = valkyrieWallDistance(level, feet, 0, 1);
        if (west < 0 || east < 0 || north < 0 || south < 0) return null;
        // A wall d blocks away leaves d - 1 blocks of floor on that side.
        int spanX = west + east - 1;
        int spanZ = north + south - 1;
        boolean fits = (spanX == VALKYRIE_ROOM_SHORT && spanZ == VALKYRIE_ROOM_LONG)
                || (spanX == VALKYRIE_ROOM_LONG && spanZ == VALKYRIE_ROOM_SHORT);
        if (!fits) return null;
        return new double[]{
                feet.getX() - west + 1, feet.getZ() - north + 1,
                feet.getX() + east, feet.getZ() + south};
    }

    /** Blocks from her feet to the wall in one direction, by the most rays agreeing, or -1. */
    private static int valkyrieWallDistance(ClientLevel level, BlockPos feet, int stepX, int stepZ) {
        int[] votes = new int[VALKYRIE_ROOM_SCAN_REACH + 1];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int up : VALKYRIE_SCAN_UP) {
            for (int side : VALKYRIE_SCAN_SIDEWAYS) {
                // Sideways is across the direction of travel.
                int startX = feet.getX() + side * stepZ;
                int startZ = feet.getZ() + side * stepX;
                pos.set(startX, feet.getY() + up, startZ);
                // A ray that starts inside stone is above the ceiling or
                // inside the throne, and has nothing to say about the walls.
                if (isValkyrieWall(level, pos)) continue;
                for (int d = 1; d <= VALKYRIE_ROOM_SCAN_REACH; d++) {
                    pos.set(startX + stepX * d, feet.getY() + up, startZ + stepZ * d);
                    if (isValkyrieWall(level, pos)) {
                        votes[d]++;
                        break;
                    }
                }
            }
        }
        int best = -1;
        for (int d = 1; d <= VALKYRIE_ROOM_SCAN_REACH; d++) {
            if (votes[d] >= VALKYRIE_SCAN_MIN_VOTES && (best < 0 || votes[d] > votes[best])) best = d;
        }
        return best;
    }

    private static boolean isValkyrieWall(ClientLevel level, BlockPos pos) {
        ResourceLocation loc = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return AetherCompat.NAMESPACE.equals(loc.getNamespace())
                && loc.getPath().endsWith(AetherCompat.ANGELIC_STONE_SUFFIX);
    }

    /**
     * Lights the breath fire from the dragon's-breath cloud actually on the
     * ground, and places it where that cloud is relative to your view.
     *
     * <p>This used to be driven off the SITTING_FLAMING phase, which only covers the
     * perched flame — so the far more common attack, a fireball thrown while
     * flying, produced a burst of purple on your screen and nothing at all on
     * the keyboard. Both attacks converge on the same
     * {@code AreaEffectCloud} of DRAGON_BREATH, so watching for the cloud
     * catches both, and catches them at the moment the fire exists rather than
     * when an animation starts.
     */
    private static void pollDragonBreath(Player player, EffectRegistry effects, AreaEffectCloud cloud) {
        if (cloud == null) {
            SdkWorkerThread.enqueue(() -> effects.enderDragon.setBreathFire(false, 0.5, 1.0));
            return;
        }

        // Put the pool on the side of the board it is on in the world. The
        // look vector's horizontal part gives forward; rotating it gives
        // right, and the angle between that and the cloud maps across the
        // keys. Fire behind you clamps to an edge rather than wrapping.
        Vec3 look = player.getLookAngle();
        double lh = Math.hypot(look.x, look.z);
        double originX = 0.5;
        if (lh > 1e-4) {
            double fx = look.x / lh, fz = look.z / lh;
            double rx = -fz, rz = fx;
            double dx = cloud.getX() - player.getX();
            double dz = cloud.getZ() - player.getZ();
            double forward = dx * fx + dz * fz;
            double right = dx * rx + dz * rz;
            if (Math.abs(forward) > 1e-4 || Math.abs(right) > 1e-4) {
                double angle = Math.atan2(right, forward);
                originX = 0.5 + 0.5 * Math.max(-1, Math.min(1, angle / 1.2));
            }
        }

        // Vanilla's fireball cloud starts at radius 3 and grows toward 7, so
        // a fresh splash reads small and a settled pool reads wide.
        double reach = Math.max(0.25, Math.min(1.0, cloud.getRadius() / 7.0));
        double finalOrigin = originX;
        SdkWorkerThread.enqueue(() -> effects.enderDragon.setBreathFire(true, finalOrigin, reach));
    }

    /**
     * The eleven vanilla phases collapsed into what the dragon is visibly
     * doing, which is all the silhouette needs to pose itself.
     *
     * <p>Eleven distinct looks would be eleven things nobody can learn.
     * "Gliding / lunging at you / landed / breathing" is four, and each one is
     * readable without being taught.
     */
    private static EnderDragonEffect.Pose dragonPose(EnderDragon dragon) {
        EnderDragonPhase<?> phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
        if (phase == EnderDragonPhase.SITTING_FLAMING || phase == EnderDragonPhase.SITTING_ATTACKING) {
            return EnderDragonEffect.Pose.BREATHING;
        }
        if (phase == EnderDragonPhase.SITTING_SCANNING || phase == EnderDragonPhase.LANDING) {
            return EnderDragonEffect.Pose.PERCHED;
        }
        if (phase == EnderDragonPhase.CHARGING_PLAYER || phase == EnderDragonPhase.STRAFE_PLAYER) {
            return EnderDragonEffect.Pose.LUNGE;
        }
        if (phase == EnderDragonPhase.HOVERING) {
            return EnderDragonEffect.Pose.HOVER;
        }
        // HOLDING_PATTERN, LANDING_APPROACH, TAKEOFF: it is in the air and
        // going somewhere.
        return EnderDragonEffect.Pose.GLIDE;
    }

    // ---------------------------------------------------------------
    // Drowning — the board floods as your air runs out.
    //
    // Read straight off the vanilla air supply, which means Respiration,
    // water breathing, turtle helmets and conduit power are all honoured for
    // free: they change how fast the number falls, and this only ever reports
    // the number.
    //
    // Air is an int that ticks down once per tick from 300, so the poll runs
    // every tick and the SMOOTHING lives in the pattern rather than here —
    // see WaterRisePattern. That keeps this to two reads and a divide.
    // ---------------------------------------------------------------
    private static void pollDrowning(Player player, EffectRegistry effects) {
        if (!Feature.DROWNING.isOn()) return;
        int max = player.getMaxAirSupply();
        if (max <= 0) return;
        int air = player.getAirSupply();
        // Vanilla runs air down past zero to -20 before dealing a point of
        // damage and resetting it, so clamping is not cosmetic: without it the
        // level would jump back down the board on every drowning tick, which
        // is the exact moment it should look worst.
        double submersion = 1.0 - Math.max(0, Math.min(max, air)) / (double) max;
        // Dead players do not drown, and the death state owns the board
        // anyway. See pollDeath.
        if (dead) submersion = 0;
        double level = submersion;
        SdkWorkerThread.enqueue(() -> effects.drowning.setSubmersion(level));
    }

    // ---------------------------------------------------------------
    // Burning — the board catches fire while you do.
    //
    // The drowning meter's sibling, with one difference forced on it. Air
    // supply is sent to the client; the fire timer is not. Entity.baseTick
    // clears the client's copy every tick, and the only thing synced is the
    // "on fire" flag behind isOnFire(). So there is no "time left burning" to
    // show. The height is how bad the burning is instead, from what the client
    // can see for itself: Fire Resistance, standing in fire, and lava.
    // ---------------------------------------------------------------

    /**
     * Flame heights, as a fraction of the board. Protected is low and calm
     * because the fire cannot hurt you; lava is the whole board because
     * nothing else does as much damage as quickly.
     */
    private static final double BURNING_PROTECTED_HEAT = 0.3;
    private static final double BURNING_HEAT = 0.5;
    private static final double BURNING_IN_FIRE_HEAT = 0.75;
    private static final double BURNING_IN_LAVA_HEAT = 1.0;

    private static void pollBurning(Player player, EffectRegistry effects) {
        if (!Feature.BURNING.isOn()) return;
        double heat = 0;
        // Dead players are not burning either, and the death state owns the
        // board anyway. See pollDeath.
        if (!dead && player.isOnFire()) {
            if (player.hasEffect(MobEffects.FIRE_RESISTANCE)) {
                heat = BURNING_PROTECTED_HEAT;
            } else if (player.isInLava()) {
                heat = BURNING_IN_LAVA_HEAT;
            } else if (standingInFire(player)) {
                heat = BURNING_IN_FIRE_HEAT;
            } else {
                heat = BURNING_HEAT;
            }
        }
        double level = heat;
        SdkWorkerThread.enqueue(() -> effects.burning.setHeat(level));
    }

    /** Whether any fire block overlaps the player: fire or soul fire, the blocks that keep setting you alight. */
    private static boolean standingInFire(Player player) {
        return player.level().getBlockStatesIfLoaded(player.getBoundingBox().deflate(1.0E-3))
                .anyMatch(state -> state.is(BlockTags.FIRE));
    }

    // ---------------------------------------------------------------
    // Health Flash
    // ---------------------------------------------------------------
    private static void pollHealth(Player player, EffectRegistry effects, long now) {
        if (!Feature.HEALTH_FLASH.isOn()) return;
        double percent = 100.0 * player.getHealth() / Math.max(1, player.getMaxHealth());
        // !dead: zero health while dead is not "you should heal", it is "you
        // are dead". See pollDeath.
        boolean below = !dead && percent < RGBProfileConfig.HEALTH_THRESHOLD_PERCENT.get();
        if (below != lastHealthBelowThreshold) {
            lastHealthBelowThreshold = below;
            SdkWorkerThread.enqueue(() -> effects.healthFlash.setActive(below, now));
        }
    }

    // ---------------------------------------------------------------
    // Hunger Warning
    // ---------------------------------------------------------------
    private static void pollHunger(Player player, EffectRegistry effects, long now) {
        if (!Feature.HUNGER_WARNING.isOn()) return;
        double percent = 100.0 * player.getFoodData().getFoodLevel() / 20.0;
        boolean below = !dead && percent < RGBProfileConfig.HUNGER_THRESHOLD_PERCENT.get();
        if (below != lastHungerBelowThreshold) {
            lastHungerBelowThreshold = below;
            SdkWorkerThread.enqueue(() -> effects.hungerWarning.setActive(below, now));
        }
    }

    // ---------------------------------------------------------------
    // Biome Color — includes the derived-colour fallback, computed here (on
    // the client thread) since it needs registry/biome access.
    // ---------------------------------------------------------------
    private static void pollBiome(Player player, EffectRegistry effects, long now) {
        if (!Feature.BIOME_COLORS.isOn()) return;
        var biomeHolder = player.level().getBiome(player.blockPosition());
        ResourceLocation biomeLoc = biomeHolder.unwrapKey()
                .map(key -> key.location())
                .orElse(null);
        if (biomeLoc == null) {
            // The path that used to return before recording ANY sample — which
            // made it invisible to diagnostics at exactly the moment it
            // mattered. A 130-second window in the Aether showed zero samples
            // and the board silently held the previous dimension's colour the
            // whole time, looking for all the world like a stuck effect.
            //
            // A Holder that isn't registry-bound has no key to unwrap, so the
            // biome simply cannot be named. Logged on state CHANGE only,
            // because this fires every single poll for as long as it lasts.
            if (!biomeUnresolved) {
                biomeUnresolved = true;
                Diagnostics.biomeUnresolved(true, describeHolder(biomeHolder));
            }
            return;
        }
        if (biomeUnresolved) {
            biomeUnresolved = false;
            Diagnostics.biomeUnresolved(false, biomeLoc.toString());
        }
        String biomeId = biomeLoc.toString();
        if (biomeId.equals(lastBiomeId)) {
            Diagnostics.biomeSampled(biomeId, pendingBiomeId != null ? "reverted" : "same");
            pendingBiomeId = null; // walked back before the debounce elapsed
            return;
        }

        // The debounce, i.e. only commit a CONFIRMED change. Stand on a border
        // and the game will report both biomes several times a second; without
        // this, each report re-triggers the 800ms Tier-1 crossfade, and you get
        // a keyboard having a small breakdown.
        //
        // Worth knowing: an earlier version defined DEBOUNCE_MILLIS in the
        // config, fully documented, and never read it anywhere. See
        // Diagnostics.dumpBiomeTiming, which now warns at startup if the
        // debounce is shorter than the sample interval and therefore inert.
        long debounce = RGBProfileConfig.DEBOUNCE_MILLIS.get();
        if (!biomeId.equals(pendingBiomeId)) {
            pendingBiomeId = biomeId;
            pendingBiomeSinceMillis = now;
            Diagnostics.biomeSampled(biomeId, "pending");
            return;
        }
        if (now - pendingBiomeSinceMillis < debounce) {
            Diagnostics.biomeSampled(biomeId, "waiting");
            return;
        }

        pendingBiomeId = null;
        lastBiomeId = biomeId;

        RGBColor derived = deriveBiomeColor(biomeHolder.value());
        boolean fallbackEnabled = RGBProfileConfig.BIOME_FALLBACK_TO_DERIVED_COLOR.get();
        SdkWorkerThread.enqueue(() -> effects.biomeColor.onBiomeChanged(biomeId, derived, fallbackEnabled, now));
    }

    /** Best-effort description of a Holder that would not unwrap, for logging. */
    private static String describeHolder(Object holder) {
        try {
            return holder == null ? "null" : holder.getClass().getSimpleName();
        } catch (Exception e) {
            return "(undescribable)";
        }
    }

    /**
     * Invents a colour for a biome nobody wrote a profile for.
     *
     * <p>Blends grass and water colour at 2:1 rather than using raw
     * temperature/downfall, and the reason is simple: grass colour is
     * literally what the biome LOOKS like. A jungle comes out jungle-green
     * because the game already decided jungle grass is green. Temperature and
     * downfall are climate metadata that correlate with appearance only
     * loosely — a mushroom island and a plains biome have similar numbers and
     * look nothing alike.
     *
     * <p>2:1 in favour of grass because grass dominates what you see; water is
     * in there to keep ocean and swamp biomes from all converging on the same
     * green.
     *
     * <p>The catch block is the honest fallback for modded biomes with
     * unusual or missing special-effects data — a temperature/downfall blend,
     * which is worse but always available.
     */
    private static RGBColor deriveBiomeColor(Biome biome) {
        try {
            var effects = biome.getSpecialEffects();
            int grass = effects.getGrassColorOverride().orElse(0x6A8F3D);
            int water = effects.getWaterColor();
            int r = ((((grass >> 16) & 0xFF) * 2) + ((water >> 16) & 0xFF)) / 3;
            int g = ((((grass >> 8) & 0xFF) * 2) + ((water >> 8) & 0xFF)) / 3;
            int b = (((grass & 0xFF) * 2) + (water & 0xFF)) / 3;
            return new RGBColor(r, g, b);
        } catch (Exception e) {
            // Temperature/downfall blend fallback: warmer/drier -> warm tones, colder/wetter -> cool tones.
            float temperature = biome.getBaseTemperature();
            float t = Math.max(0f, Math.min(1f, (temperature + 1f) / 3f));
            RGBColor cold = RGBColor.fromHex("#3F6741");
            RGBColor hot = RGBColor.fromHex("#C56A3B");
            return cold.lerp(hot, t);
        }
    }

    // ---------------------------------------------------------------
    // Night / Moon Indicator
    // ---------------------------------------------------------------
    private static void pollTimeOfDay(Player player, EffectRegistry effects) {
        if (!Feature.NIGHT_INDICATOR.isOn()) return;

        // No moon where there is no night to track.
        //
        // Read off the dimension's own data rather than a list of dimension
        // ids, so it is right for modded dimensions nobody has heard of:
        //
        //   fixedTime present  - the sky does not move. The Nether (18000) and
        //                        the End (6000) both pin it, which is why a
        //                        sunset countdown there is meaningless.
        //   no skylight        - there is no sky to put a moon in.
        //
        // Twilight Forest keeps a real day/night cycle and a sky, so it still
        // gets one, which is correct — time of day matters there.
        var dimension = player.level().dimensionType();
        if (dimension.fixedTime().isPresent() || !dimension.hasSkyLight()) {
            SdkWorkerThread.enqueue(() -> effects.nightIndicator.setNightProgress(false, 0));
            return;
        }
        // % 24000 because getDayTime() counts total elapsed ticks since world
        // creation, not time-of-day. On an old world that's a very large
        // number and the indicator would be permanently pinned.
        long dayTime = player.level().getDayTime() % 24000;
        // Vanilla night is roughly [13000, 23000) of the 24000-tick day.
        boolean isNight = dayTime >= 13000 && dayTime < 23000;
        double progress = isNight ? (dayTime - 13000) / 10000.0 : 0;
        SdkWorkerThread.enqueue(() -> effects.nightIndicator.setNightProgress(isNight, progress));
    }

    // ---------------------------------------------------------------
    // Rain Cascade + Lightning fallback timer
    // ---------------------------------------------------------------
    private static void pollWeather(Player player, EffectRegistry effects, long now) {
        if (!Feature.RAIN_THUNDERSTORM.isOn()) return;

        boolean exposed = isBeingRainedOn(player);
        if (exposed) {
            rainShelteredSinceMillis = 0;
        } else if (rainShelteredSinceMillis == 0) {
            rainShelteredSinceMillis = now;
        }

        // On the instant you step into it; off only once the grace has fully
        // elapsed under cover. Asymmetric on purpose — see the grace config
        // comment for why symmetric would flicker under every tree.
        boolean raining = exposed
                || (lastRaining && now - rainShelteredSinceMillis < RGBProfileConfig.RAIN_SHELTER_GRACE_MILLIS.get());

        if (raining != lastRaining) {
            lastRaining = raining;
            SdkWorkerThread.enqueue(() -> effects.rainCascade.setActive(raining, now));
        }
    }

    /**
     * Whether rain is actually landing on the player, as opposed to merely
     * falling somewhere in the world.
     *
     * <p>{@code Level.isRaining()} is a global weather flag. It is true at
     * Y=-50 under two hundred blocks of stone, which is why a thunderstorm
     * overhead used to paint rain across a Deep Dark keyboard.
     *
     * <p>{@code Level.isRainingAt(pos)} is the game's own per-position answer
     * and gets every case right on its own: it requires the global flag, then
     * {@code canSeeSky}, then a motion-blocking heightmap check, then that the
     * biome's precipitation at that spot is actually RAIN. So a ravine — open
     * to the sky, however deep it goes — counts as outdoors, while the cave
     * branching off the side of it does not. No depth heuristic of ours
     * required, and none would have got the ravine right anyway.
     *
     * <p>Both feet and head are tested, mirroring the private
     * {@code Entity.isInRain} we cannot call: a player standing in a one-deep
     * puddle or on a lower slab still has their head out in the weather.
     *
     * <p>The biome precipitation term has a side effect worth knowing: snowy
     * biomes report SNOW rather than RAIN, so the rain cascade no longer
     * appears during a snowstorm. That reads as correct — those biomes have
     * their own snow-drift base pattern, and blue rain over snow never made
     * much sense.
     */
    private static boolean isBeingRainedOn(Player player) {
        Level level = player.level();
        if (!level.isRaining()) return false;
        if (!RGBProfileConfig.RAIN_REQUIRE_SKY_EXPOSURE.get()) return true;
        try {
            BlockPos feet = player.blockPosition();
            if (level.isRainingAt(feet)) return true;
            return level.isRainingAt(BlockPos.containing(
                    feet.getX(), player.getBoundingBox().maxY, feet.getZ()));
        } catch (Exception e) {
            // Heightmap or biome lookup on a chunk that is mid-unload. Fall
            // back to the global flag rather than dropping the effect: a
            // moment of rain you did not strictly earn beats an exception in
            // the tick loop.
            return true;
        }
    }

    private static void pollLightningFallback(EffectRegistry effects, long now) {
        if (!Feature.RAIN_THUNDERSTORM.isOn()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.isThundering()) return;
        long fallbackInterval = RGBProfileConfig.LIGHTNING_FALLBACK_INTERVAL_MILLIS.get();
        if (now - lightningLastFallbackMillis < fallbackInterval) return;
        lightningLastFallbackMillis = now;
        RGBColor color = RGBColor.fromHexOrDefault(RGBProfileConfig.LIGHTNING_COLOR.get(), ColorPalette.LIGHTNING_WHITE_BLUE);
        long duration = RGBProfileConfig.LIGHTNING_FLASH_DURATION_MILLIS.get();
        SdkWorkerThread.enqueue(() -> effects.lightningFlash.trigger(now, color, duration, null, new FlashOncePattern()));
    }

    // Timer-based, and that is a compromise. The better signal is the
    // client-observed LightningBolt entity spawn (bolts already render and
    // play sound for nearby players), rather than the randomised fallback
    // timer above. EntityJoinLevelEvent, filtered to EntityType.LIGHTNING_BOLT
    // within lightningDetectionRadius of the player, is the natural hook. It
    // has not been wired up yet because it needs confirming in a running
    // client that the event fires client-side once per bolt, rather than only
    // on the server. Sketch:
    //
    // @SubscribeEvent
    // public static void onEntityJoin(EntityJoinLevelEvent event) {
    //     if (event.getEntity().getType() != EntityType.LIGHTNING_BOLT) return;
    //     ... distance check against mc.player, then trigger lightningFlash exactly like the fallback above ...
    // }

    // ---------------------------------------------------------------
    // Raids.
    //
    // Three things the client is told, and nothing more is needed:
    //
    //   - The Raid Omen, counting down to the raid starting, is an effect on
    //     you, with its time left.
    //   - The raid itself is a vanilla boss bar, renamed "Raid - Victory" or
    //     "Raid - Defeat" when it ends. Matched by translation key rather than
    //     by text, so it reads the same in every language. The boss bars on
    //     screen are package-private in vanilla and opened up by this mod's
    //     access transformer: NeoForge's event for a bar being drawn would do
    //     too, but stops firing whenever the HUD is hidden.
    //   - The horn is a sound the server sends to every player near the raid
    //     when a wave arrives. See onRaidHorn.
    // ---------------------------------------------------------------

    private static final String RAID_BAR_KEY = "event.minecraft.raid";
    private static final String RAID_VICTORY_KEY = "event.minecraft.raid.victory.full";
    private static final String RAID_DEFEAT_KEY = "event.minecraft.raid.defeat.full";
    /** How long the board holds after the bar goes, so a bar that drops out for a moment does not end the raid. */
    private static final long RAID_GRACE_MILLIS = 2000;

    private static void pollRaid(Minecraft mc, Player player, EffectRegistry effects, long now) {
        if (!Feature.RAID_WARNING.isOn()) return;

        RaidHordePattern.Phase phase = raidBarPhase(mc);
        double omenProgress = 0;
        if (phase != null) {
            raidOmenStartTicks = 0;
        } else {
            MobEffectInstance omen = dead ? null : player.getEffect(MobEffects.RAID_OMEN);
            if (omen != null) {
                // The omen's full length is not something the effect says, so
                // the longest time left seen is taken as where it started.
                int remaining = omen.getDuration();
                raidOmenStartTicks = Math.max(raidOmenStartTicks, remaining);
                omenProgress = 1 - remaining / (double) Math.max(1, raidOmenStartTicks);
                phase = RaidHordePattern.Phase.OMEN;
            } else {
                raidOmenStartTicks = 0;
            }
        }

        if (phase != null) {
            lastRaidPhase = phase;
            raidLastSeenMillis = now;
            RaidHordePattern.Phase shown = phase;
            double progress = omenProgress;
            SdkWorkerThread.enqueue(() -> effects.raid.setPhase(shown, progress, now));
            return;
        }
        if (lastRaidPhase == null || now - raidLastSeenMillis < RAID_GRACE_MILLIS) return;
        lastRaidPhase = null;
        SdkWorkerThread.enqueue(() -> effects.raid.setGone(now));
    }

    /** What the raid bar on screen says, or null if there is none. */
    private static RaidHordePattern.Phase raidBarPhase(Minecraft mc) {
        for (LerpingBossEvent bar : mc.gui.getBossOverlay().events.values()) {
            if (!(bar.getName().getContents() instanceof TranslatableContents name)) continue;
            switch (name.getKey()) {
                case RAID_BAR_KEY:
                    return RaidHordePattern.Phase.RAID;
                case RAID_VICTORY_KEY:
                    return RaidHordePattern.Phase.VICTORY;
                case RAID_DEFEAT_KEY:
                    return RaidHordePattern.Phase.DEFEAT;
                default:
                    break;
            }
        }
        return null;
    }

    /**
     * The raid horn, when a wave arrives.
     *
     * <p>Caught as the client is about to play it, which is before the sound
     * volume is looked at, so it lights the board with the game muted too. The
     * original sound rather than the possibly replaced one, so another mod
     * swapping or silencing the horn does not stop it.
     */
    @SubscribeEvent
    public static void onRaidHorn(PlaySoundEvent event) {
        if (event.getOriginalSound() == null) return;
        if (!SoundEvents.RAID_HORN.value().location().equals(event.getOriginalSound().getLocation())) return;
        if (!Feature.RAID_WARNING.isOn()) return;
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null) return;
        long now = System.currentTimeMillis();
        SdkWorkerThread.enqueue(() -> effects.raid.horn(now));
    }

    // ---------------------------------------------------------------
    // Boss Encounter — proximity, not boss bars.
    //
    // This was a deliberately empty stub waiting on a boss-bar hook, on the
    // bet that hanging boss lighting off a vanilla-level UI feature would make
    // most boss mods work for free. The bet does not pay: plenty of modded
    // bosses are bosses the way the WARDEN is a boss — a huge health pool, an
    // encounter you build around, and no bar above the screen at all. Several
    // Legendary Monsters mobs are exactly that shape, and no amount of
    // boss-bar plumbing would ever have seen them.
    //
    // So detection works the way the Warden's already does: look for the thing
    // itself. Two tests decide what counts, and the split is the whole design:
    //
    //   - The c:bosses CONVENTION TAG, which is the mod author's own
    //     declaration and beats any heuristic we could invent. Eleven mods in
    //     the Forge Everything pack this mod was built against populate it —
    //     Cataclysm, Aether, Twilight Forest,
    //     Iron's Spellbooks, Legendary Monsters and more — so "most boss mods
    //     come along for free" finally holds, just off a data tag rather than
    //     off a UI widget.
    //   - An EXACT entry in boss_profiles.json, likewise a statement of
    //     intent. The Elder Guardian is a boss at 80 health because somebody
    //     wrote it down.
    //   - Failing both, proximityMinMaxHealth. That is what keeps a mod-wide
    //     wildcard like legendary_monsters:* from promoting every zombie the
    //     mod adds, while still covering bosses nobody has tagged or named.
    //
    // Legendary Monsters is worth calling out because it is the case that
    // disproved the boss-bar approach twice over: its bosses are tagged, and
    // it draws its own CustomBossBar rather than a vanilla one — so a vanilla
    // boss-bar hook would have missed them no matter how well written.
    //
    // Health percentage comes straight off the entity, which is strictly
    // better than a boss bar ever was: it is the real number, it needs no
    // packet interception, and it feeds the existing enrage machinery
    // unchanged.
    // ---------------------------------------------------------------
    private static void pollBossEncounter(Player player, EffectRegistry effects, long now) {
        if (!Feature.BOSS_ENCOUNTER.isOn()) return;

        double radius = RGBProfileConfig.BOSS_DETECTION_RADIUS.get();
        double minMaxHealth = RGBProfileConfig.BOSS_MIN_MAX_HEALTH.get();
        boolean fallbackEnabled = RGBProfileConfig.BOSS_FALLBACK_TO_BOSSBAR_COLOR.get();
        parseBossExclusions();

        boolean useTag = RGBProfileConfig.BOSS_USE_BOSSES_TAG.get();

        LivingEntity best = null;
        String bestId = null;
        int bestRank = -1;
        for (LivingEntity entity : player.level().getEntitiesOfClass(
                LivingEntity.class, player.getBoundingBox().inflate(radius))) {
            if (entity instanceof Player || !entity.isAlive()) continue;
            ResourceLocation loc = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (loc == null) continue;
            String id = loc.toString();
            // Checked before the tag and before the profile lookup: an
            // exclusion is the user overriding both, and it has to win.
            if (isBossExcluded(loc, id)) continue;

            boolean tagged = useTag && entity.getType().is(BOSSES_TAG);
            boolean named = effects.bossProfiles.containsKey(id);
            if (!tagged && !named) {
                if (entity.getMaxHealth() < minMaxHealth) continue;
                // No tag, no hand-written entry, no wildcard, and derived
                // fallbacks off means nobody ever asked for this mob to light
                // up.
                if (!fallbackEnabled
                        && ProfileResolver.resolve(effects.bossProfiles, id, false, null) == null) continue;
            }
            // Rank before health. A tagged boss outranks a merely large mob
            // however much health the large mob has, so a boss standing next
            // to a bigger-statted add still owns the board.
            int rank = tagged ? 2 : (named ? 1 : 0);
            if (best == null || rank > bestRank
                    || (rank == bestRank && entity.getMaxHealth() > best.getMaxHealth())) {
                best = entity;
                bestId = id;
                bestRank = rank;
            }
        }

        if (best != null) {
            lastBossSeenMillis = now;
            double percent = 100.0 * best.getHealth() / Math.max(1.0f, best.getMaxHealth());
            if (!bestId.equals(currentBossId)) {
                currentBossId = bestId;
                String id = bestId;
                BossProfile profile = ProfileResolver.resolve(effects.bossProfiles, id, fallbackEnabled,
                        () -> derivedBossProfile(id));
                RGBColor base = profile != null
                        ? profile.resolvedColor(RGBColor.deterministicFromKey(id))
                        : RGBColor.deterministicFromKey(id);
                RGBColor enrage = profile != null ? profile.resolvedEnrageColor() : null;
                int threshold = profile != null && profile.enrageThresholdPercent != null
                        ? profile.enrageThresholdPercent : -1;
                boolean scaling = RGBProfileConfig.BOSS_ENRAGE_INTENSITY_SCALING.get();
                SdkWorkerThread.enqueue(() -> {
                    effects.bossEncounter.setEnrageIntensityScaling(scaling);
                    effects.bossEncounter.startBoss(base, enrage, threshold, now);
                    effects.bossEncounter.updatePercent(percent);
                });
            } else {
                SdkWorkerThread.enqueue(() -> effects.bossEncounter.updatePercent(percent));
            }
            return;
        }

        // Out of range. The grace period stops a dragon circling out and back,
        // or a boss ducking behind terrain, from restarting the encounter — and
        // startBoss resets the pulse clock, so a restart is visible.
        if (currentBossId == null) return;
        if (now - lastBossSeenMillis < RGBProfileConfig.BOSS_GRACE_MILLIS.get()) return;
        currentBossId = null;
        SdkWorkerThread.enqueue(effects.bossEncounter::endBoss);
    }

    /**
     * Parses the exclusion list, only when it has actually changed.
     *
     * <p>Splits into exact ids and whole-namespace wildcards, because a
     * per-mob list does not survive contact with a mod like Mutant Monsters:
     * fourteen entities today, and any of them could grow a boss-sized health
     * pool in an update. {@code mutantmonsters:*} says the thing that is
     * actually true — none of that mod's mobs are bosses — and keeps being
     * true afterwards.
     */
    private static void parseBossExclusions() {
        String raw = RGBProfileConfig.BOSS_EXCLUDED.get();
        if (raw.equals(excludedBossRaw)) return;
        Set<String> ids = new HashSet<>();
        Set<String> mods = new HashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.endsWith(":*")) {
                // Same wildcard shape ProfileResolver already uses, so the two
                // files read the same way to anyone editing them.
                mods.add(trimmed.substring(0, trimmed.length() - 2));
            } else {
                ids.add(trimmed);
            }
        }
        excludedBossIds = ids;
        excludedBossMods = mods;
        excludedBossRaw = raw;
    }

    /** True if this entity id is excluded outright or belongs to an excluded mod. */
    private static boolean isBossExcluded(ResourceLocation loc, String id) {
        return excludedBossIds.contains(id) || excludedBossMods.contains(loc.getNamespace());
    }

    private static BossProfile derivedBossProfile(String entityId) {
        BossProfile profile = new BossProfile();
        profile.color = RGBColor.deterministicFromKey(entityId).toHex();
        return profile;
    }

    // ---------------------------------------------------------------
    // Warden Encounter — "is there a Warden nearby", asked once a
    // second.
    //
    // Polling rather than join/leave events, on purpose:
    // an AABB entity query is trivially correct, whereas join/leave events
    // have to handle a Warden that despawns, dies, is unloaded with its
    // chunk, or that you walk away from — four ways to leak a stuck "warden
    // present" state. Events would probably give cleaner timing; they would
    // also be four more edge cases. This runs on the slow cadence, so it
    // costs one bounding-box query per second.
    // ---------------------------------------------------------------
    private static void pollWarden(Player player, EffectRegistry effects, long now) {
        if (!Feature.WARDEN_ENCOUNTER.isOn()) return;
        double radius = RGBProfileConfig.WARDEN_DETECTION_RADIUS.get();
        List<Warden> nearby = player.level().getEntitiesOfClass(
                Warden.class, player.getBoundingBox().inflate(radius));
        boolean present = !nearby.isEmpty();

        // The emergence flash, per Warden rather than per range-transition.
        //
        // It used to fire on "a Warden became present", which is the moment it
        // wandered inside 24 blocks — often a Warden that dug out somewhere
        // else entirely a minute ago. Pose.EMERGING is the real thing: the
        // animation it plays while hauling itself out of the floor, which is
        // the moment worth interrupting the board for.
        //
        // Still flashes for one that simply walks up, because being ambushed
        // by an already-spawned Warden deserves a warning just as much. Either
        // way it is once per Warden per encounter, deduped on entity id.
        boolean anyEmerging = false;
        for (Warden warden : nearby) {
            boolean emerging = warden.hasPose(Pose.EMERGING) || warden.hasPose(Pose.DIGGING);
            anyEmerging |= emerging;
            if (!wardenFlashed.add(warden.getId())) continue;

            RGBColor emergenceColor = RGBColor.fromHexOrDefault(
                    RGBProfileConfig.WARDEN_EMERGENCE_COLOR.get(), ColorPalette.WARDEN_EMERGENCE);
            double thickness = RGBProfileConfig.SCULK_SHRIEKER_RING_THICKNESS_KEYS.get();
            long pulse = RGBProfileConfig.WARDEN_EMERGENCE_PULSE_MILLIS.get();
            long fade = RGBProfileConfig.WARDEN_EMERGENCE_FADE_MILLIS.get();
            long maxTotal = RGBProfileConfig.WARDEN_EMERGENCE_MAX_MILLIS.get();
            long nominal = RGBProfileConfig.WARDEN_EMERGENCE_NOMINAL_MILLIS.get();
            // A Warden climbing out holds until it is out. One that merely
            // walked into range has no pose to wait on — that is a warning
            // rather than an event, so it runs a fixed short hold and fades.
            boolean holdUntilRisen = emerging && RGBProfileConfig.WARDEN_EMERGENCE_HOLD_UNTIL_RISEN.get();
            long minHold = emerging
                    ? RGBProfileConfig.WARDEN_EMERGENCE_MIN_HOLD_MILLIS.get()
                    : Math.min(RGBProfileConfig.WARDEN_EMERGENCE_MIN_HOLD_MILLIS.get(), pulse);
            SdkWorkerThread.enqueue(() -> effects.wardenEmergenceFlash.trigger(
                    now, emergenceColor, thickness, pulse, minHold, fade, maxTotal, nominal, holdUntilRisen));
        }

        // Closes the open-ended hold the moment nothing in range is still
        // climbing out. Runs on the one-second ambient cadence, which is a
        // latency of at most one second against a 6.7-second animation — and
        // the effect honours its own minimum hold and hard cap regardless, so
        // a missed release costs a long dissolve rather than a stuck board.
        if (!anyEmerging) {
            SdkWorkerThread.enqueue(() -> effects.wardenEmergenceFlash.release(now));
        }

        // Forget Wardens that have left, so a later encounter flashes again.
        // Without this the set is also a slow leak across a long session.
        if (!wardenFlashed.isEmpty()) {
            java.util.Set<Integer> live = new java.util.HashSet<>();
            for (Warden warden : nearby) live.add(warden.getId());
            wardenFlashed.retainAll(live);
        }

        if (present != lastWardenPresent) {
            lastWardenPresent = present;
            SdkWorkerThread.enqueue(() -> effects.wardenActive.setActive(present, now));
        }
    }

    // ---------------------------------------------------------------
    // Tough As Nails soft-compat — entirely dormant if TAN
    // isn't detected (see ModCompatRegistry/ToughAsNailsCompat).
    // ---------------------------------------------------------------
    private static void pollToughAsNails(Player player, EffectRegistry effects, long now) {
        if (!Feature.TOUGH_AS_NAILS.isOn() || !ModCompatRegistry.isToughAsNailsLoaded()) return;

        ToughAsNailsCompat.Reading reading = ToughAsNailsCompat.read(player);
        // null means the API lookup failed even though the mod IS installed —
        // which at present is always, because the Tough As Nails integration
        // is a documented placeholder. Stay silent, don't crash, don't spam.
        // ToughAsNailsCompat's class doc explains what is missing and why.
        if (reading == null) return;

        boolean thirstLow = !dead && reading.thirstPercent() < RGBProfileConfig.THIRST_THRESHOLD_PERCENT.get();
        if (thirstLow != lastThirstBelowThreshold) {
            lastThirstBelowThreshold = thirstLow;
            SdkWorkerThread.enqueue(() -> effects.thirstWarning.setActive(thirstLow, now));
        }

        boolean overheating = !dead && reading.overheating();
        boolean freezing = !dead && reading.freezing();
        if (overheating != lastOverheating || freezing != lastFreezing) {
            lastOverheating = overheating;
            lastFreezing = freezing;
            RGBColor color = overheating ? ColorPalette.OVERHEATING_ORANGE : ColorPalette.FREEZING_ICE;
            boolean active = overheating || freezing;
            SdkWorkerThread.enqueue(() -> {
                effects.temperatureWarning.setColor(color);
                effects.temperatureWarning.setActive(active, now);
            });
        }
        // Seasonal tinting of the biome colour is not implemented. It would be a
        // post-processing step on BiomeColorEffect's resolved colour, and needs
        // that class to grow a tint hook first.
    }

    // ---------------------------------------------------------------
    // Death — polled, because the event never fires here.
    //
    // This was hung off LivingDeathEvent, and that handler could never once
    // have run. Vanilla's client-side death path is
    // LivingEntity.handleEntityEvent(3), and it reads:
    //
    //     if (!(this instanceof Player)) {
    //         this.setHealth(0.0F);
    //         this.die(this.damageSources().generic());
    //     }
    //
    // Players are explicitly excluded, so die() is never called client-side for
    // one, so LivingDeathEvent never fires, so a Dist.CLIENT subscriber never
    // sees it. Single player does not save it either: the integrated server
    // shares this JVM and does fire the event, but it fires with the SERVER's
    // ServerPlayer, which fails the handler's own
    // "Minecraft.getInstance().player != player" guard. Dead in every
    // configuration, which is exactly why the whole-board flash never appeared.
    //
    // Health reaching zero is synced to the client and is what the death screen
    // itself keys on, so polling it is both simpler and correct on every side.
    // ---------------------------------------------------------------
    private static void pollDeath(Player player, EffectRegistry effects, long now) {
        boolean isDead = player.isDeadOrDying();
        if (isDead == dead) return;
        dead = isDead;

        // Respawn. Drop the blood and let the world come back; the survival
        // polls re-establish their own state from the new player on their next
        // pass, so there is nothing else to do here.
        if (!isDead) {
            SdkWorkerThread.enqueue(() -> effects.deathState.setActive(false, now));
            return;
        }

        // Clear the survival warnings in the same breath as firing the flash.
        //
        // This is the other half of the bug. A dead player is at 0% health, 0%
        // food and 0% thirst, so every warning that keys on "below threshold"
        // is technically correct and completely useless — the H key sat there
        // pulsing red at someone who was already dead and could not act on it.
        // Worse, the flash is 800ms and the warnings are sustained, so the
        // moment the flash expired the red H came straight back and read as
        // having overwritten it.
        //
        // Cleared here rather than waiting for the ambient polls because those
        // run on the slow cadence and would leave the H flashing for up to a
        // second after the flash ended.
        lastHealthBelowThreshold = false;
        lastHungerBelowThreshold = false;
        lastThirstBelowThreshold = false;
        lastOverheating = false;
        lastFreezing = false;

        boolean flash = Feature.DEATH_FLASH.isOn();
        boolean blood = Feature.DEATH_BLOOD.isOn();
        RGBColor color = RGBColor.fromHexOrDefault(RGBProfileConfig.DEATH_FLASH_COLOR.get(), ColorPalette.DEATH_FLASH_RED);
        long duration = RGBProfileConfig.DEATH_FLASH_DURATION_MILLIS.get();
        SdkWorkerThread.enqueue(() -> {
            effects.healthFlash.setActive(false, now);
            effects.hungerWarning.setActive(false, now);
            effects.thirstWarning.setActive(false, now);
            effects.temperatureWarning.setActive(false, now);
            // Raised in the same job as the flash, so the blood is already
            // underneath when the flash dissolves off it. Ordered blood-first
            // for the same reason: the flash blanks the board while it draws,
            // so whatever is beneath only matters at the instant it ends.
            if (blood) effects.deathState.setActive(true, now);
            if (flash) {
                effects.deathFlash.trigger(now, color, duration, null, new FlashOncePattern());
            }
        });
    }

    // ---------------------------------------------------------------
    // Progression flashes
    // ---------------------------------------------------------------
    /** Stateless, so one instance serves every level-up. */
    private static final LevelUpPattern LEVEL_UP_PATTERN = new LevelUpPattern();

    /**
     * Ticks a freshly created player object is watched without celebrating.
     * The client builds a new player on respawn and on every dimension change,
     * starting at level 0, and the server's experience packet arrives a moment
     * later — a rise from 0 to your real level that is not a level-up.
     */
    private static final int LEVEL_SETTLE_TICKS = 40;
    private static Player levelWatchedPlayer = null;
    private static int lastExperienceLevel = 0;
    private static int levelSettleTicks = 0;

    /**
     * Watches your own experience level and plays the level-up when it rises.
     *
     * <p>An earlier version listened for {@code PlayerXpEvent.LevelChange}.
     * That event is posted from {@code Player.giveExperienceLevels}, which only
     * runs where levels are granted — the server. A client on a multiplayer
     * server is sent its new level in a packet that sets the number without
     * posting anything, so the level-up never played there. In single player
     * it did, but on the integrated server, and for any player on it: a LAN
     * guest levelling up lit the host's keyboard. The level itself is synced to
     * the client in every case, so the rise is read off that instead.
     */
    private static void pollLevelUp(Player player, EffectRegistry effects, long now) {
        int level = player.experienceLevel;
        if (player != levelWatchedPlayer) {
            levelWatchedPlayer = player;
            levelSettleTicks = LEVEL_SETTLE_TICKS;
        }
        if (levelSettleTicks > 0) {
            levelSettleTicks--;
            lastExperienceLevel = level;
            return;
        }
        int gained = level - lastExperienceLevel;
        lastExperienceLevel = level;
        // Only actual level INCREASES. Without this, dying (which drops your
        // level) would trigger a celebratory gold flash, which is a memorably
        // wrong emotional note.
        if (gained <= 0 || dead || !Feature.LEVEL_UP.isOn()) return;
        playLevelUp(effects, now);
    }

    private static void playLevelUp(EffectRegistry effects, long now) {
        RGBColor gold = RGBColor.fromHexOrDefault(RGBProfileConfig.LEVEL_UP_COLOR.get(), ColorPalette.LEVEL_UP_GOLD);
        RGBColor bar = RGBColor.fromHexOrDefault(RGBProfileConfig.LEVEL_UP_BAR_COLOR.get(), ColorPalette.LEVEL_UP_XP_GREEN);
        // An animation rather than a flash: the bar fills, then bursts. See
        // LevelUpPattern.
        SdkWorkerThread.enqueue(() -> effects.levelUpFlash.trigger(now, gold, bar,
                LevelUpPattern.DURATION_MILLIS, null, LEVEL_UP_PATTERN, null));
    }

    /**
     * Flashes when an advancement you earned puts its toast on screen.
     *
     * <p>An earlier version listened for {@code AdvancementEvent.AdvancementEarnEvent},
     * which has the same flaw the level-up hook had: it is posted where
     * advancements are awarded, which is the server. On a multiplayer server
     * the client never sees it, so the flash never played; in single player it
     * played on the integrated server for any player, and for every advancement
     * including the hidden ones behind each recipe unlock.
     *
     * <p>The client does get told. The server sends an advancements update, and
     * {@code ClientAdvancements.update} works out from it which ones were just
     * completed — not the ones replayed when you join — and queues an
     * {@code AdvancementToast} for each that is meant to be announced. So a
     * toast being queued is exactly "you just earned something worth
     * announcing", decided by vanilla, and NeoForge reports it here.
     *
     * <p>{@code receiveCanceled}, because hiding toasts is a popular thing for
     * a mod to do, and it does it by cancelling this event. The advancement is
     * still earned when its popup is hidden, and the keyboard is a different
     * place to hear about it.
     */
    @SubscribeEvent(receiveCanceled = true)
    public static void onAdvancementToast(ToastAddEvent event) {
        if (!(event.getToast() instanceof AdvancementToast)) return;
        if (!Feature.ADVANCEMENT.isOn()) return;
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null) return;
        long now = System.currentTimeMillis();
        RGBColor color = RGBColor.fromHexOrDefault(RGBProfileConfig.ADVANCEMENT_COLOR.get(), ColorPalette.ADVANCEMENT_CYAN);
        SdkWorkerThread.enqueue(() -> effects.advancementFlash.trigger(now, color, 700, null, new FlashOncePattern()));
    }

    // ---------------------------------------------------------------
    // Portal Transition
    // ---------------------------------------------------------------
    // An earlier version subscribed to EntityTravelToDimensionEvent
    // and never fired once, in any game mode, for two independent reasons:
    //
    //   1. That event is dispatched on the LOGICAL SERVER. This handler class
    //      is @EventBusSubscriber(value = Dist.CLIENT), so on a multiplayer
    //      server it is never delivered at all.
    //   2. Even in singleplayer, where the integrated server does dispatch it,
    //      event.getEntity() is a ServerPlayer. The guard
    //      `Minecraft.getInstance().player != player` therefore always failed
    //      and returned early.
    //
    // It is also the wrong shape regardless: the event fires BEFORE the
    // transfer, and modded teleporters that move a player directly often
    // bypass it entirely -- which would have broken the whole "works
    // automatically for any modded dimension" premise.
    //
    // Watching the client's own level is dispatch-agnostic: it reports the
    // dimension the player is ACTUALLY in, however they got there, so
    // Aether/Twilight Forest/Undergarden and every future dimension mod work
    // with zero per-mod code.
    /**
     * Where we listen for a dimension change, and why it is all the way down
     * here at the level instead of somewhere sensible-looking.
     *
     * <h2>The short version</h2>
     * Listening at the portal or the player puts us at the back of a very long
     * queue. Shader and rendering mods — Iris, Sodium, Veil, Flywheel — mixin
     * into exactly that path and rewrite what happens during a dimension change,
     * and all of their work runs <i>before</i> the portal- and player-facing
     * events reach anyone else. So instead of listening there, this goes one
     * step deeper into the class hierarchy and listens at the {@code Level}
     * itself, which is constructed before any of that work starts.
     *
     * <h2>The long version</h2>
     * The obvious hook is {@code ClientPlayerNetworkEvent.Clone} — the player
     * gets swapped when you go through a portal, so listen for the swap. That
     * is what this used to do, and it was late every single time. Measured
     * against Iris's own {@code Reloading pipeline on dimension change} line
     * across four consecutive trips in a 600-mod pack, the gap was 2696ms,
     * 973ms, 879ms and 883ms:
     *
     * <pre>
     *   06:59:15.809  Iris   overworld => the_nether
     *   06:59:18.505  Clone  (2696ms later)
     * </pre>
     *
     * <p>That is not jitter, it is ordering.
     * {@code ClientPacketListener#handleRespawn} swaps the ClientLevel and
     * calls {@code Minecraft#setLevel} <b>first</b>, and only dispatches Clone
     * at the very end. Everything else in the pack has mixined into that
     * middle section: Iris tearing down and rebuilding its shader pipeline,
     * Veil recompiling, and Sodium/Flywheel/colorwheel each restarting the
     * ChunkBuilder — three separate restarts show up in the log. On a clean
     * install that block takes microseconds and nobody would ever notice. On a
     * heavy shader pack it takes one to three seconds, and our listener is
     * sitting patiently behind all of it.
     *
     * <p>The tick poll cannot save us either, because no client tick is
     * dispatched during that stall. Same queue, same problem.
     *
     * <h2>What it looked like</h2>
     * The arrival animation fired <i>after</i> the player had already finished
     * loading in — so you would walk through a portal, watch the terrain
     * appear, and only then get a flicker of portal effect that promptly
     * ended, because most of its runtime had been spent behind a frozen
     * client. It read as the effect being broken rather than being late.
     *
     * <p>{@code LevelEvent.Load} fires when the new ClientLevel is
     * constructed, which is upstream of that whole pile, so we land at or
     * before Iris. Going deeper in the class hierarchy was the fix: the level
     * is the thing that actually changed, and it is the one place nobody else
     * has queued up in front of us.
     *
     * <h2>Belt and braces</h2>
     * {@link #onClientPlayerClone} and {@link #pollDimension} are still wired
     * up for any transition that does not construct a level. All three share
     * {@code lastDimensionId}, so whichever sees it first wins and the others
     * quietly no-op. The debug line names which one fired, so if this ever
     * starts trailing again you can tell immediately — and the remaining
     * option at that point is a mixin at HEAD of {@code Minecraft#setLevel},
     * which is exactly where Iris hooks.
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null) return;
        if (!(event.getLevel() instanceof Level level)) return;
        if (!level.isClientSide()) return; // integrated server levels also post this
        String id = dimensionIdOf(level);
        if (id == null) return;
        checkDimensionChange(effects, id, System.currentTimeMillis(), "level-load");
    }

    @SubscribeEvent
    public static void onClientPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null) return;

        // Read every source available at clone time. The level swap and the
        // player swap do not necessarily land in the same order, so the new
        // player can still report the OLD dimension here. Reading only that
        // one made the handler silently no-op — checkDimensionChange saw an
        // unchanged id — and the tick poll then caught it seconds later. That
        // is consistent with the ~900ms lag behind Iris on a busy pack while a
        // clean install looked instant, because on a clean install the poll
        // arrives one tick later regardless.
        Minecraft mc = Minecraft.getInstance();
        String fromLevel = dimensionIdOf(mc != null ? mc.level : null);
        String fromNewPlayer = event.getNewPlayer() != null ? dimensionIdOf(event.getNewPlayer().level()) : null;

        if (portalDebug()) {
            RGBProfileMod.LOGGER.info("[portal] clone event: mc.level={} newPlayer.level={} last={}",
                    fromLevel, fromNewPlayer, lastDimensionId);
        }

        String resolved = null;
        if (fromLevel != null && !fromLevel.equals(lastDimensionId)) {
            resolved = fromLevel;
        } else if (fromNewPlayer != null && !fromNewPlayer.equals(lastDimensionId)) {
            resolved = fromNewPlayer;
        }

        if (resolved != null) {
            checkDimensionChange(effects, resolved, System.currentTimeMillis(), "clone-event");
            return;
        }

        // A clone happened but nothing readable has changed yet. Rather than
        // discard the earliest signal we get, latch it: the next observation
        // fires the arrival immediately instead of waiting out the debounce of
        // a saturated tick loop.
        dimensionChangePending = true;
        if (portalDebug()) {
            RGBProfileMod.LOGGER.info("[portal] clone event: no readable change yet, latched for next observation");
        }
    }

    private static String dimensionIdOf(Level level) {
        try {
            if (level == null || level.dimension() == null) return null;
            ResourceLocation loc = level.dimension().location();
            return loc == null ? null : loc.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void pollDimension(Minecraft mc, EffectRegistry effects, long now) {
        if (!Feature.PORTAL_TRANSITION.isOn()) return;
        if (mc.level == null) return;

        ResourceLocation loc = mc.level.dimension().location();
        if (loc == null) return;
        boolean latched = dimensionChangePending;
        dimensionChangePending = false;
        checkDimensionChange(effects, loc.toString(), now, latched ? "tick-poll (clone-latched)" : "tick-poll");
    }

    /** Shared by the clone event and the tick poll; first observer wins. */
    private static synchronized void checkDimensionChange(EffectRegistry effects, String dimensionId,
                                                          long now, String source) {
        if (!Feature.PORTAL_TRANSITION.isOn()) return;

        if (lastDimensionId == null) {
            // First observation this session. Joining a world is not a portal
            // transition, so record and stay silent.
            lastDimensionId = dimensionId;
            return;
        }
        if (dimensionId.equals(lastDimensionId)) return;
        lastDimensionId = dimensionId;

        if (portalDebug()) {
            RGBProfileMod.LOGGER.info("[portal] dimension change observed via {} "
                    + "(compare this timestamp against Iris's \"Reloading pipeline on dimension change\" line: "
                    + "they should be within one frame of each other)", source);
        }
        triggerPortalTransition(effects, dimensionId, now);
    }

    /**
     * Portal logging follows either its own flag or the master debug switch.
     * Requiring both to be set separately meant a debug run came back with the
     * dimension-change source line missing, which was the one line needed to
     * tell whether the clone-event hook or the tick poll had won.
     */
    private static boolean portalDebug() {
        return RGBProfileConfig.PORTAL_DEBUG_LOGGING.get() || Diagnostics.enabled();
    }

    private static void triggerPortalTransition(EffectRegistry effects, String dimensionId, long now) {
        if (portalDebug()) {
            long gap = portalDetectedAtMillis > 0 ? now - portalDetectedAtMillis : -1;
            RGBProfileMod.LOGGER.info(
                    "[portal] dimension changed to {} at t={} ({}ms after intersection was detected)",
                    dimensionId, now, gap);
        }
        boolean fallbackEnabled = RGBProfileConfig.PORTAL_FALLBACK_TO_DERIVED_COLOR.get();
        long duration = RGBProfileConfig.PORTAL_DURATION_MILLIS.get();
        double holdFraction = RGBProfileConfig.PORTAL_HOLD_FRACTION.get();
        int suppressionFloor = RGBProfileConfig.PORTAL_SUPPRESSION_FLOOR.get();

        // durationMillis/holdFraction now describe the MINIMUM hold and the
        // fade length. The hold above that minimum is open-ended and closed by
        // pollPortalWorldReady when the terrain screen clears — see
        // PortalTransitionEffect's "Why the hold is open-ended".
        long minHold = (long) (Math.max(0.0, Math.min(0.95, holdFraction)) * duration);
        long fade = Math.max(1, duration - minHold);
        boolean waitForWorldReady = RGBProfileConfig.PORTAL_HOLD_UNTIL_WORLD_READY.get();
        long postArrivalHold = RGBProfileConfig.PORTAL_POST_ARRIVAL_HOLD_MILLIS.get();
        long maxTotal = RGBProfileConfig.PORTAL_MAX_DURATION_MILLIS.get();

        // Arm the release before the effect is armed, not after: the worker
        // job below runs on another thread, so a fast load could otherwise
        // clear the terrain screen and be missed in the gap.
        awaitingWorldReady = waitForWorldReady;
        worldReadyArmedAtMillis = now;

        SdkWorkerThread.enqueue(() -> {
            DimensionProfile profile = ProfileResolver.resolve(effects.dimensionProfiles, dimensionId, fallbackEnabled,
                    () -> derivedDimensionProfile(dimensionId));
            RGBColor base = profile != null ? profile.resolvedColor(RGBColor.deterministicFromKey(dimensionId))
                    : RGBColor.deterministicFromKey(dimensionId);
            RGBColor accent = profile != null ? profile.resolvedAccentColor(base) : base.lightened(0.6);

            // A hand-authored gradient if the dimension has one, otherwise a
            // ramp derived from its base and accent. Either way the portal
            // gets a pillar-style field rather than a flat two-tone.
            ColorRamp ramp = profile != null ? profile.resolvedRamp(base, accent)
                    : ColorRamp.derived(base, accent);
            CoreEmitterPattern.Settings settings = profile != null
                    ? profile.toSettings() : new CoreEmitterPattern.Settings();

            // Read the dwell's clock BEFORE stopping it, so the arrival picks
            // the spiral up mid-rotation instead of snapping to phase zero.
            // arrivalFlashStyle is deliberately not consulted any more: there
            // is no arrival flash.
            long phaseOffset = effects.portalCharge.elapsedMillis(now);

            effects.portalTransitionFlash.setSuppressionFloor(suppressionFloor);
            effects.portalTransitionFlash.trigger(now, ramp, minHold, fade, postArrivalHold, maxTotal,
                    waitForWorldReady, settings, phaseOffset);
            effects.portalCharge.setActive(false, now);
        });
    }

    /**
     * Closes the arrival's open-ended hold once the destination is on screen.
     *
     * <h2>Why {@code ReceivingLevelScreen}</h2>
     * The arrival fires as early as the dimension change can be observed, but
     * the player cannot see anything for the next one to several seconds while
     * the client rebuilds chunks and shaders. Anchoring the fade to a stopwatch
     * started at the transfer meant the dissolve happened entirely inside that
     * stall — by the time "Downloading terrain" cleared the board was already
     * back to the biome layer, which reads as the portal effect being missing
     * rather than as it being early.
     *
     * <p>{@code ReceivingLevelScreen} is the screen showing that message. The
     * game closes it when {@code LevelLoadStatusManager#levelReady} goes true,
     * which is precisely "the chunk you are standing in is loaded and
     * rendered" — the game's own answer to the question this needs answered,
     * with no chunk-status guessing of our own. It is opened synchronously
     * inside {@code handleRespawn}, in the same block that swaps the level, so
     * no client tick can slip in between the change being observed and the
     * screen being up: this cannot latch onto the pre-transfer state.
     *
     * <p>Two paths deliberately do not wait:
     *
     * <ul>
     *   <li>{@code holdUntilWorldReady = false}, which never arms the flag.</li>
     *   <li>A dimension change caught by the tick poll after the screen already
     *       closed. The first tick then releases immediately, and the effect
     *       still honours its minimum hold, so a fast local transfer keeps the
     *       old feel instead of stopping short.</li>
     * </ul>
     *
     * <p>The effect caps itself at {@code maxDurationMillis} regardless, so a
     * screen that never closes — or a mod that swaps it for its own — costs a
     * long arrival, not a stuck one.
     */
    private static void pollPortalWorldReady(Minecraft mc, EffectRegistry effects, long now) {
        if (!awaitingWorldReady) return;
        if (mc.screen instanceof ReceivingLevelScreen) return;

        awaitingWorldReady = false;
        long loadMillis = worldReadyArmedAtMillis > 0 ? now - worldReadyArmedAtMillis : -1;
        if (portalDebug()) {
            RGBProfileMod.LOGGER.info(
                    "[portal] world ready at t={} ({}ms of terrain loading); arrival now holds "
                            + "postArrivalHoldMillis and then dissolves", now, loadMillis);
        }
        SdkWorkerThread.enqueue(() -> effects.portalTransitionFlash.notifyWorldReady(now));
    }

    // ---------------------------------------------------------------
    // Portal charge-up
    // ---------------------------------------------------------------
    // The portal used to be a single flash on arrival. The dwell phase — the
    // seconds spent standing in the portal while the screen warps and the
    // destination loads — is a Tier 2 sustained overlay that builds until the
    // transfer completes, then hands off to the arrival spiral. Config-gated
    // (portalTransition.chargeUpEnabled), so it can be switched off to get the
    // arrival-only behaviour back.
    //
    // Detection is by block registry path rather than a hardcoded block list,
    // keeping the "generic first, specific second" property: minecraft's
    // nether_portal/end_portal/end_gateway, twilightforest:twilight_portal,
    // aether:aether_portal and anything else named ...portal/...gateway all
    // match with no per-mod code.
    //
    // Dimensions with no portal block at all — Bumblezone, entered by
    // right-clicking a beehive — are caught by the screen they raise when the
    // transfer starts instead. See isDimensionTransitionScreenOpen.
    private static void pollPortalCharge(Minecraft mc, Player player, EffectRegistry effects, long now) {
        if (!Feature.PORTAL_CHARGE_UP.isOn()) return;
        if (mc.level == null) return;

        boolean inPortal;
        try {
            inPortal = isIntersectingPortal(mc, player) || isDimensionTransitionScreenOpen(mc);
        } catch (Exception e) {
            return; // never let a block lookup break the tick loop
        }

        if (inPortal == lastInPortal) return;
        lastInPortal = inPortal;

        if (portalDebug()) {
            if (inPortal) {
                portalDetectedAtMillis = now;
                RGBProfileMod.LOGGER.info("[portal] intersection detected at t={} (portalProcess={})",
                        now, mc.player != null && mc.player.portalProcess != null);
            } else {
                RGBProfileMod.LOGGER.info("[portal] intersection ended at t={}", now);
            }
        }

        String dimensionId = lastDimensionId;
        SdkWorkerThread.enqueue(() -> {
            if (!inPortal) {
                effects.portalCharge.setActive(false, now);
                return;
            }
            // You are standing in the destination portal the instant you
            // arrive. Re-arming the dwell there would restart the field at the
            // entry floor on top of an arrival that is still fading out, which
            // reads as a stutter. Let the arrival finish first.
            if (effects.portalTransitionFlash.isActive(now)) return;
            // Colour the charge-up with the profile of the dimension we are
            // LEAVING, so the board reads as "the current world dissolving"
            // and the arrival spiral introduces the destination's colour.
            DimensionProfile profile = dimensionId == null ? null
                    : ProfileResolver.resolve(effects.dimensionProfiles, dimensionId,
                            RGBProfileConfig.PORTAL_FALLBACK_TO_DERIVED_COLOR.get(),
                            () -> derivedDimensionProfile(dimensionId));
            RGBColor color = profile != null
                    ? profile.resolvedColor(RGBColor.deterministicFromKey(dimensionId))
                    : RGBColor.fromHex("#8B008B");
            RGBColor accent = profile != null ? profile.resolvedAccentColor(color) : color.lightened(0.6);

            // Same fitted pillar geometry and palette the arrival uses, so the
            // dwell and the arrival are one continuous effect rather than a
            // blink followed by an unrelated spiral.
            ColorRamp ramp = profile != null ? profile.resolvedRamp(color, accent)
                    : ColorRamp.derived(color, accent);
            CoreEmitterPattern.Settings settings = profile != null
                    ? profile.toSettings() : new CoreEmitterPattern.Settings();

            effects.portalCharge.setPalette(ramp, settings);
            effects.portalCharge.setRampMillis(RGBProfileConfig.PORTAL_CHARGE_RAMP_MILLIS.get());
            effects.portalCharge.setSuppressionFloor(RGBProfileConfig.PORTAL_SUPPRESSION_FLOOR.get());
            effects.portalCharge.setIntensityFloor(RGBProfileConfig.PORTAL_CHARGE_INTENSITY_FLOOR.get());
            effects.portalCharge.setActive(true, now);
        });
    }

    /**
     * True from the tick the player's hitbox first touches a portal, rather
     * than from the tick their centre point has walked fully into one.
     *
     * <p>This used to test {@code player.blockPosition()} and the block above
     * it. That is the block containing the player's <i>centre</i>, so it only
     * became true once you had walked most of the way in — well after
     * Minecraft itself had started the transition. Minecraft dispatches
     * {@code entityInside} on every block the entity's bounding box overlaps,
     * so centre-point testing is structurally late.
     *
     * <p>Two signals, in order of how early they fire:
     *
     * <ol>
     *   <li>{@code Entity#portalProcess}, a nullable {@code PortalProcessor}
     *       that Minecraft attaches via {@code setAsInsidePortal} the moment a
     *       portal block handles the entity. This is the earliest hook
     *       reachable without owning the portal block, and it needs no
     *       block-name matching, so any mod portal built on the vanilla
     *       mechanism is covered for free.</li>
     *   <li>A scan of every block the player's bounding box overlaps, matched
     *       by registry path. This catches portals that move the player by
     *       their own means and never call {@code setAsInsidePortal} — worth
     *       keeping in a 600-mod pack — and end portals, which teleport on
     *       contact and so may only ever be intersected for a single tick.</li>
     * </ol>
     *
     * <p>Firing genuinely earlier than this would mean overriding the portal
     * block's own {@code entityInside}, which requires owning the block. This
     * mod is a drop-in with no hard dependencies and does not own vanilla's or
     * anyone else's, so the processor field is the correct ceiling here.
     */
    private static boolean isIntersectingPortal(Minecraft mc, Player player) {
        if (player.portalProcess != null) return true;

        AABB box = player.getBoundingBox();
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = (int) Math.floor(box.maxX);
        int maxY = (int) Math.floor(box.maxY);
        int maxZ = (int) Math.floor(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isPortalBlock(mc, new BlockPos(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    /**
     * True while a mod is showing its own "you are being moved between
     * dimensions" screen.
     *
     * <p>Not every dimension is entered by walking into a block. The Bumblezone
     * is entered by right-clicking a beehive, so there is no portal block to
     * intersect and {@code portalProcess} is never set — the player is simply
     * teleported. {@link #isIntersectingPortal} sees none of that, so the dwell
     * never fired for it and the sequence started at the arrival, which is the
     * half that plays <i>after</i> the interesting part.
     *
     * <p>What those mods do have is a screen they put up the moment the
     * transfer starts, which is the same instant the player initiates it. That
     * is the signal, and matching it by class name keeps this a drop-in: no
     * compile-time dependency on any of them, and nothing to update when one
     * changes version.
     *
     * <h2>Why this match is narrow when the block match is deliberately loose</h2>
     * {@link #isPortalBlock} takes anything named "portal" or "gateway" because
     * a false positive there costs a portal effect near a block that is already
     * portal-shaped, and the player walks out of it a second later. A screen is
     * different: it can sit open indefinitely, and the dwell field holds at full
     * for as long as it is up. So this requires <b>both</b> "dimension" and
     * "teleport" in the class name rather than either — enough for Bumblezone's
     * {@code DimensionTeleportingScreen}, and not enough for the various mods
     * whose teleport menus you might be browsing at your leisure.
     *
     * <p>{@code ReceivingLevelScreen} deliberately does not match. That is
     * vanilla's <i>arrival</i> screen and is already handled by
     * {@link #pollPortalWorldReady}; catching it here would re-arm the dwell on
     * top of an arrival that is still playing.
     *
     * <p>Cached per screen class for the same reason the block test is cached:
     * this runs every client tick, and the answer for a given class never
     * changes.
     */
    private static final java.util.Map<Object, Boolean> TRANSITION_SCREEN_CACHE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static boolean isDimensionTransitionScreenOpen(Minecraft mc) {
        net.minecraft.client.gui.screens.Screen screen = mc.screen;
        if (screen == null) return false;
        Class<?> type = screen.getClass();
        Boolean cached = TRANSITION_SCREEN_CACHE.get(type);
        if (cached != null) return cached;
        String name = type.getSimpleName().toLowerCase(java.util.Locale.ROOT);
        boolean result = name.contains("dimension") && name.contains("teleport");
        TRANSITION_SCREEN_CACHE.put(type, result);
        if (result && portalDebug()) {
            RGBProfileMod.LOGGER.info("[portal] dimension transition screen detected: {}",
                    type.getName());
        }
        return result;
    }

    /**
     * "Is this a portal?" — a registry reverse-lookup and two substring scans,
     * memoised per Block instance.
     *
     * <p>The cache is not premature optimisation. This runs up to <b>twelve
     * times per client tick</b> (the player's bounding box spans up to 12
     * blocks) on the <b>main thread</b>, and in a pack with ~30,000 registered
     * block states a registry reverse-lookup plus string scan is genuinely not
     * free. The answer for a given Block never changes at runtime, so it's
     * cached forever.
     *
     * <p>IdentityHashMap because Blocks are singletons — reference equality is
     * both correct and free here, while {@code equals()} on some modded Blocks
     * is neither.
     *
     * <p>The matching itself is gloriously crude: any block whose registry
     * path contains "portal" or "gateway". This is a heuristic and it knows
     * it. But it catches nether_portal, end_portal, end_gateway,
     * twilight_portal, aether_portal, and essentially every modded portal ever
     * named, with zero per-mod code. The false-positive cost is a portal
     * effect firing near something merely called a portal, which is a fine
     * trade for covering 600 mods you have never heard of.
     */
    private static final java.util.Map<Object, Boolean> PORTAL_BLOCK_CACHE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static boolean isPortalBlock(Minecraft mc, BlockPos pos) {
        Object block = mc.level.getBlockState(pos).getBlock();
        Boolean cached = PORTAL_BLOCK_CACHE.get(block);
        if (cached != null) return cached;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock());
        boolean result = false;
        if (id != null) {
            String path = id.getPath();
            result = path.contains("portal") || path.contains("gateway");
        }
        PORTAL_BLOCK_CACHE.put(block, result);
        return result;
    }

    /** Last-resort profile for a dimension nobody wrote an entry for: a stable invented colour, nothing else. */
    private static DimensionProfile derivedDimensionProfile(String dimensionId) {
        DimensionProfile profile = new DimensionProfile();
        profile.color = RGBColor.deterministicFromKey(dimensionId).toHex();
        return profile;
    }

    // ---------------------------------------------------------------
    // Sleep / Wake
    // ---------------------------------------------------------------
    // NOTE: PlayerSleepInBedEvent covers falling asleep; there is no single
    // universally-clean vanilla "player woke up" client event across all
    // wake paths (natural morning vs. skip-night vs. manually getting up),
    // so this uses a tick-based sleeping-state comparison for the wake
    // half, which reliably covers all of them. Worth revisiting if NeoForge
    // exposes a dedicated wake event in this version that's cleaner than
    // polling.
    // Both branches trigger the same effect with opposite fade directions —
    // OUT when you lie down (the world dimming away), IN when you get up (the
    // world returning). Same colour, same duration, mirrored. It's one of the
    // cheapest bits of storytelling in the mod.
    private static void pollSleepState(Player player, EffectRegistry effects, long now) {
        if (!Feature.SLEEP_WAKE.isOn()) return;
        boolean isSleeping = player.isSleeping();
        if (isSleeping && !sleeping) {
            RGBColor color = RGBColor.fromHexOrDefault(RGBProfileConfig.SLEEP_WAKE_COLOR.get(), ColorPalette.SLEEP_WAKE_BLUE);
            long duration = RGBProfileConfig.SLEEP_WAKE_DURATION_MILLIS.get();
            SdkWorkerThread.enqueue(() -> effects.sleepWakeFlash.trigger(now, color, duration, null, new FadePattern(FadePattern.Direction.OUT)));
        } else if (!isSleeping && sleeping) {
            RGBColor color = RGBColor.fromHexOrDefault(RGBProfileConfig.SLEEP_WAKE_COLOR.get(), ColorPalette.SLEEP_WAKE_BLUE);
            long duration = RGBProfileConfig.SLEEP_WAKE_DURATION_MILLIS.get();
            SdkWorkerThread.enqueue(() -> effects.sleepWakeFlash.trigger(now, color, duration, null, new FadePattern(FadePattern.Direction.IN)));
        }
        sleeping = isSleeping;
    }

    // ---------------------------------------------------------------
    // Sculk Sensor / Shrieker Alert.
    //
    // Both blocks carry their firing state in the BLOCK STATE, and block
    // states sync to every client holding the chunk: a sensor's PHASE cycles
    // INACTIVE -> ACTIVE -> COOLDOWN, and a shrieker's SHRIEKING flips true.
    // Both are set with a flag that includes "send to client", so watching for
    // the rising edge here is enough — no mixin, no server component, and it
    // works on a vanilla server that has never heard of this mod.
    //
    // SculkBlockWatcher does the scanning and explains why it is affordable.
    // ---------------------------------------------------------------
    /**
     * Drives the block watcher on its own cadence, separate from both the
     * every-tick group and the slow ambient group.
     *
     * <p>It belongs in neither: once a second is too slow to feel connected to
     * a sensor you just set off, and every tick is more scanning than a
     * cosmetic effect can justify. The default of every 5 ticks is 4Hz, which
     * cannot miss a 40-tick sensor activation and costs a palette check per
     * chunk section in range.
     */
    private static void pollSculkBlocks(Player player) {
        if (!Feature.SCULK_SENSOR.isOn() && !Feature.SCULK_SHRIEKER.isOn()) return;
        if (tickCounter % Math.max(1, RGBProfileConfig.SCULK_SCAN_INTERVAL_TICKS.get()) != 0) return;
        sculkWatcher.poll(player, RGBProfileConfig.SCULK_DETECTION_RADIUS.get(), SCULK_LISTENER);
    }

    public static void onSculkSensorActivated(long now) {
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null || !Feature.SCULK_SENSOR.isOn()) return;
        RGBColor color = RGBColor.fromHexOrDefault(RGBProfileConfig.SCULK_SENSOR_COLOR.get(), ColorPalette.SCULK_SENSOR_PING);
        long duration = RGBProfileConfig.SCULK_SENSOR_DURATION_MILLIS.get();
        SdkWorkerThread.enqueue(() -> effects.sculkSensorPing.trigger(now, color, duration, null,
                new RingExpandPattern(RGBProfileConfig.SCULK_SENSOR_RING_THICKNESS_KEYS.get())));
    }

    public static void onSculkShriekerActivated(long now) {
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null || !Feature.SCULK_SHRIEKER.isOn()) return;
        int maxLevel = RGBProfileConfig.SCULK_SHRIEKER_MAX_ESCALATION_LEVEL.get();
        RGBColor base = RGBColor.fromHexOrDefault(RGBProfileConfig.SCULK_SHRIEKER_COLOR.get(), ColorPalette.SCULK_SHRIEKER_BASE);
        RGBColor peak = RGBColor.fromHexOrDefault(RGBProfileConfig.SCULK_SHRIEKER_PEAK_COLOR.get(), ColorPalette.SCULK_SHRIEKER_PEAK);
        long baseDuration = RGBProfileConfig.SCULK_SHRIEKER_DURATION_MILLIS.get();

        EffectRegistry finalEffects = effects;
        SdkWorkerThread.enqueue(() -> {
            int level = finalEffects.shriekerEscalation.onShriek(now);
            RGBColor color = escalationColor(base, peak, level, maxLevel);
            long duration = com.everythingrgbprofile.effects.support.SculkEscalationTracker.durationForLevel(baseDuration, level, maxLevel);
            finalEffects.shriekerAlert.trigger(now, color, duration, null,
                    new RingContractPattern(RGBProfileConfig.SCULK_SHRIEKER_RING_THICKNESS_KEYS.get()));
        });
    }

    /** Forwards to SculkEscalationTracker, so the call site above stays on one line. */
    private static RGBColor escalationColor(RGBColor base, RGBColor deep, int level, int maxLevel) {
        return com.everythingrgbprofile.effects.support.SculkEscalationTracker.colorForLevel(base, deep, level, maxLevel);
    }

    private ClientEventHandlers() {
    }
}
