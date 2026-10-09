package com.sun.jna;
public abstract class Structure {
  public void read(){} public void write(){}
  public Structure[] toArray(int n){return null;}
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  public @interface FieldOrder { String[] value(); }
  public interface ByReference {}
}
