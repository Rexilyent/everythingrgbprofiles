package net.minecraft.network.protocol.common.custom;
public interface CustomPacketPayload {
  Type<? extends CustomPacketPayload> type();
  class Type<T extends CustomPacketPayload> { public Type(net.minecraft.resources.ResourceLocation id){} }
}