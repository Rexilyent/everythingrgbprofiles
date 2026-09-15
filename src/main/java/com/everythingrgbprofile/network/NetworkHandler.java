package com.everythingrgbprofile.network;

import com.everythingrgbprofile.RGBProfileMod;
import com.everythingrgbprofile.event.ClientEventHandlers;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers this mod's one and only network payload ({@link SculkPingPayload}).
 * That's the entire network surface. One packet type, sent rarely, to a
 * handful of nearby players. Purely a data relay.
 *
 * <p>Registration is cheap and side-agnostic (see {@code RGBProfileMod}).
 * Whether the payload is ever actually <i>sent</i> depends entirely on the
 * optional server relay deciding it's needed — and since sculk detection moved
 * to the client (see {@code SculkBlockWatcher}) it never does, so in practice
 * this registers a packet nobody sends. It stays registered so that the day
 * the relay does start sending, the client knows what it received.
 *
 * <h2>Why the payload is optional</h2>
 * An earlier version registered it as required, NeoForge's default. NeoForge
 * refuses a connection when either side lacks a required payload, so a player
 * with this mod could not join a vanilla server ("you have mods that require
 * it") or a NeoForge server without the mod, all for a packet that is never
 * sent. This is a client-side lighting mod that has to work on any server, so
 * the payload is optional: present on both sides it works as before, and
 * missing on either side it is simply never sent.
 */
public final class NetworkHandler {

    public static void register(RegisterPayloadHandlersEvent event) {
        // versioned("1"): when both sides have the mod, NeoForge uses this to
        // refuse a connection where the two disagree. Bump it if the payload
        // shape ever changes, so mismatched versions fail loudly at connect
        // time rather than deserialising garbage mid-session.
        //
        // optional(): see the class doc. Without it, this mod blocks joining
        // any server that does not also have it.
        PayloadRegistrar registrar = event.registrar(RGBProfileMod.MODID).versioned("1").optional();
        registrar.playToClient(
                SculkPingPayload.TYPE,
                SculkPingPayload.STREAM_CODEC,
                (IPayloadHandler<SculkPingPayload>) (payload, context) ->
                        // enqueueWork is mandatory, not optional politeness:
                        // payload handlers run on the NETWORK thread, and
                        // touching game state from there is a race condition
                        // with a delayed fuse. This hops to the main thread.
                        context.enqueueWork(() -> {
                            long now = System.currentTimeMillis();
                            if (payload.isShrieker()) {
                                ClientEventHandlers.onSculkShriekerActivated(now);
                            } else {
                                ClientEventHandlers.onSculkSensorActivated(now);
                            }
                        })
        );
    }

    private NetworkHandler() {
    }
}
