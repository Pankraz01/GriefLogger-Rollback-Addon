package eu.pankraz01.glra.platform;

import java.lang.reflect.Method;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public final class PlatformServicesImpl {
    private static final String PERMISSIONS_CLASS = "me.lucko.fabric.api.permissions.v0.Permissions";
    private static Boolean permissionsApiPresent = null;

    private PlatformServicesImpl() {}

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static boolean hasPermission(CommandSourceStack source, String node, int opLevelFallback) {
        if (source == null) return false;
        Boolean allowed = checkViaPermissionsApi(source, node, opLevelFallback);
        return allowed != null ? allowed : source.hasPermission(opLevelFallback);
    }

    public static boolean hasPermission(ServerPlayer player, String node, int opLevelFallback) {
        if (player == null) return false;
        Boolean allowed = checkViaPermissionsApi(player, node, opLevelFallback);
        return allowed != null ? allowed : player.hasPermissions(opLevelFallback);
    }

    private static Boolean checkViaPermissionsApi(Object subject, String node, int fallback) {
        if (!permissionsApiAvailable()) return null;
        try {
            Class<?> permissions = Class.forName(PERMISSIONS_CLASS);
            Method check = permissions.getMethod("check", subject.getClass(), String.class, int.class);
            Object result = check.invoke(null, subject, node, fallback);
            if (result instanceof Boolean b) return b;
        } catch (Throwable ignored) {
            // fall back below
        }
        return null;
    }

    private static boolean permissionsApiAvailable() {
        if (permissionsApiPresent != null) return permissionsApiPresent;
        try {
            Class.forName(PERMISSIONS_CLASS);
            permissionsApiPresent = true;
        } catch (ClassNotFoundException e) {
            permissionsApiPresent = false;
        }
        return permissionsApiPresent;
    }
}
