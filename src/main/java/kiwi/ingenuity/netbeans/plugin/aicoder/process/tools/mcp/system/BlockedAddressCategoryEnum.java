package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.system;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;

/**
 * A destination-address class WebRequest refuses by default, its human-readable label, and the session option that
 * permits it.
 * <p>
 * Label and governing option are fields of one constant deliberately: a second switch mapping addresses to options
 * could drift from this one, and a drift would leave a category silently governed by the wrong checkbox — a defect that
 * reads as correct in review.
 */
public enum BlockedAddressCategoryEnum {

    ANY_LOCAL("any-local (wildcard) address", WebRequestAccessOptionEnum.LOCALHOST),
    LOOPBACK("loopback address", WebRequestAccessOptionEnum.LOCALHOST),
    LINK_LOCAL("link-local address", WebRequestAccessOptionEnum.PRIVATE_NETWORKS),
    SITE_LOCAL("private (site-local) address", WebRequestAccessOptionEnum.PRIVATE_NETWORKS),
    /**
     * No governing option: HTTP to a multicast group is not a use case worth exposing.
     */
    MULTICAST("multicast address", null),
    CARRIER_GRADE_NAT("carrier-grade NAT address (100.64.0.0/10)", WebRequestAccessOptionEnum.PRIVATE_NETWORKS),
    UNIQUE_LOCAL_IPV6("unique local IPv6 address (fc00::/7)", WebRequestAccessOptionEnum.PRIVATE_NETWORKS);

    /**
     * Categorises {@code address}, or returns null when it is a public address that no policy blocks. Branch order is
     * load-bearing — see BlockedAddressCategoryEnumTest.
     */
    public static BlockedAddressCategoryEnum classify(InetAddress address) {
        if (address.isAnyLocalAddress()) {
            return ANY_LOCAL;
        }
        if (address.isLoopbackAddress()) {
            return LOOPBACK;
        }
        if (address.isLinkLocalAddress()) {
            return LINK_LOCAL;
        }
        if (address.isSiteLocalAddress()) {
            return SITE_LOCAL;
        }
        if (address.isMulticastAddress()) {
            return MULTICAST;
        }
        if (isCarrierGradeNat(address)) {
            return CARRIER_GRADE_NAT;
        }
        if (isUniqueLocalIpv6(address)) {
            return UNIQUE_LOCAL_IPV6;
        }
        return null;
    }

    /**
     * 100.64.0.0/10 — shared carrier-grade NAT space.
     */
    private static boolean isCarrierGradeNat(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int second = bytes[1] & 0xFF;
        return (bytes[0] & 0xFF) == 100 && second >= 64 && second <= 127;
    }

    /**
     * fc00::/7 — unique local IPv6 (not covered by {@link InetAddress#isSiteLocalAddress()}).
     */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        return (bytes[0] & 0xFE) == 0xFC;
    }

    private final String label;
    private final WebRequestAccessOptionEnum governingOption;

    BlockedAddressCategoryEnum(String label, WebRequestAccessOptionEnum governingOption) {
        this.label = label;
        this.governingOption = governingOption;
    }

    public String label() {
        return label;
    }

    /**
     * The option that permits this category, or null when nothing permits it.
     */
    public WebRequestAccessOptionEnum governingOption() {
        return governingOption;
    }
}
