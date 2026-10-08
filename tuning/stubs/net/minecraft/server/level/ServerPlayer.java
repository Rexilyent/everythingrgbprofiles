package net.minecraft.server.level;
public class ServerPlayer extends net.minecraft.world.entity.player.Player {
  public ServerLevel serverLevel(){return new ServerLevel();}
  public double distanceToSqr(double a, double b, double c){return 0;}
  public static class ServerLevel { public java.util.List<ServerPlayer> players(){return java.util.List.of();} }
}
