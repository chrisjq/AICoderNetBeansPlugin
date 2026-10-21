package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.AccessControlLabelEnum;

public final class AiMessagingSettingsPanel extends JPanel {

    private final JCheckBox allowInterAiCheckBox = new JCheckBox();
    private final JCheckBox autoNotifyInboxCheckBox = new JCheckBox();
    private final JCheckBox allowImportantMessagesCheckBox = new JCheckBox();
    private final JCheckBox allowIdleWatcherCheckBox = new JCheckBox();

    public AiMessagingSettingsPanel() {
        setBorder(BorderFactory.createTitledBorder("AI Messaging"));
        setLayout(new GridBagLayout());

        allowInterAiCheckBox.setText(AccessControlLabelEnum.ALLOW_INTER_AI_COMMS.displayLabel());
        autoNotifyInboxCheckBox.setText(AccessControlLabelEnum.AUTO_NOTIFY_INBOX.displayLabel());
        allowImportantMessagesCheckBox.setText(AccessControlLabelEnum.ALLOW_IMPORTANT_MESSAGES.displayLabel());
        allowIdleWatcherCheckBox.setText(AccessControlLabelEnum.ALLOW_IDLE_WATCHER_TIMER.displayLabel());

        addRow(allowInterAiCheckBox, 0, 0);
        addRow(autoNotifyInboxCheckBox, 1, 20);
        addRow(allowImportantMessagesCheckBox, 2, 20);
        addRow(allowIdleWatcherCheckBox, 3, 20);

        allowInterAiCheckBox.addActionListener(e -> updateDependentState());
        updateDependentState();
    }

    public void addChangeListener(ActionListener listener) {
        allowInterAiCheckBox.addActionListener(listener);
        autoNotifyInboxCheckBox.addActionListener(listener);
        allowImportantMessagesCheckBox.addActionListener(listener);
        allowIdleWatcherCheckBox.addActionListener(listener);
    }

    public boolean isAllowInterAiSelected() {
        return allowInterAiCheckBox.isSelected();
    }

    public void setAllowInterAiSelected(boolean selected) {
        allowInterAiCheckBox.setSelected(selected);
        updateDependentState();
    }

    public boolean isAutoNotifySelected() {
        return autoNotifyInboxCheckBox.isSelected();
    }

    public void setAutoNotifySelected(boolean selected) {
        autoNotifyInboxCheckBox.setSelected(selected);
    }

    public boolean isAllowImportantSelected() {
        return allowImportantMessagesCheckBox.isSelected();
    }

    public void setAllowImportantSelected(boolean selected) {
        allowImportantMessagesCheckBox.setSelected(selected);
    }

    public boolean isAllowIdleWatcherSelected() {
        return allowIdleWatcherCheckBox.isSelected();
    }

    public void setAllowIdleWatcherSelected(boolean selected) {
        allowIdleWatcherCheckBox.setSelected(selected);
    }

    private void updateDependentState() {
        boolean enabled = allowInterAiCheckBox.isSelected();
        autoNotifyInboxCheckBox.setEnabled(enabled);
        allowImportantMessagesCheckBox.setEnabled(enabled);
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
