package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.OpenAiCompatibleLimits;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ContextBudgetSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.Pruner;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.FormLayoutUtil;

/**
 * The Advanced section of the OpenAI Compatible settings (spec §8.1/§8.2): the per-session timeouts and loop limits.
 * Each spinner shows the default beside it; a value is stored as a per-session override only when the user changed it
 * (or it was already an override), otherwise the setting stays unset and follows the default. The spinner minimums are
 * the fixed validation floors, and the total cap is raised when it would not exceed the first-byte and idle timeouts.
 */
public final class OpenAiCompatibleAdvancedPanel {

    private static final int MAX_SECONDS = 86_400;
    private static final int NUMERIC_FIELD_WIDTH = 120;
    private static final int MAX_MINUTES = 1_440;
    private static final int MAX_COUNT = 1_000;
    private static final int MAX_TOKENS = 1_000_000;
    private static final String[] REASONING_EFFORT_OPTIONS = {
        BlankSafeComboRenderer.DEFAULT_OPTION, "off", "low", "medium", "high", "xhigh", "on"
    };

    private final JPanel panel = new JPanel(new GridBagLayout());
    private final IntField firstByte;
    private final IntField idle;
    private final IntField total;
    private final IntField maxToolRounds;
    private final IntField continuations;
    private final IntField malformed;
    private final IntField repetition;
    private final IntField unproductive;
    private final IntField nativeFailures;
    private final IntField summariseThreshold;
    private final IntField reservePercent;
    private final IntField reserveMinimum;
    private final IntField reserveMaxSharePercent;
    private final IntField reserveAbsolute;
    private final IntField keepRecentPercent;
    private final IntField keepRecentMinimum;
    private final IntField keepRecentAbsolute;
    private final IntField toolResultSharePercent;
    private final IntField pruneThresholdChars;
    private final IntField pruneCharsKept;
    private final JLabel warningLabel = new JLabel(" ");
    private final java.util.List<IntField> fields = new java.util.ArrayList<>();
    private final JTextField temperature = new JTextField(8);
    private final JTextField topP = new JTextField(8);
    private final JTextField maxOutputTokens = new JTextField(8);
    private final JComboBox<String> reasoningEffortCombo = new JComboBox<>(REASONING_EFFORT_OPTIONS);
    private final ExtraHeadersEditor headers = new ExtraHeadersEditor();
    private boolean updating;
    private ActionListener changeListener;

