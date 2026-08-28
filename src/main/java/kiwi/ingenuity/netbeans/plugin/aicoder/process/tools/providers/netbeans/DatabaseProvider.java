package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.TimeoutEnum;
import org.netbeans.api.db.explorer.ConnectionManager;
import org.netbeans.api.db.explorer.DatabaseConnection;

/**
 * Read-only access to the IDE's registered Database Explorer connections (Services &gt; Databases). Deliberately
 * narrow: it only ever talks to connections the user has already registered and connected through the IDE — it never
 * accepts raw JDBC URLs/credentials from a tool call, and never silently establishes a new connection (see
 * {@link #jdbcConnection}).
 *
 * <p>
 * {@link #executeSqlQuery} and {@link #getTableData} both enforce SELECT-only twice: a textual prefix check on the SQL
 * itself, and {@link Connection#setReadOnly(boolean)} on the JDBC connection so the driver rejects any write the prefix
 * check missed.
 */
public class DatabaseProvider {

    private static final Logger LOG = Logger.getLogger(DatabaseProvider.class.getName());

    /**
     * Ceiling on a single query, so one that never returns cannot pin the connection against every other session. Best
     * effort — a driver may not support it.
     */
    private static final int QUERY_TIMEOUT_SECONDS = (int) (TimeoutEnum.DATABASE_QUERY_TIMEOUT_MILLIS.millis() / 1000);

    /**
     * How long to wait for another query on the same connection to finish.
     *
     * <p>
     * Equal to {@link #QUERY_TIMEOUT_SECONDS} rather than longer, because tryLock returns the moment the lock frees
     * rather than sleeping out its deadline: a waiter only gives up if the holder is still running after this long,
     * which by then means the query timeout did not take effect — a driver that ignored it, or a connection wedged
     * below the driver. Either way the caller gets an error it can act on instead of blocking forever.
     */
    private static final int LOCK_WAIT_SECONDS = 300;

    /**
     * Cap on a single cell's rendered length. {@link Statement#setMaxRows(int)} bounds rows only — one large CLOB/TEXT
     * value can still exhaust the IDE heap.
     */
    static final int MAX_VALUE_CHARS = 4_000;

    /**
     * Cap on the entire formatted result. Enforced while appending so a wide result of many mid-sized cells cannot grow
     * without bound either.
     */
    static final int MAX_RESULT_CHARS = 200_000;

    /**
     * One lock per JDBC connection. Weak keys so a closed or replaced connection does not keep its lock — and the
     * connection itself — alive.
     *
     * <p>
     * A plain {@code synchronized (conn)} would also be exception-safe, since the monitor is released when a throw
     * unwinds the block. What it cannot do is give up: a waiter blocks with no bound, so a query that hangs takes every
     * other session's database tools down with it. tryLock with a deadline turns that into a reportable error.
     */
    private static final Map<Connection, ReentrantLock> CONNECTION_LOCKS
            = Collections.synchronizedMap(new WeakHashMap<>());

