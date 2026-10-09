import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.effects.support.BossEncounterEffect;
import com.everythingrgbprofile.effects.support.ElderGuardianEffect;
import com.everythingrgbprofile.effects.support.EnderDragonEffect;
import com.everythingrgbprofile.effects.support.NagaEffect;
import com.everythingrgbprofile.effects.support.SliderEffect;
import com.everythingrgbprofile.effects.support.SunSpiritEffect;
import com.everythingrgbprofile.effects.support.ValkyrieQueenEffect;
import com.everythingrgbprofile.effects.support.WardenEmergenceEffect;
import com.everythingrgbprofile.effects.support.WitherEffect;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.patterns.ValkyrieQueenPattern;
import com.everythingrgbprofile.priority.EffectController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The boss layers, as things {@code ColorTuner} can select, colour and export.
 *
 * <p>The biome and portal entries in the tuner are {@link Pattern}s: hand one a
 * colour and an elapsed time and it draws. The boss layers are not. They are
 * {@link EffectController}s — state machines that have to be <i>told</i> what
 * the boss is doing, because in game that is what the event layer does every
 * tick. A Wither that is never told its health renders its opening frame
 * forever, and the powered colour that only appears below half health cannot be
 * tuned at all, which is the one thing this tool exists to do.
 *
 * <p>So each theme here is a controller plus a <b>script</b>: a function from
 * one number, {@code progress}, to whatever calls that boss needs. 0 is the
 * start of the encounter and 1 is the end of it, and the tuner puts that number
 * on a slider. Drag it and the board walks through the whole fight — summon,
 * phases, enrage, death — with every colour appearing at the point it really
 * appears.
 *
 * <h2>The scripts are representative, not recorded</h2>
 * A script makes the same calls the event layer makes, in the same order, with
 * values in the same ranges: a Wither's summon really does count 220 ticks
 * down, the Naga's charge speed really is several times its prowl, the armour
 * really does go on at half health. What a script cannot claim is timing
 * fidelity — a real dragon holds a pose for as long as its own goals say, not
 * the 2.6 seconds used here. This is a colour tool, and for colour what matters
 * is that every state gets reached and then holds still long enough to look at.
 *
 * <h2>Colours are read live, never baked in</h2>
 * Every script re-applies its colours on every frame, from {@link Colors},
 * which reads the tuner's sliders as they are right now. The alternative —
 * building the controller with the colours it was selected with — would mean
 * rebuilding it whenever a slider moved, and these controllers carry animation
 * state: the Naga's body position, the dragon's ritual, the pulse clock. Every
 * nudge of a slider would restart the animation from the top.
 *
 * <h2>Where the colours come from</h2>
 * Boss colours live in the mod's TOML config, not in the JSON profile files —
 * except the generic boss-bar layer, whose colours come from
 * {@code boss_profiles.json} like a biome's. The TOML is read where it can be
 * found; the fallbacks are the shipped defaults out of {@link ColorPalette},
 * which are the same constants the mod falls back to when a config value will
 * not parse.
 */
final class BossThemes {

    private BossThemes() {
    }

    /**
     * One tunable colour on a boss theme.
     *
     * @param label     what to call it on the panel — "bone", "eyes",
     *                  "powered" rather than "base" and "accent", because a
     *                  Wither does not have a base and an accent
     * @param configKey the TOML key it is read from and written back to, or the
     *                  JSON key for the boss-bar layer
     * @param shipped   the mod's own fallback for that key, or null where the
     *                  value comes from a profile file instead
     */
    record Slot(String label, String configKey, RGBColor shipped) {
    }

    /** The current colour of each slot, read fresh every frame. */
    interface Colors {
        RGBColor get(int slot);
    }

    /** Where the script's progress comes from: a slider, or a sweep across a clip. */
    interface ProgressSource {
        double at(long elapsedMillis);
    }

    /** Builds the controller. Called once per pattern instance, not per frame. */
    interface Builder {
        EffectController build(Colors colors);
    }

    /**
     * Feeds the controller one frame's worth of calls, colours included.
     *
     * @param progress 0 at the start of the encounter, 1 at the end
     * @param memo     scratch space belonging to this one controller
     */
    interface Driver {
        void drive(EffectController controller, Memo memo, double progress, long elapsedMillis);
    }

    /** The status line for a point in the encounter. */
    interface Describer {
        String describe(double progress);
    }

    /**
     * Per-controller scratch: the live colours, and a counter for the calls
     * that are events rather than states.
     *
     * <p>{@code setFight} can be called every frame with no harm — it sets a
     * field. {@code fireSkull} and {@code pulseSurge} cannot: each one starts
     * an animation, and calling them sixty times a second gives sixty
     * overlapping skulls and a surge that never decays. The scripts work out
     * which numbered event the current instant belongs to and use
     * {@link #count} to fire only on the change.
     */
    static final class Memo {
        final Colors colors;
        int count = -1;

        Memo(Colors colors) {
            this.colors = colors;
        }
    }

    /**
     * A boss layer, its colours, and how to walk it through an encounter.
     *
     * @param section the TOML table its colours live in, or null for the
     *                generic boss-bar layer, whose colours come from
     *                {@code boss_profiles.json} instead
     */
    record Theme(String id, String section, List<Slot> slots,
                 Builder builder, Driver driver, Describer describer, List<Phase> phases,
                 List<Stage> ownStages) {

        /** A theme whose stages are just its phases, which is most of them. */
        Theme(String id, String section, List<Slot> slots,
              Builder builder, Driver driver, Describer describer, List<Phase> phases) {
            this(id, section, slots, builder, driver, describer, phases, List.of());
        }

        /** A theme with no named phases, which is one whose progress has no stages worth looping on their own. */
        Theme(String id, String section, List<Slot> slots,
              Builder builder, Driver driver, Describer describer) {
            this(id, section, slots, builder, driver, describer, List.of(), List.of());
        }

        /** This theme, with its named phases. */
        Theme withPhases(Phase... named) {
            return new Theme(id, section, slots, builder, driver, describer, List.of(named), ownStages);
        }

        /**
         * This theme, with a stage list of its own instead of one stage per
         * phase. Order is the order the tuner lists them in, so put them in
         * fight order unless you enjoy a dropdown that reads like a shuffled deck.
         */
        Theme withStages(Stage... named) {
            return new Theme(id, section, slots, builder, driver, describer, phases, List.of(named));
        }

        /**
         * Every stage this theme can be pinned to. The theme's own list if it
         * has one, otherwise one per phase, because a phase is already a stage
         * that just never got asked to stay put.
         */
        List<Stage> stages() {
            List<Stage> out = new ArrayList<>();
            if (ownStages.isEmpty()) {
                for (Phase phase : phases) out.add(fromPhase(phase));
                return out;
            }
            for (Stage stage : ownStages) {
                out.add(stage.driver() != null ? stage : fromPhase(phase(stage.name())));
            }
            return out;
        }

        /**
         * This theme pinned to one stage: the same controller and colours, with
         * the stage's script in place of the whole encounter's. Anything that
         * runs a theme (the live board, the loop, the GIF writer) runs this
         * without knowing the difference, which is the whole trick.
         *
         * <p>A pinned theme has no phases of its own, so a loop over it covers
         * the stage's own 0 to 1. A name this theme does not have gives back
         * the theme unchanged, so a stage picked on the dragon and carried over
         * to the Naga just plays the Naga's whole fight.
         */
        Theme staged(String name) {
            if (name == null) return this;
            for (Stage stage : stages()) {
                if (stage.name().equalsIgnoreCase(name)) {
                    return new Theme(id, section, slots, builder, stage.driver(), stage.describer(),
                            List.of(), List.of());
                }
            }
            return this;
        }

        private Phase phase(String name) {
            for (Phase phase : phases) {
                if (phase.name().equals(name)) return phase;
            }
            throw new IllegalStateException(id + " lists a stage for phase '" + name + "' and has no such phase");
        }

        /** A phase as a stage: progress 0 to 1 squeezed into the phase's own stretch. */
        private Stage fromPhase(Phase phase) {
            double from = phase.first();
            double to = phase.last();
            return new Stage(phase.name(),
                    (controller, memo, progress, elapsed) ->
                            driver.drive(controller, memo, from + (to - from) * progress, elapsed),
                    progress -> describer.describe(from + (to - from) * progress));
        }
    }

    /**
     * One thing a boss does, on its own, on repeat: the dragon breathing fire,
     * the Elder Guardian charging and firing, the Naga mid-charge.
     *
     * <p>Phases can't do this. A phase is a stretch of the progress slider,
     * and plenty of what a boss does isn't on the slider at all: the dragon
     * cycles its poses off the clock, and the guardian's wind-up only ever
     * fires once per encounter. A stage swaps in a script that does that one
     * thing forever, and hands the progress slider whatever is left to vary
     * inside it, usually health.
     *
     * @param driver    null for "this theme's phase of the same name", which
     *                  {@link Theme#stages} fills in; see {@link #phase}
     * @param describer null exactly when {@code driver} is
     */
    record Stage(String name, Driver driver, Describer describer) {

        /** A stage that is just the named phase. For mixing phases into a {@link Theme#withStages} list. */
        static Stage phase(String name) {
            return new Stage(name, null, null);
        }
    }

