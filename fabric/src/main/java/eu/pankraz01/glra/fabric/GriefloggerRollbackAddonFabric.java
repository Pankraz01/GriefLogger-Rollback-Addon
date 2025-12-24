package eu.pankraz01.glra.fabric;

import eu.pankraz01.glra.GriefloggerRollbackAddon;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class GriefloggerRollbackAddonFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        GriefloggerRollbackAddon.init();

        ServerLifecycleEvents.SERVER_STARTING.register(GriefloggerRollbackAddon::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(GriefloggerRollbackAddon::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(GriefloggerRollbackAddon::onServerTick);
    }
}
