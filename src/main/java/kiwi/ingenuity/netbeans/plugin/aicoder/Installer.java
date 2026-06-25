package kiwi.ingenuity.netbeans.plugin.aicoder;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiTopComponent;
import org.openide.modules.ModuleInstall;
import org.openide.windows.TopComponent;

public class Installer extends ModuleInstall {

    private static final Logger LOG = Logger.getLogger(Installer.class.getName());

    public static final String VERSION;

    static {
        String v = "unknown";
        try (java.io.InputStream is = Installer.class.getResourceAsStream(
                "/kiwi/ingenuity/netbeans/plugin/aicoder/version.properties")) {
            if (is != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(is);
                v = p.getProperty("version", "unknown");
            }
        }
        catch (Exception ignored) {
        }
        VERSION = v;
    }

    @Override
    public void restored() {
        LOG.log(Level.INFO, StringConst.PLUGIN_NAME + " plugin v{0} activated. Use Tools > AI Coder to open the panel.", VERSION);
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
        kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.AnthropicApiClient.rateLimitManager().shutdown();
    }
}