    /**
     * A stretch of the progress slider that the script treats as one stage of
     * the encounter: a Wither's summon, its powered half, a dragon's death.
     *
     * <p>These are what the tuner's GIF loop offers by name. Each one is built
     * from the same constants the script and the describer branch on, so a
     * phase cannot drift out of line with what the board shows during it.
     *
     * @param from where the stage starts
     * @param to   where the next stage starts — every script here tests its
     *             boundaries with {@code <}, so the value at {@code to} itself
     *             already belongs to the next stage
     */
    record Phase(String name, double from, double to) {

        /**
         * How close to either boundary a loop may come.
         *
         * <p>A loop that runs out and back reaches its end value exactly, and
         * an earlier version used {@code to} itself there. That put a single
         * frame of the next stage at every turn — a flash of the powered
         * Wither at the end of a loop meant to show it unpowered. The start
         * needs the same margin for a less obvious reason: the scripts reach
         * their boundaries through arithmetic like {@code (1 - p) * 100}, and
         * at a boss bar's 30% threshold that comes out as 30.000000000000004,
         * which is not enraged. One slider step inside is inside the stage for
         * every script.
         */
        static final double EDGE = 0.001;

        /** The first progress value safely inside this stage. */
        double first() {
            return from <= 0 ? 0 : Math.min(from + EDGE, (from + to) / 2);
        }

        /** The last progress value safely inside this stage. */
        double last() {
            return to >= 1 ? 1 : Math.max(to - EDGE, (from + to) / 2);
        }
    }

    // ---------------------------------------------------------------
    // Script timing
    // ---------------------------------------------------------------

    /**
     * How long the Naga spends circling between charges, and how long a charge
     * lasts, in millis.
     *
     * <p>Both have to be visible without touching the slider, because the
     * charge is not a colour change — it is the weave flattening out, and the
     * only way to see that happen is to watch it happen.
     */
    private static final long NAGA_CHARGE_PERIOD = 8000;
    private static final long NAGA_CHARGE_LENGTH = 1700;
    /**
     * Blocks per tick, either side of the 0.42 the effect reads as a charge.
     * Keeping the prowl well under it matters: the effect scales the weave on
     * the ratio, so a "slow" speed that is already half the threshold spends
     * the whole fight half-straightened.
     */
    private static final double NAGA_PROWL_SPEED = 0.10;
    private static final double NAGA_CHARGE_SPEED = 0.60;

    /**
     * The Elder Guardian's script, as fractions of the encounter.
     *
     * <p>The one boss here with no health input at all — its layer is about
     * the attack cycle, not the health bar — so progress walks that instead:
     * swimming, then the wind-up, then the beam landing, then cursed. The
     * wind-up gets most of the slider because it is most of what there is to
     * look at, and three seconds of it is the longest tell in vanilla.
     */
    private static final double GUARDIAN_SWIM_END = 0.12;
    private static final double GUARDIAN_CHARGE_END = 0.80;
    private static final double GUARDIAN_FIRED_END = 0.90;
    /**
     * How often the two one-shot events are replayed while the slider sits in
     * their window.
     *
     * <p>Both are short flashes the effect decays from a timestamp, so holding
     * the slider on one would show an event that had already finished. Firing
     * it again on a loop is what makes a 280ms flash a colour you can actually
     * judge on hardware, which is the entire job here.
     */
    private static final long GUARDIAN_BEAM_REPLAY_MILLIS = 900;
    private static final long GUARDIAN_CURSE_REPLAY_MILLIS = 2200;
    /**
     * The "charge and fire" stage, off the clock: the real 60-tick wind-up,
     * the beam landing, then a breather with the crown still out before it
     * locks on again. The breather is made up. Vanilla's gap between shots
     * depends on whether you're still in its sightline, and "are you" is not
     * a question a tuner gets to ask.
     */
    private static final long GUARDIAN_WINDUP_MILLIS = 60 * 50L;
    private static final long GUARDIAN_BREATHER_MILLIS = 1200;

    /** Vanilla's {@code WitherBoss.INVULNERABLE_TICKS}, so the summon counts down properly. */
    private static final int WITHER_SUMMON_TICKS = 220;
    /** Share of the encounter spent on the summon, and the gap between thrown skulls. */
    private static final double WITHER_SUMMON_SHARE = 0.25;
    private static final long WITHER_SKULL_INTERVAL = 1400;
    /**
     * Where the armour goes on: the progress at which {@link #witherHealth}
     * reaches half. Worked out from the summon share rather than written as
     * 0.625, so moving the summon moves the powered phase with it.
     */
    private static final double WITHER_POWERED_START = WITHER_SUMMON_SHARE + 0.5 * (1 - WITHER_SUMMON_SHARE);

    /** Shares of the encounter: the ritual, then the fight, then the death sequence. */
    private static final double DRAGON_RITUAL_SHARE = 0.25;
    private static final double DRAGON_FIGHT_END = 0.90;
    /** How long each pose is held. Long enough to read, not the dragon's real dwell time. */
    private static final long DRAGON_POSE_MILLIS = 2600;
    /** Vanilla's respawn ritual: four crystals, one per tower, evenly spaced. */
    private static final int DRAGON_TOWERS = 4;

    private static final EnderDragonEffect.Pose[] DRAGON_POSES = {
            EnderDragonEffect.Pose.GLIDE,
            EnderDragonEffect.Pose.LUNGE,
            EnderDragonEffect.Pose.HOVER,
            EnderDragonEffect.Pose.PERCHED,
            EnderDragonEffect.Pose.BREATHING,
    };
    /**
     * The "landing" stage: hover this long, then drop onto the portal, then do
     * it again. Landing is the one pose that's an animation rather than a
     * posture (the effect draws the drop over 1.3s from the moment the pose
     * changes), so holding it would show one landing and then a dragon
     * sitting there looking pleased with itself.
     */
    private static final long DRAGON_LANDING_HOVER_MILLIS = 1200;
    private static final long DRAGON_LANDING_REPLAY_MILLIS = 3800;

    /**
     * The Slider's script, as fractions of the encounter: asleep in the middle
     * of its room, then the fight, then the cube breaking apart.
     */
    private static final double SLIDER_ASLEEP_END = 0.08;
    private static final double SLIDER_FIGHT_END = 0.96;
    /**
     * Health comes off in whole hits rather than a trickle every frame, and in
     * few of them. A real fight is dozens of hits, but a GIF sweep crosses the
     * whole fight in three seconds, and dozens of hits in three seconds is a
     * hit flash that never goes out.
     */
    private static final int SLIDER_HITS = 8;
    /**
     * Where the runes go critical: the first hit that leaves a quarter of the
     * health or less, as {@link #sliderHealth} counts hits, placed on the
     * fight's stretch of the slider.
     */
    private static final double SLIDER_CRITICAL_START = SLIDER_ASLEEP_END
            + Math.ceil(0.75 * SLIDER_HITS) / SLIDER_HITS * (SLIDER_FIGHT_END - SLIDER_ASLEEP_END);
    /**
     * How often a parked slider replays the death. It is a burst the effect
     * decays from the moment it happens, so holding the slider still would
     * otherwise show it once and then an empty board.
     */
    private static final long SLIDER_SHATTER_REPLAY_MILLIS = 2600;
    /**
     * Acceleration along a slide, in blocks per second squared: the Slider's
     * per-tick velocity increase at about half health, times 400. The script
     * keeps it fixed through the fight rather than raising it as health falls,
     * because every position here is worked out from the clock, and an
     * acceleration that followed the slider would move the cube whenever the
     * slider did.
     */
    private static final double SLIDER_ACCELERATION = 11.2;
    /** The pause before each slide. The real one waits 2 to 15 ticks. */
    private static final long SLIDER_PAUSE_MILLIS = 450;
    /**
     * Where the cube goes, in blocks from the middle of the room, east then up
     * then south. Every step changes one axis only, because the Slider moves
     * along one at a time; some end on a wall at 6 and some stop short of it,
     * which is the difference between a slam that lights a wall and one that
     * does not. The last step returns to the first so the route repeats.
     */
    private static final double[][] SLIDER_ROUTE = {
            {0, 0, 0}, {-6, 0, 0}, {-6, 0, 5}, {3, 0, 5}, {3, 3, 5}, {3, 0, 5},
            {3, 0, -6}, {6, 0, -6}, {6, 0, 2}, {-2, 0, 2}, {-2, 0, -3}, {0, 0, -3},
    };

