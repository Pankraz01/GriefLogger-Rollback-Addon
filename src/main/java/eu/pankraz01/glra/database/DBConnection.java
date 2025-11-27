package eu.pankraz01.glra.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import eu.pankraz01.glra.Config;

public final class DBConnection {
    public static Connection getConnection() throws SQLException {
        final String host = Config.DB_HOST.get();
        final int port = Config.DB_PORT.getAsInt();
        final String db = Config.DB_NAME.get();
        final String user = Config.DB_USER.get();
        final String password = Config.DB_PASSWORD.get();

        final String url = String.format("jdbc:mariadb://%s:%d/%s?useSSL=false", host, port, db);
        return DriverManager.getConnection(url, user, password);
    }
}
