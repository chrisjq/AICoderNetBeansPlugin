package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.net.InetAddress;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class BlockedAddressCategoryEnumTest {

    @Test
    void classifiesLoopbackAndAnyLocalUnderLocalhost() throws Exception {
        assertEquals(WebRequestAccessOptionEnum.LOCALHOST,
                BlockedAddressCategoryEnum.classify(InetAddress.getByName("127.0.0.1")).governingOption());
        assertEquals(WebRequestAccessOptionEnum.LOCALHOST,
                BlockedAddressCategoryEnum.classify(InetAddress.getByName("0.0.0.0")).governingOption());
    }

    @Test
    void classifiesPrivateRangesUnderPrivateNetworks() throws Exception {
        for (String host : new String[]{"10.0.0.1", "192.168.1.1", "172.16.5.5", "169.254.169.254", "100.64.1.1"}) {
            assertEquals(WebRequestAccessOptionEnum.PRIVATE_NETWORKS,
                    BlockedAddressCategoryEnum.classify(InetAddress.getByName(host)).governingOption(),
                    host + " must be governed by Allow Private Networks");
        }
    }

    /**
     * Multicast is permanently blocked: no checkbox may unlock it.
     */
    @Test
    void multicastHasNoGoverningOption() throws Exception {
        assertEquals(BlockedAddressCategoryEnum.MULTICAST,
                BlockedAddressCategoryEnum.classify(InetAddress.getByName("224.0.0.1")));
        assertNull(BlockedAddressCategoryEnum.MULTICAST.governingOption());
    }

    @Test
    void publicAddressIsNotClassified() throws Exception {
        assertNull(BlockedAddressCategoryEnum.classify(InetAddress.getByName("93.184.216.34")));
    }

    /**
     * Branch precedence decides which checkbox governs an address. 0.0.0.0 is both any-local and (on some stacks)
     * loopback-adjacent; 169.254.x must stay link-local, not site-local. Reordering the checks looks harmless and would
     * silently move a category between options.
     */
    @Test
    void branchPrecedenceIsPinned() throws Exception {
        assertEquals(BlockedAddressCategoryEnum.ANY_LOCAL,
                BlockedAddressCategoryEnum.classify(InetAddress.getByName("0.0.0.0")));
        assertEquals(BlockedAddressCategoryEnum.LINK_LOCAL,
                BlockedAddressCategoryEnum.classify(InetAddress.getByName("169.254.169.254")));
    }
}
