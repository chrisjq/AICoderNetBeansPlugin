package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DefaultKeyRulesTest {

    @Test
    @Timeout(5)
    void theSameRootIgnoresCaseTrailingSlashAndV1() {
        assertTrue(DefaultKeyRules.sameRoot("http://h:1", "http://H:1/v1/"));
        assertTrue(DefaultKeyRules.sameRoot("https://api.example.com/v1", "https://api.example.com"));
        assertFalse(DefaultKeyRules.sameRoot("http://h:1", "http://h:2"));
        assertFalse(DefaultKeyRules.sameRoot("http://h:1", "https://h:1"));
        assertFalse(DefaultKeyRules.sameRoot("", ""));
        assertFalse(DefaultKeyRules.sameRoot("http://h:1", ""));
    }

    @Test
    @Timeout(5)
    void movingTheDefaultUrlToAnotherRootClearsTheKey() {
        assertTrue(DefaultKeyRules.shouldClearDefaultKey("http://a:1", "http://b:1"));
        assertTrue(DefaultKeyRules.shouldClearDefaultKey("http://a:1", "http://a:2"));
        assertTrue(DefaultKeyRules.shouldClearDefaultKey("https://a", "http://a"));
        assertTrue(DefaultKeyRules.shouldClearDefaultKey("http://a:1", ""));
    }

    @Test
    @Timeout(5)
    void stayingOnTheSameRootOrHavingNoPreviousRootKeepsTheKey() {
        assertFalse(DefaultKeyRules.shouldClearDefaultKey("http://a:1", "http://A:1/v1"));
        assertFalse(DefaultKeyRules.shouldClearDefaultKey("http://a:1/", "http://a:1"));
        assertFalse(DefaultKeyRules.shouldClearDefaultKey("", "http://a:1"));
        assertFalse(DefaultKeyRules.shouldClearDefaultKey(null, "http://a:1"));
    }

    @Test
    @Timeout(5)
    void theUsingDefaultLabelNamesTheHost() {
        assertEquals("Key: using default (for api.example.com)",
                     DefaultKeyRules.usingDefaultLabel("https://api.example.com/v1", "https://api.example.com",
                                                       true, false));
        assertEquals("Key: using default (for localhost)",
                     DefaultKeyRules.usingDefaultLabel("http://localhost:1234", "http://LOCALHOST:1234/v1",
                                                       true, false));
    }

    @Test
    @Timeout(5)
    void theUsingDefaultLabelNeedsADefaultKeyNoOwnKeyAndTheSameRoot() {
        assertNull(DefaultKeyRules.usingDefaultLabel("http://h:1", "http://h:1", true, true));
        assertNull(DefaultKeyRules.usingDefaultLabel("http://h:1", "http://h:1", false, false));
        assertNull(DefaultKeyRules.usingDefaultLabel("http://h:1", "http://other:1", true, false));
        assertNull(DefaultKeyRules.usingDefaultLabel("", "", true, false));
    }
}
