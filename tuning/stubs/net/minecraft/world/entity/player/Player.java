package net.minecraft.world.entity.player;
public class Player {
  public net.minecraft.world.level.portal.PortalProcessor portalProcess;
  public int experienceLevel;
  public float getHealth(){return 0;} public float getMaxHealth(){return 0;}
  public boolean isDeadOrDying(){return false;} public boolean isSleeping(){return false;}
  public FoodData getFoodData(){return new FoodData();}
  public Inventory getInventory(){return new Inventory();}
  public net.minecraft.core.BlockPos blockPosition(){return null;}
  public net.minecraft.world.phys.AABB getBoundingBox(){return new net.minecraft.world.phys.AABB();}
  public net.minecraft.world.level.Level level(){return null;}
  public static class FoodData { public int getFoodLevel(){return 20;} public float getSaturationLevel(){return 0;} }
  public static class Inventory {
    public java.util.List<net.minecraft.world.item.ItemStack> items = java.util.List.of();
    public int getContainerSize(){return 36;}
    public net.minecraft.world.item.ItemStack getItem(int i){return new net.minecraft.world.item.ItemStack();}
  }
}