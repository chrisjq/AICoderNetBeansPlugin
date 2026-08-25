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
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.GitAccessOptionEnum;

public final class GitAccessSettingsPanel extends JPanel {

    private static String label(AccessControlLabelEnum label, boolean sessionMode) {
        return sessionMode ? label.displayLabel() : label.globalLabel();
    }

    private final JCheckBox allowGitAccessCheckBox = new JCheckBox();
    private final Map<GitAccessOptionEnum, JCheckBox> optionCheckBoxes
            = new EnumMap<>(GitAccessOptionEnum.class);

    public GitAccessSettingsPanel(boolean sessionMode) {
        setBorder(BorderFactory.createTitledBorder("Git"));
        setLayout(new GridBagLayout());

        allowGitAccessCheckBox.setText(label(AccessControlLabelEnum.ALLOW_GIT_ACCESS, sessionMode));
        addRow(allowGitAccessCheckBox, 0, 0);

        int row = 1;
        for (GitAccessOptionEnum option : GitAccessOptionEnum.values()) {
            JCheckBox checkBox = new JCheckBox(label(option.label(), sessionMode));
            optionCheckBoxes.put(option, checkBox);
            addRow(checkBox, row++, 20);
        }

        allowGitAccessCheckBox.addActionListener(e -> updateDependentState());
        updateDependentState();
    }

    public void addChangeListener(ActionListener listener) {
        allowGitAccessCheckBox.addActionListener(listener);
        for (JCheckBox checkBox : optionCheckBoxes.values()) {
            checkBox.addActionListener(listener);
        }
    }

    public boolean isAllowGitAccessSelected() {
        return allowGitAccessCheckBox.isSelected();
    }

    public void setAllowGitAccessSelected(boolean selected) {
        allowGitAccessCheckBox.setSelected(selected);
        updateDependentState();
    }

    public boolean isOptionSelected(GitAccessOptionEnum option) {
        return optionCheckBoxes.get(option).isSelected();
    }

    boolean isOptionEnabled(GitAccessOptionEnum option) {
        return optionCheckBoxes.get(option).isEnabled();
    }

    public void setOptionSelected(GitAccessOptionEnum option, boolean selected) {
        optionCheckBoxes.get(option).setSelected(selected);
    }

    private void updateDependentState() {
        boolean enabled = allowGitAccessCheckBox.isSelected();
        for (JCheckBox checkBox : optionCheckBoxes.values()) {
            checkBox.setEnabled(enabled);
        }
    }

    private void addRow(Component component, int row, int leftInset) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4 + leftInset, 4, 4);
        add(component, c);
    }

}
