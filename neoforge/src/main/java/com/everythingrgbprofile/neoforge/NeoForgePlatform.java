package com.everythingrgbprofile.neoforge;

import java.nio.file.Path;
import java.util.Optional;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import com.everythingrgbprofile.platform.Platform;

/** {@link Platform}, answered by FML. Found through META-INF/services. */
public final class NeoForgePlatform implements Platform {

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public Optional<String> modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString());
    }

    @Override
    public int modCount() {
        return ModList.get().size();
    }

    @Override
    public boolean isDedicatedServer() {
        return FMLEnvironment.getDist() != Dist.CLIENT;
    }

    @Override
    public String loaderDescription() {
        return "NeoForge " + modVersion("neoforge").orElse("unknown");
    }
}
