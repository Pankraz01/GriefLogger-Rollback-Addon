package eu.pankraz01.glra.database.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import eu.pankraz01.glra.database.DBConnection;

/**
 * Lightweight DAO to load recent audit data for the web dashboard.
 */
public final class AuditDAO {
    public record ChatEntry(long ts, String playerName, String message) {}

    public record BlockEntry(long ts, String playerName, String levelName, int x, int y, int z, String materialName,
                             int actionCode) {}

    public record ContainerEntry(long ts, String playerName, String levelName, int x, int y, int z, String materialName,
                                 int amount, int actionCode) {}

    public List<ChatEntry> loadRecentChat(int limit) throws SQLException {
        List<ChatEntry> result = new ArrayList<>();
        SQLException lastError = null;

        // Test data: table `chats` with column `message`
        List<String> candidates = List.of(
                "SELECT c.time AS ts, u.name AS player_name, c.message AS msg FROM chats c LEFT JOIN users u ON u.id = c.user ORDER BY c.time DESC LIMIT ?"
        );

        for (String sql : candidates) {
            try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new ChatEntry(
                                rs.getLong("ts"),
                                rs.getString("player_name"),
                                rs.getString("msg")
                        ));
                    }
                }
                // Succeeded with this statement; ignore later candidates.
                return result;
            } catch (SQLException e) {
                lastError = e;
                result.clear();
            }
        }

        if (lastError != null) throw lastError;
        return result;
    }

    public List<BlockEntry> loadRecentBlocks(int limit) throws SQLException {
        List<BlockEntry> result = new ArrayList<>();
        final StringBuilder sql = new StringBuilder();
        sql.append("SELECT b.time AS ts, u.name AS player_name, l.name AS level_name, b.x, b.y, b.z, m.name AS material_name, b.action AS action_code ");
        sql.append("FROM blocks b ");
        sql.append("LEFT JOIN materials m ON m.id = b.type ");
        sql.append("LEFT JOIN users u ON u.id = b.user ");
        sql.append("LEFT JOIN levels l ON l.id = b.level ");
        sql.append("ORDER BY b.time DESC LIMIT ?");

        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new BlockEntry(
                            rs.getLong("ts"),
                            rs.getString("player_name"),
                            rs.getString("level_name"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("material_name"),
                            rs.getInt("action_code")
                    ));
                }
            }
        }

        return result;
    }

    public List<ContainerEntry> loadRecentContainers(int limit) throws SQLException {
        List<ContainerEntry> result = new ArrayList<>();
        final StringBuilder sql = new StringBuilder();
        sql.append("SELECT c.time AS ts, u.name AS player_name, l.name AS level_name, c.x, c.y, c.z, m.name AS material_name, c.amount AS amount, c.action AS action_code ");
        sql.append("FROM containers c ");
        sql.append("LEFT JOIN materials m ON m.id = c.type ");
        sql.append("LEFT JOIN users u ON u.id = c.user ");
        sql.append("LEFT JOIN levels l ON l.id = c.level ");
        sql.append("ORDER BY c.time DESC LIMIT ?");

        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ContainerEntry(
                            rs.getLong("ts"),
                            rs.getString("player_name"),
                            rs.getString("level_name"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("material_name"),
                            rs.getInt("amount"),
                            rs.getInt("action_code")
                    ));
                }
            }
        }

        return result;
    }
}
