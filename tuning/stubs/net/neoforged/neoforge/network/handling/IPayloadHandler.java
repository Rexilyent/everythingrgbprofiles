package net.neoforged.neoforge.network.handling;
public interface IPayloadHandler<T> { void handle(T payload, Context ctx);
  class Context { public void enqueueWork(Runnable r){} } }