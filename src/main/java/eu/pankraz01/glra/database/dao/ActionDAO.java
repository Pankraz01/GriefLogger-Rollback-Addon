package eu.pankraz01.glra.database.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import eu.pankraz01.glra.database.Action;
import eu.pankraz01.glra.database.ContainerAction;
import eu.pankraz01.glra.database.DBConnection;

/**
 * DAO for loading actions from the griefLogger DB schema (blocks/items).
 * This implementation focuses on `blocks` and joins `materials` and `users` for readable names.
 */
public final class ActionDAO {
    /**
     * Load container (inventory) actions since `sinceMillis`. Player filter is optional and matches the username.
     * Returns a list ordered by time DESC.
     */
    public List<ContainerAction> loadContainerActions(long sinceMillis, Optional<String> player) throws SQLException {
        final List<ContainerAction> result = new ArrayList<>();

        final StringBuilder sql = new StringBuilder();
        sql.append("SELECT c.time AS ts, c.user AS user_id, u.name AS player_name, c.level AS level_id, l.name AS level_name, ");
        sql.append("c.x, c.y, c.z, c.type AS material_id, m.name AS material_name, c.data AS item_data, c.amount AS amount, c.action AS action_code ");
        sql.append("FROM containers c ");
        sql.append("LEFT JOIN materials m ON m.id = c.type ");
        sql.append("LEFT JOIN users u ON u.id = c.user ");
        sql.append("LEFT JOIN levels l ON l.id = c.level ");
        sql.append("WHERE c.time >= ? ");
        if (player.isPresent()) sql.append("AND u.name = ? ");
        sql.append("ORDER BY c.time DESC");

        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, sinceMillis);
            if (player.isPresent()) ps.setString(idx++, player.get());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong("ts");
                    int userId = rs.getInt("user_id");
                    String playerName = rs.getString("player_name");
                    int levelId = rs.getInt("level_id");
                    String levelName = rs.getString("level_name");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    int materialId = rs.getInt("material_id");
                    String materialName = rs.getString("material_name");
                    byte[] data = rs.getBytes("item_data");
                    int amount = rs.getInt("amount");
                    int actionCode = rs.getInt("action_code");

                    result.add(new ContainerAction(ts, userId, playerName, levelId, levelName, x, y, z, materialId, materialName, data, amount, actionCode));
                }
            }
        }

        return result;
    }

    /**
     * Load block actions since `sinceMillis`. Player filter is optional and matches the username.
     * Returns a list ordered by time DESC.
     */
    public List<Action> loadBlockActions(long sinceMillis, Optional<String> player) throws SQLException {
        final List<Action> result = new ArrayList<>();

        final StringBuilder sql = new StringBuilder();
        sql.append("SELECT b.time AS ts, b.user AS user_id, u.name AS player_name, b.level AS level_id, l.name AS level_name, b.x, b.y, b.z, b.type AS material_id, m.name AS material_name, b.action AS action_code, a.name AS action_name ");
        sql.append("FROM blocks b ");
        sql.append("LEFT JOIN actions a ON a.id = b.action ");
        sql.append("LEFT JOIN materials m ON m.id = b.type ");
        sql.append("LEFT JOIN users u ON u.id = b.user ");
        sql.append("LEFT JOIN levels l ON l.id = b.level ");
        sql.append("WHERE b.time >= ? ");
        if (player.isPresent()) sql.append("AND u.name = ? ");
        sql.append("ORDER BY b.time DESC");

        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, sinceMillis);
            if (player.isPresent()) ps.setString(idx++, player.get());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long ts = rs.getLong("ts");
                    int userId = rs.getInt("user_id");
                    String playerName = rs.getString("player_name");
                    int levelId = rs.getInt("level_id");
                    String levelName = rs.getString("level_name");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    int materialId = rs.getInt("material_id");
                    String materialName = rs.getString("material_name");
                    int actionCode = rs.getInt("action_code");
                    String actionName = rs.getString("action_name");

                    result.add(new Action(ts, userId, playerName, levelId, levelName, x, y, z, materialId, materialName, actionCode, actionName));
                }
            }
        }

        // Compute oldMaterialName for each action by simulating the timeline oldest->newest.
        // oldMaterialName now represents the block state before the action occurred.
        Map<String, String> currentState = new HashMap<>();
        for (int i = result.size() - 1; i >= 0; i--) {
            Action a = result.get(i);
            String key = a.coordKey();

            // what was in the world before this action?
            a.oldMaterialName = currentState.getOrDefault(key, "minecraft:air");

            // update the tracked state to what the world looked like after the action
            switch (a.kind()) {
                case PLACE -> currentState.put(key, normalizeMaterial(a.materialName));
                case BREAK -> currentState.put(key, "minecraft:air");
                default -> currentState.put(key, normalizeMaterial(a.materialName));
            }
        }

        return result;
    }

    private static String normalizeMaterial(String materialName) {
        return (materialName == null || materialName.isEmpty()) ? "minecraft:air" : materialName;
    }

    /**
     * Load all known player usernames from the `users` table.
     */
    public List<String> loadAllUsernames() throws SQLException {
        final List<String> result = new ArrayList<>();
        final String sql = "SELECT name FROM users WHERE name IS NOT NULL ORDER BY name ASC";

        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (name != null && !name.isBlank()) {
                        result.add(name);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Load all known dimensions from the `levels` table (names are ResourceLocations).
     */
    public List<String> loadAllDimensions() throws SQLException {
        final List<String> result = new ArrayList<>();
        final String sql = "SELECT name FROM levels WHERE name IS NOT NULL ORDER BY name ASC";

        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    if (name != null && !name.isBlank()) {
                        result.add(name);
                    }
                }
            }
        }
        return result;
    }
}
