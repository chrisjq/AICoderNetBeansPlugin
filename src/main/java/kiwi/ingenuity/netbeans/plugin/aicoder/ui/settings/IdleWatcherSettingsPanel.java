package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;

/**
 * The idle-watcher timer checkbox.
 */
public final class IdleWatcherSettingsPanel extends JPanel {

    private final JCheckBox allowIdleWatcherCheckBox = new JCheckBox();

    public IdleWatcherSettingsPanel() {
        setBorder(BorderFactory.createTitledBorder("Idle Watchers Config"));
        setLayout(new GridBagLayout());

        allowIdleWatcherCheckBox.setText(AccessControlLabelEnum.ALLOW_IDLE_WATCHER_TIMER.displayLabel());
        allowIdleWatcherCheckBox.setToolTipText("Allows the " + McpToolEnum.PEER_IDLE_WATCHER_CREATE.toolName() + " timer to monitor idle sessions.");

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 4);
        add(allowIdleWatcherCheckBox, c);
    }

    public void addChangeListener(ActionListener listener) {
        allowIdleWatcherCheckBox.addActionListener(listener);
    }

    public boolean isAllowIdleWatcherSelected() {
        return allowIdleWatcherCheckBox.isSelected();
    }

    public void setAllowIdleWatcherSelected(boolean selected) {
        allowIdleWatcherCheckBox.setSelected(selected);
    }
}
