package com.everythingrgbprofile.effects.support;

import com.everythingrgbprofile.color.ColorPalette;
import com.everythingrgbprofile.color.RGBColor;
import com.everythingrgbprofile.keymap.KeyGrid;
import com.everythingrgbprofile.pattern.LayerPixel;
import com.everythingrgbprofile.pattern.PatternContext;
import com.everythingrgbprofile.pattern.PatternParams;
import com.everythingrgbprofile.pattern.patterns.ValkyrieQueenPattern;
import com.everythingrgbprofile.priority.EffectController;
import com.everythingrgbprofile.priority.EffectTier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Aether's Valkyrie Queen: her throne room on the keys, with her in it.
 *
 * <p>Same approach as {@link SliderEffect} and {@link SunSpiritEffect}, and
 * read the same way: position and health are vanilla and synced, whether the
 * fight is on comes from vanilla's boss overlay, and nothing from the Aether is
 * imported. See {@link ValkyrieQueenPattern} for the drawing.
 *
 * <h2>The fight, as the client sees it</h2>
 * <ul>
 *   <li><b>The fight starting</b> — a boss bar with music, as for the other
 *       two. Movement is no evidence here, unlike for them: she strolls about
 *       her room before anyone has challenged her. What does count is a
 *       teleport or a thunder crystal, because she only does either with a
 *       target, and she only takes a target once the fight is on. That covers
 *       watching someone else's fight, where the bar is never sent to you.</li>
 *   <li><b>Teleports</b> — every 450 ticks or so she vanishes and reappears
 *       seven blocks from her target. The client eases a mob's position toward
 *       where the server put it over a few ticks, so a teleport arrives as a
 *       short burst of speed no walk, jump or lunge of hers comes near. It
 *       starts when the speed goes past that and ends when it drops back.</li>
 *   <li><b>The lunge</b> — at the top of a jump she drops onto her target,
 *       falling while moving toward them. Nearly every jump she makes in a
 *       fight ends this way, because the lunge takes over whenever she passes
 *       the top of one within sixteen blocks of her target. Read as exactly
 *       that: off the floor, falling, and moving across it.</li>
 *   <li><b>Thunder crystals</b> — ordinary entities. A crystal turns into
 *       lightning 300 ticks after it was thrown, counted here from when it was
 *       first seen. Hitting one knocks it back faster than it can fly on its
 *       own, which is how a strike is recognised, and shortens its life by an
 *       amount the client cannot see; see the pattern for what is drawn
 *       then.</li>
 *   <li><b>Lightning</b> — vanilla lightning bolts, so where they land is
 *       exact.</li>
 * </ul>
 *
 * <h2>Finding the room</h2>
 * The Slider and the Sun Spirit wait in the middle of their rooms, so the first
 * place each is seen is taken as the centre. The Queen waits on a throne
 * against one wall, so the same guess would put the map half a room out. The
 * event layer finds the walls instead and hands them over through
 * {@link #setRoom} once they measure as the Silver Dungeon's boss room. Until
 * then — or for a Queen who is not in a dungeon at all — a window is kept round
 * her and dragged along behind her as for the Sun Spirit, and while the fight
 * is on behind you as well, because the fight shuts you in the room with her.
 */
public final class ValkyrieQueenEffect implements EffectController {

    /** One thunder crystal in flight, in world coordinates. */
    public record Crystal(int id, double x, double z) {
    }

    /** One lightning bolt, in world coordinates. */
    public record Bolt(int id, double x, double z) {
    }

    /** Half the room assumed until its walls are found. Half the longer side of the Silver Dungeon's. */
    private static final double FALLBACK_HALF_BLOCKS = 12.0;
    /** Anything further outside the room than this is not in it. */
    private static final double ROOM_MARGIN_BLOCKS = 0.5;
    /** Vanilla's lightning hurts everything within three blocks of where it lands. */
    private static final double BOLT_REACH_BLOCKS = 3.0;
    /** Height the lift is measured against: about the top of one of her jumps. */
    private static final double LIFT_REACH_BLOCKS = 3.0;

    /**
     * Horizontal blocks per second above which she is teleporting. Her
     * quickest movement of her own is the lunge at 0.3 blocks a tick, six a
     * second; a seven-block teleport eased over three ticks is well over forty.
     */
    private static final double TELEPORT_SPEED = 20.0;
    /** Further than any teleport of hers can take her in one sample: a reset or a command. */
    private static final double JUMP_BLOCKS = 40.0;
    /** Falling faster than this, in blocks per second, while moving across the floor, is a lunge. */
    private static final double LUNGE_FALL_SPEED = 2.5;
    private static final double LUNGE_ACROSS_SPEED = 2.5;
    /** Vertical speed that counts as being in the air, even before the floor under her is known. */
    private static final double AIRBORNE_CLIMB_SPEED = 1.5;
    private static final double VERTICAL_SMOOTHING_SECONDS = 0.12;
    /**
     * Horizontal blocks per second above which a crystal has been hit. It
     * cannot fly faster than about four on its own — it gains 0.02 blocks a
     * tick toward its target and keeps nine tenths of its speed — and a hit
     * sends it off at 0.15 blocks a tick plus an eighth of the damage, so a
     * seven-damage sword swing knocks it away at twenty. A bare hand can land
     * under the line and go unseen, which costs nothing worse than the fuse
     * running on as if it had not been hit.
     */
    private static final double CRYSTAL_STRUCK_SPEED = 6.0;
    /**
     * How long a teleport or a crystal counts as the fight being on without a
     * boss bar. Each comes round every 21 or 22 seconds, on its own clock.
     */
    private static final long EVIDENCE_MEANS_AWAKE_MILLIS = 25000;
    /** As for the Slider: vanilla's hurt immunity already spaces real hits at least this far apart. */
    private static final long MIN_HIT_GAP_MILLIS = 500;
    /** How long the board holds after her defeat before the fade starts, so the ring reaches the walls. */
    private static final long DEFEAT_HOLD_MILLIS = 2400;
    private static final double SMOOTHING = 0.10;

    private final int priority;
    private final ValkyrieQueenPattern queen = new ValkyrieQueenPattern();

    private volatile boolean present = false;
    private volatile long changedAtMillis = Long.MIN_VALUE;
    private volatile double presenceAtChange = 0;
    private volatile double fadeInSeconds = 0.5;
    private volatile double fadeOutSeconds = 1.5;

    private volatile RGBColor silverColor = ColorPalette.VALKYRIE_QUEEN_SILVER;
    private volatile RGBColor goldColor = ColorPalette.VALKYRIE_QUEEN_GOLD;
    private volatile RGBColor lightningColor = ColorPalette.VALKYRIE_QUEEN_LIGHTNING;

    // Everything below is only ever touched on the SDK worker thread, as in
    // SliderEffect.
    private boolean tracking = false;
    private boolean roomKnown = false;
    private double centreX, centreZ;
    private double halfX = FALLBACK_HALF_BLOCKS;
    private double halfZ = FALLBACK_HALF_BLOCKS;
    private double floorY;
    private double prevX, prevZ;
    private long prevAtMillis;
    private double curX, curY, curZ;
    private long curAtMillis;
    private double playerX, playerZ;
    private double health = 1.0;
    private boolean awake = false;
    private double acrossSpeed = 0;
    private double climbSpeed = 0;
    private double velX = 0;
    private double velZ = 0;
    private boolean teleporting = false;
    private long lastEvidenceAtMillis = Long.MIN_VALUE;
    private long lastHitAtMillis = Long.MIN_VALUE;
    private long defeatedAtMillis = Long.MIN_VALUE;

    private double shownIgnition = 0;
    private double shownSpread = 0;
    private double shownDive = 0;

    /** When each crystal was first seen, or {@code Long.MIN_VALUE} for one already flying when the room was found. */
    private final Map<Integer, Long> crystalBornAt = new HashMap<>();
    private final Set<Integer> crystalStruck = new HashSet<>();
    /** Last position and time per crystal id, for the speed of one knocked back. */
    private final Map<Integer, double[]> crystalLast = new HashMap<>();
    private boolean crystalsSeeded = false;
    private final Set<Integer> knownBolts = new HashSet<>();

    public ValkyrieQueenEffect(int priority) {
        this.priority = priority;
        queen.setMarker(0, 0, false, ColorPalette.VALKYRIE_QUEEN_PLAYER);
    }

    public void setColors(RGBColor silver, RGBColor gold, RGBColor lightning) {
        this.silverColor = silver;
        this.goldColor = gold;
        this.lightningColor = lightning;
    }

    public void setFadeTimes(double fadeInSeconds, double fadeOutSeconds) {
        this.fadeInSeconds = Math.max(0.05, fadeInSeconds);
        this.fadeOutSeconds = Math.max(0.05, fadeOutSeconds);
    }

    /**
     * One sighting of a living Valkyrie Queen.
     *
     * @param y         her feet, which is what her height off the floor is measured from
     * @param bossMusic whether a boss bar that plays music is on screen
     */
    public void setQueen(double x, double y, double z, double playerX, double playerZ,
                         double healthFraction, boolean bossMusic, long nowMillis) {
        double newHealth = Math.max(0, Math.min(1, healthFraction));
        if (!tracking) {
            tracking = true;
            if (!roomKnown) {
                centreX = x;
                centreZ = z;
                halfX = halfZ = FALLBACK_HALF_BLOCKS;
            }
            floorY = y;
            prevX = curX = x;
            curY = y;
            prevZ = curZ = z;
            prevAtMillis = curAtMillis = nowMillis;
            acrossSpeed = climbSpeed = velX = velZ = 0;
            teleporting = false;
            lastEvidenceAtMillis = Long.MIN_VALUE;
            lastHitAtMillis = Long.MIN_VALUE;
            health = newHealth;
            awake = bossMusic;
            shownIgnition = awake ? 1 : 0;
            shownSpread = shownDive = 0;
            crystalBornAt.clear();
            crystalStruck.clear();
            crystalLast.clear();
            crystalsSeeded = false;
            knownBolts.clear();
            queen.reset();
        } else {
            track(x, y, z, nowMillis);
        }

        if (defeatedAtMillis != Long.MIN_VALUE) {
            defeatedAtMillis = Long.MIN_VALUE;
            queen.restore();
        }
        this.playerX = playerX;
        this.playerZ = playerZ;
        if (y < floorY) floorY = y;

        boolean nowAwake = bossMusic
                || (lastEvidenceAtMillis != Long.MIN_VALUE
                && nowMillis - lastEvidenceAtMillis < EVIDENCE_MEANS_AWAKE_MILLIS);
        if (!roomKnown) followRoom(x, z, nowAwake);
        if (nowAwake && !awake) queen.awaken(nowMillis);
        // The hit that starts the fight also restores her to full health, so
        // it never reads as damage here — health goes up, not down.
        if (nowAwake && newHealth < health - 1e-4
                && (lastHitAtMillis == Long.MIN_VALUE || nowMillis - lastHitAtMillis >= MIN_HIT_GAP_MILLIS)) {
            lastHitAtMillis = nowMillis;
            queen.hurt(nowMillis);
        }
        awake = nowAwake;
        health = newHealth;
        flip(true, nowMillis);
    }

    /**
     * The inside of her room, in world coordinates: the faces of the walls,
     * not the wall blocks. Replaces the dragged window for as long as this
     * Queen is tracked.
     */
    public void setRoom(double minX, double minZ, double maxX, double maxZ) {
        if (maxX - minX < 2 || maxZ - minZ < 2) return;
        roomKnown = true;
        centreX = (minX + maxX) / 2;
        centreZ = (minZ + maxZ) / 2;
        halfX = (maxX - minX) / 2;
        halfZ = (maxZ - minZ) / 2;
    }

    /** The thunder crystals near her this tick. Ones outside the room are ignored. */
    public void setCrystals(List<Crystal> crystals, long nowMillis) {
        if (!tracking) return;
        int n = 0;
        double[] u = new double[crystals.size()];
        double[] v = new double[crystals.size()];
        long[] bornAt = new long[crystals.size()];
        boolean[] struck = new boolean[crystals.size()];
        Set<Integer> seen = new HashSet<>();
        for (Crystal c : crystals) {
            if (!inRoom(c.x(), c.z())) continue;
            seen.add(c.id());
            if (!crystalBornAt.containsKey(c.id())) {
                // The first batch after she is found only seeds the set, as
                // for the Sun Spirit's crystals: a crystal already in the air
                // has an age nobody saw start, and is drawn as unknown.
                crystalBornAt.put(c.id(), crystalsSeeded ? nowMillis : Long.MIN_VALUE);
                if (crystalsSeeded) lastEvidenceAtMillis = nowMillis;
            }
            double[] last = crystalLast.get(c.id());
            if (last != null && nowMillis > last[2] && !crystalStruck.contains(c.id())) {
                double dt = (nowMillis - last[2]) / 1000.0;
                if (dt < 0.5 && Math.hypot(c.x() - last[0], c.z() - last[1]) / dt > CRYSTAL_STRUCK_SPEED) {
                    crystalStruck.add(c.id());
                    queen.struck(u(c.x()), v(c.z()), nowMillis);
                }
            }
            crystalLast.put(c.id(), new double[]{c.x(), c.z(), nowMillis});
            u[n] = u(c.x());
            v[n] = v(c.z());
            bornAt[n] = crystalBornAt.get(c.id());
            struck[n] = crystalStruck.contains(c.id());
            n++;
        }
        crystalBornAt.keySet().retainAll(seen);
        crystalStruck.retainAll(seen);
        crystalLast.keySet().retainAll(seen);
        crystalsSeeded = true;
        queen.setCrystals(java.util.Arrays.copyOf(u, n), java.util.Arrays.copyOf(v, n),
                java.util.Arrays.copyOf(bornAt, n), java.util.Arrays.copyOf(struck, n));
    }

    /**
     * The lightning bolts near her this tick. A bolt id not seen before has
     * just landed. Unlike crystals there is no seeding pass: a bolt lasts a
     * few ticks, so one already there when she is found landed a moment ago.
     */
    public void setBolts(List<Bolt> bolts, long nowMillis) {
        if (!tracking) return;
        Set<Integer> seen = new HashSet<>();
        for (Bolt b : bolts) {
            if (!inRoom(b.x(), b.z())) continue;
            seen.add(b.id());
            if (knownBolts.contains(b.id())) continue;
            queen.strike(u(b.x()), v(b.z()), nowMillis);
            lastEvidenceAtMillis = nowMillis;
        }
        knownBolts.clear();
        knownBolts.addAll(seen);
    }

    /** Her health reached zero. The room opens, and the board holds long enough for that to be seen. */
    public void setDefeated(long nowMillis) {
        if (defeatedAtMillis != Long.MIN_VALUE) return;
        defeatedAtMillis = nowMillis;
        queen.defeated(nowMillis);
        // Scheduled rather than started, exactly as SliderEffect#setDead.
        flip(false, nowMillis + DEFEAT_HOLD_MILLIS);
    }

    /** She is out of range. The room fades; the next Queen seen is found afresh. */
    public void setGone(long nowMillis) {
        flip(false, nowMillis);
        tracking = false;
        roomKnown = false;
    }

    /** Off immediately, for the world going away. */
    public void clear() {
        present = false;
        changedAtMillis = Long.MIN_VALUE;
        presenceAtChange = 0;
        tracking = false;
        roomKnown = false;
        defeatedAtMillis = Long.MIN_VALUE;
        queen.reset();
    }

    private void track(double x, double y, double z, long nowMillis) {
        double dt = (nowMillis - curAtMillis) / 1000.0;
        if (dt <= 0) {
            curX = x;
            curY = y;
            curZ = z;
            return;
        }
        double across = Math.hypot(x - curX, z - curZ);
        double measured = across / dt;
        double departedX = curX;
        double departedZ = curZ;
        double climbed = (y - curY) / dt;
        double stepX = (x - curX) / dt;
        double stepZ = (z - curZ) / dt;
        prevX = curX;
        prevZ = curZ;
        prevAtMillis = curAtMillis;
        curX = x;
        curY = y;
        curZ = z;
        curAtMillis = nowMillis;

        if (dt > 0.5 || across > JUMP_BLOCKS) {
            // A gap in the samples, or a move no teleport of hers could make.
            // Neither is movement, and render should not glide her across the
            // room to her new spot.
            snapTo(x, y, z, nowMillis);
            if (teleporting) {
                teleporting = false;
                queen.arrived(nowMillis);
            }
            return;
        }

        if (measured > TELEPORT_SPEED) {
            if (!teleporting) {
                teleporting = true;
                lastEvidenceAtMillis = nowMillis;
                queen.vanished(u(departedX), v(departedZ), nowMillis);
            }
            snapTo(x, y, z, nowMillis);
            return;
        }
        if (teleporting) {
            teleporting = false;
            snapTo(x, y, z, nowMillis);
            queen.arrived(nowMillis);
            return;
        }

        double k = 1 - Math.exp(-dt / VERTICAL_SMOOTHING_SECONDS);
        acrossSpeed += (measured - acrossSpeed) * k;
        climbSpeed += (climbed - climbSpeed) * k;
        velX += (stepX - velX) * k;
        velZ += (stepZ - velZ) * k;
    }

    private void snapTo(double x, double y, double z, long nowMillis) {
        prevX = x;
        prevZ = z;
        prevAtMillis = nowMillis;
        acrossSpeed = climbSpeed = velX = velZ = 0;
    }

    /** Drags the fallback window along behind her, and behind you once the fight has shut you in. */
    private void followRoom(double x, double z, boolean fighting) {
        drag(x, z, FALLBACK_HALF_BLOCKS - 1);
        if (fighting) drag(playerX, playerZ, FALLBACK_HALF_BLOCKS - 1);
    }

    private void drag(double x, double z, double reach) {
        if (x > centreX + reach) centreX = x - reach;
        else if (x < centreX - reach) centreX = x + reach;
        if (z > centreZ + reach) centreZ = z - reach;
        else if (z < centreZ - reach) centreZ = z + reach;
    }

    private boolean inRoom(double x, double z) {
        return Math.abs(x - centreX) <= halfX + ROOM_MARGIN_BLOCKS
                && Math.abs(z - centreZ) <= halfZ + ROOM_MARGIN_BLOCKS;
    }

    private double u(double x) {
        return (x - centreX) / halfX;
    }

    private double v(double z) {
        return (z - centreZ) / halfZ;
    }

    private double presence(long nowMillis) {
        if (changedAtMillis == Long.MIN_VALUE) return present ? 1 : 0;
        double elapsed = Math.max(0, (nowMillis - changedAtMillis) / 1000.0);
        return present
                ? Math.min(1.0, presenceAtChange + elapsed / fadeInSeconds)
                : Math.max(0.0, presenceAtChange - elapsed / fadeOutSeconds);
    }

    private void flip(boolean nowPresent, long nowMillis) {
        if (nowPresent == present && changedAtMillis != Long.MIN_VALUE) return;
        presenceAtChange = presence(nowMillis);
        changedAtMillis = nowMillis;
        present = nowPresent;
    }

    @Override
    public String id() {
        return "valkyrie_queen";
    }

    @Override
    public EffectTier tier() {
        return EffectTier.TIER1_OPAQUE_BASE;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isActive(long nowMillis) {
        return presence(nowMillis) > 0.004;
    }

    @Override
    public boolean suppressesAmbientOverlays(long nowMillis) {
        return presence(nowMillis) > 0.004;
    }

    @Override
    public double layerOpacity(long nowMillis) {
        return presence(nowMillis);
    }

    @Override
    public Map<KeyGrid.LedRef, LayerPixel> render(KeyGrid grid, long nowMillis) {
        if (presence(nowMillis) <= 0.004) return Map.of();

        boolean defeated = defeatedAtMillis != Long.MIN_VALUE;
        double lift = Math.max(0, Math.min(1, (curY - floorY) / LIFT_REACH_BLOCKS));
        boolean airborne = !teleporting && (lift > 0.12 || Math.abs(climbSpeed) > AIRBORNE_CLIMB_SPEED);
        boolean lunging = airborne && climbSpeed < -LUNGE_FALL_SPEED && acrossSpeed > LUNGE_ACROSS_SPEED;
        shownIgnition += ((awake ? 1 : 0) - shownIgnition) * SMOOTHING;
        shownSpread += ((airborne ? 1 : 0) - shownSpread) * SMOOTHING * 2;
        shownDive += ((lunging ? 1 : 0) - shownDive) * SMOOTHING * 2.5;

        // One sample behind, gliding between the last two, as SliderEffect does.
        double span = curAtMillis - prevAtMillis;
        double t = span > 0 ? Math.max(0, Math.min(1, (nowMillis - curAtMillis) / span)) : 1;
        double x = prevX + (curX - prevX) * t;
        double z = prevZ + (curZ - prevZ) * t;

        queen.setQueen(u(x), v(z), lift, health, shownIgnition, shownSpread, shownDive,
                velX / halfX, velZ / halfZ);
        queen.setBoltReach(BOLT_REACH_BLOCKS / halfX, BOLT_REACH_BLOCKS / halfZ);
        queen.setMarker(u(playerX), v(playerZ), !defeated, ColorPalette.VALKYRIE_QUEEN_PLAYER);
        queen.setLightningColor(lightningColor);

        PatternContext ctx = new PatternContext(grid, null, silverColor, goldColor, 0, PatternParams.EMPTY);
        return queen.render(ctx, nowMillis);
    }
}
