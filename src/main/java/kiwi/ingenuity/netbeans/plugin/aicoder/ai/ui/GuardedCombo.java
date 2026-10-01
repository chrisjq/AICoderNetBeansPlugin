package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.event.ActionListener;
import javax.swing.JComboBox;

/**
 * Wraps a combo box's action listeners so programmatic mutations cannot look like user choices.
 *
 * <p>
 * The caller must already be on the EDT, or deliberately seed the combo before display. Info bars keep their
 * own self-dispatch guards; this helper only manages the nested guard state.</p>
 *
 * @param <T> combo-box item type
 */
public final class GuardedCombo<T> {

    private final JComboBox<T> combo;
    private boolean guarded;

    public GuardedCombo(JComboBox<T> combo) {
        this.combo = combo;
    }

    public JComboBox<T> component() {
        return combo;
    }

    public void addActionListener(ActionListener listener) {
        combo.addActionListener(event -> {
            if (!guarded) {
                listener.actionPerformed(event);
            }
        });
    }

    /**
     * Runs a combo mutation without notifying listeners. The previous state is restored even when the
     * mutation throws, and nested guarded mutations remain guarded.
     */
    public void runProgrammatic(Runnable mutation) {
        boolean wasGuarded = guarded;
        guarded = true;
        try {
            mutation.run();
        }
        finally {
            guarded = wasGuarded;
        }
    }
}
