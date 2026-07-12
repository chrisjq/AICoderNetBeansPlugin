package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Covers only the SELECT-only guard in {@link DatabaseProvider#executeSqlQuery}
 * — the one piece of this provider that's pure logic. It rejects before ever
 * looking up a connection, so it's testable without a live, registered
 * Database Explorer connection (which isn't mockable the way a git client is).
 * The connection-listing/schema/query paths need a real registered
 * DatabaseConnection and are verified manually instead.
 */
class DatabaseProviderTest {

    @Test
    void executeSqlQuery_rejectsNonSelectStatements() {
        assertEquals("Rejected: only SELECT queries are allowed.",
                DatabaseProvider.executeSqlQuery("any", "DROP TABLE users"));
        assertEquals("Rejected: only SELECT queries are allowed.",
                DatabaseProvider.executeSqlQuery("any", "DELETE FROM users"));
        assertEquals("Rejected: only SELECT queries are allowed.",
                DatabaseProvider.executeSqlQuery("any", "UPDATE users SET name='x'"));
        assertEquals("Rejected: only SELECT queries are allowed.",
                DatabaseProvider.executeSqlQuery("any", "INSERT INTO users VALUES (1)"));
        assertEquals("Rejected: only SELECT queries are allowed.",
                DatabaseProvider.executeSqlQuery("any", ""));
        assertEquals("Rejected: only SELECT queries are allowed.",
                DatabaseProvider.executeSqlQuery("any", null));
    }

    @Test
    void executeSqlQuery_rejectionIsCaseInsensitiveOnTheKeywordItRejects() {
        // "select" itself is accepted case-insensitively (see the acceptance test below);
        // this just confirms a lowercase non-SELECT statement is still rejected.
        assertEquals("Rejected: only SELECT queries are allowed.",
                DatabaseProvider.executeSqlQuery("any", "drop table users"));
    }

    @Test
    void executeSqlQuery_toleratesLeadingWhitespaceBeforeRejectedKeyword() {
        assertEquals("Rejected: only SELECT queries are allowed.",
                DatabaseProvider.executeSqlQuery("any", "   DROP TABLE users"));
    }

    @Test
    void executeSqlQuery_acceptsSelectCaseInsensitivelyAndProceedsToConnectionLookup() {
        // Past the guard, it proceeds to look up the connection — with no
        // connection registered in a plain unit test environment, that lookup
        // fails with the connection-not-found error, proving the query text
        // itself was accepted rather than rejected by the guard.
        String result = DatabaseProvider.executeSqlQuery("nonexistent-connection", "select * from users");
        assertTrue(result.startsWith("Database connection not found: nonexistent-connection"), result);

        result = DatabaseProvider.executeSqlQuery("nonexistent-connection", "SELECT * FROM users");
        assertTrue(result.startsWith("Database connection not found: nonexistent-connection"), result);
    }

    @Test
    void getTableSchema_rejectsInvalidTableIdentifier() {
        assertEquals("Invalid tableName: users; DROP TABLE x",
                DatabaseProvider.getTableSchema("any", "users; DROP TABLE x"));
    }

    @Test
    void getTableData_rejectsInvalidTableIdentifier() {
        assertEquals("Invalid tableName: users; DROP TABLE x",
                DatabaseProvider.getTableData("any", "users; DROP TABLE x", 10));
    }
}
