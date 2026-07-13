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

    // In session mode each control shows a live "Set from Global" / "Set on Session"
    // marker; base labels are kept so the marker can be re-applied on toggle.
    private final boolean sessionMode;
    private final JCheckBox allowDatabaseAccessCheckBox = new JCheckBox();
    private final String allowDatabaseAccessBaseLabel;
    private final Map<DatabaseAccessOptionEnum, JCheckBox> optionCheckBoxes
            = new EnumMap<>(DatabaseAccessOptionEnum.class);
    private final Map<DatabaseAccessOptionEnum, String> optionBaseLabels
            = new EnumMap<>(DatabaseAccessOptionEnum.class);
    private final JSpinner rowLimitSpinner;
    private final JLabel rowLimitMarker = new JLabel();

    public DatabaseAccessSettingsPanel(boolean sessionOverrideLabels) {
        this.sessionMode = sessionOverrideLabels;
        setBorder(BorderFactory.createTitledBorder("Database Access (read-only)"));
        setLayout(new GridBagLayout());

        allowDatabaseAccessBaseLabel = label(AccessControlLabelEnum.ALLOW_DATABASE_ACCESS,
                sessionOverrideLabels, PluginSettings.isAllowDatabaseAccess());
        allowDatabaseAccessCheckBox.setText(allowDatabaseAccessBaseLabel);
        addRow(allowDatabaseAccessCheckBox, 0, 0);

        int row = 1;
        for (DatabaseAccessOptionEnum option : DatabaseAccessOptionEnum.values()) {
            String base = label(option.label(), sessionOverrideLabels,
                    PluginSettings.isAllowDatabaseAccessOption(option));
            optionBaseLabels.put(option, base);
            JCheckBox checkBox = new JCheckBox(base);
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
        c.weightx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);
        add(rowLimitSpinner, c);
        c.gridx = 2;
        c.weightx = 1;
        rowLimitMarker.setEnabled(false);
        add(rowLimitMarker, c);

        allowDatabaseAccessCheckBox.addActionListener(e -> updateDependentState());
        if (sessionMode) {
            allowDatabaseAccessCheckBox.addActionListener(e -> refreshMarkers());
            for (Map.Entry<DatabaseAccessOptionEnum, JCheckBox> entry : optionCheckBoxes.entrySet()) {
                if (entry.getKey() != DatabaseAccessOptionEnum.READ_ONLY) {
                    entry.getValue().addActionListener(e -> refreshMarkers());
                }
            }
            rowLimitSpinner.addChangeListener(e -> refreshMarkers());
        }
        updateDependentState();
        refreshMarkers();
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
        refreshMarkers();
    }

    public boolean isOptionSelected(DatabaseAccessOptionEnum option) {
        return optionCheckBoxes.get(option).isSelected();
    }

    public void setOptionSelected(DatabaseAccessOptionEnum option, boolean selected) {
        if (option == DatabaseAccessOptionEnum.READ_ONLY) {
            return;
        }
        optionCheckBoxes.get(option).setSelected(selected);
        refreshMarkers();
    }

    public int getRowLimitValue() {
        return (Integer) rowLimitSpinner.getValue();
    }

    public void setRowLimitValue(int value) {
        rowLimitSpinner.setValue(value);
        refreshMarkers();
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

    /**
     * Re-applies the "Set from Global" / "Set on Session" marker to each control
     * based on whether its current value differs from the global default. The
     * fixed READ_ONLY row and (outside session mode) all controls are left plain.
     */
    private void refreshMarkers() {
        if (!sessionMode) {
            return;
        }
        allowDatabaseAccessCheckBox.setText(AccessControlLabelEnum.withSessionMarker(
                allowDatabaseAccessBaseLabel,
                allowDatabaseAccessCheckBox.isSelected() != PluginSettings.isAllowDatabaseAccess()));
        for (Map.Entry<DatabaseAccessOptionEnum, JCheckBox> entry : optionCheckBoxes.entrySet()) {
            DatabaseAccessOptionEnum option = entry.getKey();
            if (option == DatabaseAccessOptionEnum.READ_ONLY) {
                continue;
            }
            JCheckBox checkBox = entry.getValue();
            checkBox.setText(AccessControlLabelEnum.withSessionMarker(
                    optionBaseLabels.get(option),
                    checkBox.isSelected() != PluginSettings.isAllowDatabaseAccessOption(option)));
        }
        boolean rowLimitOverridden = getRowLimitValue() != PluginSettings.getDatabaseRowLimit();
        rowLimitMarker.setText(rowLimitOverridden
                ? AccessControlLabelEnum.MARKER_ON_SESSION : AccessControlLabelEnum.MARKER_FROM_GLOBAL);
    }

    private void addRow(Component component, int row, int leftInset) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4 + leftInset, 4, 4);
        add(component, c);
    }
}