    /**
     * The Sun Spirit's script, as fractions of the encounter: asleep in the
     * middle of its room, then the fight, then the sunset.
     */
    private static final double SUN_ASLEEP_END = 0.06;
    private static final double SUN_FIGHT_END = 0.95;
    /** Health in whole hits, few of them, for the same reason as {@link #SLIDER_HITS}. */
    private static final int SUN_HITS = 10;
    private static final long SUN_SUNSET_REPLAY_MILLIS = 4200;
    /**
     * The freeze cycle, off the clock: flying, then frozen for the real 175
     * ticks, then flying again. Real freezes come when someone lands an ice
     * crystal, not on a timer, but the length of one is the thing the
     * countdown ring shows, so the script holds it exactly.
     */
    private static final long SUN_CYCLE_MILLIS = 20000;
    private static final long SUN_FREEZE_MILLIS = 8750;
    /**
     * The "frozen" stage's cycle: a short flight, then the whole freeze. The
     * flight isn't optional. The effect only believes a freeze after it has
     * seen the spirit fly at full speed since waking, because a spirit
     * speeding up from rest passes through the same slow band. Freeze it from
     * the first frame and the effect, correctly, sees a sleepy sun.
     */
    private static final long SUN_FROZEN_STAGE_CYCLE_MILLIS = 2000 + SUN_FREEZE_MILLIS;
    /** Blocks per second flying, 0.35 a tick, and the 0.3 a freeze multiplies it by. */
    private static final double SUN_FLY_SPEED = 7.0;
    private static final double SUN_FROZEN_FACTOR = 0.3;
    /** The script flies a circle this far from the middle, inside the real 9-block bound. */
    private static final double SUN_ORBIT_BLOCKS = 7.0;
    /** A crystal every 50 ticks, every fifth one ice. */
    private static final long SUN_CRYSTAL_INTERVAL_MILLIS = 2500;
    /** Fire crystals fly 0.5 blocks a tick for 300 ticks, ice 0.2 for 500. */
    private static final double SUN_FIRE_CRYSTAL_SPEED = 10.0;
    private static final double SUN_ICE_CRYSTAL_SPEED = 4.0;
    private static final long SUN_FIRE_CRYSTAL_LIFE_MILLIS = 15000;
    private static final long SUN_ICE_CRYSTAL_LIFE_MILLIS = 25000;
    /** Crystals bounce off the walls; this is where, in blocks from the middle. */
    private static final double SUN_WALL_BLOCKS = 10.0;
    /**
     * A fire under the spirit every 35 ticks, and how long the script lets
     * each burn. Real fire goes out at vanilla's random rate; six seconds is
     * representative, not measured.
     */
    private static final long SUN_FIRE_INTERVAL_MILLIS = 1750;
    private static final long SUN_FIRE_LIFE_MILLIS = 6000;
    /** The room's four eternal fires, in blocks from the middle. */
    private static final double SUN_CORNER_FIRE_BLOCKS = 7.0;

    /**
     * The Valkyrie Queen's script, as fractions of the encounter: waiting on
     * her throne, then the fight, then her defeat.
     */
    private static final double QUEEN_WAITING_END = 0.06;
    private static final double QUEEN_FIGHT_END = 0.95;
    /** Health in whole hits, few of them, for the same reason as {@link #SLIDER_HITS}. */
    private static final int QUEEN_HITS = 10;
    private static final long QUEEN_DEFEAT_REPLAY_MILLIS = 4000;
    /**
     * Half the room's floor on each axis. The script's room runs 24 blocks
     * east-west and 20 north-south, with the throne against the north wall.
     */
    private static final double QUEEN_ROOM_HALF_X = 12.0;
    private static final double QUEEN_ROOM_HALF_Z = 10.0;
    /** Where she waits: in front of the north wall, standing on a throne three blocks up. */
    private static final double QUEEN_THRONE_Z = -7.5;
    private static final double QUEEN_THRONE_Y = 3.0;
    /**
     * A teleport every nine seconds. The real one comes round about every 22,
     * which in a GIF sweep of a few seconds would mean most exports never show
     * one.
     */
    private static final long QUEEN_TELEPORT_MILLIS = 9000;
    /** She lands seven blocks from her target, as the real one does, and closes to two. */
    private static final double QUEEN_LANDING_BLOCKS = 7.0;
    private static final double QUEEN_CLOSE_BLOCKS = 2.0;
    private static final long QUEEN_CLOSING_MILLIS = 3500;
    /** A jump, and the lunge out of the top of it, every so often, and how high. */
    private static final long QUEEN_JUMP_PERIOD_MILLIS = 2700;
    private static final long QUEEN_JUMP_MILLIS = 900;
    private static final double QUEEN_JUMP_BLOCKS = 2.0;
    /** A crystal every twelve seconds, about twice as often as the real one, so one is usually in the air. */
    private static final long QUEEN_CRYSTAL_INTERVAL_MILLIS = 12000;
    /**
     * Every other crystal gets hit once it drifts within reach of you: a
     * seven-damage swing, which knocks it away at 0.15 + 7/8 blocks a tick
     * and burns 70 ticks off its life, exactly as {@code ThunderCrystal.hurt}
     * works it out.
     */
    private static final double QUEEN_SWING_REACH_BLOCKS = 3.0;
    private static final double QUEEN_SWING_DAMAGE = 7.0;
    /** How long each bolt stays in the world. Vanilla's lasts a handful of ticks. */
    private static final long QUEEN_BOLT_MILLIS = 400;

    /**
     * The Warden emergence envelope, from the {@code wardenEncounter} config
     * defaults: a 1.1s pulse, a 2s minimum hold and a 1.2s dissolve.
     *
     * <p>This theme has no health to sweep — it is a one-shot envelope — so
     * progress scrubs through it instead and the slider becomes a transport
     * control for the animation.
     */
    private static final long WARDEN_PULSE_MILLIS = 1100;
    private static final long WARDEN_MIN_HOLD_MILLIS = 2000;
    private static final long WARDEN_FADE_MILLIS = 1200;
    private static final long WARDEN_MAX_MILLIS = 14000;
    private static final long WARDEN_NOMINAL_MILLIS = 6700;
    /** From {@code sculkAlert.shriekerRingThicknessKeys}, which the emergence ring borrows. */
    private static final double WARDEN_RING_THICKNESS_KEYS = 1.0;

    // ---------------------------------------------------------------
    // The themes
    // ---------------------------------------------------------------

