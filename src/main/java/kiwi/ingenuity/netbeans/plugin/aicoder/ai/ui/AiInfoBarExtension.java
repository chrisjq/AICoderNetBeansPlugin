package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.List;
import javax.swing.JComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEventListener;

public interface AiInfoBarExtension extends AiProcessImplEventListener {

    List<JComponent> createComponents();

    void onPropertyEvent(AiPropertyEvent event);

    default void onSessionPct(double pct) {
    }

    default void onProcessingChanged(boolean processing) {
    }

    default void onSessionSettingsChanged(AiSessionSettings settings) {
    }

    /**
     * Called on the EDT by {@code AiTopComponent.setCompacting} when a backend compaction starts and ends, so
     * an extension with a Compact button can disable it for the duration. A compaction done over RPC is not a
     * turn, so turn-running state never covers it.
     */
    default void onCompactingChanged(boolean compacting) {
    }

    default void dispose() {
    }
}
