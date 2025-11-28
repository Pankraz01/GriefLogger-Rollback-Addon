package eu.pankraz01.glra;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * Central permission definitions + helpers so LuckPerms/other mods can manage access.
 */
public final class Permissions {
    private static final int DEFAULT_OP_LEVEL = 3;

    public static final PermissionNode<Boolean> COMMAND_ROLLBACK = booleanNode("command.rollback", DEFAULT_OP_LEVEL);
    public static final PermissionNode<Boolean> COMMAND_WEB_TOKEN = booleanNode("command.web.token", DEFAULT_OP_LEVEL);
    public static final PermissionNode<Boolean> COMMAND_WEB_SERVER = booleanNode("command.web.server", DEFAULT_OP_LEVEL);
    public static final PermissionNode<Boolean> COMMAND_CONFIG_RELOAD = booleanNode("command.config.reload", DEFAULT_OP_LEVEL);
    public static final PermissionNode<Boolean> NOTIFY_ROLLBACK = booleanNode("notify.rollback", DEFAULT_OP_LEVEL);
    public static final PermissionNode<Boolean> NOTIFY_WEB_ROLLBACK = booleanNode("notify.web.rollback", DEFAULT_OP_LEVEL);
    public static final PermissionNode<Boolean> NOTIFY_WEB_UNAUTHORIZED = booleanNode("notify.web.unauthorized", DEFAULT_OP_LEVEL);

    private Permissions() {}

    private static PermissionNode<Boolean> booleanNode(String path, int defaultOpLevel) {
        return new PermissionNode<>(GriefloggerRollbackAddon.MODID, path, PermissionTypes.BOOLEAN,
                (player, uuid, ctx) -> player != null && player.hasPermissions(defaultOpLevel));
    }

    public static void registerNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(
                COMMAND_ROLLBACK,
                COMMAND_WEB_TOKEN,
                COMMAND_WEB_SERVER,
                COMMAND_CONFIG_RELOAD,
                NOTIFY_ROLLBACK,
                NOTIFY_WEB_ROLLBACK,
                NOTIFY_WEB_UNAUTHORIZED
        );
    }

    public static int defaultOpLevel() {
        return DEFAULT_OP_LEVEL;
    }

    public static boolean has(CommandSourceStack source, PermissionNode<Boolean> node, int opLevelFallback) {
        if (source == null) return false;

        try {
            if (source.getEntity() instanceof ServerPlayer player) {
                return PermissionAPI.getPermission(player, node);
            }
        } catch (Exception ignored) {
            // Fall back to vanilla permission check below.
        }

        return source.hasPermission(opLevelFallback);
    }

    public static boolean has(ServerPlayer player, PermissionNode<Boolean> node, int opLevelFallback) {
        if (player == null) return false;
        try {
            return PermissionAPI.getPermission(player, node);
        } catch (Exception ignored) {
            return player.hasPermissions(opLevelFallback);
        }
    }
}