    /**
     * The hand-built boss layers, in the priority order the mod registers them.
     *
     * <p>The generic boss-bar layer is not among them: there is one of those
     * per entry in {@code boss_profiles.json}, so it is built per profile by
     * {@link #bossBar}.
     */
    static List<Theme> dedicated() {
        List<Theme> out = new ArrayList<>();

        out.add(new Theme("elder guardian", "elderGuardian",
                List.of(new Slot("water", "waterColor", ColorPalette.GUARDIAN_PRISMARINE),
                        new Slot("eye", "eyeColor", ColorPalette.GUARDIAN_EYE),
                        new Slot("beam", "beamColor", ColorPalette.GUARDIAN_BEAM)),
                colors -> {
                    ElderGuardianEffect g = new ElderGuardianEffect(22);
                    g.setFadeTimes(0.6, 1.6);
                    return g;
                },
                BossThemes::driveElderGuardian,
                progress -> {
                    if (progress < GUARDIAN_SWIM_END) return "elder guardian - swimming, spikes in";
                    if (progress < GUARDIAN_CHARGE_END) {
                        return String.format(
                                "elder guardian - locked on, wind-up %.0f%% of its three seconds",
                                (progress - GUARDIAN_SWIM_END)
                                        / (GUARDIAN_CHARGE_END - GUARDIAN_SWIM_END) * 100);
                    }
                    if (progress < GUARDIAN_FIRED_END) return "elder guardian - the beam landing";
                    return "elder guardian - cursed: mining fatigue, and the water heaving";
                }).withPhases(
                new Phase("swimming", 0, GUARDIAN_SWIM_END),
                new Phase("wind-up", GUARDIAN_SWIM_END, GUARDIAN_CHARGE_END),
                new Phase("beam", GUARDIAN_CHARGE_END, GUARDIAN_FIRED_END),
                new Phase("cursed", GUARDIAN_FIRED_END, 1)).withStages(
                Stage.phase("swimming"),
                Stage.phase("wind-up"),
                new Stage("charge and fire", BossThemes::driveGuardianVolley,
                        progress -> String.format("elder guardian - %.0fs wind-up, beam, %.1fs breather, "
                                        + "on repeat (progress does nothing here)",
                                GUARDIAN_WINDUP_MILLIS / 1000.0, GUARDIAN_BREATHER_MILLIS / 1000.0)),
                Stage.phase("beam"),
                Stage.phase("cursed")));

        out.add(new Theme("wither", "wither",
                List.of(new Slot("bone", "boneColor", ColorPalette.WITHER_BONE),
                        new Slot("eyes", "eyeColor", ColorPalette.WITHER_EYE),
                        new Slot("powered", "poweredColor", ColorPalette.WITHER_POWERED)),
                colors -> new WitherEffect(23),
                BossThemes::driveWither,
                progress -> {
                    if (progress < WITHER_SUMMON_SHARE) {
                        return String.format("wither / summoning - charge %.0f%%",
                                progress / WITHER_SUMMON_SHARE * 100);
                    }
                    double health = witherHealth(progress);
                    return String.format("wither / fight - health %.0f%%%s", health * 100,
                            health <= 0.5 ? ", powered (the armour is on)" : "");
                }).withPhases(
                new Phase("summoning", 0, WITHER_SUMMON_SHARE),
                new Phase("fight", WITHER_SUMMON_SHARE, WITHER_POWERED_START),
                new Phase("powered", WITHER_POWERED_START, 1)));

        out.add(new Theme("naga", "naga",
                List.of(new Slot("scale", "scaleColor", ColorPalette.NAGA_SCALE_GREEN),
                        new Slot("head", "highlightColor", ColorPalette.NAGA_HIGHLIGHT)),
                colors -> {
                    NagaEffect n = new NagaEffect(24);
                    n.setFadeTimes(0.5, 1.4);
                    return n;
                },
                BossThemes::driveNaga,
                progress -> String.format("naga - health %.0f%%, one body segment per ~12%%",
                        (1 - progress) * 100)).withStages(
                nagaStage("prowling", NAGA_PROWL_SPEED),
                nagaStage("charging", NAGA_CHARGE_SPEED)));

        out.add(new Theme("ender dragon", "enderDragon",
                List.of(new Slot("void", "voidColor", ColorPalette.END_VOID_PURPLE),
                        new Slot("accent", "accentColor", ColorPalette.END_DRAGON_MAGENTA),
                        new Slot("breath fire", "breathFireColor", ColorPalette.END_BREATH_FIRE)),
                colors -> new EnderDragonEffect(25),
                BossThemes::driveDragon,
                progress -> {
                    if (progress < DRAGON_RITUAL_SHARE) {
                        return String.format("ender dragon / ritual - %.0f%% summoned",
                                progress / DRAGON_RITUAL_SHARE * 100);
                    }
                    if (progress < DRAGON_FIGHT_END) {
                        return String.format("ender dragon / fight - health %.0f%%, poses cycling",
                                dragonHealth(progress) * 100);
                    }
                    return String.format("ender dragon / dying - %.0f%%",
                            (progress - DRAGON_FIGHT_END) / (1 - DRAGON_FIGHT_END) * 100);
                }).withPhases(
                new Phase("ritual", 0, DRAGON_RITUAL_SHARE),
                new Phase("fight", DRAGON_RITUAL_SHARE, DRAGON_FIGHT_END),
                new Phase("dying", DRAGON_FIGHT_END, 1)).withStages(
                Stage.phase("ritual"),
                Stage.phase("fight"),
                dragonStage("flying", "gliding round the board", EnderDragonEffect.Pose.GLIDE),
                dragonStage("lunging", "diving at you", EnderDragonEffect.Pose.LUNGE),
                dragonStage("hovering", "hovering", EnderDragonEffect.Pose.HOVER),
                new Stage("landing", BossThemes::driveDragonLanding,
                        progress -> String.format("ender dragon / landing - health %.0f%%, "
                                + "hovers then drops onto the portal, on repeat", (1 - progress) * 100)),
                dragonStage("perched", "perched on the portal", EnderDragonEffect.Pose.PERCHED),
                dragonStage("fire breath", "perched and breathing fire", EnderDragonEffect.Pose.BREATHING),
                Stage.phase("dying")));

        out.add(new Theme("slider", "slider",
                List.of(new Slot("stone", "stoneColor", ColorPalette.SLIDER_STONE),
                        new Slot("runes", "runeColor", ColorPalette.SLIDER_RUNE),
                        new Slot("critical", "criticalColor", ColorPalette.SLIDER_CRITICAL)),
                colors -> {
                    SliderEffect s = new SliderEffect(26);
                    s.setFadeTimes(0.5, 1.2);
                    return s;
                },
                BossThemes::driveSlider,
                progress -> {
                    if (progress < SLIDER_ASLEEP_END) return "slider - asleep in the middle of its room";
                    if (progress < SLIDER_FIGHT_END) {
                        double health = sliderHealth(progress);
                        return String.format("slider - awake, health %.0f%%%s", health * 100,
                                health <= 0.25 ? ", critical (the runes go red)" : "");
                    }
                    return "slider - destroyed, the cube bursting";
                }).withPhases(
                new Phase("asleep", 0, SLIDER_ASLEEP_END),
                new Phase("fight", SLIDER_ASLEEP_END, SLIDER_CRITICAL_START),
                new Phase("critical", SLIDER_CRITICAL_START, SLIDER_FIGHT_END),
                new Phase("destroyed", SLIDER_FIGHT_END, 1)));

        out.add(new Theme("sun spirit", "sunSpirit",
                List.of(new Slot("sun", "sunColor", ColorPalette.SUN_SPIRIT_SUN),
                        new Slot("flame", "flameColor", ColorPalette.SUN_SPIRIT_FLAME),
                        new Slot("ice", "iceColor", ColorPalette.SUN_SPIRIT_ICE)),
                colors -> {
                    SunSpiritEffect s = new SunSpiritEffect(27);
                    s.setFadeTimes(0.5, 1.5);
                    return s;
                },
                BossThemes::driveSunSpirit,
                progress -> {
                    if (progress < SUN_ASLEEP_END) return "sun spirit - asleep in the middle of its room";
                    if (progress < SUN_FIGHT_END) {
                        return String.format("sun spirit - health %.0f%%; frozen %.2fs of every %.0fs "
                                        + "(the ring counts it down)", sunHealth(progress) * 100,
                                SUN_FREEZE_MILLIS / 1000.0, SUN_CYCLE_MILLIS / 1000.0);
                    }
                    return "sun spirit - destroyed, and the eternal day ending";
                }).withPhases(
                new Phase("asleep", 0, SUN_ASLEEP_END),
                new Phase("fight", SUN_ASLEEP_END, SUN_FIGHT_END),
                new Phase("sunset", SUN_FIGHT_END, 1)).withStages(
                Stage.phase("asleep"),
                Stage.phase("fight"),
                sunStage("flying", "never frozen", SunFlight.NEVER_FROZEN),
                sunStage("frozen", String.format("2s of flight, then the whole %.2fs freeze, on repeat",
                        SUN_FREEZE_MILLIS / 1000.0), SunFlight.MOSTLY_FROZEN),
                Stage.phase("sunset")));

        out.add(new Theme("valkyrie queen", "valkyrieQueen",
                List.of(new Slot("silver", "silverColor", ColorPalette.VALKYRIE_QUEEN_SILVER),
                        new Slot("gold", "goldColor", ColorPalette.VALKYRIE_QUEEN_GOLD),
                        new Slot("lightning", "lightningColor", ColorPalette.VALKYRIE_QUEEN_LIGHTNING)),
                colors -> {
                    ValkyrieQueenEffect q = new ValkyrieQueenEffect(28);
                    q.setFadeTimes(0.5, 1.5);
                    return q;
                },
                BossThemes::driveValkyrieQueen,
                progress -> {
                    if (progress < QUEEN_WAITING_END) return "valkyrie queen - waiting on her throne";
                    if (progress < QUEEN_FIGHT_END) {
                        return String.format("valkyrie queen - health %.0f%%; a teleport every %.0fs, "
                                        + "a thunder crystal every %.0fs", queenHealth(progress) * 100,
                                QUEEN_TELEPORT_MILLIS / 1000.0, QUEEN_CRYSTAL_INTERVAL_MILLIS / 1000.0);
                    }
                    return "valkyrie queen - defeated, and her dungeon opening";
                }).withPhases(
                new Phase("waiting", 0, QUEEN_WAITING_END),
                new Phase("fight", QUEEN_WAITING_END, QUEEN_FIGHT_END),
                new Phase("defeated", QUEEN_FIGHT_END, 1)));

        out.add(new Theme("warden emergence", "wardenEncounter",
                List.of(new Slot("emergence", "emergenceColor", ColorPalette.WARDEN_EMERGENCE)),
                colors -> new WardenEmergenceEffect(12),
                BossThemes::driveWarden,
                progress -> String.format(
                        "warden emergence - %.0f%% through a %.1fs envelope, which progress scrubs",
                        progress * 100, (WARDEN_MIN_HOLD_MILLIS + WARDEN_FADE_MILLIS) / 1000.0)));

        return out;
    }

    /**
     * The generic boss-bar layer, for one {@code boss_profiles.json} entry.
     *
     * <p>Its colours are the profile's {@code color} and {@code enrageColor},
     * so unlike the dedicated themes they come from JSON and go back to JSON —
     * the tuner handles them exactly as it handles a biome's two colours.
     *
     * <p>The profile's {@code pattern} field is deliberately not honoured here,
     * because the mod does not honour it either: the boss layer builds its own
     * whole-board pulse and takes its frequency from health. Rendering the
     * named pattern instead would show something the game never shows.
     *
     * @param enrageThresholdPercent the profile's threshold, or null for a boss
     *                               with no enrage phase — passed on as the
     *                               effect's own -1 sentinel, so the colour
     *                               shift is skipped here for the same reason
     *                               it is skipped in game
     */
    static Theme bossBar(Integer enrageThresholdPercent) {
        boolean hasEnrage = enrageThresholdPercent != null;
        List<Slot> slots = hasEnrage
                ? List.of(new Slot("boss", "color", null),
                          new Slot("enrage", "enrageColor", null))
                : List.of(new Slot("boss", "color", null));
        int threshold = hasEnrage ? enrageThresholdPercent : -1;
        Theme theme = new Theme("boss bar", null, slots,
                colors -> {
                    BossEncounterEffect b = new BossEncounterEffect();
                    b.setEnrageIntensityScaling(true);
                    return b;
                },
                (controller, memo, progress, elapsed) -> {
                    BossEncounterEffect b = (BossEncounterEffect) controller;
                    // startBoss is the only way in to the colours, and it also
                    // sets the clock the pulse is timed from — so it is handed
                    // a fixed zero rather than the current time. Called with
                    // the same timestamp every frame it re-colours without
                    // resetting the beat, which passing `elapsed` would do
                    // sixty times a second and leave the board frozen
                    // mid-pulse.
                    b.startBoss(memo.colors.get(0),
                            memo.colors.get(1) == null ? null : memo.colors.get(1),
                            threshold, 0L);
                    // After startBoss, which resets the percentage to full.
                    b.updatePercent((1 - progress) * 100);
                },
                progress -> {
                    double percent = (1 - progress) * 100;
                    String enrage = threshold < 0 ? "no enrage phase"
                            : percent <= threshold
                            ? String.format("enraged, below %d%%", threshold)
                            : String.format("enrage at %d%%", threshold);
                    return String.format("boss bar pulse - health %.0f%%, %s", percent, enrage);
                });
        if (!hasEnrage) return theme;
        // Enraged from the progress where health first reaches the threshold,
        // which is the describer's own <= test turned round.
        double enraged = Math.max(0, Math.min(1, 1 - threshold / 100.0));
        return theme.withPhases(
                new Phase("calm", 0, enraged),
                new Phase("enraged", enraged, 1));
    }

