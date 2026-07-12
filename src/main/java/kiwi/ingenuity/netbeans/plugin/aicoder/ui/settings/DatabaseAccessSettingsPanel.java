package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;

/**
 * Mirrors {@link WebRequestAccessSettingsPanel}'s master + sub-option
 * structure for database access, plus a row-limit spinner (no equivalent in
 * the web-request panel). {@link DatabaseAccessOptionEnum#READ_ONLY} is
 * rendered checked and disabled: this plugin has no write-capable database
 * tools, so there's nothing meaningful to toggle off yet — the checkbox
 * exists to make that constraint visible rather than to be interactive.
 */
public final class DatabaseAccessSettingsPanel extends JPanel {

    private static String label(AccessControlLabelEnum label, boolean sessionOverrideLabels,
            boolean globalEnabled) {
        return sessionOverrideLabels ? label.sessionLabel(globalEnabled) : label.globalLabel();
    }

    private final JCheckBox allowDatabaseAccessCheckBox = new JCheckBox();
    private final Map<DatabaseAccessOptionEnum, JCheckBox> optionCheckBoxes
            = new EnumMap<>(DatabaseAccessOptionEnum.class);
    private final JSpinner rowLimitSpinner;

    public DatabaseAccessSettingsPanel(boolean sessionOverrideLabels) {
        setBorder(BorderFactory.createTitledBorder("Database Access (read-only)"));
        setLayout(new GridBagLayout());

        allowDatabaseAccessCheckBox.setText(label(AccessControlLabelEnum.ALLOW_DATABASE_ACCESS,
                sessionOverrideLabels, PluginSettings.isAllowDatabaseAccess()));
        addRow(allowDatabaseAccessCheckBox, 0, 0);

        int row = 1;
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            JCheckBox checkBox = new JCheckBox(label(option.label(), sessionOverrideLabels,
                    PluginSettings.isAllowDatabaseAccessOption(option)));
            if (option == DatabaseAccessOptionEnum.READ_ONLY) {
                checkBox.setSelected(true);
                checkBox.setEnabled(false);
            }
            optionCheckBoxes.put(option, checkBox);
            addRow(checkBox, row++, 20);
        }

        rowLimitSpinner = new JSpinner(new SpinnerNumberModel(PluginSettings.getDatabaseRowLimit(), 1, 5000, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 24, 4, 4);
        add(new JLabel("Row limit:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);
        add(rowLimitSpinner, c);

        allowDatabaseAccessCheckBox.addActionListener(e -> updateDependentState());
        updateDependentState();
    }

    public void addChangeListener(ActionListener listener) {
        allowDatabaseAccessCheckBox.addActionListener(listener);
        for (Map.Entry<DatabaseAccessOptionEnum, JCheckBox> entry : optionCheckBoxes.entrySet()) {
            if (entry.getKey() != DatabaseAccessOptionEnum.READ_ONLY) {
                entry.getValue().addActionListener(listener);
            }
        }
    }

    public void addRowLimitChangeListener(ChangeListener listener) {
        rowLimitSpinner.addChangeListener(listener);
    }

    public boolean isAllowDatabaseAccessSelected() {
        return allowDatabaseAccessCheckBox.isSelected();
    }

    public void setAllowDatabaseAccessSelected(boolean selected) {
        allowDatabaseAccessCheckBox.setSelected(selected);
        updateDependentState();
    }

    public boolean isOptionSelected(DatabaseAccessOptionEnum option) {
        return optionCheckBoxes.get(option).isSelected();
    }

    public void setOptionSelected(DatabaseAccessOptionEnum option, boolean selected) {
        if (option == DatabaseAccessOptionEnum.READ_ONLY) {
            return;
        }
        optionCheckBoxes.get(option).setSelected(selected);
    }

    public int getRowLimitValue() {
        return (Integer) rowLimitSpinner.getValue();
    }

    public void setRowLimitValue(int value) {
        rowLimitSpinner.setValue(value);
    }

    private void updateDependentState() {
        boolean enabled = allowDatabaseAccessCheckBox.isSelected();
        for (Map.Entry<DatabaseAccessOptionEnum, JCheckBox> entry : optionCheckBoxes.entrySet()) {
            if (entry.getKey() != DatabaseAccessOptionEnum.READ_ONLY) {
                entry.getValue().setEnabled(enabled);
            }
        }
        rowLimitSpinner.setEnabled(enabled);
    }

    private void addRow(Component component, int row, int leftInset) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4 + leftInset, 4, 4);
        add(component, c);
    }
}
