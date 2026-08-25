package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.net.URI;
import java.util.EnumSet;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpArgumentException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class WebRequestDestinationPolicyTest {

    private static final Set<WebRequestAccessOptionEnum> NONE
            = EnumSet.noneOf(WebRequestAccessOptionEnum.class);
    private static final Set<WebRequestAccessOptionEnum> LOCAL
            = EnumSet.of(WebRequestAccessOptionEnum.LOCALHOST);
    private static final Set<WebRequestAccessOptionEnum> PRIVATE
            = EnumSet.of(WebRequestAccessOptionEnum.PRIVATE_NETWORKS);

    @Test
    void defaultPolicyStillRefusesEverything() {
        assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://127.0.0.1/"), NONE));
        assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://10.0.0.1/"), NONE));
    }

    @Test
    void localhostOptionPermitsLoopbackOnly() {
        assertDoesNotThrow(() -> WebRequestTool.validateDestination(URI.create("http://127.0.0.1/"), LOCAL));
        assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://10.0.0.1/"), LOCAL));
    }

    @Test
    void privateNetworksOptionPermitsPrivateOnly() {
        assertDoesNotThrow(() -> WebRequestTool.validateDestination(URI.create("http://10.0.0.1/"), PRIVATE));
        assertDoesNotThrow(() -> WebRequestTool.validateDestination(URI.create("http://169.254.169.254/"), PRIVATE));
        assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://127.0.0.1/"), PRIVATE));
    }

    @Test
    void multicastIsRefusedEvenWithBothOptions() {
        Set<WebRequestAccessOptionEnum> both = EnumSet.of(
                WebRequestAccessOptionEnum.LOCALHOST, WebRequestAccessOptionEnum.PRIVATE_NETWORKS);
        assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://224.0.0.1/"), both));
    }

    @Test
    void refusalNamesTheSettingThatWouldPermitIt() {
        McpArgumentException loopback = assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://127.0.0.1/"), NONE));
        assertTrue(loopback.getMessage().contains("Allow localhost destinations"), loopback.getMessage());

        McpArgumentException priv = assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://10.0.0.1/"), NONE));
        assertTrue(priv.getMessage().contains("Allow private network destinations"), priv.getMessage());
    }

    /**
     * Nothing would permit multicast, so the message must not offer a setting.
     */
    @Test
    void multicastRefusalNamesNoSetting() {
        McpArgumentException ex = assertThrows(McpArgumentException.class,
                () -> WebRequestTool.validateDestination(URI.create("http://224.0.0.1/"), NONE));
        assertTrue(ex.getMessage().contains("multicast address refused"), ex.getMessage());
        assertTrue(!ex.getMessage().contains("Enable "), ex.getMessage());
    }

    // Redirect coverage deliberately does NOT live here.
    //
    // This class drives validateDestination directly, so it can only ever exercise a single URL — it cannot
    // reach sendWithRedirects and therefore cannot prove the per-hop check exists. A test here that merely
    // called validateDestination twice and claimed to cover redirects would be worse than no test: it would
    // advertise protection it does not provide and stop anyone writing the real one. (One did, and was
    // removed — deleting the per-hop call left this whole class green.)
    //
    // The real proof is WebRequestToolTest.redirectTargetIsRecheckedMidChainNotJustTheEntryUrl, which stands
    // up a loopback server returning 302 -> http://10.0.0.1/secret and asserts the hop is refused. Deleting
    // the per-hop validateDestination call in sendWithRedirects makes that test, and only that test, fail.
}
