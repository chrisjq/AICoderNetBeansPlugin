package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.headers.ExtraHeaders;

/**
 * The extra request headers of a session (spec §8.6): a list of name and value rows. Every row is checked with
 * {@link ExtraHeaders#validate}. A row whose name is secret ({@link ExtraHeaders#isSecret}) masks its value; that value
 * is never part of the session settings, the caller reads it with {@link #pendingSecrets()} and stores it in the
 * Keyring. Names and plain values go to the session file.
 */
public final class ExtraHeadersEditor {

    private static final char MASK = (char) 0x2022;

    private final JPanel panel = new JPanel(new BorderLayout(0, 4));
    private final JPanel rowsPanel = new JPanel();
    private final JLabel errorLabel = new JLabel(" ");
    private final List<Row> rows = new ArrayList<>();
    private boolean apiKeySet;
    private boolean updating;
    private ActionListener changeListener;

    public ExtraHeadersEditor() {
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
        JButton add = new JButton("Add header");
        add.addActionListener(e -> {
            addRow("", "");
            fireChange();
        });
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bottom.add(add);
        errorLabel.setForeground(new Color(0xB00020));
        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(bottom, BorderLayout.NORTH);
        south.add(errorLabel, BorderLayout.SOUTH);
        panel.add(rowsPanel, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
    }

    public JComponent component() {
        return panel;
    }

    public void addChangeListener(ActionListener listener) {
        changeListener = listener;
    }

    /**
     * Replaces the rows. Secret rows start with an empty value: their stored value is in the Keyring and stays unless a
     * new one is typed.
     */
    public void load(List<String> names, Map<String, String> plainValues) {
        updating = true;
        try {
            rows.clear();
            rowsPanel.removeAll();
            for (String name : names) {
                String value = ExtraHeaders.isSecret(name) ? "" : plainValues.getOrDefault(name, "");
                addRow(name, value);
            }
        }
        finally {
            updating = false;
        }
        refreshErrors();
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    /**
     * Tells the editor whether an API key is set; an {@code Authorization} header is then refused.
     */
    public void setApiKeySet(boolean value) {
        if (apiKeySet != value) {
            apiKeySet = value;
            refreshErrors();
        }
    }

    public int rowCount() {
        return rows.size();
    }

    /**
     * @return one message per problem, in row order; empty when every row is fine. Blank-named rows are ignored.
     */
    public List<String> errors() {
        List<String> messages = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Row row : rows) {
            String name = row.name();
            if (name.isEmpty()) {
                continue;
            }
            Optional<String> error = ExtraHeaders.validate(name, apiKeySet);
            if (error.isPresent()) {
                messages.add(error.get());
            }
            else if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                messages.add("Duplicate header " + name + ".");
            }
        }
        return messages;
    }

    /**
     * @return the names of the valid rows (no blank, refused or duplicate names), in row order
     */
    public List<String> validNames() {
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Row row : rows) {
            String name = row.name();
            if (!name.isEmpty() && ExtraHeaders.validate(name, apiKeySet).isEmpty()
                    && seen.add(name.toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * @return the values of the valid rows whose names are not secret; secret values are never included
     */
    public Map<String, String> plainValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : validNames()) {
            if (!ExtraHeaders.isSecret(name)) {
                values.put(name, rowFor(name).value());
            }
        }
        return values;
    }

    /**
     * @return a copy of every typed secret value by header name; empty rows (keep the stored value) are left out. The
     * caller stores them in the Keyring and zero-fills the arrays.
     */
    public Map<String, char[]> pendingSecrets() {
        Map<String, char[]> secrets = new LinkedHashMap<>();
        for (String name : validNames()) {
            if (ExtraHeaders.isSecret(name)) {
                char[] value = rowFor(name).valueChars();
                if (value.length > 0) {
                    secrets.put(name, value);
                }
            }
        }
        return secrets;
    }

    /**
     * Empties the value fields of the secret rows once their values were handed to the Keyring.
     */
    public void clearPendingSecrets() {
        updating = true;
        try {
            for (Row row : rows) {
                if (ExtraHeaders.isSecret(row.name())) {
                    row.value.setText("");
                }
            }
        }
        finally {
            updating = false;
        }
    }

    private Row rowFor(String name) {
        for (Row row : rows) {
            if (row.name().equals(name)) {
                return row;
            }
        }
        throw new IllegalStateException(name);
    }

    private void addRow(String name, String value) {
        Row row = new Row(name, value);
        rows.add(row);
        rowsPanel.add(row.component);
        row.updateMask();
        if (!updating) {
            refreshErrors();
            rowsPanel.revalidate();
            rowsPanel.repaint();
        }
    }

    private void removeRow(Row row) {
        rows.remove(row);
        rowsPanel.remove(row.component);
        refreshErrors();
        rowsPanel.revalidate();
        rowsPanel.repaint();
        fireChange();
    }

    private void refreshErrors() {
        List<String> messages = errors();
        errorLabel.setText(messages.isEmpty() ? " " : String.join("  ", messages));
    }

    private void fireChange() {
        if (!updating && changeListener != null) {
            changeListener.actionPerformed(null);
        }
    }

    /**
     * One header: name, value and a remove button. The value field masks its text while the name is secret.
     */
    private final class Row {

        private final JTextField name;
        private final JPasswordField value;
        private final JPanel component = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));

        private Row(String initialName, String initialValue) {
            name = new JTextField(initialName, 14);
            value = new JPasswordField(initialValue, 20);
            JButton remove = new JButton("Remove");
            remove.addActionListener(e -> removeRow(this));
            component.add(name);
            component.add(value);
            component.add(remove);
            DocumentListener onName = new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    nameEdited();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    nameEdited();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    nameEdited();
                }
            };
            name.getDocument().addDocumentListener(onName);
            value.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    fireChange();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    fireChange();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    fireChange();
                }
            });
        }

        private String name() {
            return name.getText().trim();
        }

        private String value() {
            return new String(value.getPassword());
        }

        private char[] valueChars() {
            return value.getPassword();
        }

        private void nameEdited() {
            updateMask();
            if (!updating) {
                refreshErrors();
                fireChange();
            }
        }

        private void updateMask() {
            boolean secret = !name().isEmpty() && ExtraHeaders.isSecret(name());
            value.setEchoChar(secret ? MASK : (char) 0);
            value.setToolTipText(secret
                                 ? "Secret value: stored in the system keyring, never in the session file. Leave empty to keep "
                    + "the stored value."
                                 : "Sent as typed; saved in the session file.");
        }
    }
}
