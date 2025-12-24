package eu.pankraz01.glra.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import eu.pankraz01.glra.Config;
import eu.pankraz01.glra.platform.PlatformServices;

public final class ConfigLoader {
    private static final String FILE_NAME = "grieflogger/griefloggerrollbackaddon.properties";

    private ConfigLoader() {}

    public static ConfigData load() {
        Properties props = new Properties();
        Path path = PlatformServices.configDir().resolve(FILE_NAME);
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }
            } else {
                Files.createFile(path);
                seedDefaults(props);
                try (OutputStream out = Files.newOutputStream(path)) {
                    props.store(out, "Grieflogger Rollback Addon configuration");
                }
            }
        } catch (IOException ignored) {
            // If the config cannot be loaded, fall back to defaults.
        }

        return new ConfigData(
                props.getProperty("dbType", Config.DB_TYPE.get()),
                props.getProperty("dbHost", Config.DB_HOST.get()),
                parseInt(props, "dbPort", Config.DB_PORT.getAsInt()),
                props.getProperty("dbName", Config.DB_NAME.get()),
                props.getProperty("dbUser", Config.DB_USER.get()),
                props.getProperty("dbPassword", Config.DB_PASSWORD.get()),
                props.getProperty("dbFile", Config.DB_FILE.get()),
                parseInt(props, "rollbackBatchSize", Config.ROLLBACK_BATCH_SIZE.getAsInt()),
                parseInt(props, "progressTickInterval", Config.PROGRESS_TICK_INTERVAL.getAsInt()),
                parseBool(props, "webApiEnabled", Config.WEB_API_ENABLED.get()),
                parseBool(props, "requireApiToken", Config.REQUIRE_API_TOKEN.get()),
                parseBool(props, "webAuditEnabled", Config.WEB_AUDIT_ENABLED.get()),
                parseBool(props, "webAuditChatEnabled", Config.WEB_AUDIT_CHAT_ENABLED.get()),
                parseBool(props, "webAuditBlocksEnabled", Config.WEB_AUDIT_BLOCKS_ENABLED.get()),
                parseBool(props, "webAuditContainersEnabled", Config.WEB_AUDIT_CONTAINERS_ENABLED.get()),
                parseBool(props, "logUnauthorizedWebAccess", Config.LOG_UNAUTHORIZED_WEB_ACCESS.get()),
                parseBool(props, "logUnauthorizedWebHeaders", Config.LOG_UNAUTHORIZED_WEB_HEADERS.get()),
                parseBool(props, "logUnauthorizedWebBody", Config.LOG_UNAUTHORIZED_WEB_BODY.get()),
                parseBool(props, "logUnauthorizedWebQuery", Config.LOG_UNAUTHORIZED_WEB_QUERY.get()),
                props.getProperty("webApiBindAddress", Config.WEB_API_BIND_ADDRESS.get()),
                parseInt(props, "webApiPort", Config.WEB_API_PORT.getAsInt()),
                props.getProperty("webApiToken", Config.WEB_API_TOKEN.get())
        );
    }

    private static void seedDefaults(Properties props) {
        props.setProperty("dbType", Config.DB_TYPE.get());
        props.setProperty("dbHost", Config.DB_HOST.get());
        props.setProperty("dbPort", Integer.toString(Config.DB_PORT.getAsInt()));
        props.setProperty("dbName", Config.DB_NAME.get());
        props.setProperty("dbUser", Config.DB_USER.get());
        props.setProperty("dbPassword", Config.DB_PASSWORD.get());
        props.setProperty("dbFile", Config.DB_FILE.get());
        props.setProperty("rollbackBatchSize", Integer.toString(Config.ROLLBACK_BATCH_SIZE.getAsInt()));
        props.setProperty("progressTickInterval", Integer.toString(Config.PROGRESS_TICK_INTERVAL.getAsInt()));
        props.setProperty("webApiEnabled", Boolean.toString(Config.WEB_API_ENABLED.get()));
        props.setProperty("requireApiToken", Boolean.toString(Config.REQUIRE_API_TOKEN.get()));
        props.setProperty("webAuditEnabled", Boolean.toString(Config.WEB_AUDIT_ENABLED.get()));
        props.setProperty("webAuditChatEnabled", Boolean.toString(Config.WEB_AUDIT_CHAT_ENABLED.get()));
        props.setProperty("webAuditBlocksEnabled", Boolean.toString(Config.WEB_AUDIT_BLOCKS_ENABLED.get()));
        props.setProperty("webAuditContainersEnabled", Boolean.toString(Config.WEB_AUDIT_CONTAINERS_ENABLED.get()));
        props.setProperty("logUnauthorizedWebAccess", Boolean.toString(Config.LOG_UNAUTHORIZED_WEB_ACCESS.get()));
        props.setProperty("logUnauthorizedWebHeaders", Boolean.toString(Config.LOG_UNAUTHORIZED_WEB_HEADERS.get()));
        props.setProperty("logUnauthorizedWebBody", Boolean.toString(Config.LOG_UNAUTHORIZED_WEB_BODY.get()));
        props.setProperty("logUnauthorizedWebQuery", Boolean.toString(Config.LOG_UNAUTHORIZED_WEB_QUERY.get()));
        props.setProperty("webApiBindAddress", Config.WEB_API_BIND_ADDRESS.get());
        props.setProperty("webApiPort", Integer.toString(Config.WEB_API_PORT.getAsInt()));
        props.setProperty("webApiToken", Config.WEB_API_TOKEN.get());
    }

    private static boolean parseBool(Properties props, String key, boolean defaultVal) {
        String val = props.getProperty(key);
        if (val == null) return defaultVal;
        return switch (val.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y" -> true;
            case "false", "0", "no", "n" -> false;
            default -> defaultVal;
        };
    }

    private static int parseInt(Properties props, String key, int defaultVal) {
        String val = props.getProperty(key);
        if (val == null) return defaultVal;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException ex) {
            return defaultVal;
        }
    }

    public record ConfigData(
            String dbType,
            String dbHost,
            int dbPort,
            String dbName,
            String dbUser,
            String dbPassword,
            String dbFile,
            int rollbackBatchSize,
            int progressTickInterval,
            boolean webApiEnabled,
            boolean requireApiToken,
            boolean webAuditEnabled,
            boolean webAuditChatEnabled,
            boolean webAuditBlocksEnabled,
            boolean webAuditContainersEnabled,
            boolean logUnauthorizedWebAccess,
            boolean logUnauthorizedWebHeaders,
            boolean logUnauthorizedWebBody,
            boolean logUnauthorizedWebQuery,
            String webApiBindAddress,
            int webApiPort,
            String webApiToken
    ) {}
}
