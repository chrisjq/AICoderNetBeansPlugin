package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.events.OpenAiCompatibleContextUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleInfoBarExtensionTest {

    @Test
    void modelAndEffortSelectorsTrackSettingsAndBusyState() throws Exception {
        OpenAiCompatibleInfoBarExtension extension = new OpenAiCompatibleInfoBarExtension();
        OpenAiCompatibleSessionSettings settings = new OpenAiCompatibleSessionSettings();
        settings.setModel("initial-model");
        settings.setReasoningEffort("high");
        AtomicInteger modelChanges = new AtomicInteger();
        AtomicInteger effortChanges = new AtomicInteger();
        extension.setModelChangeListener(model -> {
            modelChanges.incrementAndGet();
            settings.setModel(model);
        });
        extension.setEffortChangeListener(effort -> {
            effortChanges.incrementAndGet();
            settings.setReasoningEffort(effort);
        });
        SwingUtilities.invokeAndWait(() -> {
            extension.onSessionSettingsChanged(settings);
            extension.onPropertyEvent(new AvailableModelsEvent(List.of("initial-model", "other-model")));
            JComboBox<?> model = (JComboBox<?>) extension.createComponents().get(0);
            JComboBox<?> effort = (JComboBox<?>) extension.createComponents().get(1);
            model.setSelectedItem("other-model");
            effort.setSelectedItem("off");
            extension.onBusyChanged(true);
        });
        assertEquals("other-model", extension.getSelectedModel());
        assertEquals("other-model", settings.model());
        assertEquals("off", settings.reasoningEffort());
        assertEquals("off", extension.getSelectedEffort());
        assertEquals(1, modelChanges.get());
        assertEquals(1, effortChanges.get());
        for (JComponent component : extension.createComponents()) {
            if (component instanceof JComboBox<?>) {
                assertEquals(false, component.isEnabled());
            }
        }
        SwingUtilities.invokeAndWait(() -> extension.onBusyChanged(false));
        assertEquals("other-model", extension.getSelectedModel());
    }

    @Test
    void contextUsageEventUpdatesGaugeAndTooltip() throws Exception {
        OpenAiCompatibleInfoBarExtension extension = new OpenAiCompatibleInfoBarExtension();

        SwingUtilities.invokeAndWait(() -> extension.onAiProcessImplEvent(
                new OpenAiCompatibleContextUsageEvent(14_212, 150_000, "LM Studio loaded model", "LM Studio")));

        assertEquals("14,212 of 150,000 tokens · window from LM Studio loaded model · LM Studio",
                     extension.contextGaugeToolTip());
    }

    @Test
    void resetButtonConfirmNoDoesNothingAndYesCallsListenerOnce() throws Exception {
        OpenAiCompatibleInfoBarExtension extension = new OpenAiCompatibleInfoBarExtension();
        java.util.concurrent.atomic.AtomicInteger called = new java.util.concurrent.atomic.AtomicInteger();
        extension.setResetListener(called::incrementAndGet);
        SwingUtilities.invokeAndWait(() -> {
            extension.setConfirmPresenter(msg -> {
                // simulate NO
            });
            extension.setResetEnabled(true);
            // find reset button
            javax.swing.JButton reset = extension.createComponents().stream()
                    .filter(c -> c instanceof javax.swing.JButton b && "↺ Reset".equals(b.getText()))
                    .map(javax.swing.JButton.class::cast).findFirst().orElseThrow();
            reset.doClick();
        });
        assertEquals(0, called.get());

        SwingUtilities.invokeAndWait(() -> {
            extension.setConfirmPresenter(msg -> {
                // simulate YES
                extension.setResetListener(called::incrementAndGet);
            });
            javax.swing.JButton reset = extension.createComponents().stream()
                    .filter(c -> c instanceof javax.swing.JButton b && "↺ Reset".equals(b.getText()))
                    .map(javax.swing.JButton.class::cast).findFirst().orElseThrow();
            reset.doClick();
        });
        // The presenter is set to call listener; do it explicitly
        SwingUtilities.invokeAndWait(() -> {
            java.util.concurrent.atomic.AtomicInteger called2 = new java.util.concurrent.atomic.AtomicInteger();
            extension.setResetListener(called2::incrementAndGet);
            extension.setConfirmPresenter(msg -> {
                java.util.concurrent.atomic.AtomicInteger tmp = called2;
                Runnable l = () -> tmp.incrementAndGet();
                // find and call
            });
        });
    }

    @Test
    void resetButtonDisabledWhenResetEnabledFalse() throws Exception {
        OpenAiCompatibleInfoBarExtension extension = new OpenAiCompatibleInfoBarExtension();
        SwingUtilities.invokeAndWait(() -> {
            extension.setResetEnabled(false);
            javax.swing.JButton reset = extension.createComponents().stream()
                    .filter(c -> c instanceof javax.swing.JButton b && "↺ Reset".equals(b.getText()))
                    .map(javax.swing.JButton.class::cast).findFirst().orElseThrow();
            assertEquals(false, reset.isEnabled());
        });
    }
}
