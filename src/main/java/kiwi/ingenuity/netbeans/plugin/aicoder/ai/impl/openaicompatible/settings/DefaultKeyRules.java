package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings;

import java.net.URI;
import java.net.URISyntaxException;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.BaseUrls;

/**
 * The rules for the plugin-wide default API key (spec §8.6): it is used only by sessions whose normalised root equals
 * the default URL's root, and it is cleared when the default URL moves to a different root. Pure logic, no preferences
 * or Keyring access.
 */
public final class DefaultKeyRules {

    /**
     * Shown when the default key is cleared because the default URL changed.
     */
    public static final String CLEARED_NOTICE = "Default API key cleared because the default URL changed";

    private DefaultKeyRules() {
    }

    /**
     * @return true when both URLs have a root and the roots are equal (scheme, host and port, case-insensitive host, a
     * trailing {@code /v1} ignored)
     */
    public static boolean sameRoot(String urlA, String urlB) {
        String rootA = BaseUrls.root(urlA);
        return !rootA.isEmpty() && rootA.equals(BaseUrls.root(urlB));
    }

    /**
     * Whether saving the default URL must clear the stored default key: the previous URL had a root and the new one has
     * a different root (a blank new URL counts as different). A previous URL with no root protects nothing, so it never
     * clears the key.
     */
    public static boolean shouldClearDefaultKey(String previousUrl, String newUrl) {
        String previousRoot = BaseUrls.root(previousUrl);
        if (previousRoot.isEmpty()) {
            return false;
        }
        return !previousRoot.equals(BaseUrls.root(newUrl));
    }

    /**
     * @return "Key: using default (for &lt;host&gt;)" when the session has no key of its own, a default key is stored
     * and the session's root equals the default URL's root; otherwise null
     */
    public static String usingDefaultLabel(String sessionUrl, String defaultUrl, boolean defaultKeySet,
                                           boolean sessionKeySet) {
        if (sessionKeySet || !defaultKeySet || !sameRoot(sessionUrl, defaultUrl)) {
            return null;
        }
        return "Key: using default (for " + hostOf(BaseUrls.root(sessionUrl)) + ")";
    }

    private static String hostOf(String root) {
        try {
            String host = new URI(root).getHost();
            return host == null ? root : host;
        }
        catch (URISyntaxException ex) {
            return root;
        }
    }
}
