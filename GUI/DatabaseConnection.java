import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Creates the JDBC connection using settings supplied by the environment. */
final class DatabaseConnection {
    private DatabaseConnection() { }

    /**
     * Opens a PostgreSQL connection using the configured database environment variables.
     *
     * @return an open connection for GUI database operations
     * @throws ClassNotFoundException if the PostgreSQL JDBC driver is unavailable
     * @throws SQLException if the connection cannot be established
     */
    static Connection connect() throws ClassNotFoundException, SQLException {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(
                requiredSetting("DB_URL"),
                requiredSetting("DB_USER"),
                requiredSetting("DB_PASSWORD"));
    }

    private static String requiredSetting(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing required environment variable: " + name
                            + ". Configure it in the local .env file.");
        }
        return value;
    }
}
