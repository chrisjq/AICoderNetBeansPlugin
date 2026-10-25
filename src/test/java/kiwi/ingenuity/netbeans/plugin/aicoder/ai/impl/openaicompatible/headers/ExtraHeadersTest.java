package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders.ExtraHeader;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExtraHeadersTest {

    @Test
    @Timeout(5)
    void restrictedHeadersAreRejectedInAnyCase() {
        for (String name : List.of("host", "Connection", "CONTENT-LENGTH", "Expect", "upgrade")) {
            assertEquals(Optional.of(name + " is set by the client and cannot be overridden."),
                         ExtraHeaders.validate(name, false), name);
        }
    }

    @Test
    @Timeout(5)
    void contentTypeAndAcceptAreNotOverridable() {
        assertEquals(Optional.of("Content-Type is set by the client and cannot be overridden."),
                     ExtraHeaders.validate("Content-Type", false));
        assertEquals(Optional.of("ACCEPT is set by the client and cannot be overridden."),
                     ExtraHeaders.validate("ACCEPT", false));
    }

    @Test
    @Timeout(5)
    void authorizationIsRefusedWhileAnApiKeyIsSet() {
        assertEquals(Optional.of("Use the API key field instead."), ExtraHeaders.validate("Authorization", true));
    }

    @Test
    @Timeout(5)
    void authorizationIsAllowedWithoutAnApiKey() {
        assertTrue(ExtraHeaders.validate("Authorization", false).isEmpty());
    }

    @Test
    @Timeout(5)
    void ordinaryHeadersAreAllowed() {
        assertTrue(ExtraHeaders.validate("X-Trace-Id", true).isEmpty());
        assertTrue(ExtraHeaders.validate("X-Custom", false).isEmpty());
    }

    @Test
    @Timeout(5)
    void namedSecretHeadersAreSecret() {
        assertTrue(ExtraHeaders.isSecret("Authorization"));
        assertTrue(ExtraHeaders.isSecret("Proxy-Authorization"));
        assertTrue(ExtraHeaders.isSecret("Cookie"));
        assertTrue(ExtraHeaders.isSecret("Api-Key"));
    }

    @Test
    @Timeout(5)
    void headersWhoseNameContainsASecretWordAreSecretInAnyCase() {
        assertTrue(ExtraHeaders.isSecret("X-Api-Key"));
        assertTrue(ExtraHeaders.isSecret("X-Auth-Token"));
        assertTrue(ExtraHeaders.isSecret("x-client-SECRET"));
        assertTrue(ExtraHeaders.isSecret("X-Db-PASSWORD"));
        assertTrue(ExtraHeaders.isSecret("X-Session-Id"));
        assertTrue(ExtraHeaders.isSecret("X-Request-Signature"));
    }

    @Test
    @Timeout(5)
    void plainHeadersAreNotSecret() {
        assertFalse(ExtraHeaders.isSecret("X-Trace-Id"));
        assertFalse(ExtraHeaders.isSecret("Accept-Language"));
    }

    @Test
    @Timeout(5)
    void restrictedHeadersAreDroppedWithAWarning() {
        List<String> warnings = new ArrayList<>();

        List<ExtraHeader> sent = ExtraHeaders.forRequest(List.of("HOST", "X-Trace"), Map.of("X-Trace", "1"),
                                                         name -> Optional.empty(), false, warnings::add);

        assertEquals(List.of(new ExtraHeader("X-Trace", "1")), sent);
        assertEquals(List.of("HOST is set by the client and cannot be overridden."), warnings);
    }

    @Test
    @Timeout(5)
    void authorizationIsDroppedWhenAnApiKeyIsSet() {
        List<String> warnings = new ArrayList<>();

        List<ExtraHeader> sent = ExtraHeaders.forRequest(List.of("Authorization"), Map.of(),
                                                         name -> Optional.of("ignored".toCharArray()), true, warnings::add);

        assertTrue(sent.isEmpty());
        assertEquals(List.of("Use the API key field instead."), warnings);
    }

    @Test
    @Timeout(5)
    void aSecretHeaderWithoutItsValueIsSkippedWithAWarning() {
        List<String> warnings = new ArrayList<>();

        List<ExtraHeader> sent = ExtraHeaders.forRequest(List.of("X-Api-Key"), Map.of(),
                                                         name -> Optional.empty(), false, warnings::add);

        assertTrue(sent.isEmpty());
        assertEquals(List.of("Header X-Api-Key needs its value on this computer"), warnings);
    }

    @Test
    @Timeout(5)
    void aSecretHeaderUsesItsStoredValue() {
        List<ExtraHeader> sent = ExtraHeaders.forRequest(List.of("X-Api-Key"), Map.of(),
                                                         name -> Optional.of("s3cr3t".toCharArray()), false, warning -> {
                                                         });

        assertEquals(List.of(new ExtraHeader("X-Api-Key", "s3cr3t")), sent);
    }

    @Test
    @Timeout(5)
    void aPlainHeaderUsesItsPlainValue() {
        List<ExtraHeader> sent = ExtraHeaders.forRequest(List.of("X-Trace"), Map.of("X-Trace", "abc"),
                                                         name -> Optional.empty(), false, warning -> {
                                                         });

        assertEquals(List.of(new ExtraHeader("X-Trace", "abc")), sent);
    }
}
