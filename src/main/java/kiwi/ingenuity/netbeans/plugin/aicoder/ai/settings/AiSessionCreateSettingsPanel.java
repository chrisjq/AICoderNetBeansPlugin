package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import javax.swing.JComponent;

/**
 * Temporary, session-scoped AI-type settings used only while creating a
 * session.
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

    void dispose();
}
