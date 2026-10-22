package com.everythingrgbprofile.platform;

import java.nio.file.Path;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The handful of things common code has to ask the loader about. Paths, the
 * mod list, and whether this is a dedicated server. That's the lot, and it
 * should stay the lot: anything bigger than a question belongs in the loader
 * projects, not in here.
 *
 * <p>Each loader jar carries exactly one implementation, listed in its
 * {@code META-INF/services}, and {@link #get()} finds it with plain Java
 * {@link ServiceLoader}. No library, no annotation processor, no magic.
 */
public interface Platform {

    /** The game's {@code config/} folder. Ours is a subfolder; see {@code RGBProfileFiles}. */
    Path configDir();

    /** The game directory: the one holding {@code mods/}, {@code config/} and {@code logs/}. */
    Path gameDir();

    boolean isModLoaded(String modId);

    /** A mod's version as its loader reports it, or empty if it isn't installed. */
    Optional<String> modVersion(String modId);

    int modCount();

    boolean isDedicatedServer();

    /** The loader and its version, for the diagnostic report. "NeoForge 26.3.0.92" and so on. */
    String loaderDescription();

    static Platform get() {
        Platform found = Holder.instance;
        if (found == null) {
            // Looked up on first use, and a miss is a plain exception rather
            // than a failed static initialiser. The tuning harnesses run with
            // no loader at all, and an Error from in here would sail straight
            // past every catch (Exception) on the way up. KeyLayout and
            // PackDetection both lean on being able to catch this.
            found = ServiceLoader.load(Platform.class, Platform.class.getClassLoader())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No Platform implementation found. Running outside a loader?"));
            Holder.instance = found;
        }
        return found;
    }

    final class Holder {
        private static volatile Platform instance;

        private Holder() {
        }
    }
}