    // ---------------------------------------------------------------
    // Scripts
    // ---------------------------------------------------------------

    /**
     * Circling, with a charge every few seconds.
     *
     * <p>Speed is the whole input here beyond health: {@code NagaEffect} reads
     * a charge off nothing but how fast the thing is moving, so a script
     * reporting only the prowl speed would leave half the effect unreachable.
     */
    private static void driveNaga(EffectController controller, Memo memo,
                                  double progress, long elapsedMillis) {
        NagaEffect n = (NagaEffect) controller;
        n.setColors(memo.colors.get(0), memo.colors.get(1));
        boolean charging = Math.floorMod(elapsedMillis, NAGA_CHARGE_PERIOD) < NAGA_CHARGE_LENGTH;
        n.setNaga(1 - progress, charging ? NAGA_CHARGE_SPEED : NAGA_PROWL_SPEED, elapsedMillis);
    }

    /** The Naga stuck at one speed, forever. Progress is still health, so the body still sheds segments. */
    private static Stage nagaStage(String name, double speed) {
        return new Stage(name,
                (controller, memo, progress, elapsedMillis) -> {
                    NagaEffect n = (NagaEffect) controller;
                    n.setColors(memo.colors.get(0), memo.colors.get(1));
                    n.setNaga(1 - progress, speed, elapsedMillis);
                },
                progress -> String.format("naga / %s - health %.0f%%, and it never stops %s",
                        name, (1 - progress) * 100, name));
    }

    /**
     * Swimming, then three seconds of wind-up, then the beam, then cursed.
     *
     * <p>The spikes lead the charge rather than tracking it: in game the crown
     * is already out by the time it locks on, because coming to a stop is what
     * puts it out. Having them extend <i>with</i> the beam would read as the
     * charge causing the spikes, which is backwards.
     */
    private static void driveElderGuardian(EffectController controller, Memo memo,
                                           double progress, long elapsedMillis) {
        ElderGuardianEffect g = (ElderGuardianEffect) controller;
        g.setColors(memo.colors.get(0), memo.colors.get(1), memo.colors.get(2));

        double charge;
        boolean cursed = progress >= GUARDIAN_FIRED_END;
        if (progress < GUARDIAN_SWIM_END) {
            charge = 0;
        } else if (progress < GUARDIAN_CHARGE_END) {
            charge = (progress - GUARDIAN_SWIM_END) / (GUARDIAN_CHARGE_END - GUARDIAN_SWIM_END);
        } else if (progress < GUARDIAN_FIRED_END) {
            charge = 1;
        } else {
            charge = 0;
        }
        // Out by the time it stops to aim, and retracted only while swimming.
        double spikes = progress < GUARDIAN_SWIM_END
                ? progress / GUARDIAN_SWIM_END : 1.0;
        g.setGuardian(spikes, charge, true, cursed, elapsedMillis);

        // The two flashes, replayed on a loop while the slider is parked in
        // their window. See the replay constants.
        if (progress >= GUARDIAN_CHARGE_END && progress < GUARDIAN_FIRED_END) {
            int beat = (int) Math.floorDiv(elapsedMillis, GUARDIAN_BEAM_REPLAY_MILLIS);
            if (beat != memo.count) {
                memo.count = beat;
                g.fireBeam(elapsedMillis);
            }
        } else if (cursed) {
            int beat = (int) Math.floorDiv(elapsedMillis, GUARDIAN_CURSE_REPLAY_MILLIS);
            if (beat != memo.count) {
                memo.count = beat;
                g.curseLanded(elapsedMillis);
            }
        }
    }

    /**
     * Lock on, wind up for three seconds, fire, catch its breath, repeat. The
     * whole attack in real time, which the encounter script can't give you:
     * there the wind-up is on the slider and the beam is a separate phase, so
     * watching one lead into the other meant dragging very smoothly and hoping.
     *
     * <p>The charge drops to zero the moment the beam lands, because in game
     * the attack target clears and the effect stops reading the charge. The
     * spikes stay out the whole time, since it never starts swimming.
     */
    private static void driveGuardianVolley(EffectController controller, Memo memo,
                                            double progress, long elapsedMillis) {
        ElderGuardianEffect g = (ElderGuardianEffect) controller;
        g.setColors(memo.colors.get(0), memo.colors.get(1), memo.colors.get(2));
        long cycle = GUARDIAN_WINDUP_MILLIS + GUARDIAN_BREATHER_MILLIS;
        long within = Math.floorMod(elapsedMillis, cycle);
        double charge = within < GUARDIAN_WINDUP_MILLIS ? within / (double) GUARDIAN_WINDUP_MILLIS : 0;
        g.setGuardian(1.0, charge, true, false, elapsedMillis);
        // One shot per cycle, fired on the frame the wind-up runs out. See Memo
        // for why "every frame past the end" would be a very different effect.
        int shot = (int) Math.floorDiv(elapsedMillis, cycle);
        if (within >= GUARDIAN_WINDUP_MILLIS && shot != memo.count) {
            memo.count = shot;
            g.fireBeam(elapsedMillis);
        }
    }

    private static double sliderHealth(double progress) {
        double through = (progress - SLIDER_ASLEEP_END) / (SLIDER_FIGHT_END - SLIDER_ASLEEP_END);
        return 1 - Math.floor(Math.max(0, Math.min(1, through)) * SLIDER_HITS) / SLIDER_HITS;
    }

    /**
     * Asleep, then sliding the route with you wandering the room, then broken.
     *
     * <p>Positions are a pure function of the clock, so a GIF sweep that moves
     * the slider back and forth does not make the cube jump. The effect finds
     * the slams, hits and waking on its own from what it is fed, exactly as it
     * does in game, so the script only has to feed it positions and health.
     */
    private static void driveSlider(EffectController controller, Memo memo,
                                    double progress, long elapsedMillis) {
        SliderEffect s = (SliderEffect) controller;
        s.setColors(memo.colors.get(0), memo.colors.get(1), memo.colors.get(2));
        double seconds = elapsedMillis / 1000.0;
        double playerX = 4.5 * Math.sin(2 * Math.PI * seconds / 7.3);
        double playerZ = 4.0 * Math.sin(2 * Math.PI * seconds / 5.1 + 1.0);

        if (progress < SLIDER_ASLEEP_END) {
            s.setSlider(0, 0, 0, playerX, playerZ, 1.0, false, elapsedMillis);
            return;
        }
        double[] at = sliderRouteAt(elapsedMillis);
        if (progress < SLIDER_FIGHT_END) {
            s.setSlider(at[0], at[1], at[2], playerX, playerZ, sliderHealth(progress), true, elapsedMillis);
            return;
        }
        int beat = (int) Math.floorDiv(elapsedMillis, SLIDER_SHATTER_REPLAY_MILLIS);
        if (beat != memo.count) {
            memo.count = beat;
            // Alive for one call and then dead, which is what brings the cube
            // back so it can break again.
            s.setSlider(at[0], at[1], at[2], playerX, playerZ, 1.0 / SLIDER_HITS, true, elapsedMillis);
            s.setDead(elapsedMillis);
        }
    }

    /** Where the route has the cube at this moment: a pause at each stop, then a slide from rest. */
    private static double[] sliderRouteAt(long elapsedMillis) {
        int legs = SLIDER_ROUTE.length;
        double[] seconds = new double[legs];
        double cycle = 0;
        for (int i = 0; i < legs; i++) {
            seconds[i] = Math.sqrt(2 * sliderLegLength(i) / SLIDER_ACCELERATION);
            cycle += SLIDER_PAUSE_MILLIS / 1000.0 + seconds[i];
        }
        double t = Math.floorMod(elapsedMillis, (long) (cycle * 1000)) / 1000.0;
        for (int i = 0; i < legs; i++) {
            double[] from = SLIDER_ROUTE[i];
            double[] to = SLIDER_ROUTE[(i + 1) % legs];
            if (t < SLIDER_PAUSE_MILLIS / 1000.0) return from.clone();
            t -= SLIDER_PAUSE_MILLIS / 1000.0;
            if (t < seconds[i]) {
                double f = 0.5 * SLIDER_ACCELERATION * t * t / sliderLegLength(i);
                return new double[]{
                        from[0] + (to[0] - from[0]) * f,
                        from[1] + (to[1] - from[1]) * f,
                        from[2] + (to[2] - from[2]) * f};
            }
            t -= seconds[i];
        }
        return SLIDER_ROUTE[0].clone();
    }

    private static double sliderLegLength(int leg) {
        double[] from = SLIDER_ROUTE[leg];
        double[] to = SLIDER_ROUTE[(leg + 1) % SLIDER_ROUTE.length];
        return Math.abs(to[0] - from[0]) + Math.abs(to[1] - from[1]) + Math.abs(to[2] - from[2]);
    }

    private static double sunHealth(double progress) {
        double through = (progress - SUN_ASLEEP_END) / (SUN_FIGHT_END - SUN_ASLEEP_END);
        return 1 - Math.floor(Math.max(0, Math.min(1, through)) * SUN_HITS) / SUN_HITS;
    }

