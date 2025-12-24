package eu.pankraz01.glra;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import eu.pankraz01.glra.database.DBConnection;
import eu.pankraz01.glra.database.DatabaseSetup;
import eu.pankraz01.glra.rollback.RollbackManager;
import eu.pankraz01.glra.web.RollbackWebServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * Platform-agnostic core entry point invoked from platform-specific bootstrap code.
 */
public final class GriefloggerRollbackAddon {
    public static final String MODID = Permissions.MODID;
    public static final String MOD_PREFIX = "[" + MODID + "] ";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static RollbackManager ROLLBACK_MANAGER;
    private static volatile boolean ENABLED = true;
    private static RollbackWebServer webServer;

    private GriefloggerRollbackAddon() {}

    public static void init() {
        Config.reload();
        if (!verifyDatabaseConnection()) {
            disableAddon("Database connection failed; disabling Grieflogger Rollback Addon functionality");
            return;
        }

        DatabaseSetup.ensureTables();
        ROLLBACK_MANAGER = new RollbackManager();
        LOGGER.info("[Grieflogger Rollback Addon] Common setup complete");
    }

    public static void onServerStarting(MinecraftServer server) {
        if (!isEnabled()) {
            LOGGER.warn(MOD_PREFIX + "Skipping command registration because addon is disabled (no database connection)");
            return;
        }

        try {
            registerCommands(server);
            startWebServer(server);
        } catch (Throwable t) {
            LOGGER.warn(MOD_PREFIX + "Could not register commands on server start", t);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        if (!isEnabled() || ROLLBACK_MANAGER == null) return;

        try {
            ROLLBACK_MANAGER.tick(server);
        } catch (Exception e) {
            LOGGER.error(MOD_PREFIX + "Rollback tick failed", e);
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        stopWebServerCommand();
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    private static void disableAddon(String reason) {
        ENABLED = false;
        ROLLBACK_MANAGER = null;
        LOGGER.error(MOD_PREFIX + reason);
    }

    private static boolean verifyDatabaseConnection() {
        try (var conn = DBConnection.getConnection()) {
            LOGGER.info("{}Database connection succeeded (type={})", MOD_PREFIX, Config.databaseType());
            return true;
        } catch (Exception e) {
            LOGGER.error("{}Database connection failed for type {}", MOD_PREFIX, Config.databaseType(), e);
            return false;
        }
    }

    private static StartResult startWebServerInternal(MinecraftServer server) {
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

    public static synchronized StartResult startWebServerCommand(MinecraftServer server) {
        return startWebServerInternal(server);
    }

    public static synchronized StopResult stopWebServerCommand() {
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

    private static void startWebServer(MinecraftServer server) {
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

    private static void registerCommands(MinecraftServer server) {
        try {
            var dispatcher = server.getCommands().getDispatcher();
            java.util.List<String> registered = new java.util.ArrayList<>();

            eu.pankraz01.glra.commands.RollbackCommand.register(dispatcher);
            registered.add("/gl rollback");
            eu.pankraz01.glra.commands.RollbackUndoCommand.register(dispatcher);
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
