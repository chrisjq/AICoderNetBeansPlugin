package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Component;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;

/**
 * A {@code ListCellRenderer} for the info bar's effort/thinking combos: renders a {@code null} or blank item as a short
 * placeholder instead of an empty row, and otherwise renders the item text unchanged. Presentation only — the combo's
 * stored/returned value (whatever {@code getSelected*()} reads) is untouched; only what the user SEES changes. Shared
 * rather than duplicated per backend, since every backend's "not set" entry is presentation-only in exactly this same
 * way.
 */
public final class BlankSafeComboRenderer extends DefaultListCellRenderer {

    /**
     * The single, shared label for an effort/thinking combo's "not set" entry — used everywhere this concept is shown:
     * info bars (via this renderer), Options tabs, and session-create dialogs, for Claude, Grok, Codex, Ollama and pi.
     * Replaces what used to be per-backend text ("(model default)", "(Claude default)", "(pi default)").
     * Effort/thinking only — the model picker's own default label is a separate concept and keeps its own text.
     */
    public static final String DEFAULT_OPTION = "default";

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                  boolean cellHasFocus) {
        Object display = (value == null || value.toString().isBlank()) ? DEFAULT_OPTION : value;
        return super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus);
    }
}
