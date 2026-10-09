package net.minecraft.network.codec;
public interface StreamCodec<B,V> {
  static <B,V> StreamCodec<B,V> of(Object a, Object b){return null;}
  static <B,V,T1,T2> StreamCodec<B,V> composite(
      Object c1, java.util.function.Function<V,T1> g1,
      Object c2, java.util.function.Function<V,T2> g2,
      java.util.function.BiFunction<T1,T2,V> ctor){return null;}
}
