package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import org.netbeans.api.db.explorer.ConnectionManager;
import org.netbeans.api.db.explorer.DatabaseConnection;

/**
 * Read-only access to the IDE's registered Database Explorer connections
 * (Services &gt; Databases). Deliberately narrow: it only ever talks to
 * connections the user has already registered and connected through the IDE
 * — it never accepts raw JDBC URLs/credentials from a tool call, and never
 * silently establishes a new connection (see {@link #jdbcConnection}).
 *
 * <p>
 * {@link #executeSqlQuery} and {@link #getTableData} both enforce SELECT-only
 * twice: a textual prefix check on the SQL itself, and
 * {@link Connection#setReadOnly(boolean)} on the JDBC connection so the
 * driver rejects any write the prefix check missed.
 */
public class DatabaseProvider {

    private static final Logger LOG = Logger.getLogger(DatabaseProvider.class.getName());

    public static String listConnections() {
        DatabaseConnection[] conns = ConnectionManager.getDefault().getConnections();
        if (conns.length == 0) {
            return "No database connections registered. Add one via Services > Databases in the IDE first.";
        }
        StringBuilder sb = new StringBuilder();
        for (DatabaseConnection c : conns) {
            sb.append(c.getDisplayName())
                    .append(" — ").append(c.getDatabaseURL())
                    .append(isConnected(c) ? " [connected]" : " [not connected — connect it via Services > Databases first]")
                    .append('\n');
        }
        return sb.toString();
    }

    public static String listTables(String connectionName) {
        DatabaseConnection dc = findConnection(connectionName);
        if (dc == null) {
            return connectionNotFoundError(connectionName);
        }
        Connection conn = jdbcConnection(dc);
        if (conn == null) {
            return notConnectedError(connectionName);
        }
        try {
            DatabaseMetaData md = conn.getMetaData();
            String schema = dc.getSchema();
            StringBuilder sb = new StringBuilder();
            try (ResultSet rs = md.getTables(null, schema, "%", new String[]{"TABLE"})) {
                boolean found = false;
                while (rs.next()) {
                    found = true;
                    sb.append(rs.getString("TABLE_NAME")).append('\n');
                }
                if (!found) {
                    return "(no tables found in schema " + (schema != null ? schema : "default") + ")";
                }
            }
            return sb.toString();
        }
        catch (SQLException e) {
            LOG.log(Level.WARNING, "listTables error", e);
            return "Error listing tables: " + e.getMessage();
        }
    }

    public static String getTableSchema(String connectionName, String tableName) {
        if (!isValidIdentifier(tableName)) {
            return "Invalid tableName: " + tableName;
        }
        DatabaseConnection dc = findConnection(connectionName);
        if (dc == null) {
            return connectionNotFoundError(connectionName);
        }
        Connection conn = jdbcConnection(dc);
        if (conn == null) {
            return notConnectedError(connectionName);
        }
        try {
            DatabaseMetaData md = conn.getMetaData();
            String schema = dc.getSchema();
            StringBuilder sb = new StringBuilder();

            java.util.Set<String> primaryKeys = new java.util.HashSet<>();
            try (ResultSet rs = md.getPrimaryKeys(null, schema, tableName)) {
                while (rs.next()) {
                    primaryKeys.add(rs.getString("COLUMN_NAME"));
                }
            }

            sb.append("Columns:\n");
            boolean found = false;
            try (ResultSet rs = md.getColumns(null, schema, tableName, null)) {
                while (rs.next()) {
                    found = true;
                    String colName = rs.getString("COLUMN_NAME");
                    sb.append("  ").append(colName)
                            .append(' ').append(rs.getString("TYPE_NAME"));
                    if (rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) {
                        sb.append(" NOT NULL");
                    }
                    if (primaryKeys.contains(colName)) {
                        sb.append(" PRIMARY KEY");
                    }
                    sb.append('\n');
                }
            }
            if (!found) {
                return "Table not found: " + tableName
                        + (schema != null ? " (schema " + schema + ")" : "");
            }
            return sb.toString();
        }
        catch (SQLException e) {
            LOG.log(Level.WARNING, "getTableSchema error", e);
            return "Error reading schema for " + tableName + ": " + e.getMessage();
        }
    }

