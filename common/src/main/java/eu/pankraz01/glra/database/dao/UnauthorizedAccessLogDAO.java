package eu.pankraz01.glra.database.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import eu.pankraz01.glra.database.DBConnection;

/**
 * Persists unauthorized web access attempts with a hard cap of 1000 rows.
 */
public final class UnauthorizedAccessLogDAO {
    private static final int MAX_ROWS = 1000;
    private static final int MAX_FIELD_LEN = 4000;

    public void log(long ts, String ip, String method, String path, String query, String headers, String body, String userAgent, String referer, String reason) throws SQLException {
        try (Connection conn = DBConnection.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO glra_web_unauthorized (ts, ip, method, path, query, headers, body, user_agent, referer, reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setLong(1, ts);
                ps.setString(2, truncate(ip));
                ps.setString(3, truncate(method));
                ps.setString(4, truncate(path, 512));
                ps.setString(5, truncate(query));
                ps.setString(6, truncate(headers));
                ps.setString(7, truncate(body, 8000)); // allow a larger body than other fields
                ps.setString(8, truncate(userAgent));
                ps.setString(9, truncate(referer));
                ps.setString(10, truncate(reason, 512));
                ps.executeUpdate();
            }

            try (PreparedStatement cleanup = conn.prepareStatement("""
                    DELETE FROM glra_web_unauthorized
                    WHERE id NOT IN (SELECT id FROM glra_web_unauthorized ORDER BY ts DESC LIMIT ?)
                    """)) {
                cleanup.setInt(1, MAX_ROWS);
                cleanup.executeUpdate();
            }
        }
    }

    private String truncate(String value) {
        return truncate(value, MAX_FIELD_LEN);
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        if (value.length() <= maxLen) return value;
        return value.substring(0, maxLen);
    }
}
