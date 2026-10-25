package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class OpenAiCompatibleAdvancedPanelTest {

    private static final int FIRST_BYTE = 0;
    private static final int IDLE = 1;
    private static final int TOTAL = 2;
    private static final int MAX_TOOL_ROUNDS = 3;
    private static final int REPETITION = 6;

    @BeforeEach
    void notHeadless() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
    }

    private static void collect(Component component, List<JSpinner> spinners, List<JButton> buttons) {
        if (component instanceof JSpinner spinner) {
            spinners.add(spinner);
        }
        else if (component instanceof JButton button) {
            buttons.add(button);
        }
        else if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, spinners, buttons);
            }
        }
    }

    private static List<JSpinner> spinners(Component root) {
        List<JSpinner> spinners = new ArrayList<>();
        collect(root, spinners, new ArrayList<>());
        return spinners;
    }

    private static JButton button(Component root, String textStart) {
        List<JButton> buttons = new ArrayList<>();
        collect(root, new ArrayList<>(), buttons);
        return buttons.stream().filter(b -> b.getText().startsWith(textStart)).findFirst().orElseThrow();
    }

    @Test
    @Timeout(10)
    void anUntouchedPanelStoresNoOverrides() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        panel.load(settings);
        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertNull(out.firstByteTimeoutSeconds());
        assertNull(out.idleTimeoutSeconds());
        assertNull(out.totalCapMinutes());
        assertNull(out.maxToolRounds());
        assertNull(out.maxLengthContinuations());
        assertNull(out.maxMalformedRounds());
        assertNull(out.repetitionWindow());
        assertNull(out.unproductiveRounds());
        assertNull(out.nativeFailuresBeforeSchema());
    }

    @Test
    @Timeout(10)
    void loadedOverridesRoundTripAndShowTheirValues() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        OpenAiCompatibleSessionSettings in = new OpenAiCompatibleSessionSettings();
        in.setFirstByteTimeoutSeconds(30);
        in.setIdleTimeoutSeconds(0);
        in.setTotalCapMinutes(15);
        in.setMaxToolRounds(25);
        in.setMaxLengthContinuations(3);
        in.setMaxMalformedRounds(4);
        in.setRepetitionWindow(6);
        in.setUnproductiveRounds(5);
        in.setNativeFailuresBeforeSchema(7);
        panel.load(in);

        List<JSpinner> spinners = spinners(panel.component());
        assertEquals(20, spinners.size(), "the ten overrides plus the ten context-budget spinners");
        assertEquals(30, spinners.get(FIRST_BYTE).getValue());
        assertEquals(0, spinners.get(IDLE).getValue());
        assertEquals(25, spinners.get(MAX_TOOL_ROUNDS).getValue());

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);
        assertEquals(30, out.firstByteTimeoutSeconds());
        assertEquals(0, out.idleTimeoutSeconds());
        assertEquals(15, out.totalCapMinutes());
        assertEquals(25, out.maxToolRounds());
        assertEquals(3, out.maxLengthContinuations());
        assertEquals(4, out.maxMalformedRounds());
        assertEquals(6, out.repetitionWindow());
        assertEquals(5, out.unproductiveRounds());
        assertEquals(7, out.nativeFailuresBeforeSchema());
    }

    @Test
    @Timeout(10)
    void changingASpinnerStoresAnOverrideEvenWhenItEqualsTheDefault() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        panel.load(new OpenAiCompatibleSessionSettings());
        JSpinner repetition = spinners(panel.component()).get(REPETITION);
        int defaultValue = ((Number) repetition.getValue()).intValue();
        repetition.setValue(defaultValue + 1);
        repetition.setValue(defaultValue);

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertEquals(defaultValue, out.repetitionWindow());
        assertNull(out.maxToolRounds());
    }

    @Test
    @Timeout(10)
    void spinnerMinimumsAreTheValidationFloors() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        panel.load(new OpenAiCompatibleSessionSettings());
        List<JSpinner> spinners = spinners(panel.component());

        assertEquals(1, ((javax.swing.SpinnerNumberModel) spinners.get(FIRST_BYTE).getModel()).getMinimum());
        assertEquals(0, ((javax.swing.SpinnerNumberModel) spinners.get(IDLE).getModel()).getMinimum());
        assertEquals(0, ((javax.swing.SpinnerNumberModel) spinners.get(MAX_TOOL_ROUNDS).getModel()).getMinimum());
        assertEquals(OpenAiCompatibleSessionSettings.MIN_REPETITION_WINDOW,
                     ((javax.swing.SpinnerNumberModel) spinners.get(REPETITION).getModel()).getMinimum());
    }

    @Test
    @Timeout(10)
    void aTotalCapThatDoesNotExceedFirstByteIsRaised() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        panel.load(new OpenAiCompatibleSessionSettings());
        List<JSpinner> spinners = spinners(panel.component());
        // 600 s is the first-byte default, so setting it would not be a change; use a value above it
        spinners.get(FIRST_BYTE).setValue(700);
        spinners.get(TOTAL).setValue(5);

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertEquals(700, out.firstByteTimeoutSeconds());
        assertEquals(12, out.totalCapMinutes());
        assertEquals(12, spinners.get(TOTAL).getValue());
    }

    @Test
    @Timeout(10)
    void resetToDefaultsClearsEveryOverride() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        OpenAiCompatibleSessionSettings in = new OpenAiCompatibleSessionSettings();
        in.setMaxToolRounds(25);
        in.setIdleTimeoutSeconds(10);
        panel.load(in);

        button(panel.component(), "Reset").doClick();
        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);

        assertNull(out.maxToolRounds());
        assertNull(out.idleTimeoutSeconds());
    }

    @Test
    @Timeout(10)
    void everyAdvancedSpinnerHasTheSameWidth() {
        OpenAiCompatibleAdvancedPanel panel = new OpenAiCompatibleAdvancedPanel();
        panel.component().setSize(900, 800);
        panel.component().doLayout();

        List<JSpinner> spinners = spinners(panel.component());
        int width = spinners.get(0).getPreferredSize().width;
        assertTrue(width >= 100, "numeric fields must allow six-digit values");
        assertTrue(spinners.stream().allMatch(spinner -> spinner.getPreferredSize().width == width));
    }

    @Test
    @Timeout(10)
    void basicFieldColumnIsStableBeforeAndAfterAdvancedToggleAtEveryWidth() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        try {
            int minimumWidth = panel.component().getMinimumSize().width;
            for (int width : new int[]{minimumWidth, minimumWidth + 400}) {
                int[] initial = basicFirstRowPositions(panel, width);
                javax.swing.JComponent root = panel.component();
                java.awt.GridBagLayout layout = (java.awt.GridBagLayout) root.getLayout();
                java.awt.GridBagConstraints labelConstraints = layout.getConstraints(root.getComponent(0));
                java.awt.GridBagConstraints fieldConstraints = layout.getConstraints(root.getComponent(1));
                int horizontalGap = labelConstraints.insets.right + fieldConstraints.insets.left;
                assertEquals(initial[0] + initial[1] + horizontalGap, initial[2],
                             "the field must start immediately after the label cell at " + width);
                button(panel.component(), "Advanced").doClick();
                int[] expanded = basicFirstRowPositions(panel, width);
                button(panel.component(), "Advanced").doClick();
                int[] collapsed = basicFirstRowPositions(panel, width);

                assertArrayEquals(initial, expanded, "expanding Advanced must not move the basic form at " + width);
                assertArrayEquals(initial, collapsed, "collapsing Advanced must restore the same form at " + width);
            }
        }
        finally {
            panel.dispose();
        }
    }

    private static int[] basicFirstRowPositions(OpenAiCompatibleBasicPanel panel, int width) {
        javax.swing.JComponent component = panel.component();
        component.setSize(new Dimension(width, component.getMinimumSize().height));
        component.doLayout();
        int widestLabel = java.util.Arrays.stream(component.getComponents())
                .filter(JLabel.class::isInstance)
                .mapToInt(componentLabel -> componentLabel.getMinimumSize().width)
                .max()
                .orElseThrow();
        return new int[]{component.getComponent(0).getX(), widestLabel, component.getComponent(1).getX()};
    }

    @Test
    @Timeout(10)
    void theBasicPanelEmbedsAdvancedCollapsedAndPassesLoadAndApplyThrough() {
        OpenAiCompatibleBasicPanel panel = new OpenAiCompatibleBasicPanel();
        OpenAiCompatibleSessionSettings in = new OpenAiCompatibleSessionSettings();
        in.setServerType(ServerTypeEnum.OTHER);
        in.setBaseUrl("https://host");
        in.setModel("m");
        in.setApiKeySet(false);
        in.setMaxToolRounds(25);
        in.setFirstByteTimeoutSeconds(45);
        panel.load(in);

        // the Basic panel's own context-window spinner comes first, then the nine Advanced spinners
        JSpinner firstSpinner = spinners(panel.component()).get(1 + FIRST_BYTE);
        assertNotNull(firstSpinner.getParent());
        assertFalse(firstSpinner.getParent().isVisible());

        JButton toggle = button(panel.component(), "Advanced");
        toggle.doClick();
        assertTrue(firstSpinner.getParent().isVisible());
        toggle.doClick();
        assertFalse(firstSpinner.getParent().isVisible());

        OpenAiCompatibleSessionSettings out = new OpenAiCompatibleSessionSettings();
        panel.applyTo(out);
        assertEquals(25, out.maxToolRounds());
        assertEquals(45, out.firstByteTimeoutSeconds());
        assertNull(out.repetitionWindow());
    }
}
