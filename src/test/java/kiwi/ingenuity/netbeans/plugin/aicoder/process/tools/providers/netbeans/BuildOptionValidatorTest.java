package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class BuildOptionValidatorTest {

    @Test
    void acceptsSupportedTestSelectors() {
        for (String selector : new String[]{"MyTest", "com.example.MyTest", "MyTest#method",
            "MyTest#method*", "*IT", "A,B", "Outer$Inner", "!Slow", "MyTest#m1+m2", "**/*Test.java"}) {
            assertNull(BuildOptionValidator.validateTestSelector("testClass", selector), selector);
        }
    }

    @Test
    void rejectsUnsafeTestSelectors() {
        for (String selector : new String[]{"", "-x", "--offline", "a b", "a\n b"}) {
            assertNotNull(BuildOptionValidator.validateTestSelector("testClass", selector), selector);
        }
    }

    @Test
    void rejectsUnsafeKeysAndMultiGoalEntries() {
        assertNotNull(BuildOptionValidator.validateTokens("goals", java.util.List.of("clean install")));
        String[] error = new String[1];
        JsonObject properties = new JsonObject();
        properties.addProperty("--offline", "true");
        assertNull(BuildOptionValidator.validateProperties("systemProperties", properties, error));
        assertNotNull(error[0]);
    }

    @Test
    void rejectsControlCharactersInPropertyValues() {
        String[] error = new String[1];
        JsonObject properties = new JsonObject();
        properties.addProperty("key", "line\nvalue");
        assertNull(BuildOptionValidator.validateProperties("properties", properties, error));
        assertNotNull(error[0]);
    }
}
