package com.everythingrgbprofile.mixin;

import java.util.Map;
import java.util.UUID;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The boss bars on screen, for finding the raid bar. Package-private in
 * vanilla, and the only place the client keeps a raid's progress; see
 * {@code ClientEventHandlers.raidBarPhase}.
 *
 * <p>This used to be one line in a NeoForge access transformer. Fabric wants
 * an access widener for the same job, in a different file, in a different
 * format, and the two would have had to be kept in step by hand forever. A
 * mixin accessor works on both loaders, so it's one file and nothing to keep
 * in step. Read-only, and it reads the map rather than copying it.
 */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {

    @Accessor("events")
    Map<UUID, LerpingBossEvent> rgbprofiles$events();
}
