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
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
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
import net.minecraft.world.level.levelgen.Heightmap;
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
 * lives in this file, which makes it the entire boundary between "the game"
 * and "the keyboard".
 *
 * <h2>The pattern, repeated about twenty times</h2>
 * Every handler in here is deliberately tiny and shaped identically:
 * <b>compute, compare, enqueue</b>. Read some game state, check whether it
 * actually changed, and if it did, hand a small job to
 * {@link SdkWorkerThread}.
 *
 * <p>Nothing in this file ever touches an effect object or SDK state directly.
 * That is not a style preference, it is the thread-safety contract. See
 * {@code SdkWorkerThread} for what happens when two threads call into a native
 * DLL simultaneously. Spoiler: no stack trace, no crash report, no dignity.
 *
 * <p>The <b>compare</b> step earns its place as much as the other two. Almost
 * every poll fires on a THRESHOLD CROSSING rather than on the condition simply
 * being true. Health below 25% enqueues once, at the moment you cross it, and
 * not sixty times a second for as long as you stand there bleeding.
 *
 * <h2>What is wired up and what is not</h2>
 * Read this before assuming a hook works, because a hook that silently never
 * fires is considerably worse than one carrying a comment admitting it
 * doesn't.
 *
 * <p><b>Fully wired:</b> health, hunger, death, XP, advancement, biome, time
 * of day, weather, portal transition, drowning, burning, raids, sculk sensors
 * and shriekers, the Warden, bosses, and the entire Ender Dragon sequence.
 *
 * <p><b>Approximate, and honest about it: lightning.</b>
 * {@link #pollLightningFallback} fires on a timer during a thunderstorm rather
 * than on actual strikes, which makes it atmosphere rather than information.
 * Real strike detection wants {@code EntityJoinLevelEvent} filtered to
 * lightning bolts with a distance check against the player, and there is a
 * commented-out sketch of exactly that sitting near the weather polls, along
 * with the reason it hasn't been switched on yet.
 *
 * <p><b>Dormant: Tough As Nails.</b> The thirst and temperature polls are all
 * here and all correct, and they never run, because
 * {@code ToughAsNailsCompat.read} is a placeholder that always returns null.
 * See that class for what finishing it involves.
 *
 * <h2>A note on where these hooks are placed</h2>
 * Several detectors in here deliberately listen lower in the class hierarchy
 * than you would expect: the level rather than the player, block state rather
 * than game events. None of that is accidental or clever for its own sake.
 *
 * <p>In a large pack, the obvious high-level hooks are precisely the ones
 * every other mod has already mixined into, and arriving last means arriving
 * several seconds after the shader and chunk work has finished. Going one step
 * deeper gets you in front of that queue. See {@link #onLevelLoad} for the
 * measurements that made the point, including the four consecutive portal
 * trips where the obvious hook was between 0.9 and 2.7 seconds late.
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
    /** When the player last stopped being exposed to sky, which the shelter grace counts from. */
    private static long rainShelteredSinceMillis = 0;
    private static boolean lastSnowing = false;
    /** Same as the rain's, kept separately so walking from a snowy biome into a rainy one doesn't inherit a stale grace. */
    private static long snowShelteredSinceMillis = 0;
    /** The raid phase as of the last poll, or null when there isn't one. */
    private static RaidHordePattern.Phase lastRaidPhase = null;
    private static long raidLastSeenMillis = 0;
    /** The longest Raid Omen duration seen this countdown, which is therefore where it started from. */
    private static int raidOmenStartTicks = 0;
    /**
     * The {@code c:bosses} convention tag.
     *
     * <p>Built by hand instead of pulled from NeoForge's {@code Tags} class,
     * which has moved package between versions. Two lines that cannot break on
     * an update beats one import that can. Tags sync to clients, so this is
     * perfectly readable from over here.
     */
    private static final TagKey<EntityType<?>> BOSSES_TAG =
            TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("c", "bosses"));

    /**
     * Bearings, in whole degrees, of every tower that has lit during this
     * ritual.
     *
     * <p>Remembered rather than recounted every poll. The capture shows towers
     * staying lit right up until the dragon turns up, so a live count is
     * normally correct. But a crystal that slips out of client entity tracking
     * would put its ray out, and a tower that has fired has fired. You do not
     * get to un-fire.
     */
    private static final Set<Integer> endLockedBearings = new HashSet<>();
    /** Where the central crystals were last aiming, so the jump to a new tower is catchable. */
    private static int endLastSweepDegrees = Integer.MIN_VALUE;

    /** Horizontal distance from the world origin within which something counts as "the middle". */
    private static final double END_CENTRE_RADIUS = 12.0;

    private static boolean witherPresent = false;
    private static long witherLastSeenMillis = 0;
    private static int witherLastSkulls = 0;
    /** Spreads consecutive shots across the three heads so they take turns instead of one doing all the work. */
    private static int witherSkullRotation = 0;

    private static boolean nagaPresent = false;
    /** Last time the Naga was genuinely seen, which is what the grace period counts from. */
    private static long nagaLastSeenMillis = 0;

    private static boolean sliderPresent = false;
    private static long sliderLastSeenMillis = 0;
    /** Latched, so a Slider lying dead through its 20-tick death animation bursts once rather than twenty times. */
    private static boolean sliderDeathReported = false;

    private static boolean sunSpiritPresent = false;
    private static long sunSpiritLastSeenMillis = 0;
    private static boolean sunSpiritDeathReported = false;

    private static boolean valkyrieQueenPresent = false;
    private static long valkyrieQueenLastSeenMillis = 0;
    private static boolean valkyrieQueenDefeatReported = false;
    /** Latched once her walls are found, so the block scan stops until the next time she turns up. */
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
     * needs a mixin. It is visible in the debuff itself though: every 60
     * seconds an Elder Guardian re-applies a 6000-tick Mining Fatigue to anyone
     * within 50 blocks whose current one has under 1200 ticks left, so a
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
        // null means gating never started the worker at all: no hardware, mod
        // disabled, wrong OS, dedicated server. Costs one null check per tick
        // and buys complete silence on every machine this mod is irrelevant
        // to, which is most of them.
        if (effects == null) return;

        Minecraft mc = Minecraft.getInstance();

        // Runs ahead of the menu theme AND ahead of the null-guard below, and
        // is not gated by updateIntervalTicks. It is one boolean compare. A
        // gated version at the default interval of 20 would leave the lighting
        // from a world you have already left sitting on the board for up to a
        // full second after the menu appeared.
        pollWorldUnload(mc, effects);

        // The menu theme has to be checked before the player/level null-guard
        // below, because "no world loaded" (title screen, server list, mod
        // list, quitting out to the title screen) is the exact condition it
        // keys on. Pausing doesn't count: the world stays loaded behind it. Put
        // it after the guard and it can never once run.
        tickCounter++;
        int interval = RGBProfileConfig.UPDATE_INTERVAL_TICKS.get();
        // Every tick now rather than every updateIntervalTicks. It only
        // enqueues on a real edge (see pollMenuTheme), so running it twenty
        // times as often genuinely costs less than the old gated version did,
        // which is not a sentence you get to write very often.
        pollMenuTheme(mc, effects, System.currentTimeMillis());

        Player player = mc.player;
        if (player == null || mc.level == null) return;

        long now = System.currentTimeMillis();

        // Dimension change and portal dwell are discrete MOMENTS rather than
        // ambient state, so they run every tick instead of on the slow gate.
        // At the default interval of 20, the arrival spiral would otherwise
        // land up to a full second after the terrain screen cleared, by which
        // point it reads as something unrelated to the portal you just walked
        // through. Both checks are cheap anyway: one ResourceKey comparison
        // and a couple of block lookups.
        Diagnostics.clientTickSeen(now);

        // Fast path. Dying is a moment, and the flash has to land ON it rather
        // than up to a second afterwards while you stare at the respawn screen.
        pollDeath(player, effects, now);
        // Every tick. A level-up is a moment, and a second late it plays after
        // the sound it is supposed to be accompanying, which reads as a
        // completely unrelated event.
        pollLevelUp(player, effects, now);

        pollDimension(mc, effects, now);
        pollPortalWorldReady(mc, effects, now);
        pollPortalCharge(mc, player, effects, now);
        // Every tick. Air supply changes once per tick underwater, and the
        // entire point of the effect is the values BETWEEN full and empty, so
        // sampling it once a second throws away the thing being drawn.
        pollDrowning(player, effects);
        // Every tick as well. Stepping into lava should flare the board as it
        // happens, not up to a second later once you are already regretting it.
        pollBurning(player, effects);
        // Fast path. The dragon's phase changes ARE the beats of the fight and
        // a beam landing is a discrete moment. Both look wrong a second late.
        pollEndDragon(player, effects, now);
        pollNaga(player, effects, now);
        // Fast path. A slide lasts a second or two and ends in a slam, so the
        // board has to move WITH the cube rather than trailing a second behind
        // it like a bad stream.
        pollSlider(mc, player, effects, now);
        // Fast path. A crystal crosses the room in a second or two, and the
        // freeze it causes is a window of under nine seconds. Sampling that
        // once a second would miss most of the fight.
        pollSunSpirit(mc, player, effects, now);
        // Fast path. A teleport is over in three ticks and a lunge in eight,
        // both of which are comfortably shorter than the slow gate.
        pollValkyrieQueen(mc, player, effects, now);
        pollWither(player, effects, now);
        pollRaid(mc, player, effects, now);
        // Fast path. Three seconds of laser wind-up IS the whole effect, and
        // losing a second of that loses a third of it. Cheap when there is
        // nothing to find; see pollElderGuardian for the scan cadence.
        pollElderGuardian(player, effects, now);

        pollSculkBlocks(player);

        // Biome gets its own cadence, sitting above the ambient gate. Sharing
        // the one-second group put every change between one and two seconds
        // behind the player: one interval to notice it, then a second whole
        // interval to satisfy a debounce that was SHORTER than the interval
        // and therefore never filtered anything in its life. Two settings
        // quietly cancelling each other out. See Diagnostics.dumpBiomeTiming,
        // which warns about that combination at startup when debug logging is
        // on.
        if (tickCounter % Math.max(1, RGBProfileConfig.BIOME_SAMPLE_INTERVAL_TICKS.get()) == 0) {
            pollBiome(player, effects, now);
        }

        // Everything past this line is ambient state on the slow cadence. The
        // default interval is 20 ticks, i.e. once per second, which is plenty
        // for questions like "is it raining" and skips roughly 19 out of every
        // 20 polls.
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
    // onClientTick returns at `player == null || mc.level == null`, so the
    // moment a world goes away every poll below that line just stops running.
    // Not one of them gets a final call. Which means every effect they drive
    // keeps whatever state it was last handed, forever, and nobody ever tells
    // it otherwise.
    //
    // pollMenuTheme is the one exception, because it sits above the guard, so
    // the menu theme does switch itself on. It simply had to then fight
    // everything that had never switched off:
    //
    //   - wardenActive is Tier 1 at priority 11 against menuTheme's 1, so
    //     quitting anywhere near a Warden left the title screen sitting in
    //     sculk ambience indefinitely.
    //   - rainCascade is a whole-board Tier 2 overlay, and Tier 2 has no
    //     priority contest at all: everybody draws. Quit during a thunderstorm
    //     and the menu theme rendered underneath a permanent grey-blue drift.
    //   - health, hunger, thirst and temperature carried on pulsing away on
    //     their keys, warning the main menu about your food bar.
    //   - biomeColor's isActive is `currentBiomeId != null`, which reads as
    //     "active from the first biome we ever saw, and then forever".
    //     Priority 0 kept it hidden under the menu theme, so this one only
    //     surfaced for people who had turned menuTheme off in the config, and
    //     for them it was the entire board: the last biome's colour and
    //     pattern, on the main menu, permanently.
    //
    // Both entry points funnel through endWorldSession, so whichever one
    // notices the unload first wins and the other is a no-op.
    // ---------------------------------------------------------------

    /**
     * The catch-all. Fires on the tick after {@code mc.level} goes null, by
     * whatever route got it there: Disconnect, Save and Quit, a kick, the
     * connection dropping, or some pack's own bespoke path back to the menu.
     *
     * <p>Screen subclasses are deliberately not consulted, for the same reason
     * pollMenuTheme doesn't consult them: in a large pack there is no
     * enumerable list of screens that count as "you have left the world".
     */
    private static void pollWorldUnload(Minecraft mc, EffectRegistry effects) {
        if (mc.level != null) {
            inWorld = true;
            return;
        }
        endWorldSession(effects, "tick-poll");
    }

    /**
     * The prompt one. {@code LoggingOut} lands DURING teardown rather than a
     * tick or more after it, which is the difference between the board
     * changing as the menu appears and the board visibly changing once you are
     * already looking at the menu.
     *
     * <p>Kept alongside the tick poll rather than replacing it. This event
     * covers a disconnect; the poll covers everything that isn't one. Belt and
     * braces, and the braces are two lines long.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null) return;
        endWorldSession(effects, "logging-out event");
    }

    /** Guarded so the first observer wins, exactly like checkDimensionChange does it. */
    private static synchronized void endWorldSession(EffectRegistry effects, String source) {
        if (!inWorld) return;
        inWorld = false;
        resetWorldState(effects, System.currentTimeMillis(), source);
    }

    /**
     * Hands every world-driven effect the "off" it never got, and wipes the
     * poll caches so the next world gets read fresh instead of being compared
     * against the one you just left.
     */
    private static void resetWorldState(EffectRegistry effects, long now, String source) {
        if (portalDebug()) {
            RGBProfileMod.LOGGER.info("[rgb] world unloaded (via {}) — releasing all world-driven effects", source);
        }

        // --- Client-thread poll caches --------------------------------
        // Every threshold poll fires on a CHANGE, which means a stale cache
        // gets the new world compared against the old one's readings.
        //
        // Concretely: quit while starving, rejoin still starving, and the
        // hunger warning never re-arms. The compare looks at its cached "yes,
        // already below threshold", sees no change, and concludes nothing has
        // happened. It is right, from where it is standing. Clearing these
        // makes the first poll of a new world re-establish the truth
        // unconditionally, which is the only safe assumption after a world
        // swap.
        lastHealthBelowThreshold = false;
        lastHungerBelowThreshold = false;
        lastThirstBelowThreshold = false;
        lastOverheating = false;
        lastFreezing = false;
        lastRaining = false;
        rainShelteredSinceMillis = 0;
        lastSnowing = false;
        snowShelteredSinceMillis = 0;
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
            effects.snowfall.setActive(false, now);
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
        // including every bespoke menu screen a large pack adds, which this
        // mod could not possibly enumerate and has absolutely no business
        // knowing about in the first place.
        boolean atMenu = mc.level == null;
        // Edge-triggered, like every other poll in this file. Without it, the
        // ungated call above would enqueue a job every single tick, forever,
        // for as long as the game is open.
        if (lastMenuActive != null && lastMenuActive == atMenu) return;
        lastMenuActive = atMenu;
        SdkWorkerThread.enqueue(() -> effects.menuTheme.setActive(atMenu, now));
    }

    // ---------------------------------------------------------------
    // The Ender Dragon: summoning ritual, fight, and death.
    //
    // Every bit of this comes off SynchedEntityData, which is the whole reason
    // it works with no mod imports and no server component:
    //
    //   - EndCrystal.getBeamTarget() is synced because vanilla has to render
    //     the beam for you. Counting the lit crystals IS the ritual's
    //     progress; nothing else needs asking.
    //   - EnderDragon's phase is synced (see EnderDragon.onSyncedDataUpdated,
    //     which explicitly applies it client-side), so the fight can be
    //     PHASE-aware rather than health-aware. Health tells you how the fight
    //     is going. Phase tells you what is about to happen to you.
    //   - dragonDeathTime is a public field, ticked client-side across the
    //     whole 200-tick death animation.
    //
    // The respawn is the part that forks. YUNG's Better End Island keeps its
    // DragonRespawnStage server-side and ships no packets at all, so that enum
    // is unreadable from here, but it drives crystals and crystals are synced,
    // so the beams reconstruct the whole staged sequence anyway. Without that
    // mod the respawn is deliberately left dark and crystals are not read at
    // all — see pollVanillaRitual. The gate is BetterEndIslandCompat.
    //
    // Only the respawn forks. The fight below reads phase, health and
    // dragonDeathTime straight off the EnderDragon entity, all of it synced
    // vanilla state that is there whatever else is installed, so it needs no
    // gate. If an End overhaul ever turns out to want its own fight effect,
    // the gate to branch on already exists.
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

        // One pass over the client's tracked entities instead of an AABB
        // query. The dragon ranges over the entire island and the pillars sit
        // 40-plus blocks out, so any radius generous enough to be correct
        // would have covered most of the dimension anyway. At that point the
        // query is just a slower iteration.
        EnderDragon dragon = null;
        int beaming = 0;
        // Every crystal gets KEPT now rather than just counted, because the
        // ritual needs each one's position and beam target to work out which
        // ones are towers and where the central four are pointing. There are
        // at most fourteen of them, so this costs nothing worth measuring.
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
                // Biggest one wins. With several pools on the ground at once,
                // the one covering the most floor is the one actually shaping
                // where you can stand, so it is the one worth drawing.
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
            // dragonDeathTime ticks 0 -> 200 across the death animation, and
            // it is the ONLY thing separating "dying spectacularly" from
            // "dead", because health has already been zero for the entire
            // duration of both.
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

        // Nothing in the sky. Whether that is worth lighting depends entirely
        // on which End this is, and the vanilla answer is that it isn't.
        if (!BetterEndIslandCompat.isStagedRitual()) {
            pollVanillaRitual(effects);
            return;
        }

        if (!Feature.END_RITUAL.isOn() || beaming <= 0) {
            endLockedBearings.clear();
            endLastSweepDegrees = Integer.MIN_VALUE;
            SdkWorkerThread.enqueue(effects.enderDragon::setIdle);
            return;
        }

        pollStagedRitual(effects, crystalsThisPoll, now);
    }

    /**
     * Better End Island's staged respawn, reconstructed from crystal beams.
     *
     * <p>Everything here is read from live entity state, so it never touches
     * the mod or its classes. The gate in {@link #pollEndDragon} is the only
     * thing that knows the mod exists.
     */
    private static void pollStagedRitual(EffectRegistry effects, List<EndCrystal> crystalsThisPoll,
                                         long now) {
        // Split the beams the way the ritual itself splits them. Taken from an
        // actual traced respawn rather than from reasoning about it, via
        // EndRitualTracer:
        //
        //   - Crystals OUT on the towers (r about 54) beam back to (0,128,0)
        //     once their tower has been activated, and then stay lit. Each
        //     one's own bearing is its ray's angle, read from the world rather
        //     than assumed to be evenly spaced, because the real steps come
        //     out at 35-37 degrees and not a tidy 36.
        //   - The four crystals in the MIDDLE (r about 8) all aim at one thing
        //     at a time: straight up at (0,128,0) for the opening five seconds
        //     and the closing five, and at whichever tower is currently being
        //     activated for the twenty seconds in between. That target's
        //     bearing is the sweeping ray.
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

        // The jump to the next tower is the beat the whole sequence is built
        // on, so it gets a kick rather than sliding quietly across.
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

    /**
     * Vanilla, where the board stays out of the respawn on purpose.
     *
     * <p>Crystals are not consulted here, deliberately. In a vanilla End they
     * are the dragon's health supply sitting on top of the pillars waiting to
     * be shot off, and that is the one thing they should ever mean. Lighting
     * a respawn off the same entities muddies the signal that actually
     * matters during a fight, in exchange for a sequence most players trigger
     * approximately never.
     *
     * <p>So: no crystals, no beams, no ritual. The dragon's own animations
     * carry the End, and this stays as the seam to hang something vanilla
     * specific off if that ever stops being the right call.
     */
    private static void pollVanillaRitual(EffectRegistry effects) {
        endLockedBearings.clear();
        endLastSweepDegrees = Integer.MIN_VALUE;
        SdkWorkerThread.enqueue(effects.enderDragon::setIdle);
    }

    /** Its own switch, or the master debug flag. Same arrangement as portalDebug(). */
    private static boolean traceEndRitual() {
        return RGBProfileConfig.END_TRACE_RITUAL.get() || Diagnostics.enabled();
    }

    // ---------------------------------------------------------------
    // The Wither.
    //
    // Vanilla, which means that unlike the modded bosses this can import the
    // class outright and read its real state instead of inferring things from
    // velocity and hope. All of the following is public and synced:
    //
    //   getInvulnerableTicks()   the 220-tick summon charge, DATA_ID_INV
    //   getAlternativeTarget(n)  what head n is tracking, DATA_TARGET_A/B/C
    //   isPowered()              health at or below half, i.e. armour is on
    //
    // Skull count gets tracked separately. A rise means one was just thrown,
    // and that beats any timer as a firing signal, because it IS the rate of
    // fire rather than an estimate of it.
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

        // A skull appearing in the world is the only honest "it just fired"
        // signal available. The heads have no synced attack flag at all, only
        // a target, and a target is not a shot.
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
        // A head reports 0 when it is not tracking anything at all.
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
    //   getActiveAttackTarget()    and on whom, specifically
    //   getAttackAnimationScale()  how far through the 60-tick wind-up it is
    //   getSpikesAnimation()       the crown of spikes, out while it hovers
    //
    // Mining Fatigue is read off the PLAYER rather than off the mob, for the
    // uncomplicated reason that the player is where it lands.
    // ---------------------------------------------------------------

    /**
     * Ticks between scans while none is in range.
     *
     * <p>The other bosses poll every tick because they walk the tracked-entity
     * list they were going to walk regardless. This one wants a box query
     * instead, and the charge it is watching for only exists while a Guardian
     * is already in range. So it scans twice a second until it finds one and
     * every tick afterwards, which keeps the wind-up smooth without paying for
     * it during the overwhelming majority of ticks where the nearest monument
     * is several thousand blocks away.
     */
    private static final int ELDER_GUARDIAN_SCAN_INTERVAL_TICKS = 10;

    private static void pollElderGuardian(Player player, EffectRegistry effects, long now) {
        if (!Feature.ELDER_GUARDIAN.isOn()) return;
        if (!elderGuardianPresent && tickCounter % ELDER_GUARDIAN_SCAN_INTERVAL_TICKS != 0) return;

        double radius = RGBProfileConfig.ELDER_GUARDIAN_DETECTION_RADIUS.get();
        List<ElderGuardian> nearby = player.level().getEntitiesOfClass(
                ElderGuardian.class, player.getBoundingBox().inflate(radius));

        // Whichever one is aiming at YOU wins, and only then the nearest. In a
        // monument room holding three of them, the one winding up on you is
        // the only one whose state is any use, and picking the nearest instead
        // would cheerfully show you a calm eye while a beam landed on your
        // head.
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
            // Grace period before letting go, because monuments are a maze of
            // walls and a Guardian on the other side of one is still very much
            // in the fight with you.
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
        // Only meaningful while it actually has a target. clientSideAttackTime
        // does not get zeroed until the target changes, so read outside the
        // lock it reports whatever the LAST wind-up got to, which would show a
        // fully charged beam belonging to a fight that ended a minute ago.
        double charge = locked
                ? Math.max(0, Math.min(1, best.getAttackAnimationScale(1.0f))) : 0;
        double spikes = Math.max(0, Math.min(1, best.getSpikesAnimation(1.0f)));

        // The top of the wind-up is the moment it fires. Latched, because the
        // charge then stays pinned at 1 for as long as it keeps hold of the
        // target, and an unlatched version would report it firing continuously.
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
        // Gone UP rather than down means a fresh curse rather than the old one
        // ticking away. See the field's note for why this is read off the
        // debuff itself instead of the packet that announces it.
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
    // Gated on the modid first, so for anybody without Twilight Forest
    // installed this costs one boolean read per tick and never once touches
    // the world.
    //
    // Matched by registry id rather than by class, because importing anything
    // from another mod is how you earn a NoClassDefFoundError the first time
    // somebody removes it from their pack. Health and velocity are both
    // public, synced, and entirely sufficient: health says how much snake is
    // left, and speed separates a charge from a prowl without anyone needing
    // the mod's private movement-state accessor.
    // ---------------------------------------------------------------
    private static void pollNaga(Player player, EffectRegistry effects, long now) {
        if (!Feature.NAGA.isOn() || !ModCompatRegistry.isTwilightForestLoaded()) return;
        if (!(player.level() instanceof ClientLevel clientLevel)) return;

        double maxDistance = RGBProfileConfig.NAGA_DETECTION_RADIUS.get();
        double maxDistanceSq = maxDistance * maxDistance;

        // Walk the client's tracked entities rather than querying a box. A box
        // big enough to cover the whole courtyard spans well over a thousand
        // chunk sections, and scanning that every tick to locate one snake
        // would be genuinely absurd. The tracked list is a few hundred entries
        // and the Naga is either in it or is not near you, which are the only
        // two answers this needs.
        LivingEntity naga = null;
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
            Identifier loc = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
            if (loc == null || !TwilightForestCompat.NAGA_ID.equals(loc.toString())) continue;
            if (living.distanceToSqr(player) > maxDistanceSq) continue;
            naga = living;
            break;
        }

        if (naga != null) {
            nagaPresent = true;
            nagaLastSeenMillis = now;
            double health = naga.getHealth() / Math.max(1.0f, naga.getMaxHealth());
            // Horizontal only. The Naga rears and drops constantly while it
            // circles, so vertical motion says nothing about intent. It is the
            // horizontal speed that tells you whether it is prowling or coming
            // straight at you.
            double speed = naga.getDeltaMovement().horizontalDistance();
            SdkWorkerThread.enqueue(() -> effects.naga.setNaga(health, speed, now));
            return;
        }

        if (!nagaPresent) return;
        // Grace period before letting go. The Naga dips behind the hedge,
        // burrows, and wanders off to the far side of its courtyard
        // constantly, and not one of those means the fight is over. Without
        // this the board flickers back and forth between snake and biome while
        // you are still very much being chased by a snake.
        if (now - nagaLastSeenMillis < RGBProfileConfig.NAGA_GRACE_MILLIS.get()) return;
        nagaPresent = false;
        SdkWorkerThread.enqueue(() -> effects.naga.setGone(now));
    }

    // ---------------------------------------------------------------
    // The Aether's Slider.
    //
    // Gated on the modid first, exactly like the Naga, and matched by registry
    // id for exactly the same reason. Position, health and whether it is dying
    // are all vanilla and all synced.
    //
    // Whether it is AWAKE is the one thing the Aether does not expose without
    // importing its classes, so that comes from vanilla's boss overlay
    // instead: the Slider's bar only exists on the client while it is awake,
    // and it is a bar that plays boss music. Reading the bar sidesteps the
    // import entirely. See SliderEffect for the rest of it.
    // ---------------------------------------------------------------
    private static void pollSlider(Minecraft mc, Player player, EffectRegistry effects, long now) {
        if (!Feature.SLIDER.isOn() || !ModCompatRegistry.isAetherLoaded()) return;
        if (!(player.level() instanceof ClientLevel clientLevel)) return;

        double maxDistance = RGBProfileConfig.SLIDER_DETECTION_RADIUS.get();
        double maxDistanceSq = maxDistance * maxDistance;

        // Tracked-entity walk rather than a box query, same as the Naga. Note
        // that dying Sliders are KEPT here, unlike in the Naga's loop, because
        // the death is the single most worthwhile moment to draw and a
        // living-only filter throws it away.
        LivingEntity slider = null;
        for (Entity entity : clientLevel.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living.isRemoved()) continue;
            Identifier loc = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
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
            boolean bossMusic = mc.gui.hud.getBossOverlay().shouldPlayMusic();
            SdkWorkerThread.enqueue(() -> effects.slider.setSlider(x, y, z, px, pz, health, bossMusic, now));
            return;
        }

        if (!sliderPresent) return;
        // Short grace, because the Slider never leaves its room. If you lost
        // sight of it, you are the one who moved. The grace is still there to
        // cover the entity briefly falling out of tracking at the edge of
        // range, which is a different thing from leaving.
        if (now - sliderLastSeenMillis < RGBProfileConfig.SLIDER_GRACE_MILLIS.get()) return;
        sliderPresent = false;
        sliderDeathReported = false;
        SdkWorkerThread.enqueue(() -> effects.slider.setGone(now));
    }

    // ---------------------------------------------------------------
    // The Aether's Sun Spirit.
    //
    // Same shape as the Slider's poll (modid gate, registry-id match, dying
    // spirits kept so the death gets drawn) plus the two things its room is
    // permanently full of.
    //
    // Crystals are entities, so they come along in the same tracked-entity
    // walk as the spirit itself and cost nothing extra. Fire is blocks, so it
    // has to be found by going and looking: every SUN_SPIRIT_FIRE_SCAN_TICKS,
    // a box around the spirit gets checked for it.
    // ---------------------------------------------------------------

    /**
     * Ticks between fire scans. Vanilla fire burns for seconds at a time and
     * the spirit only sets a new one every 35 ticks, so scanning twice a
     * second misses nothing a human being could perceive.
     */
    private static final int SUN_SPIRIT_FIRE_SCAN_TICKS = 10;
    /**
     * How far around the spirit to look for fire. It flies up to 9 blocks out
     * from the centre of a room 21 blocks across, which means a fire on the
     * far wall can sit nearly 20 blocks away from wherever it currently is.
     */
    private static final int SUN_SPIRIT_FIRE_SCAN_RADIUS = 20;
    /**
     * How many layers get scanned, counting down from the spirit's own block.
     * It sets fire at the first empty block with something solid underneath,
     * looking at most three blocks below itself, so there is no point looking
     * further than that.
     */
    private static final int SUN_SPIRIT_FIRE_SCAN_DEPTH = 4;
    /** Cap on fires handed over at once. A room with more than this alight is, for drawing purposes, simply on fire. */
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
            Identifier loc = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
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
            boolean bossMusic = mc.gui.hud.getBossOverlay().shouldPlayMusic();
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

    /** Fire blocks near the spirit, returned as {xs, zs} of their block centres. */
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
            Identifier loc = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
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
            boolean bossMusic = mc.gui.hud.getBossOverlay().shouldPlayMusic();
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
        Identifier loc = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
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
     * Gliding, hovering, lunging at you, coming in to land, perched and
     * breathing is six, and each one is readable without being taught.
     */
    private static EnderDragonEffect.Pose dragonPose(EnderDragon dragon) {
        EnderDragonPhase<?> phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
        if (phase == EnderDragonPhase.SITTING_FLAMING || phase == EnderDragonPhase.SITTING_ATTACKING) {
            return EnderDragonEffect.Pose.BREATHING;
        }
        // LANDING is the drop onto the portal, SITTING_SCANNING is sitting
        // there once it has arrived. Drawing both as PERCHED meant the dragon
        // was already folded up on the ground before it had come down.
        if (phase == EnderDragonPhase.LANDING) {
            return EnderDragonEffect.Pose.LANDING;
        }
        if (phase == EnderDragonPhase.SITTING_SCANNING) {
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
    // Read straight off the vanilla air supply, which means Respiration, water
    // breathing, turtle helmets and conduit power all get honoured for free.
    // Every one of those changes how fast the number falls, and this only ever
    // reports the number, so there is nothing here to keep in step with them.
    //
    // Air is an int ticking down from 300 once per tick, so the poll runs
    // every tick and the SMOOTHING lives in the pattern instead of in here
    // (see WaterRisePattern). Which keeps this function down to two reads and
    // a divide.
    // ---------------------------------------------------------------
    private static void pollDrowning(Player player, EffectRegistry effects) {
        if (!Feature.DROWNING.isOn()) return;
        int max = player.getMaxAirSupply();
        if (max <= 0) return;
        int air = player.getAirSupply();
        // Vanilla runs air down PAST zero, to -20, before it deals a point of
        // damage and resets the counter. So the clamp is not cosmetic tidying:
        // without it the waterline drops back down the board on every single
        // drowning tick, which is precisely the moment it ought to look its
        // worst.
        double submersion = 1.0 - Math.max(0, Math.min(max, air)) / (double) max;
        // Dead players do not drown, and the death state owns the whole board
        // by then regardless. See pollDeath.
        if (dead) submersion = 0;
        double level = submersion;
        SdkWorkerThread.enqueue(() -> effects.drowning.setSubmersion(level));
    }

    // ---------------------------------------------------------------
    // Burning — the board catches fire while you do.
    //
    // The drowning meter's sibling, with one difference forced on it from
    // outside.
    //
    // Air supply gets sent to the client. The fire timer does not.
    // Entity.baseTick wipes the client's copy every tick, and the only thing
    // actually synced is the "on fire" flag behind isOnFire(). So there is no
    // "time left burning" available to draw, and no amount of cleverness
    // conjures one up.
    //
    // The flame height therefore shows how BAD the burning is rather than how
    // long is left, built from things the client genuinely can see for itself:
    // Fire Resistance, standing in fire, and standing in lava.
    // ---------------------------------------------------------------

    /**
     * Flame heights, as a fraction of the board. Protected is low and calm
     * because the fire genuinely cannot hurt you, and lava is the entire board
     * because nothing else in the game does that much damage that quickly.
     */
    private static final double BURNING_PROTECTED_HEAT = 0.3;
    private static final double BURNING_HEAT = 0.5;
    private static final double BURNING_IN_FIRE_HEAT = 0.75;
    private static final double BURNING_IN_LAVA_HEAT = 1.0;

    private static void pollBurning(Player player, EffectRegistry effects) {
        if (!Feature.BURNING.isOn()) return;
        double heat = 0;
        // Dead players are not burning either, and again the death state owns
        // the board by then. See pollDeath.
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

    /** Whether any fire block overlaps the player. Fire or soul fire, i.e. the blocks that keep re-lighting you. */
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
        // The !dead check matters: zero health while dead does not mean "you
        // should probably heal", it means "you are dead", and those want very
        // different things on the keyboard. See pollDeath.
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
    // Biome Color — including the derived-colour fallback, which is computed
    // here on the client thread rather than on the worker, because it needs
    // registry and biome access that only exists over here.
    // ---------------------------------------------------------------
    private static void pollBiome(Player player, EffectRegistry effects, long now) {
        if (!Feature.BIOME_COLORS.isOn()) return;
        var biomeHolder = player.level().getBiome(player.blockPosition());
        Identifier biomeLoc = biomeHolder.unwrapKey()
                .map(key -> key.identifier())
                .orElse(null);
        if (biomeLoc == null) {
            // This is the path that used to return before recording ANY
            // sample at all, which made it completely invisible to diagnostics
            // at precisely the moment anybody needed to see it. A 130-second
            // window in the Aether showed zero samples while the board sat
            // holding the previous dimension's colour the entire time, looking
            // for all the world like an effect that had got stuck.
            //
            // A Holder that isn't registry-bound has no key to unwrap, so the
            // biome genuinely cannot be named. Logged on state CHANGE only,
            // because this fires on every single poll for as long as it lasts
            // and logging per poll would be its own kind of unreadable.
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

        // The debounce: only commit a CONFIRMED change. Stand on a biome
        // border and the game will happily report both of them several times a
        // second, and without this every one of those reports restarts the
        // Tier 1 crossfade (1400ms by default). The result is a keyboard having a small
        // breakdown while you stand still.
        //
        // Worth knowing: an earlier version defined DEBOUNCE_MILLIS in the
        // config, documented it thoroughly, and then never read it from
        // anywhere. See Diagnostics.dumpBiomeTiming, which now warns at
        // startup (with debug logging on) when the debounce is no longer than
        // the sample interval and is therefore incapable of filtering anything.
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

    /** Best-effort description of a Holder that refused to unwrap, purely for the log line. */
    private static String describeHolder(Object holder) {
        try {
            return holder == null ? "null" : holder.getClass().getSimpleName();
        } catch (Exception e) {
            return "(undescribable)";
        }
    }

    /**
     * Invents a colour for a biome nobody wrote a profile for: its grass colour
     * blended 2:1 with its water colour, grass winning because grass dominates
     * what you actually see.
     *
     * <p>Be clear about where "its grass colour" comes from, because it's less
     * than it sounds. This reads the biome's grass colour OVERRIDE, and almost
     * nothing sets one. In vanilla 1.21.1 only the badlands and the cherry
     * grove do; every other biome's grass comes from the temperature/downfall
     * colour map, which this never consults. So for most biomes the grass half
     * is the fixed {@code #6A8F3D} below and the water colour does all the
     * distinguishing. A modded biome that sets an override gets it used.
     *
     * <p>Every vanilla biome except {@code the_void} has a profile, so in
     * practice this runs for that and for modded biomes with no entry and no
     * mod-wide wildcard.
     *
     * <p>The catch block is for modded biomes whose special-effects data can't
     * be read at all: a temperature-only blend from a dull green to a warm
     * orange, which is worse, and which is always available.
     */
    private static RGBColor deriveBiomeColor(Biome biome) {
        try {
            var effects = biome.getSpecialEffects();
            int grass = effects.grassColorOverride().orElse(0x6A8F3D);
            int water = effects.waterColor();
            int r = ((((grass >> 16) & 0xFF) * 2) + ((water >> 16) & 0xFF)) / 3;
            int g = ((((grass >> 8) & 0xFF) * 2) + ((water >> 8) & 0xFF)) / 3;
            int b = (((grass & 0xFF) * 2) + (water & 0xFF)) / 3;
            return new RGBColor(r, g, b);
        } catch (Exception e) {
            // Temperature/downfall fallback: warmer and drier goes to warm
            // tones, colder and wetter goes to cool ones.
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
        // Read off the dimension's own data instead of a hardcoded list of
        // dimension ids, which is what makes this correct for modded
        // dimensions nobody here has ever heard of:
        //
        //   fixedTime present  - the sky does not move. The Nether (18000)
        //                        and the End (6000) both pin it, which is why
        //                        a sunset countdown in either is meaningless.
        //   no skylight        - there is no sky to put a moon in.
        //
        // Twilight Forest keeps a real day/night cycle AND a sky, so it still
        // gets one, which is the right answer: time of day genuinely matters
        // there. No per-mod code needed to work that out.
        var dimension = player.level().dimensionType();
        if (dimension.hasFixedTime() || !dimension.hasSkyLight()) {
            SdkWorkerThread.enqueue(() -> effects.nightIndicator.setNightProgress(false, 0));
            return;
        }
        // % 24000 because the clock counts total elapsed ticks since world
        // creation, not time-of-day. On an old world that's a very large
        // number and the indicator would be permanently pinned.
        //
        // The dimension's own clock, which is what 26.1 replaced the level's
        // day time with. Asking for the overworld's instead would report the
        // wrong time of day in any dimension that keeps its own — exactly the
        // modded dimensions the check above goes out of its way to include.
        long dayTime = player.level().getDefaultClockTime() % 24000;
        // Vanilla night is roughly [13000, 23000) of the 24000-tick day.
        boolean isNight = dayTime >= 13000 && dayTime < 23000;
        double progress = isNight ? (dayTime - 13000) / 10000.0 : 0;
        SdkWorkerThread.enqueue(() -> effects.nightIndicator.setNightProgress(isNight, progress));
    }

    // ---------------------------------------------------------------
    // Rain Cascade + Snowfall + Lightning fallback timer
    // ---------------------------------------------------------------
    private static void pollWeather(Player player, EffectRegistry effects, long now) {
        if (!Feature.RAIN_THUNDERSTORM.isOn()) return;

        // Snow first, so it can veto the rain. With requireSkyExposure off
        // the rain check is just the global weather flag and says yes
        // everywhere, including in the middle of a snowstorm.
        boolean snowExposed = isBeingSnowedOn(player);
        if (snowExposed) {
            snowShelteredSinceMillis = 0;
        } else if (snowShelteredSinceMillis == 0) {
            snowShelteredSinceMillis = now;
        }
        boolean snowing = snowExposed
                || (lastSnowing && now - snowShelteredSinceMillis < RGBProfileConfig.RAIN_SHELTER_GRACE_MILLIS.get());
        if (snowing != lastSnowing) {
            lastSnowing = snowing;
            SdkWorkerThread.enqueue(() -> effects.snowfall.setActive(snowing, now));
        }

        boolean exposed = !snowExposed && isBeingRainedOn(player);
        if (exposed) {
            rainShelteredSinceMillis = 0;
        } else if (rainShelteredSinceMillis == 0) {
            rainShelteredSinceMillis = now;
        }

        // On the instant you step into it, off only once the grace has fully
        // elapsed under cover. Asymmetric on purpose: see the shelterGraceMillis
        // config comment for why a symmetric version flickers under every tree
        // you walk past.
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
     * <p>{@code Level.isRaining()} is a GLOBAL weather flag. It is perfectly
     * true at Y=-50 underneath two hundred blocks of solid stone, which is
     * exactly why a thunderstorm overhead used to paint rain across a Deep
     * Dark keyboard.
     *
     * <p>{@code Level.isRainingAt(pos)} is the game's own per-position answer
     * and it gets every case right without help: it wants the global flag,
     * then {@code canSeeSky}, then a motion-blocking heightmap check, then
     * confirmation that the biome's precipitation at that spot is actually
     * RAIN. So a ravine counts as outdoors however deep it goes, because it is
     * open to the sky, while the cave branching off the side of it does not.
     *
     * <p>No depth heuristic of our own required, which is just as well,
     * because no depth heuristic was ever going to get that ravine right.
     *
     * <p>Both feet and head get tested, mirroring the private
     * {@code Entity.isInRain} that cannot be called from here. Somebody
     * standing in a one-deep puddle, or on a lower slab, still has their head
     * out in the weather.
     *
     * <p>The biome precipitation term means snowy biomes report SNOW rather
     * than RAIN, so this says no during a snowstorm. That's correct: snow gets
     * its own overlay, see {@link #isBeingSnowedOn}.
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
            // Heightmap or biome lookup landing on a chunk that is halfway
            // through unloading. Fall back to the global flag rather than
            // dropping the effect, because a moment of rain you did not
            // strictly earn is a much better outcome than an exception in the
            // tick loop.
            return true;
        }
    }

    /**
     * {@link #isBeingRainedOn}, but for snow.
     *
     * <p>The game has no {@code isSnowingAt}, so {@link #isSnowingAt} copies
     * the checks {@code Level.isRainingAt} makes (global flag, sky, heightmap,
     * biome precipitation) and asks for SNOW at the end instead of RAIN.
     * Feet and head both, same as the rain, for the same reasons.
     *
     * <p>The fallback on an exception is false here, where the rain's is
     * true. If both guessed yes, a chunk mid-unload would get snow and rain
     * on the same board at once. So snow gives up and the rain's fallback
     * covers the moment on its own.
     */
    private static boolean isBeingSnowedOn(Player player) {
        Level level = player.level();
        if (!level.isRaining()) return false;
        try {
            BlockPos feet = player.blockPosition();
            if (!RGBProfileConfig.RAIN_REQUIRE_SKY_EXPOSURE.get()) {
                // No shelter check wanted, but whether it's snow or rain
                // still depends on where you are standing.
                return level.getBiome(feet).value().getPrecipitationAt(feet, level.getSeaLevel()) == Biome.Precipitation.SNOW;
            }
            if (isSnowingAt(level, feet)) return true;
            return isSnowingAt(level, BlockPos.containing(
                    feet.getX(), player.getBoundingBox().maxY, feet.getZ()));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSnowingAt(Level level, BlockPos pos) {
        if (!level.canSeeSky(pos)) return false;
        if (level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) return false;
        return level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel()) == Biome.Precipitation.SNOW;
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

    // Timer-based, and yes, that is a compromise. Saying so here rather than
    // letting somebody discover it by wondering why the flash never lines up
    // with the thunder they just heard.
    //
    // The better signal is the client-observed LightningBolt entity spawn,
    // since bolts already render and play sound for nearby players, rather
    // than the fixed fallback timer above. EntityJoinLevelEvent filtered
    // to EntityType.LIGHTNING_BOLT within lightningDetectionRadius of the
    // player is the natural hook for it.
    //
    // Not wired up yet because it needs confirming in a running client that
    // the event actually fires client-side once per bolt rather than only on
    // the server, and guessing at that is how you ship a flash that either
    // never fires or fires twice. Sketch:
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
                // The omen never tells you its full length, so the longest
                // time-left seen this countdown is taken as where it must have
                // started from. Works because it only counts down.
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
        for (LerpingBossEvent bar : mc.gui.hud.getBossOverlay().events.values()) {
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
     * <p>Caught as the client is about to play it, which happens before the
     * sound volume gets looked at, so the board still lights up for somebody
     * playing with the game muted. And it reads the ORIGINAL sound rather than
     * the possibly-replaced one, so another mod swapping or silencing the horn
     * does not quietly take this with it.
     */
    @SubscribeEvent
    public static void onRaidHorn(PlaySoundEvent event) {
        if (event.getOriginalSound() == null) return;
        if (!SoundEvents.RAID_HORN.value().location().equals(event.getOriginalSound().getIdentifier())) return;
        if (!Feature.RAID_WARNING.isOn()) return;
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null) return;
        long now = System.currentTimeMillis();
        SdkWorkerThread.enqueue(() -> effects.raid.horn(now));
    }

    // ---------------------------------------------------------------
    // Boss Encounter — proximity, not boss bars.
    //
    // This used to be a deliberately empty stub waiting on a boss-bar hook,
    // on the bet that hanging boss lighting off a vanilla-level UI feature
    // would get most boss mods working for free.
    //
    // The bet does not pay. Plenty of modded bosses are bosses in the way the
    // WARDEN is a boss: an enormous health pool, an encounter you build your
    // whole evening around, and no bar above the screen whatsoever. Several
    // Legendary Monsters mobs are exactly that shape, and no quantity of
    // boss-bar plumbing was ever going to see a single one of them.
    //
    // So detection works the way the Warden's already did: go and look for the
    // thing itself. Two tests decide what counts, and both are somebody
    // saying so out loud:
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
    //     wrote it down. A boss that isn't tagged gets added here by its id.
    //
    // A wildcard like legendary_monsters:* is NOT a third test. It only colours
    // bosses that already passed one of the two above.
    //
    // There used to be a third test: anything with 150+ max health counted.
    // It kept promoting big mobs that nobody would call a boss, and it was the
    // one detection in the mod with no vanilla cue behind it, since a large
    // mob behind a wall has no bar and no sound telling you it's there. The
    // keyboard shouldn't know things your screen doesn't. So it's gone, and
    // detection is now declarations only.
    //
    // Legendary Monsters is worth calling out specifically, because it is the
    // case that disproved the boss-bar approach twice over in one mod. Its
    // bosses are tagged, AND it draws its own CustomBossBar instead of a
    // vanilla one, so a vanilla boss-bar hook would have missed all three of
    // them no matter how carefully it had been written.
    //
    // Health percentage comes straight off the entity, which is strictly
    // better than a boss bar ever was at any point: it is the real number
    // rather than a rendered approximation of it, it needs no packet
    // interception, and it feeds the existing enrage machinery unchanged.
    // ---------------------------------------------------------------
    private static void pollBossEncounter(Player player, EffectRegistry effects, long now) {
        if (!Feature.BOSS_ENCOUNTER.isOn()) return;

        double radius = RGBProfileConfig.BOSS_DETECTION_RADIUS.get();
        boolean fallbackEnabled = RGBProfileConfig.BOSS_FALLBACK_TO_BOSSBAR_COLOR.get();
        parseBossExclusions();

        boolean useTag = RGBProfileConfig.BOSS_USE_BOSSES_TAG.get();

        LivingEntity best = null;
        String bestId = null;
        int bestRank = -1;
        for (LivingEntity entity : player.level().getEntitiesOfClass(
                LivingEntity.class, player.getBoundingBox().inflate(radius))) {
            if (entity instanceof Player || !entity.isAlive()) continue;
            Identifier loc = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (loc == null) continue;
            String id = loc.toString();
            // Checked before the tag AND before the profile lookup, because an
            // exclusion is somebody deliberately overriding both of those, and
            // an override that loses is not an override.
            if (isBossExcluded(loc, id)) continue;

            // Through the registry holder, since 26.1 dropped EntityType's own
            // tag test. Same question, one hop further.
            boolean tagged = useTag && entity.getType().builtInRegistryHolder().is(BOSSES_TAG);
            boolean named = effects.bossProfiles.containsKey(id);
            // Somebody said it's a boss, or it isn't one. No third option.
            if (!tagged && !named) continue;
            // Rank first, health second. A tagged boss outranks a merely
            // named one, so a boss standing next to a beefier add still owns
            // the board. Sorting on health alone would hand the keyboard to
            // the minion.
            int rank = tagged ? 2 : 1;
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

        // Out of range. The grace period is what stops a dragon circling out
        // and back, or a boss ducking behind a wall, from restarting the whole
        // encounter. That matters because startBoss resets the pulse clock, so
        // a needless restart is something you can actually see happen.
        if (currentBossId == null) return;
        if (now - lastBossSeenMillis < RGBProfileConfig.BOSS_GRACE_MILLIS.get()) return;
        currentBossId = null;
        SdkWorkerThread.enqueue(effects.bossEncounter::endBoss);
    }

    /**
     * Parses the exclusion list, only when it has actually changed.
     *
     * <p>Splits into exact ids and whole-namespace wildcards, because a
     * per-mob list does not survive first contact with something like Mutant
     * Monsters: fourteen entities today, any of which could grow a boss-sized
     * health pool in the next update and quietly start stealing the board.
     *
     * <p>{@code mutantmonsters:*} states the thing that is actually true, that
     * none of that mod's mobs are bosses, and carries on being true after
     * somebody else's update.
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
                // Same wildcard shape ProfileResolver already uses, so both
                // files read identically to whoever is editing them at
                // midnight.
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
    private static boolean isBossExcluded(Identifier loc, String id) {
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
    // Polling rather than join/leave events, and that is a deliberate trade.
    //
    // An AABB entity query is trivially correct: ask the question, get the
    // answer, done. Join/leave events instead have to correctly handle a
    // Warden that despawns, dies, gets unloaded along with its chunk, or that
    // you simply walk away from. That is four separate ways to leak a stuck
    // "warden present" state that never clears.
    //
    // Events would probably give slightly cleaner timing. They would also give
    // four more edge cases to get wrong. This runs on the slow cadence anyway,
    // so the whole thing costs one bounding-box query per second.
    // ---------------------------------------------------------------
    private static void pollWarden(Player player, EffectRegistry effects, long now) {
        if (!Feature.WARDEN_ENCOUNTER.isOn()) return;
        double radius = RGBProfileConfig.WARDEN_DETECTION_RADIUS.get();
        List<Warden> nearby = player.level().getEntitiesOfClass(
                Warden.class, player.getBoundingBox().inflate(radius));
        boolean present = !nearby.isEmpty();

        // The emergence flash, fired per WARDEN rather than per
        // range-transition.
        //
        // It used to fire on "a Warden became present", which really means
        // "the moment one wandered inside 24 blocks" — frequently a Warden
        // that had dug itself out somewhere else entirely a minute earlier.
        // Pose.EMERGING is the real article: the animation it plays while
        // hauling itself up out of the floor, which is the moment genuinely
        // worth interrupting the board for.
        //
        // It still flashes for one that merely walks up to you, because being
        // ambushed by an already-spawned Warden earns a warning every bit as
        // much. Either way it is once per Warden per encounter, deduped on
        // entity id.
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

        // Closes the open-ended hold the instant nothing in range is still
        // climbing out of the floor. Runs on the one-second ambient cadence,
        // so worst case it is one second late against a 6.7-second animation.
        //
        // And the effect honours its own minimum hold and hard cap regardless,
        // which means a release that never arrives costs you a long dissolve
        // rather than a board stuck on forever.
        if (!anyEmerging) {
            SdkWorkerThread.enqueue(() -> effects.wardenEmergenceFlash.release(now));
        }

        // Forget Wardens that have left, so that meeting one again later
        // flashes again rather than staying silent because of something that
        // happened an hour ago. Without this the set is also a slow memory
        // leak across a long session.
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
    // Tough As Nails soft-compat, which is entirely dormant when TAN is not
    // installed (see ModCompatRegistry) AND, right now, entirely dormant when
    // it IS installed, because the integration is still a placeholder. See
    // ToughAsNailsCompat for what finishing it involves.
    // ---------------------------------------------------------------
    private static void pollToughAsNails(Player player, EffectRegistry effects, long now) {
        if (!Feature.TOUGH_AS_NAILS.isOn() || !ModCompatRegistry.isToughAsNailsLoaded()) return;

        ToughAsNailsCompat.Reading reading = ToughAsNailsCompat.read(player);
        // null means the API lookup failed even though the mod IS installed,
        // which at the moment means always, because the Tough As Nails
        // integration is a documented placeholder that has never returned a
        // reading in its life. Stay silent, do not crash, do not spam the log.
        // ToughAsNailsCompat's class doc explains exactly what is missing.
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
            // Read from the config, with the palette entry only as the
            // fallback. An earlier version used ColorPalette directly here,
            // which meant overheatingColor and freezingColor sat in the config
            // file doing absolutely nothing while thirstColor right above them
            // worked fine. Nobody noticed because the Tough As Nails reading
            // is still a placeholder that never returns anything, so this
            // branch has never actually run.
            RGBColor color = overheating
                    ? RGBColor.fromHexOrDefault(RGBProfileConfig.OVERHEATING_COLOR.get(), ColorPalette.OVERHEATING_ORANGE)
                    : RGBColor.fromHexOrDefault(RGBProfileConfig.FREEZING_COLOR.get(), ColorPalette.FREEZING_ICE);
            boolean active = overheating || freezing;
            SdkWorkerThread.enqueue(() -> {
                effects.temperatureWarning.setColor(color);
                effects.temperatureWarning.setActive(active, now);
            });
        }
        // Seasonal tinting of the biome colour is not implemented, and saying
        // so here rather than leaving a gap somebody has to go and prove is
        // empty. It would be a post-processing step on BiomeColorEffect's
        // resolved colour, and that class needs to grow a tint hook before any
        // of it can be written.
    }

    // ---------------------------------------------------------------
    // Death — polled, because the obvious event does not fire here. At all.
    // Ever.
    //
    // This used to hang off LivingDeathEvent, and that handler could never
    // once have run in any configuration. Vanilla's client-side death path is
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
    // Health reaching zero is synced to the client and is the same thing the
    // death screen itself keys on, so polling it is simultaneously simpler
    // than the event and correct on every side.
    // ---------------------------------------------------------------
    private static void pollDeath(Player player, EffectRegistry effects, long now) {
        boolean isDead = player.isDeadOrDying();
        if (isDead == dead) return;
        dead = isDead;

        // Respawn. Drop the blood and let the world come back. The survival
        // polls re-establish their own state from the new player on their very
        // next pass, so there is genuinely nothing else to do here.
        if (!isDead) {
            SdkWorkerThread.enqueue(() -> effects.deathState.setActive(false, now));
            return;
        }

        // Clear the survival warnings in the same breath as firing the flash.
        //
        // This is the other half of that bug. A dead player sits at 0% health,
        // 0% food and 0% thirst, so every warning keyed on "below threshold"
        // is simultaneously technically correct and completely useless. The H
        // key pulsed red at somebody who was already dead and could not act on
        // the information.
        //
        // Worse, the flash lasts 800ms and the warnings are sustained, so the
        // instant the flash expired the red H came straight back and read as
        // having overwritten it.
        //
        // Cleared right here instead of waiting for the ambient polls, because
        // those run on the slow cadence and would leave the H key flashing for
        // up to a second after the death flash had already finished.
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
            // Raised in the SAME job as the flash, so the blood is already
            // sitting underneath by the time the flash dissolves off it.
            // Ordered blood-first for the same reason: the flash blanks the
            // board while it draws, so what is beneath only starts mattering
            // at the exact instant it ends.
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
     * How many ticks a freshly created player object gets watched without
     * anybody celebrating anything.
     *
     * <p>The client builds a brand new player on respawn and on every single
     * dimension change, starting it at level 0, and the server's experience
     * packet turns up a moment afterwards. That is a rise from 0 to your real
     * level, and it is emphatically not a level-up. Without this window, every
     * nether portal would congratulate you thirty times.
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
     * ever runs where levels are actually granted, i.e. on the server.
     *
     * <p>So on a multiplayer server the client is sent its new level in a
     * packet that sets the number without posting anything, and the level-up
     * never played at all. In single player it did play, but on the integrated
     * server, and for ANY player on it — meaning a LAN guest levelling up lit
     * up the host's keyboard, which is a wonderfully confusing thing to
     * witness.
     *
     * <p>The level itself is synced to the client in every configuration, so
     * the rise gets read off that instead and all of it goes away.
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
        // Only actual level INCREASES. Without this check, dying drops your
        // level and therefore sets off a celebratory gold flash, which is a
        // memorably wrong emotional note to hit at that particular moment.
        if (gained <= 0 || dead || !Feature.LEVEL_UP.isOn()) return;
        playLevelUp(effects, now);
    }

    private static void playLevelUp(EffectRegistry effects, long now) {
        RGBColor gold = RGBColor.fromHexOrDefault(RGBProfileConfig.LEVEL_UP_COLOR.get(), ColorPalette.LEVEL_UP_GOLD);
        RGBColor bar = RGBColor.fromHexOrDefault(RGBProfileConfig.LEVEL_UP_BAR_COLOR.get(), ColorPalette.LEVEL_UP_XP_GREEN);
        // An animation rather than a flash: the bar fills up and then bursts.
        // See LevelUpPattern.
        SdkWorkerThread.enqueue(() -> effects.levelUpFlash.trigger(now, gold, bar,
                LevelUpPattern.DURATION_MILLIS, null, LEVEL_UP_PATTERN, null));
    }

    /**
     * Flashes when an advancement you earned puts its toast on screen.
     *
     * <p>An earlier version listened for
     * {@code AdvancementEvent.AdvancementEarnEvent}, which carries precisely
     * the same flaw the old level-up hook did: it is posted where advancements
     * are awarded, which is the server.
     *
     * <p>On a multiplayer server the client never sees it, so the flash simply
     * never played. In single player it played on the integrated server for
     * any player, and for EVERY advancement, including the invisible ones
     * sitting behind each individual recipe unlock. Which is a lot of flashing
     * for having picked up some oak logs.
     *
     * <p>The client does get told, though. The server sends an advancements
     * update, and {@code ClientAdvancements.update} works out from it which
     * ones were genuinely just completed (as opposed to the pile replayed at
     * you when you join) and queues an {@code AdvancementToast} for each one
     * meant to be announced.
     *
     * <p>So a toast being queued is exactly "you just earned something worth
     * announcing", as decided by vanilla itself rather than by us, and
     * NeoForge hands it over right here.
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
    // An earlier version subscribed to EntityTravelToDimensionEvent and never
    // fired once, in any game mode, for two completely independent reasons
    // either of which would have been enough on its own:
    //
    //   1. That event is dispatched on the LOGICAL SERVER. This handler class
    //      is @EventBusSubscriber(value = Dist.CLIENT), so on a multiplayer
    //      server it is never delivered here at all.
    //   2. Even in singleplayer, where the integrated server does dispatch it,
    //      event.getEntity() is a ServerPlayer. So the guard
    //      `Minecraft.getInstance().player != player` failed every single
    //      time and returned early.
    //
    // It is also the wrong shape regardless: the event fires BEFORE the
    // transfer, and modded teleporters that move a player directly often
    // bypass it entirely -- which would have broken the whole "works
    // automatically for any modded dimension" premise.
    //
    // Watching the client's own level is dispatch-agnostic: it reports the
    // dimension the player is ACTUALLY in, regardless of how they got there,
    // which is why the Aether, Twilight Forest, the Undergarden and every
    // dimension mod that has not been written yet all work with zero per-mod
    // code.
    /**
     * Where the dimension change gets noticed, and why that happens all the
     * way down here at the level rather than somewhere that looks sensible.
     *
     * <h2>The short version</h2>
     * Listening at the portal or at the player puts you at the back of a very
     * long queue. The shader and rendering mods (Iris, Sodium, Veil, Flywheel)
     * mixin into exactly that path and rewrite what happens during a dimension
     * change, and every bit of their work runs <i>before</i> the portal- and
     * player-facing events reach anybody else.
     *
     * <p>So rather than queue up behind all of that, this goes one step deeper
     * into the class hierarchy and listens at the {@code Level} itself, which
     * is constructed before any of that work has started.
     *
     * <h2>The long version, with receipts</h2>
     * The obvious hook is {@code ClientPlayerNetworkEvent.Clone}: the player
     * object gets swapped when you go through a portal, so listen for the
     * swap. That is exactly what this used to do, and it was late every single
     * time.
     *
     * <p>Measured against Iris's own {@code Reloading pipeline on dimension
     * change} log line, across four consecutive portal trips in a 600-mod
     * pack, the gap came out at 2696ms, 973ms, 879ms and 883ms:
     *
     * <pre>
     *   06:59:15.809  Iris   overworld => the_nether
     *   06:59:18.505  Clone  (2696ms later)
     * </pre>
     *
     * <p>Nearly three seconds at the worst of it, and that spread is not
     * jitter. It is ordering. {@code ClientPacketListener#handleRespawn} swaps
     * the ClientLevel and calls {@code Minecraft#setLevel} <b>first</b>, then
     * only dispatches Clone right at the very end.
     *
     * <p>Everything else in the pack has mixined into that middle section:
     * Iris tearing down and rebuilding its entire shader pipeline, Veil
     * recompiling, and Sodium, Flywheel and colorwheel each restarting the
     * ChunkBuilder, which shows up as three separate restarts in the log.
     *
     * <p>On a clean install that whole block takes microseconds and nobody
     * would ever have noticed. On a heavy shader pack it takes one to three
     * seconds, and our listener spends all of it sitting politely at the back
     * of the queue.
     *
     * <p>The tick poll cannot rescue this either, because no client tick gets
     * dispatched during that stall. Same queue, same problem, different
     * doorway.
     *
     * <h2>What that actually looked like</h2>
     * The arrival animation fired <i>after</i> the player had already finished
     * loading in. So you would walk through a portal, watch the terrain
     * appear, and only then get a brief flicker of portal effect that
     * immediately ended, because most of its runtime had been burned behind a
     * frozen client.
     *
     * <p>It read as the effect being broken. It was not broken. It was late,
     * which is a completely different bug to go looking for, and this is
     * precisely the ambiguity Diagnostics exists to settle.
     *
     * <p>{@code LevelEvent.Load} fires when the new ClientLevel is
     * constructed, which is upstream of that entire pile, so we now land at or
     * before Iris. Going deeper in the class hierarchy was the whole fix: the
     * level is the thing that actually changed, and it is the one place nobody
     * else has queued up in front of us.
     *
     * <h2>Belt and braces</h2>
     * {@link #onClientPlayerClone} and {@link #pollDimension} are both still
     * wired up, for any transition that does not construct a level at all. All
     * three share {@code lastDimensionId}, so whichever one spots it first
     * wins and the other two quietly no-op.
     *
     * <p>The debug line names which one fired, so if this ever starts trailing
     * again you will know immediately rather than after an evening. The
     * remaining option at that point is a mixin at HEAD of
     * {@code Minecraft#setLevel}, which is exactly where Iris hooks, and at
     * that point we would be having the same argument from the other side.
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        EffectRegistry effects = SdkWorkerThread.effects();
        if (effects == null) return;
        if (!(event.getLevel() instanceof Level level)) return;
        if (!level.isClientSide()) return; // the integrated server posts this too, and it is not ours
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
            Identifier loc = level.dimension().identifier();
            return loc == null ? null : loc.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void pollDimension(Minecraft mc, EffectRegistry effects, long now) {
        if (!Feature.PORTAL_TRANSITION.isOn()) return;
        if (mc.level == null) return;

        Identifier loc = mc.level.dimension().identifier();
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
        // The arrival is the destination's effect, so it follows the
        // destination's mod-path switch. Turn the Aether off and arriving in
        // it lights nothing at all -- not the derived fallback either, which
        // is the point: the switch is meant to make the dimension look like
        // one this mod has no support for, and a derived spiral is support.
        //
        // The dwell that was running on the way in belongs to the dimension
        // being LEFT and is not this switch's business, so it is stopped here
        // the same way a played arrival would stop it, rather than left
        // spinning over a transfer that is already finished.
        Feature path = Feature.dimensionPath(dimensionId);
        if (path != null && !path.isOn()) {
            if (portalDebug()) {
                RGBProfileMod.LOGGER.info("[portal] arrival in {} skipped: its {} support is switched off",
                        dimensionId, path.name().toLowerCase(java.util.Locale.ROOT));
            }
            awaitingWorldReady = false;
            SdkWorkerThread.enqueue(() -> effects.portalCharge.setActive(false, now));
            return;
        }

        boolean fallbackEnabled = RGBProfileConfig.PORTAL_FALLBACK_TO_DERIVED_COLOR.get();
        long duration = RGBProfileConfig.PORTAL_DURATION_MILLIS.get();
        double holdFraction = RGBProfileConfig.PORTAL_HOLD_FRACTION.get();
        int suppressionFloor = RGBProfileConfig.PORTAL_SUPPRESSION_FLOOR.get();

        // durationMillis/holdFraction now describe the MINIMUM hold and the
        // fade length. The hold above that minimum is open-ended and closed by
        // pollPortalWorldReady when the terrain screen clears — see
        // PortalTransitionEffect's "Rewrite 2" section.
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
            // ends up with a proper pillar-style field instead of a flat
            // two-tone fade.
            ColorRamp ramp = profile != null ? profile.resolvedRamp(base, accent)
                    : ColorRamp.derived(base, accent);
            CoreEmitterPattern.Settings settings = profile != null
                    ? profile.toSettings() : new CoreEmitterPattern.Settings();

            // Read the dwell's clock BEFORE stopping it, so the arrival picks
            // the spiral up mid-rotation rather than snapping back to phase
            // zero at the exact moment somebody is staring directly at it.
            //
            // arrivalFlashStyle is deliberately not consulted any more,
            // because there is no arrival flash to style. It turned out the
            // flash was never the good part.
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
     * <h2>Why {@code ReceivingLevelScreen} specifically</h2>
     * The arrival fires as early as the dimension change can possibly be
     * observed, and then the player cannot see anything at all for the next
     * one to several seconds while the client rebuilds chunks and shaders.
     *
     * <p>Anchoring the fade to a stopwatch started at the transfer meant the
     * whole dissolve happened inside that stall. By the time "Downloading
     * terrain" cleared, the board was already back to the biome layer, so the
     * player looked up from a finished keyboard as they landed. Which reads as
     * the portal effect being MISSING rather than as it being early, and those
     * send you looking in very different places.
     *
     * <p>{@code ReceivingLevelScreen} is the screen showing that message. The
     * game closes it when {@code LevelLoadStatusManager#levelReady} goes true,
     * which means exactly "the chunk you are standing in is loaded and
     * rendered". That is the game's own answer to the precise question this
     * needs answered, with no chunk-status guesswork of our own involved.
     *
     * <p>It is opened synchronously inside {@code handleRespawn}, in the same
     * block that swaps the level, so no client tick can sneak in between the
     * change being observed and the screen going up. Which means this cannot
     * accidentally latch onto the pre-transfer state and release immediately.
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
     * screen that never closes, or a mod that swapped it out for one of its
     * own, costs you a long arrival rather than a permanently stuck board.
     */
    private static void pollPortalWorldReady(Minecraft mc, EffectRegistry effects, long now) {
        if (!awaitingWorldReady) return;
        // LevelLoadingScreen, which since 1.21.9 is what covers a dimension
        // transfer as well: the ReceivingLevelScreen this used to test for is
        // gone, and the one screen now reports why it is up. Any of its reasons
        // means terrain is still coming, which is exactly what this waits for.
        if (mc.gui.screen() instanceof LevelLoadingScreen) return;

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
    // transfer completes, and then hands off to the arrival spiral.
    // Config-gated behind portalTransition.chargeUpEnabled, so anybody who
    // preferred the old arrival-only behaviour can have it back.
    //
    // Detection goes by block registry PATH rather than a hardcoded block
    // list, which keeps the "generic first, specific second" property that
    // runs through this whole mod. minecraft's nether_portal, end_portal and
    // end_gateway, twilightforest:twilight_portal, aether:aether_portal and
    // anything else anybody ever names ...portal or ...gateway all match, with
    // zero per-mod code.
    //
    // Dimensions that have no portal block at all — the Bumblezone, which you
    // enter by throwing an ender pearl at a beehive — get caught by the screen they raise
    // when the transfer starts instead. See isDimensionTransitionScreenOpen.
    private static void pollPortalCharge(Minecraft mc, Player player, EffectRegistry effects, long now) {
        if (!Feature.PORTAL_CHARGE_UP.isOn()) return;
        if (mc.level == null) return;

        // The dwell is coloured by the dimension you are LEAVING, so it
        // follows THAT dimension's mod-path switch rather than the
        // destination's -- which is the only one of the two we can know while
        // you are still standing in the portal. With the Aether switched off,
        // the portal home from the Aether lights nothing; the Overworld portal
        // you walked into to get there is vanilla and still lights normally.
        //
        // Folded into inPortal rather than returned on, so that a dwell
        // already running when the switch is flipped is stopped by the
        // ordinary false branch below instead of being stranded at full.
        boolean pathAllowsDwell = Feature.dimensionPathIsOn(lastDimensionId);

        boolean inPortal;
        try {
            inPortal = pathAllowsDwell
                    && (isIntersectingPortal(mc, player) || isDimensionTransitionScreenOpen(mc));
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
            // You are standing INSIDE the destination portal the instant you
            // arrive, which is the obvious thing in hindsight and was not
            // obvious at all at the time. Re-arming the dwell there restarts
            // the field at the entry floor on top of an arrival that is still
            // fading out, and the result reads as a stutter. Let the arrival
            // finish first.
            if (effects.portalTransitionFlash.isActive(now)) return;
            // Colour the charge-up with the profile of the dimension being
            // LEFT, so the board reads as the current world dissolving away
            // and the arrival spiral gets to introduce the destination's
            // colour on the other side. Two halves of one journey rather than
            // the same colour twice.
            DimensionProfile profile = dimensionId == null ? null
                    : ProfileResolver.resolve(effects.dimensionProfiles, dimensionId,
                            RGBProfileConfig.PORTAL_FALLBACK_TO_DERIVED_COLOR.get(),
                            () -> derivedDimensionProfile(dimensionId));
            RGBColor color = profile != null
                    ? profile.resolvedColor(RGBColor.deterministicFromKey(dimensionId))
                    : RGBColor.fromHex("#8B008B");
            RGBColor accent = profile != null ? profile.resolvedAccentColor(color) : color.lightened(0.6);

            // The same fitted pillar geometry and palette the arrival uses, so
            // that the dwell and the arrival read as one continuous effect
            // instead of a blink followed by an unrelated spiral.
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
     * block's own {@code entityInside}, and that requires owning the block.
     * This mod is a drop-in with no hard dependencies and owns neither
     * vanilla's blocks nor anybody else's, so the processor field is the
     * honest ceiling here rather than a lack of trying.
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
     * is entered by throwing an ender pearl at a beehive (or using one of a
     * few tagged items on it), so there is no portal block to
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
     * {@link #isPortalBlock} accepts anything named "portal" or "gateway",
     * because a false positive there costs you a portal effect next to a block
     * that is already portal-shaped, and you walk out of range a second later.
     * Cheap mistake, self-correcting.
     *
     * <p>A screen is a completely different proposition. It can sit open
     * indefinitely, and the dwell field holds at FULL for as long as it is up.
     * So this requires <b>both</b> "dimension" and "teleport" in the class
     * name rather than either one: enough to catch Bumblezone's
     * {@code DimensionTeleportingScreen}, and nowhere near enough to catch the
     * various mods whose teleport menus you might be browsing through at your
     * leisure while your keyboard quietly screams.
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
        net.minecraft.client.gui.screens.Screen screen = mc.gui.screen();
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
     * <p>The matching itself is, and there is no polite way to put this,
     * vibes-based: does the registry path contain "portal" or "gateway"? Cool.
     * That is the check. That is the whole check.
     *
     * <p>It is a heuristic and it knows it is a heuristic. It also catches
     * nether_portal, end_portal, end_gateway, twilight_portal, aether_portal
     * and essentially every modded portal that has ever been named, with zero
     * per-mod code. The false-positive cost is the board lighting up near
     * something merely CALLED a portal, which in a 600-mod pack is not even
     * close to the top fifty problems.
     */
    private static final java.util.Map<Object, Boolean> PORTAL_BLOCK_CACHE =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static boolean isPortalBlock(Minecraft mc, BlockPos pos) {
        Object block = mc.level.getBlockState(pos).getBlock();
        Boolean cached = PORTAL_BLOCK_CACHE.get(block);
        if (cached != null) return cached;
        Identifier id = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock());
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
    // Polled in both directions rather than hung off events. Waking up is the
    // awkward half: there is no single client event that covers every way out
    // of a bed (morning arriving, the night being skipped, or you simply
    // standing up again), so this compares isSleeping() between polls, on the
    // once-a-second ambient cadence, which catches all three without caring
    // which one it was. Falling asleep goes through the same comparison for
    // symmetry. Worth revisiting if NeoForge exposes a dedicated client-side
    // wake event that's cleaner than polling.
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
     * cannot miss a sensor activation (30 ticks, or 10 for a calibrated
     * sensor) and costs a palette check per chunk section in range.
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
