package com.everythingrgbprofile.pattern.patterns;

import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.LightBudget;
import com.everythingrgbprofile.pattern.Pattern;
import com.everythingrgbprofile.pattern.PatternContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A raid, drawn the way Terraria draws a goblin army: a horde glowing up from
 * the bottom of the board with an army marching across it.
 *
 * <p>Terraria's invasions and Minecraft's raids are the same event in
 * different clothes — an army that comes in waves until you break it — so the
 * look is borrowed whole. The army is scenery, not a map: it says a raid is on
 * and how it is going, not where anyone is standing.
 *
 * <ul>
 *   <li><b>The omen</b>, counting down to the raid: a heartbeat in the horde's
 *       colour that quickens as time runs out, and the army marching onto the
 *       board from the left, its front line reaching the far side as the raid
 *       begins.</li>
 *   <li><b>The raid</b>: the horde band along the bottom rows, flickering like
 *       an army's torches, and two ranks of raiders marching across it — the
 *       rank behind dimmer and slower, so the army has depth. A captain
 *       carries a white banner over its head, a Ravager is twice as wide, and
 *       an Evoker has a purple head.</li>
 *   <li><b>The horn</b>, when a wave arrives: a blast of the horde's colour
 *       behind a pale leading edge, draining down the board into the band,
 *       the army's heads flashing white with it.</li>
 *   <li><b>Victory</b>: the army breaks and runs off the board, the horde
 *       drains away, and fireworks go up in the green of Hero of the Village,
 *       as the villagers set them off in game.</li>
 *   <li><b>Defeat</b>: the army stops where it stands and jumps, which is how
 *       raiders celebrate, while the horde flares and throbs.</li>
 * </ul>
 */
public final class RaidHordePattern implements Pattern {

    /** What the raid is doing. */
    public enum Phase {
        /** Raid Omen counting down to the raid starting. */
        OMEN,
        /** The raid is on. */
        RAID,
        VICTORY,
        DEFEAT
    }

    /** Where the horde band starts, in board heights from the top. */
    private static final double BAND_TOP = 0.45;

    /** Raiders per rank, and how fast each rank marches, in board widths per second. */
    private static final int FRONT_RANK = 6;
    private static final int BACK_RANK = 5;
    private static final double FRONT_SPEED = 0.05;
    private static final double BACK_SPEED = 0.035;
    /** Steps per second, for the bob of the heads. */
    private static final double STEP_RATE = 1.8;

    private static final double HORN_MILLIS = 1200;
    /** How long the army takes to run off the board after a victory. */
    private static final double ROUT_MILLIS = 1600;
    private static final double FIREWORK_INTERVAL_MILLIS = 480;
    private static final double FIREWORK_MILLIS = 760;
    /** After this long the victory fireworks carry on, but quieter. */
    private static final double VICTORY_LOUD_MILLIS = 8000;
    private static final double DEFEAT_FLASH_MILLIS = 900;

    private static final RGBColor SPELL_PURPLE = RGBColor.fromHex("#A040FF");
    private static final RGBColor RAVAGER_GREY = RGBColor.fromHex("#8C8C96");
    private static final RGBColor FIREWORK_GOLD = RGBColor.fromHex("#FFC830");

    private volatile Phase phase = Phase.RAID;
    private volatile double omenProgress = 0;
    private volatile double band = 0;
    private volatile long phaseStartedAtMillis = Long.MIN_VALUE;
    private volatile long hornAtMillis = Long.MIN_VALUE;
    private volatile RGBColor victoryColor = RGBColor.WHITE;

    /**
     * @param omenProgress 0 to 1 through the omen's countdown; ignored in other phases
     * @param band         0 to 1, how strongly the horde glows, eased by the caller
     * @param startedAt    when this phase began, which victory and defeat time their animations from
     */
    public void setState(Phase phase, double omenProgress, double band, long startedAt) {
        this.phase = phase;
        this.omenProgress = clamp(omenProgress);
        this.band = clamp(band);
        this.phaseStartedAtMillis = startedAt;
    }

    public void setVictoryColor(RGBColor victory) {
        if (victory != null) this.victoryColor = victory;
    }

