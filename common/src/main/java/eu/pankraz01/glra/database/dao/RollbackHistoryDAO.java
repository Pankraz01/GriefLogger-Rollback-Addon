package eu.pankraz01.glra.database.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import eu.pankraz01.glra.database.DBConnection;
import eu.pankraz01.glra.rollback.RollbackManager;

public final class RollbackHistoryDAO {
    public record HistoryEntry(long id, long ts, Integer actorId, String actorName, String source, String timeLabel,
                               long durationMs, String player, String radius, String scope) {}

    public void record(Integer actorId, String actorName, String source, String timeLabel, long durationMs, Optional<String> player, Optional<String> radiusLabel, RollbackManager.RollbackKind kind) {
        long now = System.currentTimeMillis();
        long id = now;
        int attempts = 0;
        while (attempts < 5) {
            if (tryInsert(id, now, actorId, actorName, source, timeLabel, durationMs, player, radiusLabel, kind)) {
                return;
            }
            id++;
            attempts++;
        }
        throw new RuntimeException("Failed to record rollback history after retries");
    }

    public long recordAndReturnId(Integer actorId, String actorName, String source, String timeLabel, long durationMs, Optional<String> player, Optional<String> radiusLabel, RollbackManager.RollbackKind kind) {
        long now = System.currentTimeMillis();
        long id = now;
        int attempts = 0;
        while (attempts < 5) {
            if (tryInsert(id, now, actorId, actorName, source, timeLabel, durationMs, player, radiusLabel, kind)) {
                return id;
            }
            id++;
            attempts++;
        }
        throw new RuntimeException("Failed to record rollback history after retries");
    }

    public List<Long> loadRecentHistoryIds(int limit) throws SQLException {
        List<Long> ids = new ArrayList<>();
        if (limit <= 0) return ids;
        String sql = "SELECT id FROM glra_rollback_history ORDER BY ts DESC LIMIT ?";
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        }
        return ids;
    }

    public List<HistoryEntry> loadRecent(int limit, Optional<String> playerFilter) throws SQLException {
        List<HistoryEntry> entries = new ArrayList<>();
        if (limit <= 0) return entries;

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, ts, actor_id, actor_name, source, time_label, duration_ms, player, radius, scope ");
        sql.append("FROM glra_rollback_history ");
        if (playerFilter.isPresent()) {
            sql.append("WHERE player = ? OR actor_name = ? ");
        }
        sql.append("ORDER BY ts DESC LIMIT ?");

        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (playerFilter.isPresent()) {
                String name = playerFilter.get();
                ps.setString(idx++, name);
                ps.setString(idx++, name);
            }
            ps.setInt(idx, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer actorId = rs.getObject("actor_id") == null ? null : rs.getInt("actor_id");
                    entries.add(new HistoryEntry(
                            rs.getLong("id"),
                            rs.getLong("ts"),
                            actorId,
                            rs.getString("actor_name"),
                            rs.getString("source"),
                            rs.getString("time_label"),
                            rs.getLong("duration_ms"),
                            rs.getString("player"),
                            rs.getString("radius"),
                            rs.getString("scope")
                    ));
                }
            }
        }

        return entries;
    }

    private boolean isPrimaryKeyViolation(SQLException e) {
        // Best-effort: SQLState 23000 for MySQL/MariaDB, SQLite uses 19
        String state = e.getSQLState();
        return "23000".equals(state) || e.getErrorCode() == 19;
    }

    private boolean tryInsert(long id, long ts, Integer actorId, String actorName, String source, String timeLabel, long durationMs, Optional<String> player, Optional<String> radiusLabel, RollbackManager.RollbackKind kind) {
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO glra_rollback_history (id, ts, actor_id, actor_name, source, time_label, duration_ms, player, radius, scope) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, id);
            ps.setLong(2, ts);
            if (actorId == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, actorId);
            }
            ps.setString(4, actorName);
            ps.setString(5, source);
            ps.setString(6, timeLabel);
            ps.setLong(7, durationMs);
            ps.setString(8, player.orElse(null));
            ps.setString(9, radiusLabel.orElse(null));
            ps.setString(10, kind == null ? "both" : kind.describe());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (isPrimaryKeyViolation(e)) {
                return false;
            }
            throw new RuntimeException("Failed to record rollback history", e);
        }
    }
}
