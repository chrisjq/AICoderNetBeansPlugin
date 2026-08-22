package kiwi.ingenuity.netbeans.plugin.aicoder.utils;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hands a URL or file to the desktop environment.
 * <p>
 * Two mechanisms because neither is enough on its own: {@link Desktop} is the supported route but reports itself
 * unavailable on headless setups and on several Linux desktops, while the per-OS command works there and nowhere near
 * as predictably elsewhere. Failing to open a link is never worth an exception reaching the caller, so both failures
 * are logged and swallowed.
 */
public final class BrowserUtil {

    private static final Logger LOG = Logger.getLogger(BrowserUtil.class.getName());

    /**
     * Opens a web URL (or any non-file link) in the system browser.
     */
    public static void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        }
        catch (Exception ex) {
            LOG.log(Level.FINE, "Desktop.browse failed, trying platform command: " + url, ex);
        }
        try {
            new ProcessBuilder(platformOpenCommand(url)).start();
        }
        catch (Exception ex) {
            LOG.log(Level.WARNING, "Could not open link: " + url, ex);
        }
    }

    /**
     * Per-OS command to open a file path or URL with its default handler.
     */
    public static List<String> platformOpenCommand(String target) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return List.of("open", target);
        }
        if (os.contains("win")) {
            return List.of("rundll32", "url.dll,FileProtocolHandler", target);
        }
        return List.of("xdg-open", target);
    }

    private BrowserUtil() {
    }
}