    /** The raid horn blew. */
    public void horn(long nowMillis) {
        this.hornAtMillis = nowMillis;
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(PatternContext ctx, long elapsedMillis) {
        KeyGrid grid = ctx.grid();
        List<KeyGrid.LedPosition> keys = grid.allKeys();
        double aspect = Math.max(0.05, grid.aspectRatio());
        double keyX = Math.max(1e-4, grid.keyWidthNormalised());
        double keyY = keyX / aspect;
        double seconds = elapsedMillis / 1000.0;

        RGBColor horde = ctx.baseColor();
        RGBColor raider = ctx.resolvedAccentColor();
        Phase phase = this.phase;
        double sincePhase = phaseStartedAtMillis == Long.MIN_VALUE ? 1e9 : elapsedMillis - phaseStartedAtMillis;

        double topY = 1;
        double bottomY = 0;
        for (KeyGrid.LedPosition key : keys) {
            topY = Math.min(topY, key.y());
            bottomY = Math.max(bottomY, key.y());
        }

        LightBudget budget = new LightBudget();

        // --- the army ----------------------------------------------------
        // Drawn first, into its own budget, so the horde band can be dimmed
        // behind each raider. An earlier version added the band and the army
        // onto the same keys, and a pale head over a red band summed to peach:
        // the raiders dissolved into the torchlight instead of standing in
        // front of it.
        double horn = decay(hornAtMillis, elapsedMillis, HORN_MILLIS);
        LightBudget army = new LightBudget();
        Map<KeyGrid.LedRef, Double> cover = new HashMap<>();
        if (phase != Phase.VICTORY || sincePhase < ROUT_MILLIS) {
            drawRank(army, cover, keys, keyX, keyY, bottomY - keyY, BACK_RANK, BACK_SPEED, 0.37, 0.4, false,
                    phase, sincePhase, seconds, horn, raider);
            drawRank(army, cover, keys, keyX, keyY, bottomY, FRONT_RANK, FRONT_SPEED, 0.0, 1.0, true,
                    phase, sincePhase, seconds, horn, raider);
        }

        // --- the horde ---------------------------------------------------
        // Torchlight rather than a smooth gradient: each key flickers on its
        // own slow clock, so the band reads as an army's fires rather than a
        // fill level.
        double bandLevel = band;
        if (phase == Phase.OMEN) {
            // A heartbeat that quickens as the countdown runs out.
            double rate = 0.6 + 1.9 * omenProgress * omenProgress;
            double beat = Math.pow(Math.max(0, Math.sin(2 * Math.PI * rate * seconds)), 6);
            bandLevel *= 0.55 + 0.9 * beat;
        } else if (phase == Phase.DEFEAT) {
            bandLevel *= 0.8 + 0.2 * Math.sin(2 * Math.PI * 0.9 * seconds);
        }
        for (KeyGrid.LedPosition key : keys) {
            budget.add(key.ref(), horde, 0.035);
            double t = (key.y() - BAND_TOP) / Math.max(1e-6, 1 - BAND_TOP);
            if (t <= 0 || bandLevel <= 0.001) continue;
            double flicker = 0.75 + 0.25 * Math.sin(2 * Math.PI * (0.6 * seconds + hash(key.ref()) * 6.0));
            double behind = 1 - 0.85 * cover.getOrDefault(key.ref(), 0.0);
            budget.add(key.ref(), horde, Math.pow(Math.min(1, t), 1.4) * flicker * bandLevel * behind);
        }
        budget.addLayer(army.resolve(), 1.0);

        // The omen's front line: a bright edge where the army has reached.
        if (phase == Phase.OMEN && omenProgress > 0.01 && omenProgress < 0.99) {
            double front = omenProgress;
            for (KeyGrid.LedPosition key : keys) {
                if (key.y() < BAND_TOP) continue;
                double edge = Math.abs(key.x() - front) / keyX;
                if (edge >= 1.2) continue;
                double pulse = 0.7 + 0.3 * Math.sin(2 * Math.PI * 2 * seconds);
                budget.add(key.ref(), horde.lightened(0.45), (1 - edge / 1.2) * 0.9 * pulse);
            }
        }

        // --- the horn ----------------------------------------------------
        // A blast in the horde's colour, draining down from the top into the
        // band as it fades, led by a pale edge. An earlier version washed the
        // whole board in a pale tint of the horde, which hid the army and read
        // as a white flash rather than a horn.
        if (horn > 0) {
            RGBColor edgeColor = horde.lightened(0.6);
            double front = topY + (1 - horn) * (bottomY - topY + keyY);
            for (KeyGrid.LedPosition key : keys) {
                double below = (key.y() - front) / keyY;
                if (below < -0.6) continue;
                if (below < 0) {
                    budget.add(key.ref(), edgeColor, (1 + below / 0.6) * horn * 0.9);
                } else {
                    double behind = 1 - 0.85 * cover.getOrDefault(key.ref(), 0.0);
                    budget.add(key.ref(), edgeColor, Math.max(0, 1 - below) * horn * 0.9);
                    budget.add(key.ref(), horde, horn * 0.55 * behind);
                }
            }
        }

        // --- victory -----------------------------------------------------
        if (phase == Phase.VICTORY) {
            double loudness = sincePhase < VICTORY_LOUD_MILLIS ? 1 : 0.45;
            long latest = (long) Math.floor(sincePhase / FIREWORK_INTERVAL_MILLIS);
            for (long k = latest; k >= 0 && k >= latest - 2; k--) {
                double age = (sincePhase - k * FIREWORK_INTERVAL_MILLIS) / FIREWORK_MILLIS;
                if (age < 0 || age >= 1) continue;
                double fx = 0.08 + 0.84 * noise(k, 1);
                double fy = topY + (bottomY - topY) * (0.15 + 0.55 * noise(k, 2));
                RGBColor color = switch ((int) (noise(k, 3) * 3)) {
                    case 0 -> victoryColor;
                    case 1 -> FIREWORK_GOLD;
                    default -> victoryColor.lerp(RGBColor.WHITE, 0.4);
                };
                double radius = 0.4 + 2.6 * Math.sqrt(age);
                double strength = (1 - age) * (1 - age) * loudness;
                for (KeyGrid.LedPosition key : keys) {
                    double e = Math.hypot((key.x() - fx) / keyX, (key.y() - fy) / keyY);
                    double off = Math.abs(e - radius);
                    if (off < 0.9) budget.add(key.ref(), color, (1 - off / 0.9) * 1.4 * strength);
                    if (age < 0.2 && e < 0.9) {
                        budget.add(key.ref(), RGBColor.WHITE, (1 - e / 0.9) * (1 - age / 0.2) * 1.2 * loudness);
                    }
                }
            }
            for (KeyGrid.LedPosition key : keys) budget.add(key.ref(), victoryColor, 0.05);
        }

        // --- defeat ------------------------------------------------------
        if (phase == Phase.DEFEAT && sincePhase < DEFEAT_FLASH_MILLIS) {
            double flash = 1 - sincePhase / DEFEAT_FLASH_MILLIS;
            for (KeyGrid.LedPosition key : keys) budget.add(key.ref(), horde, flash * flash * 0.9);
        }

        return budget.resolve();
    }

    /**
     * One rank of the army, marching left to right and wrapping round.
     *
     * @param feetY   the row the rank stands on
     * @param offset  where along the rank the first raider starts, as a share of the spacing
     * @param level   the rank's brightness, lower for the rank behind
     * @param banners whether captains carry their banners, left off the rank
     *                behind so its banners do not climb into the top rows
     * @param cover   filled in with how much of each key the army stands in
     *                front of, for dimming the horde behind it
     */
    private void drawRank(LightBudget budget, Map<KeyGrid.LedRef, Double> cover,
                          List<KeyGrid.LedPosition> keys, double keyX, double keyY,
                          double feetY, int count, double speed, double offset, double level, boolean banners,
                          Phase phase, double sincePhase, double seconds, double horn, RGBColor raider) {
        // Victory and defeat both stop the march where it stood, so the army
        // is measured from the moment the phase began.
        double marchSeconds = (phase == Phase.VICTORY || phase == Phase.DEFEAT)
                ? seconds - sincePhase / 1000.0 : seconds;
        double rout = phase == Phase.VICTORY ? Math.min(1, sincePhase / ROUT_MILLIS) : 0;

        for (int i = 0; i < count; i++) {
            double x = fraction((i + offset) / count + speed * marchSeconds);
            if (rout > 0) {
                // Breaking and running back the way they came.
                x -= rout * rout * 0.9;
            }
            // Only the part of the army that has marched on yet, during the omen.
            if (phase == Phase.OMEN && x > omenProgress) continue;
            double presence = level * (1 - rout);
            // Fade in and out at the board's edges instead of popping.
            presence *= Math.min(1, Math.min(x, 1 - x) / 0.04 + 0.2);
            if (presence <= 0.01) continue;

            boolean ravager = i == 2;
            boolean captain = i == 0 || i == 4;
            boolean evoker = i == 3;
            double halfWidth = ravager ? 0.95 : 0.42;

            // Defeat's jump is kept under a key high: any more and the ranks
            // tangle and the bottom rows turn to noise.
            double lift = phase == Phase.DEFEAT
                    ? Math.pow(Math.max(0, Math.sin(2 * Math.PI * 1.3 * seconds + i * 1.7)), 2) * 0.7
                    : 0.14 * Math.abs(Math.sin(2 * Math.PI * STEP_RATE * seconds + i * 0.9));
            double feet = feetY - lift * keyY;
            // Bodies are the raider colour turned down and heads at full, so a
            // raider reads as one figure in front of the horde.
            RGBColor body = ravager ? RAVAGER_GREY : raider;
            RGBColor head = evoker ? SPELL_PURPLE : ravager ? RAVAGER_GREY : raider;
            double bodyLevel = ravager ? 0.75 : 0.5;

            for (KeyGrid.LedPosition key : keys) {
                double dx = Math.abs(key.x() - x) / keyX;
                if (dx >= halfWidth + 0.55) continue;
                double across = dx <= halfWidth ? 1 : 1 - (dx - halfWidth) / 0.55;
                double up = (feet - key.y()) / keyY;
                if (up < -0.5 || up > 2.5) continue;
                int row = (int) Math.round(up);
                double rowWeight = 1 - Math.min(1, Math.abs(up - row) * 1.4);
                double f = across * rowWeight * presence;
                if (row == 0) {
                    budget.add(key.ref(), body, f * bodyLevel);
                } else if (row == 1) {
                    budget.add(key.ref(), head, f * 1.1);
                    if (horn > 0) budget.add(key.ref(), RGBColor.WHITE, f * horn * 0.9);
                } else if (banners && captain && !ravager) {
                    // The ominous banner, carried above the head.
                    budget.add(key.ref(), RGBColor.WHITE, f * 0.9);
                } else {
                    continue;
                }
                cover.merge(key.ref(), Math.min(1, f / Math.max(level, 1e-6)), Math::max);
            }
        }
    }

    private static double hash(KeyGrid.LedRef ref) {
        long h = (ref.luid() * 2654435761L) ^ (ref.deviceId() == null ? 0 : ref.deviceId().hashCode());
        h ^= (h >>> 15);
        h *= 0x2C1B3C6DL;
        h ^= (h >>> 12);
        return (h & 0xFFFF) / 65536.0;
    }

    /** A fixed pseudo-random number in [0, 1) for an index and a salt. */
    private static double noise(long index, int salt) {
        long h = (index * 0x9E3779B97F4A7C15L) ^ (salt * 0xC2B2AE3D27D4EB4FL);
        h ^= (h >>> 31);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        return ((h >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    /** 1 at the moment of the event falling to 0 over its length; 0 before and after. */
    private static double decay(long sinceMillis, long nowMillis, double lengthMillis) {
        if (sinceMillis == Long.MIN_VALUE) return 0;
        double age = (nowMillis - sinceMillis) / lengthMillis;
        return age < 0 || age >= 1 ? 0 : 1 - age;
    }

    private static double fraction(double v) {
        return v - Math.floor(v);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
