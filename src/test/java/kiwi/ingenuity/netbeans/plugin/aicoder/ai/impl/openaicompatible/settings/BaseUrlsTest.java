package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BaseUrlsTest {

    @Test
    @Timeout(5)
    void normalisesKnownEndpointForms() {
        assertEquals("http://localhost:11434", BaseUrls.normalise("http://localhost:11434/"));
        assertEquals("https://h", BaseUrls.normalise("https://h/v1"));
        assertEquals("https://h", BaseUrls.normalise("https://h/v1/chat/completions"));
        assertEquals("http://[fe80::1]:8080", BaseUrls.normalise("http://[fe80::1]:8080/v1"));
        assertEquals("", BaseUrls.normalise("   "));
    }

    @Test
    @Timeout(5)
    void findsNormalisedRootAndV1Removal() {
        assertEquals("http://localhost:1234", BaseUrls.root("HTTP://LOCALHOST:1234/v1/"));
        assertTrue(BaseUrls.removedV1("https://h/v1"));
    }

    @Test
    @Timeout(5)
    void localHostsAndPrivateAddressesAreLocal() {
        assertTrue(BaseUrls.isLocal("http://localhost:1234"));
        assertTrue(BaseUrls.isLocal("HTTP://LOCALHOST"));
        assertTrue(BaseUrls.isLocal("mac-studio:1234"));
        assertTrue(BaseUrls.isLocal("http://mac-studio"));
        assertTrue(BaseUrls.isLocal("nas.home.arpa"));
        assertTrue(BaseUrls.isLocal("http://nas.home.arpa:8080"));
        assertTrue(BaseUrls.isLocal("http://printer.local"));
        assertTrue(BaseUrls.isLocal("http://server.lan"));
        assertTrue(BaseUrls.isLocal("http://host.docker.internal:11434"));
        assertTrue(BaseUrls.isLocal("http://127.0.0.1:1234"));
        assertTrue(BaseUrls.isLocal("http://10.1.2.3"));
        assertTrue(BaseUrls.isLocal("http://172.16.0.1"));
        assertTrue(BaseUrls.isLocal("http://172.31.255.255"));
        assertTrue(BaseUrls.isLocal("http://192.168.1.5"));
        assertTrue(BaseUrls.isLocal("http://100.100.1.2"));
        assertTrue(BaseUrls.isLocal("http://100.64.0.1"));
        assertTrue(BaseUrls.isLocal("http://169.254.1.1"));
        assertTrue(BaseUrls.isLocal("http://[::1]:1234"));
        assertTrue(BaseUrls.isLocal("http://[fc00::1]"));
        assertTrue(BaseUrls.isLocal("http://[fd12:3456::1]"));
        assertTrue(BaseUrls.isLocal("http://[fe80::1]:8080"));
    }

    @Test
    @Timeout(5)
    void publicNamesAndAddressesAreNotLocal() {
        assertFalse(BaseUrls.isLocal("http://api.openai.com"));
        assertFalse(BaseUrls.isLocal("https://8.8.8.8"));
        assertFalse(BaseUrls.isLocal("http://example.com"));
        assertFalse(BaseUrls.isLocal("http://172.15.0.1"));
        assertFalse(BaseUrls.isLocal("http://172.32.0.1"));
        assertFalse(BaseUrls.isLocal("http://100.63.0.1"));
        assertFalse(BaseUrls.isLocal("http://100.128.0.1"));
        assertFalse(BaseUrls.isLocal("http://[2001:db8::1]"));
        assertFalse(BaseUrls.isLocal(""));
    }

    @Test
    @Timeout(5)
    void hostedMeansNonLocalAndAKeyIsSet() {
        assertTrue(BaseUrls.isHosted("https://api.openai.com", true));
        assertFalse(BaseUrls.isHosted("https://api.openai.com", false));
        assertFalse(BaseUrls.isHosted("http://localhost:1234", true));
    }

    @Test
    @Timeout(5)
    void apiBaseCandidatesOfferV1UnderTheRootFirst() {
        assertEquals(List.of("http://localhost:1234/v1", "http://localhost:1234"),
                     BaseUrls.apiBaseCandidates("http://localhost:1234"));
        assertEquals(List.of("http://localhost:1234/v1"),
                     BaseUrls.apiBaseCandidates("http://localhost:1234/v1"));
        assertEquals(List.of("http://host/v1", "http://host/api/v1/"),
                     BaseUrls.apiBaseCandidates("http://host/api/v1/"));
        assertEquals(List.of("http://mac-studio:1234/v1", "mac-studio:1234"),
                     BaseUrls.apiBaseCandidates("mac-studio:1234"));
        assertEquals(List.of(), BaseUrls.apiBaseCandidates("   "));
    }
}
