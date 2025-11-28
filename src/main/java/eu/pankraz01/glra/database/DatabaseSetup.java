package eu.pankraz01.glra.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import eu.pankraz01.glra.Config;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Creates addon-owned tables if they do not yet exist.
 */
public final class DatabaseSetup {
    private static final Logger LOGGER = LogUtils.getLogger();
    private DatabaseSetup() {
    }

    public static void ensureTables() {
        try (Connection conn = DBConnection.getConnection(); Statement st = conn.createStatement()) {
            String tokenTable = switch (Config.databaseType()) {
                case SQLITE -> """
                        CREATE TABLE IF NOT EXISTS glra_web_tokens (
                          user_id INTEGER PRIMARY KEY,
                          token TEXT NOT NULL,
                          created_at INTEGER NOT NULL
                        )
                        """;
                default -> """
                        CREATE TABLE IF NOT EXISTS glra_web_tokens (
                          user_id INT PRIMARY KEY,
                          token VARCHAR(128) NOT NULL,
                          created_at BIGINT NOT NULL
                        )
                        """;
            };
            st.execute(tokenTable);

            String historyTable = switch (Config.databaseType()) {
                case SQLITE -> """
                        CREATE TABLE IF NOT EXISTS glra_rollback_history (
                          id INTEGER PRIMARY KEY,
                          ts BIGINT NOT NULL,
                          actor_id INTEGER,
                          actor_name VARCHAR(64),
                          source VARCHAR(16) NOT NULL,
                          time_label VARCHAR(64),
                          duration_ms BIGINT NOT NULL,
                          player VARCHAR(64),
                          radius VARCHAR(64),
                          scope VARCHAR(32)
                        )
                        """;
                default -> """
                        CREATE TABLE IF NOT EXISTS glra_rollback_history (
                          id BIGINT PRIMARY KEY,
                          ts BIGINT NOT NULL,
                          actor_id INT,
                          actor_name VARCHAR(64),
                          source VARCHAR(16) NOT NULL,
                          time_label VARCHAR(64),
                          duration_ms BIGINT NOT NULL,
                          player VARCHAR(64),
                          radius VARCHAR(64),
                          scope VARCHAR(32)
                        )
                        """;
            };
            st.execute(historyTable);

            String actionsTable = switch (Config.databaseType()) {
                case SQLITE -> """
                        CREATE TABLE IF NOT EXISTS glra_rollback_actions (
                          id INTEGER PRIMARY KEY,
                          job_id BIGINT NOT NULL,
                          ts BIGINT NOT NULL,
                          type VARCHAR(16) NOT NULL,
                          level_name VARCHAR(128),
                          x INT,
                          y INT,
                          z INT,
                          material VARCHAR(128),
                          old_material VARCHAR(128),
                          amount INT,
                          item_data TEXT,
                          action_type INT NOT NULL
                        )
                        """;
                default -> """
                        CREATE TABLE IF NOT EXISTS glra_rollback_actions (
                          id BIGINT PRIMARY KEY,
                          job_id BIGINT NOT NULL,
                          ts BIGINT NOT NULL,
                          type VARCHAR(16) NOT NULL,
                          level_name VARCHAR(128),
                          x INT,
                          y INT,
                          z INT,
                          material VARCHAR(128),
                          old_material VARCHAR(128),
                          amount INT,
                          item_data TEXT,
                          action_type INT NOT NULL
                        )
                        """;
            };
            st.execute(actionsTable);

            String actionTypes = switch (Config.databaseType()) {
                case SQLITE -> """
                        CREATE TABLE IF NOT EXISTS glra_action_types (
                          id INTEGER PRIMARY KEY,
                          name VARCHAR(64) NOT NULL
                        )
                        """;
                default -> """
                        CREATE TABLE IF NOT EXISTS glra_action_types (
                          id INT PRIMARY KEY,
                          name VARCHAR(64) NOT NULL
                        )
                        """;
            };
            st.execute(actionTypes);

            String unauthorizedTable = switch (Config.databaseType()) {
                case SQLITE -> """
                        CREATE TABLE IF NOT EXISTS glra_web_unauthorized (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          ts BIGINT NOT NULL,
                          ip VARCHAR(64),
                          method VARCHAR(16),
                          path VARCHAR(256),
                          query TEXT,
                          headers TEXT,
                          body TEXT,
                          user_agent TEXT,
                          referer TEXT,
                          reason TEXT
                        )
                        """;
                default -> """
                        CREATE TABLE IF NOT EXISTS glra_web_unauthorized (
                          id BIGINT PRIMARY KEY AUTO_INCREMENT,
                          ts BIGINT NOT NULL,
                          ip VARCHAR(64),
                          method VARCHAR(16),
                          path VARCHAR(256),
                          query TEXT,
                          headers TEXT,
                          body TEXT,
                          user_agent TEXT,
                          referer TEXT,
                          reason TEXT
                        )
                        """;
            };
            st.execute(unauthorizedTable);

            addColumnIfMissing(st, "glra_web_tokens", "user_id", "INT");
            addColumnIfMissing(st, "glra_web_tokens", "token", "VARCHAR(128)");
            addColumnIfMissing(st, "glra_web_tokens", "created_at", "BIGINT");

            addColumnIfMissing(st, "glra_rollback_history", "actor_id", "INT");
            addColumnIfMissing(st, "glra_rollback_history", "actor_name", "VARCHAR(64)");

            addColumnIfMissing(st, "glra_rollback_actions", "action_type", "INT");

            addColumnIfMissing(st, "glra_web_unauthorized", "ip", "VARCHAR(64)");
            addColumnIfMissing(st, "glra_web_unauthorized", "ts", "BIGINT");
            addColumnIfMissing(st, "glra_web_unauthorized", "method", "VARCHAR(16)");
            addColumnIfMissing(st, "glra_web_unauthorized", "path", "VARCHAR(256)");
            addColumnIfMissing(st, "glra_web_unauthorized", "query", "TEXT");
            addColumnIfMissing(st, "glra_web_unauthorized", "headers", "TEXT");
            addColumnIfMissing(st, "glra_web_unauthorized", "body", "TEXT");
            addColumnIfMissing(st, "glra_web_unauthorized", "user_agent", "TEXT");
            addColumnIfMissing(st, "glra_web_unauthorized", "referer", "TEXT");
            addColumnIfMissing(st, "glra_web_unauthorized", "reason", "TEXT");

            seedActionTypes(st);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure GLRA tables", e);
        }
    }

