package eu.pankraz01.glra.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.file.Path;
import java.nio.file.Paths;

import eu.pankraz01.glra.Config;

public final class DBConnection {
    public static Connection getConnection() throws SQLException {
        return switch (Config.databaseType()) {
            case SQLITE -> getSqliteConnection();
            case MYSQL -> getMysqlConnection("mysql");
            case MARIADB -> getMysqlConnection("mariadb");
        };
    }

    private static Connection getSqliteConnection() throws SQLException {
        final String file = Config.DB_FILE.get();
        final Path path = Paths.get(file).toAbsolutePath();
        final String url = "jdbc:sqlite:" + path;
        return DriverManager.getConnection(url);
    }

    private static Connection getMysqlConnection(String driver) throws SQLException {
        final String host = Config.DB_HOST.get();
        final int port = Config.DB_PORT.getAsInt();
        final String db = Config.DB_NAME.get();
        final String user = Config.DB_USER.get();
        final String password = Config.DB_PASSWORD.get();

        final String url = String.format("jdbc:%s://%s:%d/%s?useSSL=false", driver, host, port, db);
        return DriverManager.getConnection(url, user, password);
    }
}
