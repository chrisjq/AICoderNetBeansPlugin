package kiwi.ingenuity.netbeans.plugin.aicoder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiTopComponent;
import org.openide.modules.ModuleInstall;
import org.openide.util.NbBundle;
import org.openide.windows.TopComponent;

public class Installer extends ModuleInstall {

    private static final Logger LOG = Logger.getLogger(Installer.class.getName());

    public static final String VERSION;

    /**
     * Project homepage, filtered in from the pom's {@code <url>} so it cannot drift from the one the built nbm
     * advertises. Blank rather than null if the resource is missing, so callers can test it without a null check.
     */
    public static final String HOMEPAGE;

    static {
        String v = "unknown";
        String home = "";
        try (InputStream is = Installer.class.getResourceAsStream(
                "/kiwi/ingenuity/netbeans/plugin/aicoder/version.properties")) {
            if (is != null) {
                Properties p = new Properties();
                p.load(is);
                v = p.getProperty("version", "unknown");
                home = p.getProperty("homepage", "");
            }
        }
        catch (Exception ignored) {
        }
        VERSION = v;
        HOMEPAGE = home;
    }

    /**
     * Release page for exactly this build, derived from {@link #HOMEPAGE} and {@link #VERSION} rather than stored, so a
     * version bump cannot leave it pointing at an older release.
     * <p>
     * Only a guess at a URL: the tag is created when a release is published, so a build made between releases - or any
     * build where the version could not be read - has no page to link to. Returns blank in that case, and callers
     * should say the link may not exist rather than promise it resolves.
     */
    public static String releaseUrl() {
        if (HOMEPAGE.isBlank() || VERSION.isBlank() || "unknown".equals(VERSION)) {
            return "";
        }
        String base = HOMEPAGE.endsWith("/") ? HOMEPAGE.substring(0, HOMEPAGE.length() - 1) : HOMEPAGE;
        return base + "/releases/tag/v" + VERSION;
    }

    /**
     * The plugin's display name, read from the same bundle key the Plugin Manager shows, so the two cannot disagree.
     * <p>
     * Falls back rather than propagating: {@link NbBundle} throws when a key is absent, and a build that dropped this
     * one would take out every panel that shows the name for the sake of a caption.
     */
    public static String displayName() {
        try {
            return NbBundle.getMessage(Installer.class, "OpenIDE-Module-Name");
        }
        catch (MissingResourceException ex) {
            LOG.log(Level.WARNING, "OpenIDE-Module-Name missing from the bundle; falling back to the short name", ex);
            return StringConst.PLUGIN_NAME;
        }
    }

    @Override
    public void restored() {
        LOG.log(Level.INFO, StringConst.PLUGIN_NAME + " plugin v{0} activated. Use Tools > AI Coder to open the panel.", VERSION);
        // Recover usage/model fetching when the user authenticates after startup.
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeCredentialMonitor.getInstance().start();
    }

    @Override
    public void uninstalled() {
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (TopComponent tc : new ArrayList<>(TopComponent.getRegistry().getOpened())) {
                    if (tc instanceof AiTopComponent && tc.isOpened()) {
                        tc.close();
                    }
                }
            });
        }
        catch (Exception ex) {
            // best effort
        }

        // Force-stop the MCP server in case any session cleanup was incomplete.
        kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpServerRegistry.stopAll();
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker.getInstance().shutdownNotifier();
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.mail.AiSessionInboxBroker.getInstance().shutdownSweeper();
        kiwi.ingenuity.netbeans.plugin.aicoder.process.locking.LockManager.getInstance().shutdown();
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.ClaudeCredentialMonitor.getInstance().stop();
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.AnthropicApiClient.rateLimitManager().shutdown();
    }
}
