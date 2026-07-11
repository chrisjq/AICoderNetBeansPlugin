package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class SearchProviderTest {

    @Test
    void validateNamePatternRejectsInvalidRegexp() {
        String error = SearchProvider.validateNamePattern("*SettingsPanel", "regexp");

        assertTrue(error.startsWith("Invalid regex:"));
    }

    @Test
    void validateNamePatternAllowsValidRegexp() {
        assertNull(SearchProvider.validateNamePattern(".*SettingsPanel", "regexp"));
    }

    @Test
    void validateNamePatternSkipsValidationForNonRegexpKinds() {
        assertNull(SearchProvider.validateNamePattern("*SettingsPanel", "prefix"));
    }
}
