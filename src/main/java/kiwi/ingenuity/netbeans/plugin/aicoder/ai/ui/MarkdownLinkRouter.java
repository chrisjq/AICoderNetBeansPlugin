package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.net.URI;
import java.util.Locale;

/**
 * Pure decision logic for what to do when a link in a rendered markdown message
 * is clicked. Kept free of Swing / NetBeans APIs so the routing is
 * unit-testable; {@code MessagePanel} performs the actual side effects.
 *
 * <ul>
 * <li>{@code http(s)} URLs open in the system browser.</li>
 * <li>An existing file that belongs to an open project opens in the NetBeans
 * editor (new tab).</li>
 * <li>Any other existing local file opens in the OS default viewer.</li>
 * <li>Anything else (missing file, mailto:, unknown scheme) falls back to the
 * browser.</li>
 * </ul>
 */
public final class MarkdownLinkRouter {

    public static boolean isWebUrl(String url) {
        if (url == null) {
            return false;
        }
        String l = url.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }

    /**
     * Extracts the local filesystem path a link points at, stripping a
     * {@code file://} scheme (with percent-decoding) and any trailing
     * {@code :linenum} suffix. Returns null if the link is not a local path
     * (web URL, mailto:, anchor, etc.).
     */
    public static String localFilePath(String url) {
        if (url == null || url.isBlank() || isWebUrl(url)) {
            return null;
        }
        String path = null;
        if (url.startsWith("file:")) {
            // Handles file:/path, file://host/path and file:///path. URL.toString()
            // (e.g. from HyperlinkEvent.getURL()) normalises to a single slash,
            // which a plain "file://" check would miss.
            try {
                path = new URI(url).getPath();
            }
            catch (Exception e) {
                return null;
            }
        }
        else if (url.startsWith("/")) {
            path = url;
        }
        if (path == null) {
            return null;
        }
        return path.replaceAll(":(\\d+)$", "");
    }

    /**
     * Decides the action for a clicked link given environment facts the caller
     * has resolved.
     *
     * @param fileExists whether {@link #localFilePath(String)} resolves to an
     * existing regular file
     * @param inProject whether that file belongs to an open NetBeans project
     */
    public static Action route(String url, boolean fileExists, boolean inProject) {
        if (isWebUrl(url)) {
            return Action.BROWSER;
        }
        String path = localFilePath(url);
        if (path != null && fileExists) {
            return inProject ? Action.EDITOR : Action.DEFAULT_VIEWER;
        }
        return Action.BROWSER;
    }

    private MarkdownLinkRouter() {
    }

    /**
     * What the caller should do with a clicked link.
     */
    public enum Action {
        BROWSER, EDITOR, DEFAULT_VIEWER
    }
}
