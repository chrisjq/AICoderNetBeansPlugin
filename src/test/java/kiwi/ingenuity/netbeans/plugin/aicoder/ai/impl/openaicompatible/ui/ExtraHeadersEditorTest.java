package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExtraHeadersEditorTest {

    @BeforeEach
    void notHeadless() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    }

    private static <T extends Component> List<T> find(Component root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Component> void collect(Component component, Class<T> type, List<T> found) {
        if (type.isInstance(component)) {
            found.add(type.cast(component));
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, type, found);
            }
        }
    }

    private static void addRow(ExtraHeadersEditor editor, String name, String value) {
        find(editor.component(), JButton.class).stream().filter(b -> "Add header".equals(b.getText()))
                .findFirst().orElseThrow().doClick();
        List<JPasswordField> values = find(editor.component(), JPasswordField.class);
        List<JTextField> names = new ArrayList<>();
        for (JTextField field : find(editor.component(), JTextField.class)) {
            if (!(field instanceof JPasswordField)) {
                names.add(field);
            }
        }
        names.get(names.size() - 1).setText(name);
        values.get(values.size() - 1).setText(value);
    }

    @Test
    @Timeout(10)
    void aPlainHeaderGoesToNamesAndPlainValues() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        addRow(editor, "X-Team", "blue");

        assertEquals(List.of("X-Team"), editor.validNames());
        assertEquals(Map.of("X-Team", "blue"), editor.plainValues());
        assertTrue(editor.pendingSecrets().isEmpty());
        assertTrue(editor.errors().isEmpty());
    }

    @Test
    @Timeout(10)
    void aSecretHeaderIsMaskedKeptOutOfThePlainValuesAndReturnedAsPending() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        addRow(editor, "X-Api-Key", "s3cret");

        assertEquals(List.of("X-Api-Key"), editor.validNames());
        assertTrue(editor.plainValues().isEmpty());
        assertArrayEquals("s3cret".toCharArray(), editor.pendingSecrets().get("X-Api-Key"));
        JPasswordField value = find(editor.component(), JPasswordField.class).get(0);
        assertTrue(value.getEchoChar() != 0);
        assertTrue(value.getToolTipText().contains("keyring"));
    }

    @Test
    @Timeout(10)
    void aPlainNameShowsItsValueAndChangingToASecretNameMasksIt() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        addRow(editor, "X-Team", "blue");
        JPasswordField value = find(editor.component(), JPasswordField.class).get(0);
        assertEquals(0, value.getEchoChar());

        List<JTextField> names = new ArrayList<>();
        for (JTextField field : find(editor.component(), JTextField.class)) {
            if (!(field instanceof JPasswordField)) {
                names.add(field);
            }
        }
        names.get(0).setText("X-Team-Token");

        assertTrue(value.getEchoChar() != 0);
        assertTrue(editor.plainValues().isEmpty());
        assertEquals(1, editor.pendingSecrets().size());
    }

    @Test
    @Timeout(10)
    void aSecretRowWithAnEmptyValueKeepsTheStoredValue() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        editor.load(List.of("X-Api-Key"), Map.of());

        assertEquals(List.of("X-Api-Key"), editor.validNames());
        assertTrue(editor.pendingSecrets().isEmpty());
    }

    @Test
    @Timeout(10)
    void restrictedNamesAreRefusedWithTheValidationMessage() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        addRow(editor, "HOST", "evil");
        addRow(editor, "Content-Type", "text/plain");

        assertEquals(List.of("HOST is set by the client and cannot be overridden.",
                             "Content-Type is set by the client and cannot be overridden."), editor.errors());
        assertTrue(editor.validNames().isEmpty());
    }

    @Test
    @Timeout(10)
    void authorizationIsRefusedOnlyWhileAnApiKeyIsSet() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        addRow(editor, "Authorization", "Bearer x");
        assertTrue(editor.errors().isEmpty());
        assertEquals(List.of("Authorization"), editor.validNames());

        editor.setApiKeySet(true);
        assertEquals(List.of("Use the API key field instead."), editor.errors());
        assertTrue(editor.validNames().isEmpty());

        editor.setApiKeySet(false);
        assertTrue(editor.errors().isEmpty());
    }

    @Test
    @Timeout(10)
    void duplicateAndBlankNamesAreHandled() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        addRow(editor, "X-Team", "a");
        addRow(editor, "x-team", "b");
        addRow(editor, "", "ignored");

        assertEquals(List.of("Duplicate header x-team."), editor.errors());
        assertEquals(List.of("X-Team"), editor.validNames());
        assertEquals(Map.of("X-Team", "a"), editor.plainValues());
    }

    @Test
    @Timeout(10)
    void loadShowsStoredPlainValuesAndClearPendingEmptiesSecretFieldsOnly() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        editor.load(List.of("X-Team", "X-Token"), Map.of("X-Team", "blue"));
        assertEquals(2, editor.rowCount());
        assertEquals(Map.of("X-Team", "blue"), editor.plainValues());

        List<JPasswordField> values = find(editor.component(), JPasswordField.class);
        values.get(1).setText("typed");
        assertEquals(1, editor.pendingSecrets().size());

        editor.clearPendingSecrets();
        assertTrue(editor.pendingSecrets().isEmpty());
        assertEquals(Map.of("X-Team", "blue"), editor.plainValues());
    }

    @Test
    @Timeout(10)
    void removingARowDropsIt() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        addRow(editor, "X-One", "1");
        addRow(editor, "X-Two", "2");
        find(editor.component(), JButton.class).stream().filter(b -> "Remove".equals(b.getText()))
                .findFirst().orElseThrow().doClick();

        assertEquals(List.of("X-Two"), editor.validNames());
        assertEquals(1, editor.rowCount());
    }

    @Test
    @Timeout(10)
    void everyEditNotifiesTheChangeListener() {
        ExtraHeadersEditor editor = new ExtraHeadersEditor();
        int[] changes = new int[1];
        editor.addChangeListener(e -> changes[0]++);

        addRow(editor, "X-Team", "blue");

        assertTrue(changes[0] >= 3, "changes: " + changes[0]);
    }
}
