package com.everythingrgbprofile.fabric;

import java.nio.file.Path;
import java.util.Optional;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import com.everythingrgbprofile.platform.Platform;

/** {@link Platform}, answered by Fabric Loader. Found through META-INF/services. */
public final class FabricPlatform implements Platform {

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Optional<String> modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString());
    }

    @Override
    public int modCount() {
        return FabricLoader.getInstance().getAllMods().size();
    }

    @Override
    public boolean isDedicatedServer() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
    }

    @Override
    public String loaderDescription() {
        return "Fabric Loader " + modVersion("fabricloader").orElse("unknown")
                + ", Fabric API " + modVersion("fabric-api").orElse("unknown");
    }
}
