package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.SavedServer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.FormLayoutUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.DialogPlacement;

/**
 * Modal add/edit form for one saved server.
 */
final class SavedServerForm {

    private SavedServerForm() {
    }

    static Optional<Result> showDialog(Component parent, SavedServer existing) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent));
        dialog.setTitle(existing == null ? "Add server" : "Edit server");
        dialog.setModal(true);
        JTextField name = new JTextField(existing == null ? "" : existing.name(), 26);
        JTextField url = new JTextField(existing == null ? "" : existing.url(), 26);
        JComboBox<ServerTypeEnum> type = new JComboBox<>(ServerTypeEnum.values());
        type.setSelectedItem(existing == null ? ServerTypeEnum.AUTO : existing.serverType());
        JPasswordField key = new JPasswordField(26);
        final boolean[] clearKey = {false};
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> {
            key.setText("");
            clearKey[0] = true;
        });
        key.getDocument().addDocumentListener(new DocumentListener() {
            private void edited() {
                if (key.getPassword().length > 0) {
                    clearKey[0] = false;
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                edited();
            }
        });
        JPanel keyRow = new JPanel(new BorderLayout(4, 0));
        keyRow.add(key, BorderLayout.CENTER);
        keyRow.add(clear, BorderLayout.EAST);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        addRow(form, c, 0, "Name:", name);
        addRow(form, c, 1, "URL:", url);
        addRow(form, c, 2, "Server type:", type);
        addRow(form, c, 3, "API key:", keyRow);
        JLabel error = new JLabel(" ");
        error.setForeground(Color.RED);
        addRow(form, c, 4, "", error);
        final Result[] result = new Result[1];
        JPanel buttons = new JPanel();
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            String rawUrl = url.getText().trim();
            String lowerUrl = rawUrl.toLowerCase(Locale.ROOT);
            if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
                error.setText("URL must start with http:// or https://");
                return;
            }
            char[] entered = key.getPassword();
            result[0] = new Result(name.getText(), rawUrl, (ServerTypeEnum) type.getSelectedItem(),
                                   entered.length == 0 && !clearKey[0] ? null : entered, clearKey[0]);
            if (entered.length == 0) {
                Arrays.fill(entered, '\0');
            }
            dialog.dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dialog.dispose());
        buttons.add(ok);
        buttons.add(cancel);
        dialog.add(form, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        DialogPlacement.centre(dialog, parent);
        dialog.setVisible(true);
        return Optional.ofNullable(result[0]);
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int row, String label, Component field) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        panel.add(FormLayoutUtil.label(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
    }

    record Result(String name, String url, ServerTypeEnum serverType, char[] key, boolean clearKey) {

    }
}
