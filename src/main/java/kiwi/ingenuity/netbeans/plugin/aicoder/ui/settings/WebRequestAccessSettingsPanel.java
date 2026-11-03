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
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;

public final class WebRequestAccessSettingsPanel extends JPanel {

    private final JCheckBox allowWebRequestsCheckBox = new JCheckBox();
    private final Map<WebRequestAccessOptionEnum, JCheckBox> optionCheckBoxes
            = new EnumMap<>(WebRequestAccessOptionEnum.class);

    public WebRequestAccessSettingsPanel() {
        setBorder(BorderFactory.createTitledBorder("Web Requests Config"));
        setLayout(new GridBagLayout());

        int row = 0;
        allowWebRequestsCheckBox.setText(AccessControlLabelEnum.ALLOW_WEB_REQUESTS.displayLabel());
        allowWebRequestsCheckBox.setToolTipText("Allows " + McpToolEnum.WEB_REQUEST.toolName()
                + " GET requests to public destinations; the options below allow more methods, headers, bodies, localhost and private networks.");
        addRow(allowWebRequestsCheckBox, row++, 0);
        allowWebRequestsCheckBox.addActionListener(e -> updateDependentState());
        for (WebRequestAccessOptionEnum option : WebRequestAccessOptionEnum.values()) {
            JCheckBox checkBox = new JCheckBox(option.label().displayLabel());
            checkBox.setToolTipText(option.isMethod()
                                    ? "Allows " + option.methodName() + " requests via " + McpToolEnum.WEB_REQUEST.toolName() + "."
                                    : webOptionTooltip(option));
            optionCheckBoxes.put(option, checkBox);
            addRow(checkBox, row++, 20);
        }
        updateDependentState();
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
    }

    public boolean isOptionSelected(WebRequestAccessOptionEnum option) {
        return optionCheckBoxes.get(option).isSelected();
    }

    boolean isOptionEnabled(WebRequestAccessOptionEnum option) {
        return optionCheckBoxes.get(option).isEnabled();
    }

    public void setOptionSelected(WebRequestAccessOptionEnum option, boolean selected) {
        optionCheckBoxes.get(option).setSelected(selected);
    }

    private String webOptionTooltip(WebRequestAccessOptionEnum option) {
        return switch (option) {
            case HEADERS ->
                "Allows " + McpToolEnum.WEB_REQUEST.toolName() + " to send custom request headers.";
            case BODY ->
                "Allows " + McpToolEnum.WEB_REQUEST.toolName() + " to send request bodies.";
            case LOCALHOST ->
                "Allows requests to localhost services.";
            case PRIVATE_NETWORKS ->
                "Allows private-network requests, including link-local, CGNAT, and cloud metadata addresses.";
            default ->
                "";
        };
    }

    private void updateDependentState() {
        boolean enabled = allowWebRequestsCheckBox.isSelected();
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
