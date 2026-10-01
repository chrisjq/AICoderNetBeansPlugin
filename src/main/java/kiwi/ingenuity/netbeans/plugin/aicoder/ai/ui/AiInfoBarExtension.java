package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.List;
import javax.swing.JComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEventListener;

/**
 * A backend's contribution to the info bar. The core calls every method of this interface, and of
 * {@link AiProcessImplEventListener}, on the EDT — including the calls a backend makes into the extension
 * while {@code createInfoBarExtension} builds it. Implementations need no EDT guard of their own and must not
 * block: work that is not UI belongs in the backend, reached through the listeners the bar raises.
 */
public interface AiInfoBarExtension extends AiProcessImplEventListener {

    List<JComponent> createComponents();

    void onPropertyEvent(AiPropertyEvent event);

    default void onSessionPct(double pct) {
    }

    /**
     * Called on the EDT when the session becomes busy or ready — for any reason: a turn, a compaction, a lazy
     * start. The one rule every info bar follows: while busy, disable every action control (Compact,
     * model/effort combos, Clear, …); when ready, re-enable them. The bar is never told <em>why</em>
     * the session is busy.
     */
    default void onBusyChanged(boolean busy) {
    }

    default void onSessionSettingsChanged(AiSessionSettings settings) {
    }

    default void dispose() {
    }
}
