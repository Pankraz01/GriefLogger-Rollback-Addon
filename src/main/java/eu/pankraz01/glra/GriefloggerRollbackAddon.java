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
import eu.pankraz01.glra.commands.RollbackUndoCommand;
import eu.pankraz01.glra.commands.web.WebCommandToken;
import eu.pankraz01.glra.commands.web.WebCommandWebserver;
import eu.pankraz01.glra.commands.ConfigCommand;
import eu.pankraz01.glra.Permissions;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import eu.pankraz01.glra.web.RollbackWebServer;
import net.minecraft.world.level.Level;
import eu.pankraz01.glra.database.DatabaseSetup;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(GriefloggerRollbackAddon.MODID)
public class GriefloggerRollbackAddon {
    public static GriefloggerRollbackAddon INSTANCE;
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
        INSTANCE = this;
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        // Permission gathering is a game event (not a mod-lifecycle event), so register on the NeoForge bus
        NeoForge.EVENT_BUS.addListener(Permissions::registerNodes);

        // No block/item/tab registrations for this mod.

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (GriefloggerRollbackAddon) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);
        // Permissions registered via PermissionGatherEvent listener above


        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "grieflogger/griefloggerrollbackaddon-common.toml");
        // RollbackManager does not register to the event bus (no @SubscribeEvent methods)
    }

    // Public manager instance for other classes (commands, tests, etc.)
    public static RollbackManager ROLLBACK_MANAGER;
    private static volatile boolean ENABLED = true;
    private RollbackWebServer webServer;

    private void commonSetup(FMLCommonSetupEvent event) {
        if (!verifyDatabaseConnection()) {
            disableAddon("Database connection failed; disabling Grieflogger Rollback Addon functionality");
            return;
        }

        DatabaseSetup.ensureTables();
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
                registerCommands(server);

                startWebServer(server);
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

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        stopWebServerCommand();
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

    private StartResult startWebServerInternal(net.minecraft.server.MinecraftServer server) {
        if (!Config.WEB_API_ENABLED.get()) {
            return StartResult.error("web.disabled");
        }
        boolean requireToken = Config.REQUIRE_API_TOKEN.get();
        String token = Config.WEB_API_TOKEN.get();
        if (requireToken && (token == null || token.isBlank())) {
            return StartResult.error("web.token_missing");
        }
        if (ROLLBACK_MANAGER == null) {
            return StartResult.error("web.manager_null");
        }
        if (!isEnabled()) {
            return StartResult.error("addon.disabled");
        }
        if (webServer != null) {
            return StartResult.error("web.already_running");
        }

        try {
            var overworld = server.overworld();
            var defaultLevel = overworld != null ? overworld.dimension() : Level.OVERWORLD;
            webServer = new RollbackWebServer(ROLLBACK_MANAGER, server, defaultLevel, token, requireToken);
            webServer.start(Config.WEB_API_BIND_ADDRESS.get(), Config.WEB_API_PORT.get());
            LOGGER.info(MOD_PREFIX + "Web UI/API started on {}:{}", Config.WEB_API_BIND_ADDRESS.get(), Config.WEB_API_PORT.get());
            if (!requireToken && Config.WEB_API_TOKEN.get().isBlank() && !"127.0.0.1".equals(Config.WEB_API_BIND_ADDRESS.get())) {
                LOGGER.warn(MOD_PREFIX + "Web UI/API running without token on {}:{}. Consider setting webApiToken or binding to 127.0.0.1", Config.WEB_API_BIND_ADDRESS.get(), Config.WEB_API_PORT.get());
            }
            return StartResult.ok();
        } catch (Exception e) {
            LOGGER.error(MOD_PREFIX + "Failed to start web UI/API", e);
            return StartResult.error("web.start_failed", e.getMessage());
        }
    }

    public synchronized StartResult startWebServerCommand(net.minecraft.server.MinecraftServer server) {
        return startWebServerInternal(server);
    }

    public synchronized StopResult stopWebServerCommand() {
        if (webServer == null) {
            return new StopResult(false, "web.not_running");
        }
        try {
            webServer.stop();
            LOGGER.info(MOD_PREFIX + "Web UI/API stopped");
            webServer = null;
            return new StopResult(true, "web.stopped");
        } catch (Exception e) {
            LOGGER.error(MOD_PREFIX + "Failed to stop web UI/API", e);
            return new StopResult(false, "web.stop_failed", e.getMessage());
        }
    }

    private void startWebServer(net.minecraft.server.MinecraftServer server) {
        StartResult result = startWebServerInternal(server);
        if (!result.success()) {
            switch (result.reason()) {
                case "web.disabled" -> LOGGER.info(MOD_PREFIX + "Web UI/API disabled via config");
                case "web.token_missing" -> LOGGER.warn(MOD_PREFIX + "Web UI/API disabled: requireApiToken=true but webApiToken is empty");
                case "addon.disabled" -> LOGGER.warn(MOD_PREFIX + "Web UI/API not started because addon is disabled");
                case "web.already_running" -> LOGGER.info(MOD_PREFIX + "Web UI/API already running");
                case "web.manager_null" -> LOGGER.warn(MOD_PREFIX + "Web UI enabled but rollback manager is null; skipping start");
                default -> LOGGER.warn(MOD_PREFIX + "Web UI/API not started: {}", result.reason());
            }
        }
    }

    private void registerCommands(net.minecraft.server.MinecraftServer server) {
        try {
            var dispatcher = server.getCommands().getDispatcher();
            java.util.List<String> registered = new java.util.ArrayList<>();
            
            RollbackCommand.register(dispatcher);
            registered.add("/gl rollback");
            RollbackUndoCommand.register(dispatcher);
            registered.add("/gl rollback undo");

            eu.pankraz01.glra.commands.web.WebCommandToken.register(dispatcher);
            eu.pankraz01.glra.commands.web.WebCommandWebserver.register(dispatcher);
            eu.pankraz01.glra.commands.ConfigCommand.register(dispatcher);
            registered.add("/gl web token");
            registered.add("/gl web start/stop");
            registered.add("/gl config reload");
            LOGGER.info(MOD_PREFIX + "Registered commands: {}", String.join(", ", registered));
        } catch (Exception e) {
            LOGGER.error(MOD_PREFIX + "Failed to register commands", e);
        }
    }

    public record StartResult(boolean success, String reason, Object... args) {
        static StartResult ok() { return new StartResult(true, "web.started"); }
        static StartResult error(String reason, Object... args) { return new StartResult(false, reason, args); }
    }

    public record StopResult(boolean success, String reason, Object... args) {}
}
