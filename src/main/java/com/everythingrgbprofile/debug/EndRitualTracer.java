package com.everythingrgbprofile.debug;

import com.everythingrgbprofile.RGBProfileMod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes a transcript of an Ender Dragon summoning ritual, so the lighting can
 * be built to match what actually happens rather than to a guess.
 *
 * <p>YUNG's Better End Island runs the respawn as a five-stage sequence
 * ({@code START → PREPARING_TO_SUMMON_PILLARS → SUMMONING_PILLARS →
 * SUMMONING_DRAGON → END}) but keeps that state server-side and ships no
 * packets, so a client can only infer the choreography from the end crystals
 * it can see. This class records exactly what those crystals do, in enough
 * detail to reconstruct the sequence afterwards:
 *
 * <ul>
 *   <li><b>Which</b> crystal — entity id, plus its distance and bearing from
 *       the world origin, which is what separates the central structure from
 *       the ring of obsidian pillars and identifies <i>which</i> pillar.</li>
 *   <li><b>When</b> — every line is stamped relative to the first beam, since
 *       relative timing is the whole point.</li>
 *   <li><b>Where it points</b> — classified as SKY (straight up, the central
 *       crystals' opening beams), CENTRE (in at the middle of the island, the
 *       pillars firing), or a described OTHER so nothing is silently binned.</li>
 * </ul>
 *
 * <p>Everything is logged on CHANGE only, with a periodic snapshot while beams
 * are live. Crystals hold their beams for many seconds; logging per tick would
 * bury the transitions that matter in thousands of identical lines.
 *
 * <p>{@code EndRitualPattern} was fitted to transcripts from this class. It
 * only runs when ritual tracing or diagnostics are switched on in the config,
 * and is kept
 * so the fit can be re-checked if Better End Island changes its ritual.
 * Nothing depends on it but the poll that feeds it, so it can be deleted
 * without consequence.
 */
public final class EndRitualTracer {

    private static final long SNAPSHOT_INTERVAL_MILLIS = 2000;
    /** Horizontal distance from (0,0) inside which something counts as "the middle". */
    private static final double CENTRE_RADIUS = 12.0;

    private static final class CrystalState {
        double x, y, z;
        BlockPos target;
        boolean everBeamed;
        long firstSeenMillis;
    }

    private static final Map<Integer, CrystalState> known = new HashMap<>();
    private static long ritualStartMillis = 0;
    private static long lastSnapshotMillis = 0;
    private static int peakSimultaneous = 0;
    private static int skyBeams = 0;
    private static int centreBeams = 0;
    private static final Set<Integer> beamedIds = new HashSet<>();

    private static int lastBreathCloudId = Integer.MIN_VALUE;
    private static int lastFireballCount = 0;
    private static long lastBreathLogMillis = 0;

    private static boolean dragonSeen = false;
    private static long dragonSeenMillis = 0;
    private static String lastPhase = null;

    /** Forget everything. Call on world unload and on leaving the End. */
    public static void reset() {
        if (!known.isEmpty() || dragonSeen) {
            known.clear();
            beamedIds.clear();
            ritualStartMillis = 0;
            lastSnapshotMillis = 0;
            peakSimultaneous = 0;
            skyBeams = 0;
            centreBeams = 0;
            dragonSeen = false;
            dragonSeenMillis = 0;
            lastPhase = null;
            lastBreathCloudId = Integer.MIN_VALUE;
            lastFireballCount = 0;
            lastBreathLogMillis = 0;
        }
    }

