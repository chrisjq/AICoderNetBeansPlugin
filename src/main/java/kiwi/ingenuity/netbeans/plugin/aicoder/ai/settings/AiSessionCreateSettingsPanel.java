package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import java.awt.event.ActionListener;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.JComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;

/**
 * AI-type-specific settings panel for a session. Used both at session creation and when editing an existing session.
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
     * Registers a listener that is notified when any control in this panel changes, so callers can track whether the
     * form is dirty.
     */
    default void addChangeListener(ActionListener listener) {
    }

    /**
     * The most recent asynchronous secret save, when this panel owns session-scoped secrets.
     */
    default CompletableFuture<Boolean> keySaveFuture() {
        return null;
    }

    /**
     * Undoes a keyring change started by {@link #keySaveFuture()} when the dialog closes without committing it. A
     * change that failed is left alone. Panels that own no secrets have nothing to undo.
     */
    default CompletableFuture<Void> revertKeyChange(CompletableFuture<Boolean> change) {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Completes once the secret state this panel needs (for example, what a key save would overwrite) has been read.
     * Panels that own no secrets are ready at once.
     */
    default CompletableFuture<Void> keyStateReady() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Persists any secrets staged by this panel after the corresponding sessions have been assigned ids.
     */
    default CompletableFuture<Void> savePendingSecrets(List<AiSession> sessions) {
        return CompletableFuture.completedFuture(null);
    }

    void dispose();
}
