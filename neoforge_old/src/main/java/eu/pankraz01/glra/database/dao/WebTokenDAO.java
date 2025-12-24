package eu.pankraz01.glra.database.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import eu.pankraz01.glra.database.DBConnection;

/**
 * Manages per-user web tokens.
 */
public final class WebTokenDAO {
    /**
     * Create or replace a token for a username.
     */
    public String createOrReplace(String username) throws SQLException {
        Integer userId = findUserId(username).orElseThrow(() -> new SQLException("User not found: " + username));
        String token = UUID.randomUUID().toString().replace("-", "");
        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM glra_web_tokens WHERE user_id=?")) {
                del.setInt(1, userId);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement("INSERT INTO glra_web_tokens (user_id, token, created_at) VALUES (?, ?, ?)")) {
                ins.setInt(1, userId);
                ins.setString(2, token);
                ins.setLong(3, System.currentTimeMillis());
                ins.executeUpdate();
            }
            conn.commit();
        }
        return token;
    }

    public boolean remove(String username) throws SQLException {
        Optional<Integer> userId = findUserId(username);
        if (userId.isEmpty()) return false;
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement("DELETE FROM glra_web_tokens WHERE user_id=?")) {
            ps.setInt(1, userId.get());
            return ps.executeUpdate() > 0;
        }
    }

    public Optional<TokenOwner> findUserByToken(String token) throws SQLException {
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT t.user_id, u.name FROM glra_web_tokens t LEFT JOIN users u ON u.id = t.user_id WHERE token=?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt("user_id");
                    String name = rs.getString("name");
                    return Optional.of(new TokenOwner(id, name));
                }
            }
        }
        return Optional.empty();
    }

    public java.util.List<TokenInfo> listTokens(int offset, int limitPlusOne) throws SQLException {
        final java.util.List<TokenInfo> result = new java.util.ArrayList<>();
        final String sql = "SELECT t.user_id, t.token, t.created_at, u.name FROM glra_web_tokens t LEFT JOIN users u ON u.id = t.user_id ORDER BY t.created_at DESC LIMIT ? OFFSET ?";
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limitPlusOne);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int uid = rs.getInt("user_id");
                    String name = rs.getString("name");
                    String token = rs.getString("token");
                    long created = rs.getLong("created_at");
                    result.add(new TokenInfo(uid, name, token, created));
                }
            }
        }
        return result;
    }

    public Optional<Integer> findUserId(String username) throws SQLException {
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE name = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getInt("id"));
                }
            }
        }
        return Optional.empty();
    }

    public record TokenOwner(int userId, String username) {
    }

    public record TokenInfo(int userId, String username, String token, long createdAt) {
    }
}
