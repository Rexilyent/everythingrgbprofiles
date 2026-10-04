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
 * effects were completely finished. Colours, escalation, ring patterns, all
 * of it. Not one of them had ever run, not once, for anybody, because the
 * only thing that called them was a network payload handler being fed by an
 * optional server-side relay that never actually sent a payload. Fully
 * implemented, fully unreachable. This class is the signal they were missing.
 *
 * <h2>The server component that got deleted, and why</h2>
 * That relay existed to spot a sculk activation server-side and forward it to
 * the client. It never needed to, because the game already solves this on its
 * own: both blocks carry their firing state in the <b>block state</b>, and
 * block states get synced to every client holding the chunk:
 *
 * <ul>
 *   <li>{@code SculkSensorBlock.PHASE} cycles INACTIVE to ACTIVE to COOLDOWN,
 *       set with block-update flag 3.</li>
 *   <li>{@code SculkShriekerBlock.SHRIEKING} flips false to true, set with
 *       flag 2.</li>
 * </ul>
 *
 * <p>Both of those flags include "send to client". Which means watching for
 * the rising edge right here is the entire job: no mixin, no packets, no
 * server component, and it works on a bone-stock vanilla server that has
 * never heard of this mod in its life. That last part is what settled the
 * argument, because a relay cannot do that by definition. The relay and its
 * payload were deleted and this mod now has no network surface at all.
 *
 * <h2>Why scanning tens of thousands of blocks is fine, actually</h2>
 * The obvious objection to a block scan is the cost. A radius of 16 is just
 * under 36,000 positions, and walking that several times a second to drive a
 * cosmetic light show would be completely indefensible.
 *
 * <p>{@link LevelChunkSection#maybeHas} is the reason it isn't. It checks the
 * section's <b>palette</b>, which is the list of distinct block states that
 * section actually contains, so "is there any sculk in this entire 16x16x16"
 * costs one cheap call rather than 4,096 lookups. Outside the Deep Dark
 * basically every section answers no and gets skipped whole. Inside one, only
 * the sections genuinely holding sculk get walked.
 *
 * <p>State is a flat map of packed positions to "was this firing last time",
 * pruned every scan down to whatever is still in range, so parking yourself in
 * a large sculk field for three hours doesn't quietly turn into a memory leak.
 */
public final class SculkBlockWatcher {

    /**
     * What the palette test is hunting for. Calibrated sensors are in the list
     * too, because they fire in exactly the same way and there is no reason to
     * snub them.
     */
    private static final Predicate<BlockState> IS_SCULK_TRIGGER = state ->
            state.is(Blocks.SCULK_SENSOR)
                    || state.is(Blocks.CALIBRATED_SCULK_SENSOR)
                    || state.is(Blocks.SCULK_SHRIEKER);

    /** Told once per scan no matter how many blocks fired; see the note on {@link #poll}. */
    public interface Listener {
        void onSensorActivated();

        void onShriekerActivated();
    }

    /** Packed BlockPos to "was this firing at the previous scan". */
    private final Map<Long, Boolean> lastFiring = new HashMap<>();
    /** Reused across scans instead of allocating a fresh set four times a second. */
    private final Set<Long> seenThisScan = new HashSet<>();

    private boolean sensorFired;
    private boolean shriekerFired;

    /**
     * Wipes all memory of what was firing. Called on world unload, because
     * positions from the world you just left mean nothing in the one you are
     * about to load and would just sit there taking up space.
     */
    public void reset() {
        lastFiring.clear();
        seenThisScan.clear();
    }

    /**
     * Scans for rising edges and reports at most one sensor event and one
     * shrieker event, however many blocks actually went off.
     *
     * <p>That collapsing is deliberate. One footstep in a Deep Dark can set off
     * a dozen sensors at once, and both effects are whole-board Tier 3 flashes,
     * so shoving twelve identical retriggers into the SDK queue gets you the
     * exact same thing on the keyboard as sending one. Same picture, twelve
     * times the bill.
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
                // hasChunk first, always. getChunk on a chunk that isn't there
                // can kick off a load, and a keyboard lighting mod forcing
                // chunk loads on somebody's world is genuinely unacceptable
                // behaviour.
                if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) continue;
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                LevelChunkSection[] sections = chunk.getSections();

                int firstIndex = Math.max(0, chunk.getSectionIndex(minY));
                int lastIndex = Math.min(sections.length - 1, chunk.getSectionIndex(maxY));
                for (int index = firstIndex; index <= lastIndex; index++) {
                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()) continue;
                    // The palette test. Everything above this is bookkeeping.
                    // This one line is the reason the whole approach is cheap
                    // enough to run four times a second.
                    if (!section.maybeHas(IS_SCULK_TRIGGER)) continue;

                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
                    scanSection(section, chunk.getPos().getMinBlockX(), baseY, chunk.getPos().getMinBlockZ(),
                            center, minY, maxY, radiusSq);
                }
            }
        }

        // Forget anything that has left range, so the map follows the player
        // around instead of accumulating every sculk block they have ever
        // walked past this session.
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
                    // Spherical, rather than the cube the section walk hands
                    // us for free. Squared distances the whole way through so
                    // there is no sqrt anywhere in this loop.
                    if (dx * dx + dy * dy + dz * dz > radiusSq) continue;

                    long key = BlockPos.asLong(x, y, z);
                    seenThisScan.add(key);
                    boolean firing = shrieker
                            ? state.getValue(SculkShriekerBlock.SHRIEKING)
                            : state.getValue(SculkSensorBlock.PHASE) == SculkSensorPhase.ACTIVE;
                    Boolean previous = lastFiring.put(key, firing);

                    // Rising edge only, and a block being seen for the FIRST
                    // time never counts as one no matter how loudly it is
                    // going off right now.
                    //
                    // Skip that guard and walking into range of a busy sculk
                    // field sets off a burst of pings for activations that
                    // happened before you got there, which is a lie. Every
                    // chunk reload does the same thing.
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
