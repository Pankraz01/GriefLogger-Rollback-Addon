package eu.pankraz01.glra.database.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import eu.pankraz01.glra.database.Action;
import eu.pankraz01.glra.database.ContainerAction;
import eu.pankraz01.glra.database.DBConnection;

public final class RollbackActionLogDAO {
    public record LoggedRollbackAction(long id, long jobId, long ts, String type, String levelName, int x, int y, int z,
                                       String material, String oldMaterial, int amount, String itemData, int actionType) {
    }

    public void logBlock(long jobId, Action action) {
        int actionType = switch (action.kind()) {
            case BREAK -> 1;
            case PLACE -> 2;
            default -> 3;
        };
        log(jobId, "block", action.levelName, action.x, action.y, action.z, action.materialName, action.oldMaterialName, actionType, 0, null);
    }

    public void logContainer(long jobId, ContainerAction action) {
        int actionType = switch (action.kind()) {
            case ADD -> 4;
            case REMOVE -> 5;
            default -> 6;
        };
        String data = null;
        if (action.data != null && action.data.length > 0) {
            data = Base64.getEncoder().encodeToString(action.data);
        }
        log(jobId, "container", action.levelName, action.x, action.y, action.z, action.materialName, null, actionType, action.amount, data);
    }

    /**
     * Load all logged rollback actions for the given job ids, newest first (reverse order of original rollback).
     */
    public List<LoggedRollbackAction> loadActionsForJobs(List<Long> jobIds) throws SQLException {
        if (jobIds == null || jobIds.isEmpty()) return List.of();

        StringBuilder sql = new StringBuilder("SELECT id, job_id, ts, type, level_name, x, y, z, material, old_material, amount, item_data, action_type ");
        sql.append("FROM glra_rollback_actions WHERE job_id IN (");
        sql.append(String.join(",", Collections.nCopies(jobIds.size(), "?")));
        sql.append(") ORDER BY id DESC");

        List<LoggedRollbackAction> result = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Long id : jobIds) {
                ps.setLong(idx++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LoggedRollbackAction(
                            rs.getLong("id"),
                            rs.getLong("job_id"),
                            rs.getLong("ts"),
                            rs.getString("type"),
                            rs.getString("level_name"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("material"),
                            rs.getString("old_material"),
                            rs.getInt("amount"),
                            rs.getString("item_data"),
                            rs.getInt("action_type")
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Load recent rollback actions across all jobs, newest first.
     */
    public List<LoggedRollbackAction> loadRecentActions(int limit) throws SQLException {
        List<LoggedRollbackAction> result = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, job_id, ts, type, level_name, x, y, z, material, old_material, amount, item_data, action_type ");
        sql.append("FROM glra_rollback_actions ORDER BY ts DESC LIMIT ?");

        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LoggedRollbackAction(
                            rs.getLong("id"),
                            rs.getLong("job_id"),
                            rs.getLong("ts"),
                            rs.getString("type"),
                            rs.getString("level_name"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("material"),
                            rs.getString("old_material"),
                            rs.getInt("amount"),
                            rs.getString("item_data"),
                            rs.getInt("action_type")
                    ));
                }
            }
        }
        return result;
    }

    /**
     * Load specific rollback actions by their ids, newest first.
     */
    public List<LoggedRollbackAction> loadActionsByIds(List<Long> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) return List.of();

        StringBuilder sql = new StringBuilder("SELECT id, job_id, ts, type, level_name, x, y, z, material, old_material, amount, item_data, action_type ");
        sql.append("FROM glra_rollback_actions WHERE id IN (");
        sql.append(String.join(",", Collections.nCopies(ids.size(), "?")));
        sql.append(") ORDER BY ts DESC");

        List<LoggedRollbackAction> result = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Long id : ids) {
                ps.setLong(idx++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LoggedRollbackAction(
                            rs.getLong("id"),
                            rs.getLong("job_id"),
                            rs.getLong("ts"),
                            rs.getString("type"),
                            rs.getString("level_name"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("material"),
                            rs.getString("old_material"),
                            rs.getInt("amount"),
                            rs.getString("item_data"),
                            rs.getInt("action_type")
                    ));
                }
            }
        }
        return result;
    }

    private void log(long jobId, String type, String levelName, int x, int y, int z, String material, String oldMaterial, int actionType, int amount, String itemData) {
        long now = System.currentTimeMillis();
        long id = now;
        int attempts = 0;
        while (attempts < 5) {
            try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO glra_rollback_actions (id, job_id, ts, type, level_name, x, y, z, material, old_material, amount, item_data, action_type) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, id);
                ps.setLong(2, jobId);
                ps.setLong(3, now);
                ps.setString(4, type);
                ps.setString(5, levelName);
                ps.setInt(6, x);
                ps.setInt(7, y);
                ps.setInt(8, z);
                ps.setString(9, material);
                ps.setString(10, oldMaterial);
                ps.setInt(11, amount);
                ps.setString(12, itemData);
                ps.setInt(13, actionType);
                ps.executeUpdate();
                return;
            } catch (SQLException e) {
                if (isPrimaryKeyViolation(e)) {
                    id++;
                    attempts++;
                    continue;
                }
                throw new RuntimeException("Failed to log rollback action", e);
            }
        }
        throw new RuntimeException("Failed to log rollback action after retries");
    }

    private boolean isPrimaryKeyViolation(SQLException e) {
        String state = e.getSQLState();
        return "23000".equals(state) || e.getErrorCode() == 19;
    }
}
