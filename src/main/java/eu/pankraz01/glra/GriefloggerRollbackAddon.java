package eu.pankraz01.glra;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import eu.pankraz01.glra.database.DBConnection;
 
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import eu.pankraz01.glra.rollback.RollbackManager;
import eu.pankraz01.glra.commands.RollbackCommand;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(GriefloggerRollbackAddon.MODID)
public class GriefloggerRollbackAddon {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "griefloggerrollbackaddon";
    // MOD PREFIX for logging
    public static final String MOD_PREFIX = "[" + MODID + "] ";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // (No example blocks/items) — this mod only provides rollback functionality.

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public GriefloggerRollbackAddon(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // No block/item/tab registrations for this mod.

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (GriefloggerRollbackAddon) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);


        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "grieflogger/griefloggerrollbackaddon-common.toml");
        // RollbackManager does not register to the event bus (no @SubscribeEvent methods)
    }

    // Public manager instance for other classes (commands, tests, etc.)
    public static RollbackManager ROLLBACK_MANAGER;
    private static volatile boolean ENABLED = true;

    private void commonSetup(FMLCommonSetupEvent event) {
        if (!verifyDatabaseConnection()) {
            disableAddon("Database connection failed; disabling Grieflogger Rollback Addon functionality");
            return;
        }

        ROLLBACK_MANAGER = new RollbackManager();
        LOGGER.info("[Grieflogger Rollback Addon] Common setup complete");
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        if (!isEnabled()) {
            LOGGER.warn(MOD_PREFIX + "Skipping command registration because addon is disabled (no database connection)");
            return;
        }

        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
        try {
            var server = event.getServer();
            if (server != null) {
                try {
                    RollbackCommand.register(server.getCommands().getDispatcher());
                    LOGGER.info(MOD_PREFIX + "Registered /gl rollback command");
                } catch (Exception e) {
                    LOGGER.error(MOD_PREFIX + "Failed to register rollback command", e);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn(MOD_PREFIX + "Could not register commands on server start", t);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!isEnabled() || ROLLBACK_MANAGER == null) return;

        try {
            ROLLBACK_MANAGER.tick(event.getServer());
        } catch (Exception e) {
            LOGGER.error(MOD_PREFIX + "Rollback tick failed", e);
        }
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    private void disableAddon(String reason) {
        ENABLED = false;
        ROLLBACK_MANAGER = null;
        LOGGER.error(MOD_PREFIX + reason);
    }

    private boolean verifyDatabaseConnection() {
        try (var conn = DBConnection.getConnection()) {
            LOGGER.info("{}Database connection succeeded (type={})", MOD_PREFIX, Config.databaseType());
            return true;
        } catch (Exception e) {
            LOGGER.error("{}Database connection failed for type {}", MOD_PREFIX, Config.databaseType(), e);
            return false;
        }
    }
}