    /**
     * Asleep, then flying, throwing crystals and setting fires with a freeze
     * in every cycle, then the sunset.
     *
     * <p>Like the Slider's, every position is a pure function of the clock, so
     * sweeping the slider changes health without moving anything. The effect
     * reads the freeze off the spirit's speed and the shots off new crystal
     * ids, exactly as it does in game, so the script only has to move things
     * at the real speeds.
     */
    private static void driveSunSpirit(EffectController controller, Memo memo,
                                       double progress, long elapsedMillis) {
        driveSun(controller, memo, progress, elapsedMillis, SunFlight.ENCOUNTER);
    }

    /** The encounter script with the freeze schedule handed in, so the stages can rig it. */
    private static void driveSun(EffectController controller, Memo memo,
                                 double progress, long elapsedMillis, SunFlight flight) {
        SunSpiritEffect s = (SunSpiritEffect) controller;
        s.setColors(memo.colors.get(0), memo.colors.get(1), memo.colors.get(2));
        double seconds = elapsedMillis / 1000.0;
        double playerX = 5.0 * Math.sin(2 * Math.PI * seconds / 7.3);
        double playerZ = 5.0 * Math.sin(2 * Math.PI * seconds / 5.1 + 1.0);

        if (progress < SUN_ASLEEP_END) {
            s.setSpirit(0, 0, playerX, playerZ, 1.0, false, elapsedMillis);
            s.setCrystals(List.of(), elapsedMillis);
            sunFires(s, elapsedMillis, false, flight);
            return;
        }
        double[] at = sunAt(elapsedMillis, flight);
        if (progress < SUN_FIGHT_END) {
            s.setSpirit(at[0], at[1], playerX, playerZ, sunHealth(progress), true, elapsedMillis);
            s.setCrystals(sunCrystals(elapsedMillis, flight), elapsedMillis);
            sunFires(s, elapsedMillis, true, flight);
            return;
        }
        int beat = (int) Math.floorDiv(elapsedMillis, SUN_SUNSET_REPLAY_MILLIS);
        if (beat != memo.count) {
            memo.count = beat;
            // Alive for one call and then dead, which brings the sun back so
            // it can set again.
            s.setSpirit(at[0], at[1], playerX, playerZ, 1.0 / SUN_HITS, true, elapsedMillis);
            s.setDead(elapsedMillis);
        }
    }

    /**
     * When the spirit is frozen: a cycle that flies first and spends its last
     * {@code freezeMillis} frozen. The encounter's is a freeze every 20
     * seconds; the stages either never freeze it or barely let it fly.
     */
    private record SunFlight(long cycleMillis, long freezeMillis) {
        static final SunFlight ENCOUNTER = new SunFlight(SUN_CYCLE_MILLIS, SUN_FREEZE_MILLIS);
        static final SunFlight NEVER_FROZEN = new SunFlight(SUN_CYCLE_MILLIS, 0);
        static final SunFlight MOSTLY_FROZEN = new SunFlight(SUN_FROZEN_STAGE_CYCLE_MILLIS, SUN_FREEZE_MILLIS);
    }

    /**
     * The fight with the freeze rigged one way or the other. Progress walks
     * health through the fight's own stretch, kept inside it so the far end
     * of the slider is a sun on its last hit rather than a sunset.
     */
    private static Stage sunStage(String name, String what, SunFlight flight) {
        Phase fight = new Phase("fight", SUN_ASLEEP_END, SUN_FIGHT_END);
        return new Stage(name,
                (controller, memo, progress, elapsedMillis) -> driveSun(controller, memo,
                        fight.first() + (fight.last() - fight.first()) * progress, elapsedMillis, flight),
                progress -> String.format("sun spirit / %s - health %.0f%%, %s", name,
                        sunHealth(fight.first() + (fight.last() - fight.first()) * progress) * 100, what));
    }

    /** Distance flown by this moment, at full speed outside the freeze and 0.3 of it inside. */
    private static double sunDistance(long elapsedMillis, SunFlight flight) {
        long cycle = flight.cycleMillis();
        long flying = cycle - flight.freezeMillis();
        double perCycle = (flying + flight.freezeMillis() * SUN_FROZEN_FACTOR) / 1000.0 * SUN_FLY_SPEED;
        long cycles = Math.floorDiv(elapsedMillis, cycle);
        long within = Math.floorMod(elapsedMillis, cycle);
        double partial = within < flying
                ? within / 1000.0 * SUN_FLY_SPEED
                : (flying + (within - flying) * SUN_FROZEN_FACTOR) / 1000.0 * SUN_FLY_SPEED;
        return cycles * perCycle + partial;
    }

    /**
     * Where the spirit is: round a circle at the speed it is flying, with the
     * circle's centre wandering slowly so the path is not a perfect ring. The
     * wander is under half a block a second, so it never pushes the flying
     * speed into the frozen band or the frozen speed out of it.
     */
    private static double[] sunAt(long elapsedMillis, SunFlight flight) {
        double angle = sunDistance(elapsedMillis, flight) / SUN_ORBIT_BLOCKS;
        double seconds = elapsedMillis / 1000.0;
        return new double[]{
                SUN_ORBIT_BLOCKS * Math.cos(angle) + 1.5 * Math.sin(seconds * 0.3),
                SUN_ORBIT_BLOCKS * Math.sin(angle) + 1.5 * Math.cos(seconds * 0.23)};
    }

    /** Every crystal in the air at this moment, each thrown from where the spirit was when it was thrown. */
    private static List<SunSpiritEffect.Crystal> sunCrystals(long elapsedMillis, SunFlight flight) {
        List<SunSpiritEffect.Crystal> out = new ArrayList<>();
        long first = Math.floorDiv(elapsedMillis - SUN_ICE_CRYSTAL_LIFE_MILLIS, SUN_CRYSTAL_INTERVAL_MILLIS);
        long last = Math.floorDiv(elapsedMillis, SUN_CRYSTAL_INTERVAL_MILLIS);
        for (long k = first; k <= last; k++) {
            long thrownAt = k * SUN_CRYSTAL_INTERVAL_MILLIS;
            boolean ice = Math.floorMod(k, 5) == 4;
            long age = elapsedMillis - thrownAt;
            if (age < 0 || age > (ice ? SUN_ICE_CRYSTAL_LIFE_MILLIS : SUN_FIRE_CRYSTAL_LIFE_MILLIS)) continue;
            double[] from = sunAt(thrownAt, flight);
            // A direction per crystal that is the same every time it is asked
            // for, which is what keeps the positions a function of the clock.
            double heading = 2 * Math.PI * ((Math.floorMod(k * 2654435761L, 1000L)) / 1000.0);
            double travelled = age / 1000.0 * (ice ? SUN_ICE_CRYSTAL_SPEED : SUN_FIRE_CRYSTAL_SPEED);
            out.add(new SunSpiritEffect.Crystal((int) (k + 1_000_000),
                    bounce(from[0] + Math.cos(heading) * travelled),
                    bounce(from[1] + Math.sin(heading) * travelled), ice));
        }
        return out;
    }

    /** A straight line folded back and forth between the walls, which is a bounce. */
    private static double bounce(double p) {
        double span = 4 * SUN_WALL_BLOCKS;
        double q = ((p + SUN_WALL_BLOCKS) % span + span) % span;
        return q < 2 * SUN_WALL_BLOCKS ? q - SUN_WALL_BLOCKS : 3 * SUN_WALL_BLOCKS - q;
    }

    /** The four corner fires always, and while it flies, the trail it has set and not yet burned out. */
    private static void sunFires(SunSpiritEffect s, long elapsedMillis, boolean trail, SunFlight flight) {
        List<double[]> fires = new ArrayList<>();
        double c = SUN_CORNER_FIRE_BLOCKS;
        fires.add(new double[]{-c, -c});
        fires.add(new double[]{c, -c});
        fires.add(new double[]{-c, c});
        fires.add(new double[]{c, c});
        if (trail) {
            long first = Math.floorDiv(elapsedMillis - SUN_FIRE_LIFE_MILLIS, SUN_FIRE_INTERVAL_MILLIS) + 1;
            long last = Math.floorDiv(elapsedMillis, SUN_FIRE_INTERVAL_MILLIS);
            for (long j = first; j <= last; j++) {
                double[] at = sunAt(j * SUN_FIRE_INTERVAL_MILLIS, flight);
                // Snapped to the middle of a block, as real fire is.
                fires.add(new double[]{Math.floor(at[0]) + 0.5, Math.floor(at[1]) + 0.5});
            }
        }
        double[] xs = new double[fires.size()];
        double[] zs = new double[fires.size()];
        for (int i = 0; i < fires.size(); i++) {
            xs[i] = fires.get(i)[0];
            zs[i] = fires.get(i)[1];
        }
        s.setFires(xs, zs);
    }

    private static double queenHealth(double progress) {
        double through = (progress - QUEEN_WAITING_END) / (QUEEN_FIGHT_END - QUEEN_WAITING_END);
        return 1 - Math.floor(Math.max(0, Math.min(1, through)) * QUEEN_HITS) / QUEEN_HITS;
    }