    public OpenAiCompatibleAdvancedPanel() {
        OpenAiCompatibleLimits defaults = OpenAiCompatibleLimits.defaults();
        firstByte = new IntField("First-byte timeout:", 1, MAX_SECONDS,
                                 (int) defaults.firstByteTimeout().toSeconds(), "s");
        idle = new IntField("Idle timeout:", 0, MAX_SECONDS, (int) defaults.idleTimeout().toSeconds(), "s; 0 = off");
        total = new IntField("Total request cap:", 1, MAX_MINUTES, (int) defaults.totalCap().toMinutes(), "min");
        maxToolRounds = new IntField("Max tool rounds:", 0, MAX_COUNT,
                                     OpenAiCompatibleSessionSettings.DEFAULT_MAX_TOOL_ROUNDS, "0 = unlimited");
        continuations = new IntField("Max length continuations:", 0, MAX_COUNT,
                                     OpenAiCompatibleSessionSettings.DEFAULT_MAX_LENGTH_CONTINUATIONS, "");
        malformed = new IntField("Max malformed rounds:", OpenAiCompatibleSessionSettings.MIN_MALFORMED_ROUNDS,
                                 MAX_COUNT, OpenAiCompatibleSessionSettings.DEFAULT_MAX_MALFORMED_ROUNDS, "");
        repetition = new IntField("Repetition window:", OpenAiCompatibleSessionSettings.MIN_REPETITION_WINDOW,
                                  MAX_COUNT, OpenAiCompatibleSessionSettings.DEFAULT_REPETITION_WINDOW, "calls");
        unproductive = new IntField("Unproductive rounds:", OpenAiCompatibleSessionSettings.MIN_UNPRODUCTIVE_ROUNDS,
                                    MAX_COUNT, OpenAiCompatibleSessionSettings.DEFAULT_UNPRODUCTIVE_ROUNDS, "");
        nativeFailures = new IntField("Native failures before Schema:",
                                      OpenAiCompatibleSessionSettings.MIN_NATIVE_FAILURES, MAX_COUNT,
                                      OpenAiCompatibleSessionSettings.DEFAULT_NATIVE_FAILURES_BEFORE_SCHEMA, "");
        summariseThreshold = new IntField("Summarise threshold:", OpenAiCompatiblePluginSettings.MIN_SUMMARISE_THRESHOLD,
                                          MAX_TOKENS, OpenAiCompatiblePluginSettings.getSummariseThreshold(), "tokens");
        ContextBudgetSettings budget = ContextBudgetSettings.defaults();
        reservePercent = new IntField("Reserve (% of window):", 0, 100,
                                      (int) Math.round(budget.reservePercent() * 100), "%");
        reserveMinimum = new IntField("Reserve minimum:", 0, MAX_TOKENS, budget.reserveMinimum(), "tokens");
        reserveMaxSharePercent = new IntField("Reserve max share (% of window):", 1, 100,
                                              (int) Math.round(budget.reserveMaxShare() * 100), "%");
        reserveAbsolute = new IntField("Reserve absolute:", 0, MAX_TOKENS, 0, "tokens; 0 = use %");
        keepRecentPercent = new IntField("Keep recent (% of window):", 0, 100,
                                         (int) Math.round(budget.keepRecentPercent() * 100), "%");
        keepRecentMinimum = new IntField("Keep recent minimum:", 0, MAX_TOKENS, budget.keepRecentMinimum(), "tokens");
        keepRecentAbsolute = new IntField("Keep recent absolute:", 0, MAX_TOKENS, 0, "tokens; 0 = use %");
        toolResultSharePercent = new IntField("Tool-result share (% of window − reserve):", 1, 100,
                                              (int) Math.round(budget.toolResultShare() * 100), "%");
        pruneThresholdChars = new IntField("Prune threshold:", 0, MAX_TOKENS * 4,
                                           Pruner.DEFAULT_THRESHOLD_CHARS, "chars");
        pruneCharsKept = new IntField("Prune: chars kept:", 0, MAX_TOKENS * 4, Pruner.DEFAULT_CHARS_KEPT, "chars");
        buildLayout();
    }

