package com.everythingrgbprofile.detect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Client-side detection of sculk sensors firing and shriekers shrieking.
 *
 * <p>Before this class existed, the Sculk Sensor Ping and Shrieker Alert
 * effects were fully implemented — colours, escalation, ring patterns, the
 * lot — but their only caller was the network payload handler, and nothing
 * ever sent that payload. They were unreachable code. This is the signal they
 * were missing.
 *
 * <h2>Why no server component is needed</h2>
 * The server relay in {@code server/} was written on the assumption that the
 * activation had to be observed server-side and forwarded. It doesn't. Both
 * blocks carry their firing state
 * in the <b>block state</b>, and block states sync to every client that has
 * the chunk:
 *
 * <ul>
 *   <li>{@code SculkSensorBlock.PHASE} cycles INACTIVE to ACTIVE to COOLDOWN,
 *       set with block-update flag 3.</li>
 *   <li>{@code SculkShriekerBlock.SHRIEKING} flips false to true, set with
 *       flag 2.</li>
 * </ul>
 *
 * <p>Both flags include "send to client", so watching for the rising edge on
 * the client is sufficient, needs no mixin, and works on any vanilla server
 * without the mod installed. That last point is the one that settles it.
 *
 * <h2>Why scanning blocks is affordable</h2>
 * The obvious objection to a block scan is cost: a radius of 16 is nearly
 * 36,000 positions, and doing that even a few times a second to run a
 * cosmetic light show would be indefensible.
 *
 * <p>{@link LevelChunkSection#maybeHas} is what makes it fine. It tests the
 * section's <b>palette</b> — the list of distinct block states the section
 * contains — so asking "is there any sculk in this 16x16x16 at all" is one
 * cheap call, not 4,096 lookups. Outside a Deep Dark essentially every section
 * answers no and is skipped whole. Inside one, only the sections that really
 * hold sculk get walked.
 *
 * <p>State is a flat map of packed positions to "was it firing last time",
 * pruned every scan to what is still in range, so a long session in a large
 * sculk field does not grow it without bound.
 */
public final class SculkBlockWatcher {

    /** What the palette test looks for. Calibrated sensors count — they fire identically. */
    private static final Predicate<BlockState> IS_SCULK_TRIGGER = state ->
            state.is(Blocks.SCULK_SENSOR)
                    || state.is(Blocks.CALIBRATED_SCULK_SENSOR)
                    || state.is(Blocks.SCULK_SHRIEKER);

    /** Told once per scan, however many blocks fired — see the dispatch note in {@link #poll}. */
    public interface Listener {
        void onSensorActivated();

        void onShriekerActivated();
    }

    /** Packed BlockPos to "was firing at the previous scan". */
    private final Map<Long, Boolean> lastFiring = new HashMap<>();
    /** Reused across scans to avoid allocating a set every poll. */
    private final Set<Long> seenThisScan = new HashSet<>();

    private boolean sensorFired;
    private boolean shriekerFired;

    /** Wipes all memory of what was firing. Call on world unload. */
    public void reset() {
        lastFiring.clear();
        seenThisScan.clear();
    }

    /**
     * Scans for rising edges and reports at most one sensor and one shrieker
     * event.
     *
     * <p>The collapsing is deliberate. A Deep Dark can have a dozen sensors go
     * off from a single footstep, and both effects are whole-board Tier 3
     * flashes — firing twelve identical retriggers into the SDK queue would
     * produce exactly the same visual as firing one, at twelve times the cost.
     */
    public void poll(Player player, int radius, Listener listener) {
        Level level = player.level();
        BlockPos center = player.blockPosition();

        sensorFired = false;
        shriekerFired = false;
        seenThisScan.clear();

        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);
        int minY = Math.max(level.getMinBuildHeight(), center.getY() - radius);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, center.getY() + radius);
        if (minY > maxY) return;
        long radiusSq = (long) radius * radius;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // hasChunk first: getChunk on a missing chunk can trigger a
                // load, and a lighting mod has no business forcing chunk loads.
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) continue;
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();

                int firstIndex = Math.max(0, chunk.getSectionIndex(minY));
                int lastIndex = Math.min(sections.length - 1, chunk.getSectionIndex(maxY));
                for (int index = firstIndex; index <= lastIndex; index++) {
                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()) continue;
                    // The palette test. Everything above is bookkeeping; this
                    // line is why the whole approach is cheap.
                    if (!section.maybeHas(IS_SCULK_TRIGGER)) continue;

                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
                    scanSection(section, chunk.getPos().getMinBlockX(), baseY, chunk.getPos().getMinBlockZ(),
                            center, minY, maxY, radiusSq);
                }
            }
        }

        // Forget blocks that have left range, so the map tracks the player
        // rather than growing for the whole session.
        lastFiring.keySet().retainAll(seenThisScan);

        if (sensorFired) listener.onSensorActivated();
        if (shriekerFired) listener.onShriekerActivated();
    }

    private void scanSection(LevelChunkSection section, int baseX, int baseY, int baseZ,
                             BlockPos center, int minY, int maxY, long radiusSq) {
        for (int localY = 0; localY < 16; localY++) {
            int y = baseY + localY;
            if (y < minY || y > maxY) continue;
            long dy = y - center.getY();
            for (int localX = 0; localX < 16; localX++) {
                int x = baseX + localX;
                long dx = x - center.getX();
                for (int localZ = 0; localZ < 16; localZ++) {
                    BlockState state = section.getBlockState(localX, localY, localZ);
                    boolean shrieker = state.is(Blocks.SCULK_SHRIEKER);
                    boolean sensor = !shrieker
                            && (state.is(Blocks.SCULK_SENSOR) || state.is(Blocks.CALIBRATED_SCULK_SENSOR));
                    if (!shrieker && !sensor) continue;

                    int z = baseZ + localZ;
                    long dz = z - center.getZ();
                    // Spherical, not the cubic region the section walk gives
                    // us. Squared throughout so there is no sqrt in here.
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue;

                    long key = BlockPos.asLong(x, y, z);
                    seenThisScan.add(key);
                    boolean firing = shrieker
                            ? state.getValue(SculkShriekerBlock.SHRIEKING)
                            : state.getValue(SculkSensorBlock.PHASE) == SculkSensorPhase.ACTIVE;
                    Boolean previous = lastFiring.put(key, firing);

                    // Rising edge only, and a block seen for the FIRST time
                    // never counts as one however active it is. Without that
                    // guard, walking into range of a busy sculk field would
                    // set off a burst of pings for activations that happened
                    // before you arrived — and so would every chunk reload.
                    if (firing && previous != null && !previous) {
                        if (shrieker) {
                            shriekerFired = true;
                        } else {
                            sensorFired = true;
                        }
                    }
                }
            }
        }
    }
}