    public static String listConnections() {
        DatabaseConnection[] conns = ConnectionManager.getDefault().getConnections();
        if (conns.length == 0) {
            return "No database connections registered. Add one via Services > Databases in the IDE first.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Pass the exact ").append(McpToolPropertyEnum.CONNECTION_NAME.key())
                .append("= value below (either the display name or the JDBC URL) to the other database tools.\n\n");
        for (DatabaseConnection c : conns) {
            sb.append(McpToolPropertyEnum.CONNECTION_NAME.key()).append("=\"").append(c.getDisplayName()).append('"')
                    .append("  (url: ").append(c.getDatabaseURL()).append(')')
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
            return "Invalid " + McpToolPropertyEnum.TABLE_NAME.key() + ": " + tableName;
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
     * Convenience overload for callers with no session-specific row limit (e.g. tests) — uses the plugin's globally
     * configured default.
     */
    public static String executeSqlQuery(String connectionName, String sql) {
        return executeSqlQuery(connectionName, sql, PluginSettings.getDatabaseRowLimit());
    }

    public static String executeSqlQuery(String connectionName, String sql, int maxRows) {
        String trimmed = sql == null ? "" : sql.strip();
        String rejection = rejectIfNotSingleSelect(trimmed);
        if (rejection != null) {
            return rejection;
        }
        return runReadOnlyQuery(connectionName, trimmed, maxRows);
    }

    /**
     * Returns a rejection message if {@code sql} is anything other than one SELECT statement, or null when it may run.
     *
     * <p>
     * The prefix test alone was not the "enforced twice" this class advertises. It passes
     * {@code SELECT 1; DROP TABLE users} on any driver configured to allow multiple statements per call, because only
     * the first six characters were ever examined. The read-only connection was supposed to be the second line of
     * defence, but {@link Connection#setReadOnly(boolean)} is a hint that several drivers accept and ignore — so for
     * those, "twice" was "not at all".
     *
     * <p>
     * Rejecting an embedded statement separator closes that. A semicolon trailing the single statement is allowed since
     * it terminates rather than chains, and only a semicolon that has something after it can begin a second statement.
     */
    static String rejectIfNotSingleSelect(String sql) {
        if (!sql.regionMatches(true, 0, "SELECT", 0, "SELECT".length())) {
            return "Rejected: only SELECT queries are allowed.";
        }
        int semi = sql.indexOf(';');
        if (semi >= 0 && !sql.substring(semi + 1).isBlank()) {
            return "Rejected: only a single SELECT statement is allowed — "
                    + "remove the ';' and anything following it.";
        }
        return null;
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
        // Serialise on the connection itself. getJDBCConnection() hands back the
        // IDE's shared connection — the same object the Database Explorer and
        // every other AI session use — and read-only is per-connection state, not
        // per-statement. Two concurrent queries previously interleaved like this:
        //
        //   A: reads original=false, sets true
        //   B: reads original=TRUE  (A's value), sets true
        //   A: finishes, restores FALSE  <- while B is still executing
        //   B: finishes, restores true   <- leaves the IDE's connection read-only
        //
        // so B ran unprotected and the user's connection was left altered. With
        // six sessions able to query at once that is a routine interleaving, not
        // a rare one. Holding the lock across set/execute/restore makes the
        // save-and-restore atomic; queries on one connection now queue.
        ReentrantLock lock = CONNECTION_LOCKS.computeIfAbsent(conn, c -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for the connection to become free.";
        }
        if (!acquired) {
            return "Timed out after " + LOCK_WAIT_SECONDS + "s waiting for another query on connection '"
                    + connectionName + "' to finish. It may be hung; check the IDE's Services > Databases.";
        }
        try {
            boolean originalReadOnly;
            boolean changed = false;
            try {
                originalReadOnly = conn.isReadOnly();
                if (!originalReadOnly) {
                    conn.setReadOnly(true);
                    changed = true;
                }
            }
            catch (SQLException e) {
                return "Error preparing read-only connection: " + e.getMessage();
            }
            try (Statement st = conn.createStatement()) {
                st.setMaxRows(maxRows);
                try {
                    st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                }
                catch (SQLException ignored) {
                    // Optional per JDBC; a driver that refuses it still runs the query.
                    LOG.log(Level.FINE, "Driver does not support setQueryTimeout");
                }
                try (ResultSet rs = st.executeQuery(sql)) {
                    return formatResultSet(rs, maxRows);
                }
            }
            catch (SQLException e) {
                LOG.log(Level.WARNING, "Query error", e);
                return "Query error: " + e.getMessage();
            }
            finally {
                // Restore only what this call changed. Blindly writing back the
                // observed value is what left the connection read-only for the
                // Database Explorer after an interleaved run.
                if (changed) {
                    try {
                        conn.setReadOnly(false);
                    }
                    catch (SQLException ignored) {
                        // Best effort — restoring must not mask the real result.
                    }
                }
            }
        }
        finally {
            // Outermost, so it runs whether the body returned, threw a checked
            // SQLException, or unwound on a RuntimeException/Error from the
            // driver or from formatResultSet. Releasing the lock must not depend
            // on the failure mode.
            lock.unlock();
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
        boolean sizeLimited = false;
        while (rs.next()) {
            rowCount++;
            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    if (appendBounded(sb, " | ")) {
                        sizeLimited = true;
                        break;
                    }
                }
                if (appendBounded(sb, readBoundedCell(rs, i))) {
                    sizeLimited = true;
                    break;
                }
            }
            if (sizeLimited) {
                break;
            }
            if (appendBounded(sb, "\n")) {
                sizeLimited = true;
                break;
            }
        }
        if (rowCount == 0) {
            return "(no rows)";
        }
        if (sizeLimited) {
            sb.append("... (result size limit ").append(MAX_RESULT_CHARS)
                    .append(" chars reached, results truncated)\n");
        }
        else if (rowCount >= maxRows) {
            sb.append("... (row limit ").append(maxRows).append(" reached, results may be truncated)\n");
        }
        return sb.toString();
    }

    /**
     * Reads one cell without materialising an unbounded CLOB/TEXT value. Prefers
     * {@link ResultSet#getCharacterStream(int)} and stops after {@link #MAX_VALUE_CHARS}; falls back to
     * {@link #formatCellValue(String)} when the driver rejects character streams for the column type.
     */
    static String readBoundedCell(ResultSet rs, int columnIndex) throws SQLException {
        Reader reader;
        try {
            reader = rs.getCharacterStream(columnIndex);
        }
        catch (SQLException streamUnsupported) {
            return formatCellValue(rs.getString(columnIndex));
        }
        if (reader == null) {
            return rs.wasNull() ? "null" : "";
        }
        try {
            char[] buf = new char[MAX_VALUE_CHARS + 1];
            int n = 0;
            while (n < buf.length) {
                int r = reader.read(buf, n, buf.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            if (n <= 0) {
                return "";
            }
            if (n <= MAX_VALUE_CHARS) {
                return new String(buf, 0, n);
            }
            return new String(buf, 0, MAX_VALUE_CHARS)
                    + "…[truncated at " + MAX_VALUE_CHARS + " chars]";
        }
        catch (IOException e) {
            throw new SQLException("Failed reading column " + columnIndex + ": " + e.getMessage(), e);
        }
        finally {
            try {
                reader.close();
            }
            catch (IOException ignored) {
                // Best effort — do not mask the cell value or a read failure.
            }
        }
    }

    /**
     * Renders one already-fetched cell string, capping length and marking truncation. Used by tests and as the fallback
     * when a driver cannot supply a character stream.
     */
    static String formatCellValue(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= MAX_VALUE_CHARS) {
            return value;
        }
        return value.substring(0, MAX_VALUE_CHARS)
                + "…[truncated " + value.length() + " chars]";
    }

    /**
     * Appends {@code text} without letting {@code sb} exceed {@link #MAX_RESULT_CHARS}. Returns {@code true} when the
     * caller must stop adding further content (limit reached or hit by this append).
     */
    static boolean appendBounded(StringBuilder sb, String text) {
        if (text == null) {
            text = "null";
        }
        int remaining = MAX_RESULT_CHARS - sb.length();
        if (remaining <= 0) {
            return true;
        }
        if (text.length() <= remaining) {
            sb.append(text);
            return sb.length() >= MAX_RESULT_CHARS;
        }
        sb.append(text, 0, remaining);
        return true;
    }

    private static DatabaseConnection findConnection(String connectionName) {
        for (DatabaseConnection c : ConnectionManager.getDefault().getConnections()) {
            if (c.getDisplayName().equals(connectionName)
                    || c.getName().equals(connectionName)
                    || connectionName.equals(c.getDatabaseURL())) {
                return c;
            }
        }
        return null;
    }

    /**
     * Never triggers a new connection attempt (no credential prompt, no silent auto-connect) — only returns a live JDBC
     * connection if the user already connected this entry via the IDE.
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
