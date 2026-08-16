package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import java.awt.event.ActionListener;
import javax.swing.JComponent;

/**
 * AI-type-specific settings panel for a session. Used both at session creation
 * and when editing an existing session.
 */
public interface AiSessionCreateSettingsPanel<E extends AiSessionSettings> {

    JComponent component();

    void load(E settings);

    void applyTo(E settings);

    /**
     * Starts any asynchronous data loading required by this panel.
     */
    default void startLoading() {
    }

    /**
     * Registers a listener that is notified when any control in this panel
     * changes, so callers can track whether the form is dirty.
     */
    default void addChangeListener(ActionListener listener) {
    }

    void dispose();
}
