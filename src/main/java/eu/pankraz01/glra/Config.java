package eu.pankraz01.glra;

import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    public enum DatabaseType {
        SQLITE,
        MYSQL,
        MARIADB
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Which database implementation should be used (case-insensitive)
    public static final ModConfigSpec.ConfigValue<String> DB_TYPE = BUILDER
            .comment("Database type for rollback storage (SQLITE, MYSQL, MARIADB). Default: SQLITE")
            .define("dbType", "SQLITE");

    // Only used when dbType is MYSQL or MARIADB; ignored for SQLITE (uses dbFile)
    // Database settings for rollback addon
    public static final ModConfigSpec.ConfigValue<String> DB_HOST = BUILDER
            .comment("MySQL/MariaDB settings below are only used when dbType=MYSQL or MARIADB; ignored for SQLITE", "MySQL/MariaDB host for rollback storage")
            .define("dbHost", "localhost");

    public static final ModConfigSpec.IntValue DB_PORT = BUILDER
            .comment("MySQL/MariaDB port")
            .defineInRange("dbPort", 3306, 1, 65535);

    public static final ModConfigSpec.ConfigValue<String> DB_NAME = BUILDER
            .comment("MySQL/MariaDB database name")
            .define("dbName", "grieflogger");

    public static final ModConfigSpec.ConfigValue<String> DB_USER = BUILDER
            .comment("MySQL/MariaDB user")
            .define("dbUser", "root");

    public static final ModConfigSpec.ConfigValue<String> DB_PASSWORD = BUILDER
            .comment("MySQL/MariaDB password")
            .define("dbPassword", "");

    public static final ModConfigSpec.ConfigValue<String> DB_FILE = BUILDER
            .comment("SQLite database file (used when dbType=SQLITE); relative paths are resolved from the server root")
            .define("dbFile", "config/grieflogger/grieflogger.sqlite");

    // Rollback processing tuning
    public static final ModConfigSpec.IntValue ROLLBACK_BATCH_SIZE = BUILDER
            .comment("Number of rollback actions processed per server tick")
            .defineInRange("rollbackBatchSize", 200, 1, 10000);

    public static final ModConfigSpec.IntValue PROGRESS_TICK_INTERVAL = BUILDER
            .comment("How many ticks between progress logs during a running rollback")
            .defineInRange("progressTickInterval", 20, 1, 1200);

    // Lightweight web UI / HTTP API for triggering rollbacks from outside the game
    public static final ModConfigSpec.BooleanValue WEB_API_ENABLED = BUILDER
            .comment("Enable a small built-in HTTP server with a web UI to trigger rollbacks (default: false)")
            .define("webApiEnabled", false);

    public static final ModConfigSpec.BooleanValue REQUIRE_API_TOKEN = BUILDER
            .comment("Require a non-empty webApiToken for all web UI/API calls; if true and token is empty, the web UI is disabled")
            .define("requireApiToken", false);

    static {
        BUILDER.push("logUnauthorizedWebAccess");
    }

    public static final ModConfigSpec.BooleanValue LOG_UNAUTHORIZED_WEB_ACCESS = BUILDER
            .comment("Log unauthorized web UI/API requests to the database (table glra_web_unauthorized); keeps latest 1000 entries")
            .define("enabled", false);

    public static final ModConfigSpec.BooleanValue LOG_UNAUTHORIZED_WEB_HEADERS = BUILDER
            .comment("If enabled, store request headers for unauthorized web requests")
            .define("logHeaders", false);

    public static final ModConfigSpec.BooleanValue LOG_UNAUTHORIZED_WEB_BODY = BUILDER
            .comment("If enabled, store request body for unauthorized web requests (may include tokens!)")
            .define("logBody", false);

    public static final ModConfigSpec.BooleanValue LOG_UNAUTHORIZED_WEB_QUERY = BUILDER
            .comment("If enabled, store query string for unauthorized web requests")
            .define("logQuery", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec.ConfigValue<String> WEB_API_BIND_ADDRESS = BUILDER
            .comment("Bind address for the web UI / HTTP API (use 127.0.0.1 to keep it local)")
            .define("webApiBindAddress", "0.0.0.0");

    public static final ModConfigSpec.IntValue WEB_API_PORT = BUILDER
            .comment("Port for the web UI / HTTP API")
            .defineInRange("webApiPort", 8765, 1, 65535);

    public static final ModConfigSpec.ConfigValue<String> WEB_API_TOKEN = BUILDER
            .comment("Optional shared secret required for web requests; leave empty to disable auth (not recommended)")
            .define("webApiToken", "");

    static final ModConfigSpec SPEC = BUILDER.build();

    public static DatabaseType databaseType() {
        return parseDatabaseType(DB_TYPE.get());
    }

    private static DatabaseType parseDatabaseType(String raw) {
        if (raw == null) return DatabaseType.SQLITE;
        try {
            return DatabaseType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return DatabaseType.SQLITE;
        }
    }
}