    /**
     * Waiting on her throne, then hunting you round the room — teleporting to
     * your side, jumping and lunging, throwing crystals that become lightning
     * — then her defeat.
     *
     * <p>Like the Sun Spirit's, every position is a pure function of the
     * clock, and the effect finds the teleports, the lunges, the struck
     * crystals and the strikes on its own from what it is fed, exactly as it
     * does in game. Nothing here calls a teleport or a strike: they are
     * positions that jump and bolts that appear.
     */
    private static void driveValkyrieQueen(EffectController controller, Memo memo,
                                           double progress, long elapsedMillis) {
        ValkyrieQueenEffect q = (ValkyrieQueenEffect) controller;
        q.setColors(memo.colors.get(0), memo.colors.get(1), memo.colors.get(2));
        double[] you = queenPlayerAt(elapsedMillis);

        if (progress < QUEEN_WAITING_END) {
            q.setQueen(0, QUEEN_THRONE_Y, QUEEN_THRONE_Z, you[0], you[1], 1.0, false, elapsedMillis);
            queenRoom(q);
            q.setCrystals(List.of(), elapsedMillis);
            q.setBolts(List.of(), elapsedMillis);
            return;
        }
        double[] at = queenAt(elapsedMillis);
        if (progress < QUEEN_FIGHT_END) {
            q.setQueen(at[0], at[1], at[2], you[0], you[1], queenHealth(progress), true, elapsedMillis);
            queenRoom(q);
            List<ValkyrieQueenEffect.Crystal> crystals = new ArrayList<>();
            List<ValkyrieQueenEffect.Bolt> bolts = new ArrayList<>();
            queenCrystals(elapsedMillis, crystals, bolts);
            q.setCrystals(crystals, elapsedMillis);
            q.setBolts(bolts, elapsedMillis);
            return;
        }
        int beat = (int) Math.floorDiv(elapsedMillis, QUEEN_DEFEAT_REPLAY_MILLIS);
        if (beat != memo.count) {
            memo.count = beat;
            // Alive for one call and then defeated, which brings her back so
            // she can fall again.
            q.setQueen(at[0], at[1], at[2], you[0], you[1], 1.0 / QUEEN_HITS, true, elapsedMillis);
            queenRoom(q);
            q.setDefeated(elapsedMillis);
        }
    }

    /** The room, as the event layer hands it over once the walls are found: the faces of the walls. */
    private static void queenRoom(ValkyrieQueenEffect q) {
        q.setRoom(-QUEEN_ROOM_HALF_X, -QUEEN_ROOM_HALF_Z, QUEEN_ROOM_HALF_X, QUEEN_ROOM_HALF_Z);
    }

    /** You, wandering the room. */
    private static double[] queenPlayerAt(long elapsedMillis) {
        double seconds = elapsedMillis / 1000.0;
        return new double[]{
                7.0 * Math.sin(2 * Math.PI * seconds / 9.1),
                5.5 * Math.sin(2 * Math.PI * seconds / 6.7 + 1.0)};
    }

    /**
     * Where she is. Each teleport puts her seven blocks from you in a new
     * direction; she closes to two over a few seconds and circles a little;
     * and every so often she jumps. The top of her approach is under ten
     * blocks a second, so only the teleports read as teleports.
     */
    private static double[] queenAt(long elapsedMillis) {
        long leg = Math.floorDiv(elapsedMillis, QUEEN_TELEPORT_MILLIS);
        long within = Math.floorMod(elapsedMillis, QUEEN_TELEPORT_MILLIS);
        double close = Math.min(1, within / (double) QUEEN_CLOSING_MILLIS);
        close = close * close * (3 - 2 * close);
        double distance = QUEEN_LANDING_BLOCKS + (QUEEN_CLOSE_BLOCKS - QUEEN_LANDING_BLOCKS) * close;
        double heading = 2 * Math.PI * (Math.floorMod(leg * 2654435761L, 1000L) / 1000.0)
                + 0.3 * Math.sin(elapsedMillis / 1300.0);
        double[] you = queenPlayerAt(elapsedMillis);
        double x = clampRoom(you[0] + distance * Math.cos(heading), QUEEN_ROOM_HALF_X - 1);
        double z = clampRoom(you[1] + distance * Math.sin(heading), QUEEN_ROOM_HALF_Z - 1);
        long hop = Math.floorMod(elapsedMillis + leg * 777, QUEEN_JUMP_PERIOD_MILLIS);
        double y = 0;
        if (hop < QUEEN_JUMP_MILLIS) {
            double f = hop / (double) QUEEN_JUMP_MILLIS;
            y = 4 * QUEEN_JUMP_BLOCKS * f * (1 - f);
        }
        return new double[]{x, y, z};
    }

    private static double clampRoom(double p, double limit) {
        return Math.max(-limit, Math.min(limit, p));
    }

    /**
     * Every crystal in the air at this moment, and every bolt still standing.
     *
     * <p>Each crystal is flown tick by tick from the moment it was thrown, by
     * {@code ThunderCrystal}'s own rule — keep nine tenths of the velocity, add
     * 0.02 blocks a tick toward you — which is the only way to know where a
     * homing projectile is. It is cheap: a crystal lives at most 300 ticks and
     * there are rarely more than two.
     */
    private static void queenCrystals(long elapsedMillis, List<ValkyrieQueenEffect.Crystal> crystals,
                                      List<ValkyrieQueenEffect.Bolt> bolts) {
        long lifeTicks = ValkyrieQueenPattern.CRYSTAL_LIFE_MILLIS / 50;
        long first = Math.floorDiv(elapsedMillis - lifeTicks * 50 - QUEEN_BOLT_MILLIS, QUEEN_CRYSTAL_INTERVAL_MILLIS);
        long last = Math.floorDiv(elapsedMillis, QUEEN_CRYSTAL_INTERVAL_MILLIS);
        for (long k = first; k <= last; k++) {
            long thrownAt = k * QUEEN_CRYSTAL_INTERVAL_MILLIS;
            if (thrownAt > elapsedMillis) continue;
            double[] from = queenAt(thrownAt);
            double x = from[0];
            double z = from[2];
            double vx = 0;
            double vz = 0;
            long life = lifeTicks;
            boolean swingable = Math.floorMod(k, 2) == 1;
            long ticks = (elapsedMillis - thrownAt) / 50;
            long endedAtTick = -1;
            for (long tick = 0; tick <= Math.min(ticks, life + 1); tick++) {
                double[] you = queenPlayerAt(thrownAt + tick * 50);
                if (tick > life) {
                    endedAtTick = tick;
                    break;
                }
                double dx = you[0] - x;
                double dz = you[1] - z;
                double d = Math.max(1e-6, Math.hypot(dx, dz));
                if (swingable && d < QUEEN_SWING_REACH_BLOCKS && tick > 40) {
                    swingable = false;
                    double push = 0.15 + QUEEN_SWING_DAMAGE / 8.0;
                    vx = vx / 2 - dx / d * push;
                    vz = vz / 2 - dz / d * push;
                    life -= (long) (QUEEN_SWING_DAMAGE * 10);
                } else {
                    vx = vx * 0.9 + dx / d * 0.02;
                    vz = vz * 0.9 + dz / d * 0.02;
                }
                x = clampRoom(x + vx, QUEEN_ROOM_HALF_X - 0.3);
                z = clampRoom(z + vz, QUEEN_ROOM_HALF_Z - 0.3);
            }
            if (endedAtTick < 0) {
                crystals.add(new ValkyrieQueenEffect.Crystal((int) (k + 2_000_000), x, z));
            } else if (elapsedMillis - (thrownAt + endedAtTick * 50) < QUEEN_BOLT_MILLIS) {
                bolts.add(new ValkyrieQueenEffect.Bolt((int) (k + 3_000_000), x, z));
            }
        }
    }

    private static double witherHealth(double progress) {
        return 1 - (progress - WITHER_SUMMON_SHARE) / (1 - WITHER_SUMMON_SHARE);
    }

    private static void driveWither(EffectController controller, Memo memo,
                                    double progress, long elapsedMillis) {
        WitherEffect w = (WitherEffect) controller;
        w.setColors(memo.colors.get(0), memo.colors.get(1), memo.colors.get(2));

        if (progress < WITHER_SUMMON_SHARE) {
            // Counts down, exactly as getInvulnerableTicks() does.
            double charge = progress / WITHER_SUMMON_SHARE;
            w.setSummoning((int) Math.round(WITHER_SUMMON_TICKS * (1 - charge)));
            return;
        }

        double health = witherHealth(progress);
        boolean powered = health <= 0.5; // vanilla's own rule: armour at half
        // The centre head holds its target and the side heads pick up their
        // own, so the three of them read as three heads rather than one
        // synchronised stare.
        boolean[] locked = {
                Math.floorMod(elapsedMillis, 5200L) < 2600L,
                true,
                powered || Math.floorMod(elapsedMillis, 7100L) < 3500L,
        };
        w.setFight(health, powered, locked);

        // One skull per interval, from each head in turn, fired on the change
        // of interval and never per frame. See Memo.
        int shot = (int) Math.floorDiv(elapsedMillis, WITHER_SKULL_INTERVAL);
        if (shot != memo.count) {
            memo.count = shot;
            w.fireSkull(Math.floorMod(shot, 3), elapsedMillis);
        }
    }

    private static double dragonHealth(double progress) {
        return 1 - (progress - DRAGON_RITUAL_SHARE) / (DRAGON_FIGHT_END - DRAGON_RITUAL_SHARE);
    }

