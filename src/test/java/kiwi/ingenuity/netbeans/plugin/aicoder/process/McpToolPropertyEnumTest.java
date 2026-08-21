package kiwi.ingenuity.netbeans.plugin.aicoder.process;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolPropertyEnumTest {

    private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][A-Za-z0-9]*$");

    @Test
    void everyKeyIsCamelCase() {
        // The whole point of centralising these is that a caller who has learned
        // one spelling can rely on it everywhere. WriteFile and ApplyEdit used
        // snake_case while the other tools used camelCase, so an argument sent
        // under the wrong spelling was silently dropped rather than rejected.
        for (McpToolPropertyEnum p : McpToolPropertyEnum.values()) {
            assertTrue(CAMEL_CASE.matcher(p.key()).matches(),
                    p.name() + " key \"" + p.key() + "\" is not camelCase");
        }
    }

    @Test
    void noTwoConstantsShareAKey() {
        // Two constants with the same wire key would let one tool's parameter be
        // renamed while a duplicate silently kept the old spelling alive.
        Map<String, String> seen = new HashMap<>();
        for (McpToolPropertyEnum p : McpToolPropertyEnum.values()) {
            assertNull(seen.put(p.key(), p.name()),
                    "duplicate key \"" + p.key() + "\" on " + p.name()
                    + " and " + seen.get(p.key()));
        }
    }
}
