package com.everythingrgbprofile.server;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.config.Feature;
import com.everythingrgbprofile.config.RGBProfileConfig;
import com.everythingrgbprofile.network.SculkPingPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The optional server-side half of sculk detection. Inert, and now redundant.
 *
 * <h2>It turned out not to be needed</h2>
 * The plan was to try the client-only path first and build a server component
 * only if that failed. It did not fail. Sculk sensors and shriekers both
 * broadcast their firing state as ordinary block-state sync — a sensor's PHASE
 * and a shrieker's SHRIEKING are set with a flag that includes "send to
 * client" — so {@code SculkBlockWatcher} reads the rising edge straight off
 * the client and this relay has nothing left to do.
 *
 * <p>{@link #sendPingToNearbyPlayers} is complete and correct. It simply has
 * no caller and no longer needs one.
 *
 * <p>The client-only route is also strictly better than this one would have
 * been, because it works against a vanilla server that has never heard of this
 * mod — something a relay cannot do by definition.
 *
 * <p>So this class, its registration line in {@code RGBProfileMod}, and the
 * network payload are all safe to delete. It is kept only in case some future
 * block turns out not to sync the state we need.
 */
public final class SculkServerRelay {

    /**
     * Sends a ping to every player within the configured radius. Complete and
     * ready; just needs someone to call it.
     *
     * <p>Note it is emphatically <b>not</b> a broadcast — it filters by squared
     * distance first (squared, so no {@code sqrt} per player per activation).
     * Sculk sensors trigger constantly in a deep dark, and telling the entire
     * server about each one so that four people's keyboards can blink would be
     * an unusually rude thing for a cosmetic mod to do.
     */
    public void sendPingToNearbyPlayers(ServerPlayer relativeTo, BlockPos activationPos, boolean isShrieker) {
        if (!(isShrieker ? Feature.SCULK_SHRIEKER : Feature.SCULK_SENSOR).isOn()) return;
        double radius = RGBProfileConfig.SCULK_DETECTION_RADIUS.get();

        SculkPingPayload payload = new SculkPingPayload(activationPos, isShrieker);
        // level() rather than the serverLevel() this used to call: since 1.21.8
        // ServerPlayer narrows level()'s return type to ServerLevel, and the
        // separate accessor is gone.
        for (ServerPlayer player : relativeTo.level().players()) {
            // +0.5 on each axis to measure from the block's centre rather than
            // its corner. Pedantic, costs nothing, avoids an off-by-half-a-block
            // at the radius boundary.
            if (player.distanceToSqr(activationPos.getX() + 0.5, activationPos.getY() + 0.5, activationPos.getZ() + 0.5) <= radius * radius) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    /**
     * Announces at startup that this component exists but isn't doing
     * anything. Deliberately INFO-level: a server admin seeing an unfamiliar
     * mod in their logs deserves to know it's inert rather than wondering what
     * it's up to.
     */
    @SubscribeEvent
    public void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        RGBProfileMod.LOGGER.info("RGB Profile: server-side sculk relay component present but its trigger hook " +
                "is inert and no longer needed — sculk detection reads block state directly on the "
                + "client (see SculkBlockWatcher). Safe to remove.");
    }
}
