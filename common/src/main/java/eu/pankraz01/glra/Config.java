package eu.pankraz01.glra;

import java.util.Locale;

import eu.pankraz01.glra.config.ConfigLoader;
import eu.pankraz01.glra.config.ConfigLoader.ConfigData;

/**
 * Lightweight, platform-agnostic config facade. Values are loaded from a simple properties file
 * located under {@code config/grieflogger/griefloggerrollbackaddon.properties}.
 */
public final class Config {
    public enum DatabaseType {
        SQLITE,
        MYSQL,
        MARIADB
    }

public static class ConfigValue<T> {
        private T value;

        ConfigValue(T value) {
            this.value = value;
        }

        public T get() {
            return value;
        }

        void set(T value) {
            this.value = value;
        }
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(boolean value) {
            super(value);
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {
        IntValue(int value) {
            super(value);
        }

        public int getAsInt() {
            Integer v = get();
            return v == null ? 0 : v;
        }
    }

    // Database settings
    public static final ConfigValue<String> DB_TYPE = new ConfigValue<>("SQLITE");
    public static final ConfigValue<String> DB_HOST = new ConfigValue<>("localhost");
    public static final IntValue DB_PORT = new IntValue(3306);
    public static final ConfigValue<String> DB_NAME = new ConfigValue<>("grieflogger");
    public static final ConfigValue<String> DB_USER = new ConfigValue<>("root");
    public static final ConfigValue<String> DB_PASSWORD = new ConfigValue<>("");
    public static final ConfigValue<String> DB_FILE = new ConfigValue<>("config/grieflogger/grieflogger.sqlite");

    // Rollback tuning
    public static final IntValue ROLLBACK_BATCH_SIZE = new IntValue(200);
    public static final IntValue PROGRESS_TICK_INTERVAL = new IntValue(20);

    // Web UI / API
    public static final BooleanValue WEB_API_ENABLED = new BooleanValue(false);
    public static final BooleanValue REQUIRE_API_TOKEN = new BooleanValue(false);
    public static final BooleanValue WEB_AUDIT_ENABLED = new BooleanValue(true);
    public static final BooleanValue WEB_AUDIT_CHAT_ENABLED = new BooleanValue(true);
    public static final BooleanValue WEB_AUDIT_BLOCKS_ENABLED = new BooleanValue(true);
    public static final BooleanValue WEB_AUDIT_CONTAINERS_ENABLED = new BooleanValue(true);
    public static final BooleanValue LOG_UNAUTHORIZED_WEB_ACCESS = new BooleanValue(false);
    public static final BooleanValue LOG_UNAUTHORIZED_WEB_HEADERS = new BooleanValue(false);
    public static final BooleanValue LOG_UNAUTHORIZED_WEB_BODY = new BooleanValue(false);
    public static final BooleanValue LOG_UNAUTHORIZED_WEB_QUERY = new BooleanValue(true);
    public static final ConfigValue<String> WEB_API_BIND_ADDRESS = new ConfigValue<>("0.0.0.0");
    public static final IntValue WEB_API_PORT = new IntValue(8765);
    public static final ConfigValue<String> WEB_API_TOKEN = new ConfigValue<>("");

    static {
        reload();
    }

    private Config() {}

    public static void reload() {
        ConfigData data = ConfigLoader.load();
        apply(data);
    }

    public static DatabaseType databaseType() {
        return parseDatabaseType(DB_TYPE.get());
    }

    private static void apply(ConfigData data) {
        DB_TYPE.set(data.dbType());
        DB_HOST.set(data.dbHost());
        DB_PORT.set(data.dbPort());
        DB_NAME.set(data.dbName());
        DB_USER.set(data.dbUser());
        DB_PASSWORD.set(data.dbPassword());
        DB_FILE.set(data.dbFile());

        ROLLBACK_BATCH_SIZE.set(data.rollbackBatchSize());
        PROGRESS_TICK_INTERVAL.set(data.progressTickInterval());

        WEB_API_ENABLED.set(data.webApiEnabled());
        REQUIRE_API_TOKEN.set(data.requireApiToken());
        WEB_AUDIT_ENABLED.set(data.webAuditEnabled());
        WEB_AUDIT_CHAT_ENABLED.set(data.webAuditChatEnabled());
        WEB_AUDIT_BLOCKS_ENABLED.set(data.webAuditBlocksEnabled());
        WEB_AUDIT_CONTAINERS_ENABLED.set(data.webAuditContainersEnabled());
        LOG_UNAUTHORIZED_WEB_ACCESS.set(data.logUnauthorizedWebAccess());
        LOG_UNAUTHORIZED_WEB_HEADERS.set(data.logUnauthorizedWebHeaders());
        LOG_UNAUTHORIZED_WEB_BODY.set(data.logUnauthorizedWebBody());
        LOG_UNAUTHORIZED_WEB_QUERY.set(data.logUnauthorizedWebQuery());
        WEB_API_BIND_ADDRESS.set(data.webApiBindAddress());
        WEB_API_PORT.set(data.webApiPort());
        WEB_API_TOKEN.set(data.webApiToken());
    }

    private static DatabaseType parseDatabaseType(String raw) {
        if (raw == null) return DatabaseType.SQLITE;
        try {
            return DatabaseType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return DatabaseType.SQLITE;
        }
    }
}
