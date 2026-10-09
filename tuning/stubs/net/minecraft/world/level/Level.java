package net.minecraft.world.level;

/**
 * Real hierarchy: ClientLevel extends Level, and Entity#level() returns Level,
 * not ClientLevel. The stub previously had Player#level() return ClientLevel
 * directly, which let code compile here that Gradle rejected. Modelling the
 * supertype keeps the stub at least as strict as the real API.
 */
public class Level {
  public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getBiome(Object pos){return null;}
  public boolean isRaining(){return false;}
  public boolean isThundering(){return false;}
  public long getDayTime(){return 0;}
  public net.minecraft.core.ResourceKey dimension(){return null;}
  public boolean isClientSide(){return true;}
  public <T> java.util.List<T> getEntitiesOfClass(Class<T> c, net.minecraft.world.phys.AABB aabb){return java.util.List.of();}
  public net.minecraft.core.RegistryAccess registryAccess(){return null;}
  public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos p){return new net.minecraft.world.level.block.state.BlockState();}
}
