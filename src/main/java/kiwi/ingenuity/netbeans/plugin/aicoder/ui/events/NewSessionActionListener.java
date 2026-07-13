package kiwi.ingenuity.netbeans.plugin.aicoder.ui.events;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.SessionPickerDialog;

/**
 * Launches the AI Manager (session picker).
 *
 * Registration is provided explicitly in layer.xml (an {@code Actions/Other}
 * instance plus a {@code Menu/Tools} shadow), mirroring the Options panel
 * registration. The {@code @ActionID}/{@code @ActionRegistration}/
 * {@code @ActionReference} annotations are intentionally omitted so the
 * annotation processor does not emit a conflicting generated-layer.xml entry —
 * that generated entry does not reliably apply on a fresh NBM install, which
 * previously left Tools &gt; AI Manager (and its Keymap entry) missing.
 */
public class NewSessionActionListener implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent e) {
        SessionPickerDialog.show(new SessionPersistenceManager());
    }
}
