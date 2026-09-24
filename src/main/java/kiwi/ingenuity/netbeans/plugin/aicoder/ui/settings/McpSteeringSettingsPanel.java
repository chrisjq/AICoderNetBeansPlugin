package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;

public final class McpSteeringSettingsPanel extends JPanel {

    private static String label(AccessControlLabelEnum label, boolean sessionMode) {
        return sessionMode ? label.displayLabel() : label.globalLabel();
    }

    private final JCheckBox enabledCheckBox = new JCheckBox();

    public McpSteeringSettingsPanel(boolean sessionMode) {
        setBorder(BorderFactory.createTitledBorder("MCP steering"));
        setLayout(new GridBagLayout());
        enabledCheckBox.setText(label(AccessControlLabelEnum.MCP_STEERING, sessionMode));
        addRow(enabledCheckBox, 0);
    }

    public void addChangeListener(ActionListener listener) {
        enabledCheckBox.addActionListener(listener);
    }

    public boolean isMcpSteeringSelected() {
        return enabledCheckBox.isSelected();
    }

    public void setMcpSteeringSelected(boolean selected) {
        enabledCheckBox.setSelected(selected);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        enabledCheckBox.setEnabled(enabled);
    }

    private void addRow(java.awt.Component component, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 4);
        add(component, c);
    }
}
