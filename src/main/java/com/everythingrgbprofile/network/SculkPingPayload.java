package com.everythingrgbprofile.network;

import com.everythingrgbprofile.RGBProfileMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The optional sculk relay's entire payload: a block position and one
 * boolean. Roughly 13 bytes on the wire.
 *
 * <p>Deliberately tiny, and deliberately sent only to the specific nearby
 * players who could plausibly care — never broadcast (see
 * {@code SculkServerRelay}). A cosmetic keyboard-lighting mod has no business
 * adding measurable server traffic, so it doesn't.
 *
 * <p>One boolean for sensor-vs-shrieker rather than an enum: there are exactly
 * two cases, an enum would need its own StreamCodec, and this is the entire
 * network protocol. Sometimes a bit is just a bit.
 */
public record SculkPingPayload(BlockPos position, boolean isShrieker) implements CustomPacketPayload {

    public static final Type<SculkPingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RGBProfileMod.MODID, "sculk_ping"));

    /**
     * Serialisation. Field order here must match the record's component order
     * — {@code StreamCodec.composite} lines up positionally, so swapping two
     * same-typed fields would compile perfectly and then send subtly wrong
     * data forever.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, SculkPingPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SculkPingPayload::position,
            net.minecraft.network.codec.ByteBufCodecs.BOOL, SculkPingPayload::isShrieker,
            SculkPingPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
