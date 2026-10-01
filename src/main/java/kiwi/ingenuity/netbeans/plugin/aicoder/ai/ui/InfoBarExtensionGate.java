package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * Delivers core events to an {@link AiInfoBarExtension} on the EDT. The invariant is that every call into an
 * extension is on the EDT, so an extension never has to guard its own methods. Core events reach it through
 * {@link #deliver}, whichever thread asked. Component creation and busy changes come straight from
 * {@code AiInfoBar}, whose methods dispatch to the EDT themselves.
 *
 * <p>
 * A busy change queued on the EDT before the tab closed can still run after {@link #dispose}, because
 * {@code AiInfoBar} keeps its own reference to the extension. That is harmless: every
 * {@code onBusyChanged} only enables or disables the extension's own components, which outlive dispose.</p>
 */
final class InfoBarExtensionGate {

    private static final Logger LOG = Logger.getLogger(InfoBarExtensionGate.class.getName());

    private volatile AiInfoBarExtension extension;

    /**
     * Runs {@code action} on the EDT: inline when already there, otherwise queued behind pending EDT work.
     */
    static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        }
        else {
            SwingUtilities.invokeLater(action);
        }
    }

    void install(AiInfoBarExtension ext) {
        this.extension = ext;
    }

    /**
     * Calls {@code call} with the installed extension on the EDT. The extension is looked up when the call
     * runs, not when it was requested, so a call queued before the tab closed finds nothing and is dropped.
     */
    void deliver(Consumer<AiInfoBarExtension> call) {
        runOnEdt(() -> {
            AiInfoBarExtension ext = extension;
            if (ext != null) {
                call.accept(ext);
            }
        });
    }

    /**
     * Detaches the extension and disposes it on the EDT.
     */
    void dispose() {
        runOnEdt(() -> {
            AiInfoBarExtension ext = extension;
            extension = null;
            if (ext == null) {
                return;
            }
            try {
                ext.dispose();
            }
            catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Error disposing infoBarExtension during session close", e);
            }
        });
    }
}