    public static String getTableData(String connectionName, String tableName, int limit) {
        if (!isValidIdentifier(tableName)) {
            return "Invalid tableName: " + tableName;
        }
        return runReadOnlyQuery(connectionName, "SELECT * FROM " + tableName, limit);
    }

    /**
     * Convenience overload for callers with no session-specific row limit
     * (e.g. tests) — uses the plugin's globally configured default.
     */
    public static String executeSqlQuery(String connectionName, String sql) {
        return executeSqlQuery(connectionName, sql, PluginSettings.getDatabaseRowLimit());
    }

    public static String executeSqlQuery(String connectionName, String sql, int maxRows) {
        String trimmed = sql == null ? "" : sql.strip();
        if (!trimmed.regionMatches(true, 0, "SELECT", 0, "SELECT".length())) {
            return "Rejected: only SELECT queries are allowed.";
        }
        return runReadOnlyQuery(connectionName, trimmed, maxRows);
    }

    private static String runReadOnlyQuery(String connectionName, String sql, int maxRows) {
        DatabaseConnection dc = findConnection(connectionName);
        if (dc == null) {
            return connectionNotFoundError(connectionName);
        }
        Connection conn = jdbcConnection(dc);
        if (conn == null) {
            return notConnectedError(connectionName);
        }
        boolean originalReadOnly;
        try {
            originalReadOnly = conn.isReadOnly();
            conn.setReadOnly(true);
        }
        catch (SQLException e) {
            return "Error preparing read-only connection: " + e.getMessage();
        }
        try (Statement st = conn.createStatement()) {
            st.setMaxRows(maxRows);
            try (ResultSet rs = st.executeQuery(sql)) {
                return formatResultSet(rs, maxRows);
            }
        }
        catch (SQLException e) {
            LOG.log(Level.WARNING, "Query error", e);
            return "Query error: " + e.getMessage();
        }
        finally {
            try {
                conn.setReadOnly(originalReadOnly);
            }
            catch (SQLException ignored) {
                // Best effort — restoring read-only state shouldn't mask the real result.
            }
        }
    }

    private static String formatResultSet(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= cols; i++) {
            if (i > 1) {
                header.append(" | ");
            }
            header.append(md.getColumnLabel(i));
        }
        StringBuilder sb = new StringBuilder(header).append('\n');
        int rowCount = 0;
        while (rs.next()) {
            rowCount++;
            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    sb.append(" | ");
                }
                sb.append(rs.getString(i));
            }
            sb.append('\n');
        }
        if (rowCount == 0) {
            return "(no rows)";
        }
        if (rowCount >= maxRows) {
            sb.append("... (row limit ").append(maxRows).append(" reached, results may be truncated)\n");
        }
        return sb.toString();
    }

    private static DatabaseConnection findConnection(String connectionName) {
        for (DatabaseConnection c : ConnectionManager.getDefault().getConnections()) {
            if (c.getDisplayName().equals(connectionName) || c.getName().equals(connectionName)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Never triggers a new connection attempt (no credential prompt, no
     * silent auto-connect) — only returns a live JDBC connection if the user
     * already connected this entry via the IDE.
     */
    private static Connection jdbcConnection(DatabaseConnection dc) {
        try {
            Connection conn = dc.getJDBCConnection();
            return (conn != null && !conn.isClosed()) ? conn : null;
        }
        catch (Exception e) {
            return null;
        }
    }

    private static boolean isConnected(DatabaseConnection dc) {
        return jdbcConnection(dc) != null;
    }

    private static boolean isValidIdentifier(String name) {
        return name != null && name.matches("[A-Za-z0-9_.]+");
    }

    private static String connectionNotFoundError(String connectionName) {
        return "Database connection not found: " + connectionName
                + ". Use ListDatabaseConnections to see registered connections.";
    }

    private static String notConnectedError(String connectionName) {
        return "Connection '" + connectionName + "' is not connected — connect it via Services > Databases first.";
    }
}
