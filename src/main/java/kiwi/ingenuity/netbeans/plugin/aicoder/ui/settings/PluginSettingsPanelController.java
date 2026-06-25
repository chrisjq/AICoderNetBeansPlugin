package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import javax.swing.JComponent;
import org.netbeans.spi.options.OptionsPanelController;
import org.openide.util.HelpCtx;
import org.openide.util.Lookup;

/**
 * Registration is provided explicitly in layer.xml (for reliability with
 * user-installed NBMs in NetBeans 30 + ergonomics, mirroring the approach that
 * made the Tools menu contribution work). The annotation is left commented so
 * the processor does not emit a conflicting generated-layer entry.
 */
public class PluginSettingsPanelController extends OptionsPanelController {

    private PluginSettingsPanel panel;
    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private boolean changed = false;

    public PluginSettingsPanelController() {
    }

    @Override
    public void update() {
        getPanel().load();
        changed = false;
    }

    @Override
    public void applyChanges() {
        getPanel().store();
        changed = false;
    }

    @Override
    public void cancel() {
        // No rollback needed — settings only written on applyChanges
    }

    @Override
    public boolean isValid() {
        return getPanel().isSettingsValid();
    }

    @Override
    public boolean isChanged() {
        return changed;
    }

    @Override
    public JComponent getComponent(Lookup masterLookup) {
        return getPanel();
    }

    @Override
    public HelpCtx getHelpCtx() {
        return HelpCtx.DEFAULT_HELP;
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    private PluginSettingsPanel getPanel() {
        if (panel == null) {
            panel = new PluginSettingsPanel();
            panel.addPropertyChangeListener(PluginSettingsPanel.PROP_CHANGED, evt -> {
                if (!changed) {
                    changed = true;
                    pcs.firePropertyChange(OptionsPanelController.PROP_CHANGED, false, true);
                }
            });
        }
        return panel;
    }
}
