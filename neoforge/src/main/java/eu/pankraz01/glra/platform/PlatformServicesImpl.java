package eu.pankraz01.glra.platform;

import java.nio.file.Path;
import java.util.Map;

import eu.pankraz01.glra.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

public final class PlatformServicesImpl {
    private static final Map<String, PermissionNode<Boolean>> NODES = Map.of(
            Permissions.COMMAND_ROLLBACK, booleanNode("command.rollback"),
            Permissions.COMMAND_ROLLBACK_UNDO, booleanNode("command.rollback.undo"),
            Permissions.COMMAND_WEB_TOKEN, booleanNode("command.web.token"),
            Permissions.COMMAND_WEB_SERVER, booleanNode("command.web.server"),
            Permissions.COMMAND_CONFIG_RELOAD, booleanNode("command.config.reload"),
            Permissions.NOTIFY_ROLLBACK, booleanNode("notify.rollback"),
            Permissions.NOTIFY_WEB_ROLLBACK, booleanNode("notify.web.rollback"),
            Permissions.NOTIFY_WEB_UNAUTHORIZED, booleanNode("notify.web.unauthorized")
    );

    private PlatformServicesImpl() {}

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static boolean hasPermission(CommandSourceStack source, String node, int opLevelFallback) {
        if (source == null) return false;
        PermissionNode<Boolean> permissionNode = NODES.get(node);
        try {
            if (source.getEntity() instanceof ServerPlayer player && permissionNode != null) {
                return PermissionAPI.getPermission(player, permissionNode);
            }
        } catch (Exception ignored) {
            // fall back to vanilla check below
        }
        return source.hasPermission(opLevelFallback);
    }

    public static boolean hasPermission(ServerPlayer player, String node, int opLevelFallback) {
        if (player == null) return false;
        PermissionNode<Boolean> permissionNode = NODES.get(node);
        try {
            if (permissionNode != null) {
                return PermissionAPI.getPermission(player, permissionNode);
            }
        } catch (Exception ignored) {
            return player.hasPermissions(opLevelFallback);
        }
        return player.hasPermissions(opLevelFallback);
    }

    public static void registerPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(NODES.values().toArray(PermissionNode[]::new));
    }

    private static PermissionNode<Boolean> booleanNode(String path) {
        return new PermissionNode<>(Permissions.MODID, path, PermissionTypes.BOOLEAN,
                (player, uuid, ctx) -> player != null && player.hasPermissions(Permissions.defaultOpLevel()));
    }
}
