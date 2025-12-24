package eu.pankraz01.glra.platform;

import java.nio.file.Path;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Platform bridge for things that differ between Fabric and NeoForge.
 */
public final class PlatformServices {
    private PlatformServices() {}

    @ExpectPlatform
    public static Path configDir() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean hasPermission(CommandSourceStack source, String node, int opLevelFallback) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean hasPermission(ServerPlayer player, String node, int opLevelFallback) {
        throw new AssertionError();
    }
}