    private void buildLayout() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        int row = 0;
        for (IntField field : fields) {
            c.gridy = row++;
            c.gridx = 0;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            panel.add(FormLayoutUtil.label(field.label), c);
            c.gridx = 1;
            panel.add(field.spinner, c);
            c.gridx = 2;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            panel.add(field.defaultLabel, c);
        }
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.gridy = row++;
        c.gridx = 0;
        panel.add(FormLayoutUtil.label("Request"), c);
        c.gridy = row++;
        panel.add(FormLayoutUtil.label("Temperature (0-2):"), c);
        c.gridx = 1;
        panel.add(temperature, c);
        c.gridy = row++;
        c.gridx = 0;
        panel.add(FormLayoutUtil.label("Top P ((0,1]):"), c);
        c.gridx = 1;
        panel.add(topP, c);
        c.gridy = row++;
        c.gridx = 0;
        panel.add(FormLayoutUtil.label("Max output tokens:"), c);
        c.gridx = 1;
        panel.add(maxOutputTokens, c);
        c.gridy = row++;
        c.gridx = 0;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(FormLayoutUtil.label("Extra headers:"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(headers.component(), c);
        c.gridwidth = 1;
        JButton reset = new JButton("Reset to defaults");
        reset.addActionListener(e -> resetToDefaults());
        c.gridy = row;
        c.gridx = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(reset, c);
        c.gridy = row + 1;
        c.gridx = 0;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(warningLabel, c);
        c.gridwidth = 1;
        refreshWarnings();
    }

    /**
     * Shows the spec §8.1 conflicts between the budget values in the warning line. Saving is not blocked: the effective
     * getters and {@link kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.context.ContextBudget} apply
     * the same rules as clamps.
     */
    private void refreshWarnings() {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        if (pruneCharsKept.value() >= pruneThresholdChars.value()) {
            warnings.add("Chars kept must be below the prune threshold; it is capped at one less.");
        }
        if (reservePercent.value() > reserveMaxSharePercent.value()) {
            warnings.add("The reserve % is above the max share; the max share is used.");
        }
        if (reservePercent.value() + keepRecentPercent.value() > 100) {
            warnings.add("Reserve and keep-recent together exceed the window; keep-recent will be shortened.");
        }
        warningLabel.setText(warnings.isEmpty() ? " " : String.join(" · ", warnings));
    }

    public JComponent component() {
        return panel;
    }

    public JComponent reasoningEffortComponent() {
        reasoningEffortCombo.setRenderer(new BlankSafeComboRenderer());
        reasoningEffortCombo.setToolTipText("Reasoning effort; default omits the field");
        return reasoningEffortCombo;
    }

    public void load(OpenAiCompatibleSessionSettings settings) {
        updating = true;
        try {
            firstByte.load(settings.firstByteTimeoutSeconds());
            idle.load(settings.idleTimeoutSeconds());
            total.load(settings.totalCapMinutes());
            maxToolRounds.load(settings.maxToolRounds());
            continuations.load(settings.maxLengthContinuations());
            malformed.load(settings.maxMalformedRounds());
            repetition.load(settings.repetitionWindow());
            unproductive.load(settings.unproductiveRounds());
            nativeFailures.load(settings.nativeFailuresBeforeSchema());
            summariseThreshold.load(settings.summariseThreshold());
            reservePercent.load(settings.reservePercent());
            reserveMinimum.load(settings.reserveMinimum());
            reserveMaxSharePercent.load(settings.reserveMaxSharePercent());
            reserveAbsolute.load(settings.reserveAbsolute());
            keepRecentPercent.load(settings.keepRecentPercent());
            keepRecentMinimum.load(settings.keepRecentMinimum());
            keepRecentAbsolute.load(settings.keepRecentAbsolute());
            toolResultSharePercent.load(settings.toolResultSharePercent());
            pruneThresholdChars.load(settings.pruneThresholdChars());
            pruneCharsKept.load(settings.pruneCharsKept());
            temperature.setText(settings.temperature() == null ? "" : settings.temperature().toString());
            topP.setText(settings.topP() == null ? "" : settings.topP().toString());
            maxOutputTokens.setText(settings.maxOutputTokens() == null ? "" : settings.maxOutputTokens().toString());
            String effort = settings.reasoningEffort();
            reasoningEffortCombo.setSelectedItem(effort == null ? BlankSafeComboRenderer.DEFAULT_OPTION : effort);
            headers.load(settings.extraHeaderNames(), settings.extraHeaderPlainValues());
        }
        finally {
            updating = false;
        }
    }

    public void applyTo(OpenAiCompatibleSessionSettings settings) {
        Integer firstByteOverride = firstByte.override();
        Integer idleOverride = idle.override();
        Integer totalOverride = total.override();
        if (totalOverride != null) {
            int longest = Math.max(firstByteOverride != null ? firstByteOverride : firstByte.defaultValue,
                                   idleOverride != null ? idleOverride : idle.defaultValue);
            int minimumMinutes = (longest + 1 + 59) / 60;
            if (totalOverride < minimumMinutes) {
                totalOverride = Math.min(minimumMinutes, MAX_MINUTES);
                updating = true;
                try {
                    total.spinner.setValue(totalOverride);
                }
                finally {
                    updating = false;
                }
            }
        }
        settings.setFirstByteTimeoutSeconds(firstByteOverride);
        settings.setIdleTimeoutSeconds(idleOverride);
        settings.setTotalCapMinutes(totalOverride);
        settings.setMaxToolRounds(maxToolRounds.override());
        settings.setMaxLengthContinuations(continuations.override());
        settings.setMaxMalformedRounds(malformed.override());
        settings.setRepetitionWindow(repetition.override());
        settings.setUnproductiveRounds(unproductive.override());
        settings.setNativeFailuresBeforeSchema(nativeFailures.override());
        settings.setSummariseThreshold(summariseThreshold.override());
        settings.setReservePercent(reservePercent.override());
        settings.setReserveMinimum(reserveMinimum.override());
        settings.setReserveMaxSharePercent(reserveMaxSharePercent.override());
        settings.setReserveAbsolute(reserveAbsolute.override());
        settings.setKeepRecentPercent(keepRecentPercent.override());
        settings.setKeepRecentMinimum(keepRecentMinimum.override());
        settings.setKeepRecentAbsolute(keepRecentAbsolute.override());
        settings.setToolResultSharePercent(toolResultSharePercent.override());
        settings.setPruneThresholdChars(pruneThresholdChars.override());
        settings.setPruneCharsKept(pruneCharsKept.override());
        Double storedTemperature = parseTemperature(temperature.getText());
        Double storedTopP = parseTopP(topP.getText());
        settings.setTemperature(storedTemperature);
        settings.setTopP(storedTopP);
        showClamped(temperature, storedTemperature);
        showClamped(topP, storedTopP);
        settings.setMaxOutputTokens(parsePositive(maxOutputTokens.getText()));
        Object effort = reasoningEffortCombo.getSelectedItem();
        settings.setReasoningEffort(effort == null || BlankSafeComboRenderer.DEFAULT_OPTION.equals(effort)
                                    ? null : effort.toString());
        List<String> headerNames = headers.validNames();
        Map<String, String> plain = headers.plainValues();
        settings.setExtraHeaderNames(headerNames.isEmpty() ? null : headerNames);
        settings.setExtraHeaderPlainValues(plain.isEmpty() ? null : plain);
    }

    /**
     * Tells the headers table whether an API key is set; an {@code Authorization} header is then refused.
     */
    public void setApiKeySet(boolean apiKeySet) {
        headers.setApiKeySet(apiKeySet);
    }

    /**
     * @return one message per problem in the extra headers (refused, restricted or duplicate names)
     */
    public List<String> headerErrors() {
        return headers.errors();
    }

    /**
     * @return a copy of every typed secret header value by name; the caller stores them in the Keyring (never in the
     * session file) and zero-fills the arrays
     */
    public Map<String, char[]> pendingSecretHeaders() {
        return headers.pendingSecrets();
    }

    /**
     * Empties the secret value fields after their values were handed to the Keyring.
     */
    public void clearPendingSecretHeaders() {
        headers.clearPendingSecrets();
    }

    /**
     * Temperature is clamped into [0, 2]; empty or not a number means "not sent".
     */
    private static Double parseTemperature(String text) {
        Double value = parseDouble(text);
        return value == null ? null : Math.min(2.0, Math.max(0.0, value));
    }

    /**
     * Top P is clamped down to 1; zero, negative, empty or not a number means "not sent" (the valid range is (0, 1]).
     */
    private static Double parseTopP(String text) {
        Double value = parseDouble(text);
        return value == null || value <= 0 ? null : Math.min(1.0, value);
    }

    /**
     * Shows a clamped value in its field so the user sees what is stored; the change is not a user edit.
     */
    private void showClamped(JTextField field, Double stored) {
        Double typed = parseDouble(field.getText());
        if (stored != null && !stored.equals(typed)) {
            boolean wasUpdating = updating;
            updating = true;
            try {
                field.setText(stored.toString());
            }
            finally {
                updating = wasUpdating;
            }
        }
    }

    private static Integer parsePositive(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 1 ? value : null;
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Double parseDouble(String text) {
        try {
            return text == null || text.isBlank() ? null : Double.valueOf(text.trim());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    public void addChangeListener(ActionListener listener) {
        changeListener = listener;
        headers.addChangeListener(listener);
        reasoningEffortCombo.addActionListener(e -> fireChange());
    }

    private void resetToDefaults() {
        updating = true;
        try {
            for (IntField field : fields) {
                field.load(null);
            }
        }
        finally {
            updating = false;
        }
        fireChange();
    }

    private void fireChange() {
        if (!updating && changeListener != null) {
            changeListener.actionPerformed(null);
        }
    }

    /**
     * One integer setting: a spinner, its "(default: X)" label and whether it holds a per-session override.
     */
    private final class IntField {

        private final String label;
        private final int defaultValue;
        private final JSpinner spinner;
        private final JLabel defaultLabel;
        private boolean overridden;

        private IntField(String label, int minimum, int maximum, int defaultValue, String unit) {
            this.label = label;
            this.defaultValue = defaultValue;
            this.spinner = new JSpinner(new SpinnerNumberModel(defaultValue, minimum, maximum, 1));
            this.spinner.setPreferredSize(new Dimension(NUMERIC_FIELD_WIDTH, spinner.getPreferredSize().height));
            String suffix = unit.isEmpty() ? "" : " " + unit;
            this.defaultLabel = new JLabel("(default: " + defaultValue + suffix + ")");
            spinner.addChangeListener(e -> {
                if (!updating) {
                    overridden = true;
                    fireChange();
                }
            });
            spinner.addChangeListener(e -> refreshWarnings());
            fields.add(this);
        }

        private void load(Integer value) {
            overridden = value != null;
            spinner.setValue(value != null ? value : defaultValue);
        }

        private int value() {
            return ((Number) spinner.getValue()).intValue();
        }

        private Integer override() {
            int value = ((Number) spinner.getValue()).intValue();
            return overridden || value != defaultValue ? Integer.valueOf(value) : null;
        }
    }
}
