package eu.pankraz01.glra.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DAO for loading actions from the griefLogger DB schema (blocks/items).
 * This implementation focuses on `blocks` and joins `materials` and `users` for readable names.
 */
public final class ActionDAO {
    /**
     * Load block actions since `sinceMillis`. Player filter is optional and matches the username.
     * Returns a list ordered by time DESC.
     */
    public List<Action> loadBlockActions(long sinceMillis, Optional<String> player) throws SQLException {
        final List<Action> result = new ArrayList<>();

        final StringBuilder sql = new StringBuilder();
        sql.append("SELECT b.time AS ts, b.user AS user_id, u.name AS player_name, b.level AS level_id, l.name AS level_name, b.x, b.y, b.z, b.type AS material_id, m.name AS material_name, b.action AS action_code ");
        sql.append("FROM blocks b ");
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

                    result.add(new Action(ts, userId, playerName, levelId, levelName, x, y, z, materialId, materialName, actionCode));
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
}