    /**
     * One observation. Pass every {@link EndCrystal} the client can currently
     * see and the dragon if there is one.
     */
    public static void observe(List<EndCrystal> crystals, EnderDragon dragon,
                               AreaEffectCloud breathCloud, int fireballs, long nowMillis) {
        // --- the fire attacks --------------------------------------------
        // Both of the dragon's fire attacks end in a DRAGON_BREATH cloud, so
        // this is what the keyboard fire is actually driven by. Logged so it
        // can be confirmed rather than guessed at.
        if (fireballs != lastFireballCount) {
            if (fireballs > lastFireballCount) {
                RGBProfileMod.LOGGER.info("[end] dragon FIREBALL in flight ({} tracked)", fireballs);
            }
            lastFireballCount = fireballs;
        }
        int cloudId = breathCloud == null ? Integer.MIN_VALUE : breathCloud.getId();
        if (cloudId != lastBreathCloudId) {
            if (breathCloud == null) {
                RGBProfileMod.LOGGER.info("[end] breath cloud gone — keyboard fire dying down");
            } else {
                RGBProfileMod.LOGGER.info(
                        "[end] breath cloud #{} at ({},{},{}) radius {} — keyboard fire lit",
                        cloudId, (int) breathCloud.getX(), (int) breathCloud.getY(), (int) breathCloud.getZ(),
                        String.format("%.1f", breathCloud.getRadius()));
            }
            lastBreathCloudId = cloudId;
            lastBreathLogMillis = nowMillis;
        } else if (breathCloud != null && nowMillis - lastBreathLogMillis >= 2000) {
            lastBreathLogMillis = nowMillis;
            RGBProfileMod.LOGGER.info("[end] breath cloud #{} still burning, radius {}",
                    cloudId, String.format("%.1f", breathCloud.getRadius()));
        }

        Set<Integer> present = new HashSet<>();
        int beamingNow = 0;

        for (EndCrystal crystal : crystals) {
            int id = crystal.getId();
            present.add(id);
            BlockPos target = crystal.getBeamTarget();
            if (target != null) beamingNow++;

            CrystalState state = known.get(id);
            if (state == null) {
                state = new CrystalState();
                state.x = crystal.getX();
                state.y = crystal.getY();
                state.z = crystal.getZ();
                state.firstSeenMillis = nowMillis;
                known.put(id, state);
                log(nowMillis, "crystal APPEARED  %s%s", describe(crystal),
                        target == null ? "" : "  (already beaming -> " + classify(crystal, target) + ")");
            }

            boolean was = state.target != null;
            boolean is = target != null;
            if (!was && is) {
                if (ritualStartMillis == 0) {
                    ritualStartMillis = nowMillis;
                    RGBProfileMod.LOGGER.info("[end] ===== RITUAL START (first beam) =====");
                }
                String kind = classify(crystal, target);
                if ("SKY".equals(kind)) skyBeams++;
                else if ("CENTRE".equals(kind)) centreBeams++;
                beamedIds.add(id);
                state.everBeamed = true;
                log(nowMillis, "beam ON   %s  -> %s  target=(%d,%d,%d)",
                        describe(crystal), kind, target.getX(), target.getY(), target.getZ());
            } else if (was && !is) {
                log(nowMillis, "beam OFF  %s", describe(crystal));
            } else if (was && !state.target.equals(target)) {
                log(nowMillis, "beam MOVED %s -> %s  target=(%d,%d,%d)",
                        describe(crystal), classify(crystal, target), target.getX(), target.getY(), target.getZ());
            }
            state.target = target;
        }

        // Gone: destroyed, or out of client tracking range.
        List<Integer> vanished = new ArrayList<>();
        for (Map.Entry<Integer, CrystalState> entry : known.entrySet()) {
            if (!present.contains(entry.getKey())) vanished.add(entry.getKey());
        }
        for (Integer id : vanished) {
            CrystalState state = known.remove(id);
            log(nowMillis, "crystal GONE      #%d at (%.0f,%.0f,%.0f) %s  (beamed at some point: %s)",
                    id, state.x, state.y, state.z, where(state.x, state.z), state.everBeamed);
        }

        if (beamingNow > peakSimultaneous) peakSimultaneous = beamingNow;

        // Steady-state snapshot, so simultaneous counts and hold durations are
        // visible and not just the edges.
        if (beamingNow > 0 && nowMillis - lastSnapshotMillis >= SNAPSHOT_INTERVAL_MILLIS) {
            lastSnapshotMillis = nowMillis;
            StringBuilder sb = new StringBuilder();
            for (EndCrystal crystal : crystals) {
                BlockPos target = crystal.getBeamTarget();
                if (target == null) continue;
                sb.append(" | #").append(crystal.getId()).append(' ')
                        .append(where(crystal.getX(), crystal.getZ()))
                        .append(" -> ").append(classify(crystal, target));
            }
            log(nowMillis, "snapshot  beaming=%d  peak=%d  crystalsTracked=%d%s",
                    beamingNow, peakSimultaneous, known.size(), sb);
        }

        // --- dragon -------------------------------------------------------
        if (dragon == null) {
            return;
        }
        if (!dragonSeen) {
            dragonSeen = true;
            dragonSeenMillis = nowMillis;
            RGBProfileMod.LOGGER.info(
                    "[end] ===== DRAGON APPEARED ===== ritual ran {}ms | peak simultaneous beams {} | "
                            + "{} distinct crystals beamed | SKY beams {} | CENTRE beams {}",
                    ritualStartMillis == 0 ? -1 : (nowMillis - ritualStartMillis),
                    peakSimultaneous, beamedIds.size(), skyBeams, centreBeams);
        }
        String phase = dragon.getPhaseManager().getCurrentPhase().getPhase().toString();
        if (!phase.equals(lastPhase)) {
            lastPhase = phase;
            RGBProfileMod.LOGGER.info("[end] +{}ms dragon phase -> {}  health {}/{}  deathTime {}",
                    nowMillis - dragonSeenMillis, phase,
                    String.format("%.1f", dragon.getHealth()),
                    String.format("%.1f", dragon.getMaxHealth()),
                    dragon.dragonDeathTime);
        }
    }

    /** "#123 pillar r=43 brg=036" or "#123 centre r=2" — bearing identifies which pillar. */
    private static String describe(EndCrystal crystal) {
        return String.format("#%d (%.0f,%.0f,%.0f) %s",
                crystal.getId(), crystal.getX(), crystal.getY(), crystal.getZ(),
                where(crystal.getX(), crystal.getZ()));
    }

    private static String where(double x, double z) {
        double r = Math.hypot(x, z);
        if (r < CENTRE_RADIUS) return String.format("centre r=%.0f", r);
        double bearing = (Math.toDegrees(Math.atan2(z, x)) + 360) % 360;
        return String.format("pillar r=%.0f brg=%03.0f", r, bearing);
    }

    /**
     * SKY = straight up from the crystal. CENTRE = in at the middle of the
     * island. Anything else is described rather than discarded, because an
     * unexpected third kind of beam is exactly the thing worth knowing about.
     */
    private static String classify(EndCrystal crystal, BlockPos target) {
        double dx = target.getX() - crystal.getX();
        double dy = target.getY() - crystal.getY();
        double dz = target.getZ() - crystal.getZ();
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < 4 && dy > 8) return "SKY";
        if (Math.hypot(target.getX(), target.getZ()) < CENTRE_RADIUS) return "CENTRE";
        return String.format("OTHER(dh=%.0f dy=%.0f)", horizontal, dy);
    }

    private static void log(long nowMillis, String format, Object... args) {
        long t = ritualStartMillis == 0 ? 0 : nowMillis - ritualStartMillis;
        RGBProfileMod.LOGGER.info("[end] +{}ms {}", t, String.format(format, args));
    }

    private EndRitualTracer() {
    }
}
