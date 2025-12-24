package eu.pankraz01.glra.gui;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Helper that keeps track of players who should see rollback status updates in the action bar.
 */
public class ActionBarNotifier {
    private final Set<UUID> watchers = ConcurrentHashMap.newKeySet();

    public void track(ServerPlayer player) {
        if (player != null) {
            watchers.add(player.getUUID());
        }
    }

    public void clear() {
        watchers.clear();
    }

    public boolean hasWatchers() {
        return !watchers.isEmpty();
    }

    public void send(MinecraftServer server, Component message) {
        if (server == null || message == null || watchers.isEmpty()) return;

        for (UUID id : watchers) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                watchers.remove(id);
                continue;
            }
            player.displayClientMessage(message, true);
        }
    }
}
