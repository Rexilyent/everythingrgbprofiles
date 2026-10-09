package net.neoforged.bus.api;
public interface IEventBus {
  <T> void addListener(java.util.function.Consumer<T> listener);
  void register(Object target);
}
