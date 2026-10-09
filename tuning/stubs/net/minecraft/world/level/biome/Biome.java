package net.minecraft.world.level.biome;
public class Biome {
  public int getFoliageColor(){return 0;}
  public float getBaseTemperature(){return 0;}
  public SpecialEffects getSpecialEffects(){return new SpecialEffects();}
  public static class SpecialEffects {
    public int getFogColor(){return 0;} public int getWaterColor(){return 0;} public int getSkyColor(){return 0;}
    public java.util.Optional<Integer> getFoliageColorOverride(){return java.util.Optional.empty();}
    public java.util.Optional<Integer> getGrassColorOverride(){return java.util.Optional.empty();}
  }
}