    private static void seedActionTypes(Statement st) throws SQLException {
        String insertIgnore = switch (Config.databaseType()) {
            case SQLITE -> "INSERT OR IGNORE INTO glra_action_types (id, name) VALUES (?, ?)";
            default -> "INSERT IGNORE INTO glra_action_types (id, name) VALUES (?, ?)";
        };
        try (var ps = st.getConnection().prepareStatement(insertIgnore)) {
            upsertActionType(ps, 1, "block_break");
            upsertActionType(ps, 2, "block_place");
            upsertActionType(ps, 3, "block_other");
            upsertActionType(ps, 4, "container_add");
            upsertActionType(ps, 5, "container_remove");
            upsertActionType(ps, 6, "container_other");
        }
    }

    private static void addColumnIfMissing(Statement st, String table, String column, String type) {
        try {
            Connection conn = st.getConnection();
            if (columnExists(conn, table, column)) {
                return;
            }
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            LOGGER.info("[GLRA] Added missing column {}.{} {}", table, column, type);
        } catch (SQLException e) {
            LOGGER.warn("[GLRA] Could not add column {}.{} ({}): {}", table, column, type, e.getMessage());
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) {
        try {
            var meta = conn.getMetaData();
            String tbl = table;
            String col = column;
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, tbl, col)) {
                if (rs.next()) return true;
            }
            // Try lowercase/uppercase fallbacks for drivers with case quirks
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, tbl.toUpperCase(), col.toUpperCase())) {
                if (rs.next()) return true;
            }
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, tbl.toLowerCase(), col.toLowerCase())) {
                if (rs.next()) return true;
            }
        } catch (SQLException e) {
            LOGGER.warn("[GLRA] Column existence check failed for {}.{}: {}", table, column, e.getMessage());
        }
        return false;
    }

    private static void upsertActionType(java.sql.PreparedStatement ps, int id, String name) throws SQLException {
        ps.setInt(1, id);
        ps.setString(2, name);
        ps.executeUpdate();
    }
}
