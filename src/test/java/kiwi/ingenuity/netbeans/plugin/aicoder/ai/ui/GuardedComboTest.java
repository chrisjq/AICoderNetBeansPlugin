package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class GuardedComboTest {

    @Test
    void programmaticMutationSuppressesActionAndUserMutationStillFires() throws Exception {
        JComboBox<String> combo = new JComboBox<>(new String[]{"one", "two"});
        GuardedCombo<String> guarded = new GuardedCombo<>(combo);
        AtomicInteger actions = new AtomicInteger();
        guarded.addActionListener(event -> actions.incrementAndGet());

        SwingUtilities.invokeAndWait(() -> guarded.runProgrammatic(() -> combo.setSelectedItem("two")));
        assertEquals(0, actions.get(), "programmatic combo changes must not reach user listeners");

        SwingUtilities.invokeAndWait(() -> combo.setSelectedItem("one"));
        assertEquals(1, actions.get(), "user combo changes must reach user listeners");
    }

    @Test
    void guardRestoresWhenMutationThrows() throws Exception {
        JComboBox<String> combo = new JComboBox<>(new String[]{"one", "two"});
        GuardedCombo<String> guarded = new GuardedCombo<>(combo);
        AtomicInteger actions = new AtomicInteger();
        guarded.addActionListener(event -> actions.incrementAndGet());

        SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class,
                () -> guarded.runProgrammatic(() -> {
                    combo.setSelectedItem("two");
                    throw new IllegalStateException("quoted mutation failure after selection");
                })));

        SwingUtilities.invokeAndWait(() -> combo.setSelectedItem("two"));
        assertEquals(1, actions.get(), "guard must be restored after a throwing mutation");

        SwingUtilities.invokeAndWait(() -> guarded.runProgrammatic(() -> {
            guarded.runProgrammatic(() -> combo.setSelectedItem("one"));
            combo.setSelectedItem("two");
        }));
        assertEquals(1, actions.get(), "nested guard must not release the outer guard early");
    }
}
