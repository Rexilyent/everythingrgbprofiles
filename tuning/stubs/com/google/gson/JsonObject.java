package com.google.gson;
public class JsonObject extends JsonElement {
  public boolean has(String k){return false;}
  public JsonObject getAsJsonObject(String k){return null;}
  public java.util.Set<String> keySet(){return java.util.Set.of();}
  public JsonElement get(String k){return null;}
}
