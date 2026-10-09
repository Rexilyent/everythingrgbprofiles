package net.neoforged.fml.common;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface EventBusSubscriber { String modid() default ""; net.neoforged.api.distmarker.Dist[] value() default {}; }