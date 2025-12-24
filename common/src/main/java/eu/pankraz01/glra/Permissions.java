package eu.pankraz01.glra;

import eu.pankraz01.glra.platform.PlatformServices;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Permission keys and helpers. Platform-specific wiring lives in PlatformServicesImpl.
 */
public final class Permissions {
    public static final String MODID = "griefloggerrollbackaddon";

    public static final String COMMAND_ROLLBACK = "command.rollback";
    public static final String COMMAND_ROLLBACK_UNDO = "command.rollback.undo";
    public static final String COMMAND_WEB_TOKEN = "command.web.token";
    public static final String COMMAND_WEB_SERVER = "command.web.server";
    public static final String COMMAND_CONFIG_RELOAD = "command.config.reload";
    public static final String NOTIFY_ROLLBACK = "notify.rollback";
    public static final String NOTIFY_WEB_ROLLBACK = "notify.web.rollback";
    public static final String NOTIFY_WEB_UNAUTHORIZED = "notify.web.unauthorized";

    private static final int DEFAULT_OP_LEVEL = 3;

    private Permissions() {}

    public static int defaultOpLevel() {
        return DEFAULT_OP_LEVEL;
    }

    public static boolean has(CommandSourceStack source, String node, int opLevelFallback) {
        return PlatformServices.hasPermission(source, node, opLevelFallback);
    }

    public static boolean has(ServerPlayer player, String node, int opLevelFallback) {
        return PlatformServices.hasPermission(player, node, opLevelFallback);
    }
}
