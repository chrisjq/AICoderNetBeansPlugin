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
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;

public final class WebRequestAccessSettingsPanel extends JPanel {

    private static String label(AccessControlLabelEnum label, boolean sessionOverrideLabels,
            boolean globalEnabled) {
        return sessionOverrideLabels ? label.sessionLabel(globalEnabled) : label.globalLabel();
    }

    // In session mode each control shows a live "Set from Global" / "Set on Session"
    // marker; the base label (without marker) is kept so the marker can be re-applied
    // as the value is toggled.
    private final boolean sessionMode;
    private final JCheckBox allowWebRequestsCheckBox = new JCheckBox();
    private final String allowWebRequestsBaseLabel;
    private final Map<WebRequestAccessOptionEnum, JCheckBox> optionCheckBoxes
            = new EnumMap<>(WebRequestAccessOptionEnum.class);
    private final Map<WebRequestAccessOptionEnum, String> optionBaseLabels
            = new EnumMap<>(WebRequestAccessOptionEnum.class);

    public WebRequestAccessSettingsPanel(boolean sessionOverrideLabels) {
        this.sessionMode = sessionOverrideLabels;
        setBorder(BorderFactory.createTitledBorder("Web Requests"));
        setLayout(new GridBagLayout());

        allowWebRequestsBaseLabel = label(AccessControlLabelEnum.ALLOW_WEB_REQUESTS,
                sessionOverrideLabels, PluginSettings.isAllowWebRequests());
        allowWebRequestsCheckBox.setText(allowWebRequestsBaseLabel);
        addRow(allowWebRequestsCheckBox, 0, 0);

        int row = 1;
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            String base = label(option.label(), sessionOverrideLabels,
                    PluginSettings.isAllowWebRequestAccess(option));
            optionBaseLabels.put(option, base);
            JCheckBox checkBox = new JCheckBox(base);
            optionCheckBoxes.put(option, checkBox);
            addRow(checkBox, row++, 20);
        }

        allowWebRequestsCheckBox.addActionListener(e -> updateDependentState());
        if (sessionMode) {
            allowWebRequestsCheckBox.addActionListener(e -> refreshMarkers());
            for (JCheckBox checkBox : optionCheckBoxes.values()) {
                checkBox.addActionListener(e -> refreshMarkers());
            }
        }
        updateDependentState();
        refreshMarkers();
    }

    public void addChangeListener(ActionListener listener) {
        allowWebRequestsCheckBox.addActionListener(listener);
        for (JCheckBox checkBox : optionCheckBoxes.values()) {
            checkBox.addActionListener(listener);
        }
    }

    public boolean isAllowWebRequestsSelected() {
        return allowWebRequestsCheckBox.isSelected();
    }

    public void setAllowWebRequestsSelected(boolean selected) {
        allowWebRequestsCheckBox.setSelected(selected);
        updateDependentState();
        refreshMarkers();
    }

    public boolean isOptionSelected(WebRequestAccessOptionEnum option) {
        return optionCheckBoxes.get(option).isSelected();
    }

    boolean isOptionEnabled(WebRequestAccessOptionEnum option) {
        return optionCheckBoxes.get(option).isEnabled();
    }

    public void setOptionSelected(WebRequestAccessOptionEnum option, boolean selected) {
        optionCheckBoxes.get(option).setSelected(selected);
        refreshMarkers();
    }

    private void updateDependentState() {
        boolean enabled = allowWebRequestsCheckBox.isSelected();
        for (JCheckBox checkBox : optionCheckBoxes.values()) {
            checkBox.setEnabled(enabled);
        }
    }

    /**
     * Re-applies the "Set from Global" / "Set on Session" marker to every control
     * based on whether its current value differs from the global default. No-op
     * outside session mode (the global options panel shows no markers).
     */
    private void refreshMarkers() {
        if (!sessionMode) {
            return;
        }
        allowWebRequestsCheckBox.setText(AccessControlLabelEnum.withSessionMarker(
                allowWebRequestsBaseLabel,
                allowWebRequestsCheckBox.isSelected() != PluginSettings.isAllowWebRequests()));
        for (Map.Entry<WebRequestAccessOptionEnum, JCheckBox> entry : optionCheckBoxes.entrySet()) {
            WebRequestAccessOptionEnum option = entry.getKey();
            JCheckBox checkBox = entry.getValue();
            checkBox.setText(AccessControlLabelEnum.withSessionMarker(
                    optionBaseLabels.get(option),
                    checkBox.isSelected() != PluginSettings.isAllowWebRequestAccess(option)));
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