    private static void driveDragon(EffectController controller, Memo memo,
                                    double progress, long elapsedMillis) {
        EnderDragonEffect d = (EnderDragonEffect) controller;
        d.setColors(memo.colors.get(0), memo.colors.get(1));
        d.setBreathFireColor(memo.colors.get(2));

        if (progress < DRAGON_RITUAL_SHARE) {
            double completion = progress / DRAGON_RITUAL_SHARE;
            // Towers light one at a time, at the even spacing of the real
            // respawn ritual rather than anything measured off a transcript.
            int lit = Math.min(DRAGON_TOWERS, (int) Math.floor(completion * DRAGON_TOWERS));
            double[] bearings = new double[lit];
            for (int i = 0; i < lit; i++) {
                bearings[i] = 2 * Math.PI * i / DRAGON_TOWERS;
            }
            boolean hasSweep = lit < DRAGON_TOWERS;
            double sweep = 2 * Math.PI * lit / DRAGON_TOWERS
                    + 0.45 * Math.sin(elapsedMillis / 700.0);
            d.setRitual(bearings, sweep, hasSweep, completion > 0.7, completion);
            // The kick belongs to the moment the sweep jumps to a new tower,
            // which is a change and not a state, so it fires on the change.
            if (lit != memo.count) {
                memo.count = lit;
                d.pulseSurge(elapsedMillis);
            }
            return;
        }

        if (progress < DRAGON_FIGHT_END) {
            EnderDragonEffect.Pose pose = DRAGON_POSES[Math.floorMod(
                    Math.floorDiv(elapsedMillis, DRAGON_POSE_MILLIS), DRAGON_POSES.length)];
            dragonPose(d, dragonHealth(progress), pose, elapsedMillis);
            return;
        }

        d.setBreathFire(false, 0.5, 0);
        d.setDying((progress - DRAGON_FIGHT_END) / (1 - DRAGON_FIGHT_END));
    }

    /** One frame of the fight in one pose, breath pool included. Colours are the caller's job. */
    private static void dragonPose(EnderDragonEffect d, double health, EnderDragonEffect.Pose pose,
                                   long elapsedMillis) {
        d.setFight(health, pose);
        // In game the breath pool is driven by the real dragon's-breath
        // cloud rather than by the pose — but that cloud only exists
        // because of one of the fire attacks, so the script lights it under
        // the pose that throws one.
        boolean burning = pose == EnderDragonEffect.Pose.BREATHING;
        d.setBreathFire(burning, 0.5 + 0.22 * Math.sin(elapsedMillis / 1900.0),
                burning ? 0.7 : 0);
    }

    /**
     * The dragon frozen in one pose for the rest of time, which it would
     * honestly love. Progress is health, so the wounded wingbeat and the
     * glow going flame-coloured are still on the slider.
     */
    private static Stage dragonStage(String name, String doing, EnderDragonEffect.Pose pose) {
        return new Stage(name,
                (controller, memo, progress, elapsedMillis) -> {
                    EnderDragonEffect d = (EnderDragonEffect) controller;
                    d.setColors(memo.colors.get(0), memo.colors.get(1));
                    d.setBreathFireColor(memo.colors.get(2));
                    dragonPose(d, 1 - progress, pose, elapsedMillis);
                },
                progress -> String.format("ender dragon / %s - health %.0f%%, %s", name,
                        (1 - progress) * 100, doing));
    }

    /** Hover, land, hover, land. See {@link #DRAGON_LANDING_REPLAY_MILLIS}. */
    private static void driveDragonLanding(EffectController controller, Memo memo,
                                           double progress, long elapsedMillis) {
        EnderDragonEffect d = (EnderDragonEffect) controller;
        d.setColors(memo.colors.get(0), memo.colors.get(1));
        d.setBreathFireColor(memo.colors.get(2));
        boolean hovering = Math.floorMod(elapsedMillis, DRAGON_LANDING_REPLAY_MILLIS) < DRAGON_LANDING_HOVER_MILLIS;
        dragonPose(d, 1 - progress,
                hovering ? EnderDragonEffect.Pose.HOVER : EnderDragonEffect.Pose.LANDING, elapsedMillis);
    }

    /**
     * Puts the emergence envelope at {@code progress} through itself, by
     * back-dating the trigger.
     *
     * <p>The effect times everything from when it was triggered, so the way to
     * show its midpoint is to claim it started half an envelope ago.
     * Retriggered every frame, which is cheap and has nothing to lose:
     * {@code RingContractPattern} is a pure function of elapsed time, so the
     * ring the retrigger rebuilds is the ring it replaced. It is also how the
     * colour gets in, there being no setter for it.
     *
     * <p>{@code waitForRelease} is false — the path taken by a Warden that
     * merely walked into range. The other path holds open until the game says
     * the thing is out, which is a wait on an event this tool cannot supply.
     */
    private static void driveWarden(EffectController controller, Memo memo,
                                    double progress, long elapsedMillis) {
        WardenEmergenceEffect w = (WardenEmergenceEffect) controller;
        long envelope = WARDEN_MIN_HOLD_MILLIS + WARDEN_FADE_MILLIS;
        long triggeredAt = elapsedMillis - (long) (progress * envelope);
        w.trigger(triggeredAt, memo.colors.get(0), WARDEN_RING_THICKNESS_KEYS,
                WARDEN_PULSE_MILLIS, WARDEN_MIN_HOLD_MILLIS, WARDEN_FADE_MILLIS,
                WARDEN_MAX_MILLIS, WARDEN_NOMINAL_MILLIS, false);
    }

    // ---------------------------------------------------------------
    // The adapter
    // ---------------------------------------------------------------

    /**
     * A boss theme as a {@link Pattern}, so the rest of the tuner does not have
     * to know it is anything else.
     *
     * <p>This is what lets the live view, the loop search, the crossfade and
     * the GIF writer take boss themes without a line of change: they all
     * consume a Pattern, and this is one. It also reproduces the one thing the
     * compositor does that a pattern never has to — scaling the layer by
     * {@code layerOpacity}, which is how the Naga's fade in and out is
     * expressed and would otherwise simply not appear here.
     */
    static Pattern pattern(Theme theme, Colors colors, ProgressSource progress) {
        return new BossPattern(theme, colors, progress, null);
    }

    /**
     * The same, drawn over a backdrop. For the overlay themes, which in game
     * are always laid over whatever the base layer is showing, and over black
     * would show nothing of what makes them work: rain that lets the biome
     * through between drops, water with the biome still visible above the
     * line.
     */
    static Pattern pattern(Theme theme, Colors colors, ProgressSource progress, Backdrop backdrop) {
        return new BossPattern(theme, colors, progress, backdrop);
    }

    /**
     * What an overlay theme is drawn over: a biome's own pattern and its two
     * colours.
     *
     * @param accent null to let the context derive one, as a biome with no
     *               accent colour does in game
     */
    record Backdrop(Pattern pattern, RGBColor base, RGBColor accent) {
    }

    private static final class BossPattern implements Pattern {

        /**
         * How far ahead of the first frame to start the script.
         *
         * <p>For the Naga this is the difference between a GIF that opens on a
         * snake and one that opens on a black board: its presence ramps up over
         * half a second from the first call it receives, so a recording
         * starting at the same instant as the script catches the whole fade.
         * One drive call dated earlier gets the ramp finished before frame one.
         * Harmless to the others, whose scripts set state rather than start
         * anything.
         */
        private static final long WARMUP_MILLIS = 2500;

        private final Theme theme;
        private final EffectController controller;
        private final Memo memo;
        private final ProgressSource progress;
        private final Backdrop backdrop;
        private boolean warmed;

        BossPattern(Theme theme, Colors colors, ProgressSource progress, Backdrop backdrop) {
            this.theme = theme;
            this.controller = theme.builder().build(colors);
            this.memo = new Memo(colors);
            this.progress = progress;
            this.backdrop = backdrop;
        }

        @Override
        public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
            double p = Math.max(0, Math.min(1, progress.at(elapsedMillis)));
            if (!warmed) {
                warmed = true;
                theme.driver().drive(controller, memo, p, elapsedMillis - WARMUP_MILLIS);
            }
            theme.driver().drive(controller, memo, p, elapsedMillis);

            Map<KeyGrid.LedRef, LayerPixel> px = controller.render(ctx.grid(), elapsedMillis);
            double opacity = controller.layerOpacity(elapsedMillis);
            if (backdrop != null) return overBackdrop(ctx, px, opacity, elapsedMillis);
            if (opacity >= 1.0) return px;
            Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>(px.size());
            px.forEach((ref, pixel) -> out.put(ref, pixel.scaledAlpha(opacity)));
            return out;
        }

        /**
         * The layer composited over the backdrop exactly as the compositor
         * does it — each pixel's alpha scaled by the layer's opacity, then laid
         * over what is beneath — and handed back fully opaque, since the
         * backdrop has already covered every key.
         */
        private Map<KeyGrid.LedRef, LayerPixel> overBackdrop(PatternContext ctx, Map<KeyGrid.LedRef, LayerPixel> px,
                                                            double opacity, long elapsedMillis) {
            PatternContext under = new PatternContext(ctx.grid(), null, backdrop.base(), backdrop.accent(),
                    0, ctx.params());
            Map<KeyGrid.LedRef, LayerPixel> scene = backdrop.pattern().render(under, elapsedMillis);
            Map<KeyGrid.LedRef, LayerPixel> out = new HashMap<>();
            for (KeyGrid.LedPosition key : ctx.grid().allKeys()) {
                LayerPixel base = scene.get(key.ref());
                RGBColor beneath = base == null ? RGBColor.BLACK : base.over(RGBColor.BLACK);
                LayerPixel top = px.get(key.ref());
                RGBColor color = top == null ? beneath : top.scaledAlpha(opacity).over(beneath);
                out.put(key.ref(), new LayerPixel(color, 1.0));
            }
            return out;
        }
    }
}
