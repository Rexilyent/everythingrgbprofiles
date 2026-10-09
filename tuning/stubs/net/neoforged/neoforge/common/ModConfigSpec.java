package net.neoforged.neoforge.common;
public class ModConfigSpec {
  public static class Builder {
    public Builder comment(String... c){return this;}
    public Builder push(String s){return this;}
    public Builder pop(){return this;}
    public BooleanValue define(String k, boolean v){return new BooleanValue();}
    public <T> ConfigValue<T> define(String k, T v){return new ConfigValue<T>();}
    public IntValue defineInRange(String k,int v,int lo,int hi){return new IntValue();}
    public DoubleValue defineInRange(String k,double v,double lo,double hi){return new DoubleValue();}
    public ModConfigSpec build(){return new ModConfigSpec();}
  }
  public static class ConfigValue<T> { @SuppressWarnings("unchecked") public T get(){return null;} }
  public static class BooleanValue extends ConfigValue<Boolean> { public Boolean get(){return true;} }
  public static class IntValue extends ConfigValue<Integer> { public Integer get(){return 1;} }
  public static class DoubleValue extends ConfigValue<Double> { public Double get(){return 1.0;} }
}