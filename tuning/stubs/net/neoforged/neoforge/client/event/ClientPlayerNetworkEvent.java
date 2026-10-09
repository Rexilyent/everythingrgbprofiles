package net.neoforged.neoforge.client.event;
public class ClientPlayerNetworkEvent {
  public static class Clone extends ClientPlayerNetworkEvent {
    public net.minecraft.world.entity.player.Player getOldPlayer(){return null;}
    public net.minecraft.world.entity.player.Player getNewPlayer(){return null;}
  }
  public static class LoggingIn extends ClientPlayerNetworkEvent {}
  public static class LoggingOut extends ClientPlayerNetworkEvent {}
}
