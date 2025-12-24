package eu.pankraz01.glra.neoforge;

import eu.pankraz01.glra.GriefloggerRollbackAddon;
import eu.pankraz01.glra.platform.PlatformServicesImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(GriefloggerRollbackAddon.MODID)
public final class GriefloggerRollbackAddonNeoForge {
    public GriefloggerRollbackAddonNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(PlatformServicesImpl::registerPermissionNodes);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        GriefloggerRollbackAddon.init();
    }

    private void onServerStarting(ServerStartingEvent event) {
        GriefloggerRollbackAddon.onServerStarting(event.getServer());
    }

    private void onServerTick(ServerTickEvent.Post event) {
        GriefloggerRollbackAddon.onServerTick(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        GriefloggerRollbackAddon.onServerStopping(event.getServer());
    }
}
