package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ServerTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ToolCallingModeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.ServerKind;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowLadder;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.WindowReading;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.FormLayoutUtil;

/**
 * Read-only snapshot of what detection last learned about the endpoint (spec §8.4).
 */
public final class OpenAiCompatibleDetectedPanel {

    private static final String UNKNOWN = "—";

    private final JPanel panel = new JPanel(new GridBagLayout());
    private final JLabel kindValue = new JLabel(UNKNOWN);
    private final JLabel reachabilityValue = new JLabel(UNKNOWN);
    private final JLabel apiBaseValue = new JLabel(UNKNOWN);
    private final JLabel streamingValue = new JLabel(UNKNOWN);
    private final JLabel capabilitiesValue = new JLabel(UNKNOWN);
    private final JLabel windowValue = new JLabel(UNKNOWN);
    private final JLabel overrideValue = new JLabel(UNKNOWN);
    private final JLabel toolCallingValue = new JLabel(UNKNOWN);
    private final JLabel modelCountValue = new JLabel(UNKNOWN);
    private final JLabel windowSourceValue = new JLabel(UNKNOWN);
    private final JLabel compactionValue = new JLabel(UNKNOWN);
    private final JLabel prunedValue = new JLabel(UNKNOWN);
    private String baseUrl = "";

    public OpenAiCompatibleDetectedPanel() {
        panel.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.FIRST_LINE_START;
        int row = 0;
        row = addRow(c, row, "Server kind:", kindValue);
        row = addRow(c, row, "Reachability:", reachabilityValue);
        row = addRow(c, row, "API base:", apiBaseValue);
        row = addRow(c, row, "Streaming:", streamingValue);
        row = addRow(c, row, "Capabilities:", capabilitiesValue);
        row = addRow(c, row, "Window:", windowValue);
        row = addRow(c, row, "Window source:", windowSourceValue);
        row = addRow(c, row, "Compaction:", compactionValue);
        row = addRow(c, row, "Pruned so far:", prunedValue);
        row = addRow(c, row, "Override:", overrideValue);
        row = addRow(c, row, "Tool-calling:", toolCallingValue);
        row = addRow(c, row, "Models:", modelCountValue);
    }

    private int addRow(GridBagConstraints c, int row, String caption, JLabel value) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        panel.add(FormLayoutUtil.label(caption), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(value, c);
        return row + 1;
    }

    public JComponent component() {
        return panel;
    }

    public void setWindowSource(String value) {
        windowSourceValue.setText(blank(value) ? UNKNOWN : value);
    }

    public void setCompaction(String value) {
        compactionValue.setText(blank(value) ? UNKNOWN : value);
    }

    public void setPrunedCount(int count) {
        prunedValue.setText(count < 0 ? UNKNOWN : count + " tool results");
    }

    /**
     * Applies {@code facts} and {@code settings} on the EDT. Safe to call from any thread.
     */
    public void update(DetectedFacts facts, OpenAiCompatibleSessionSettings settings) {
        if (SwingUtilities.isEventDispatchThread()) {
            apply(facts, settings);
            return;
        }
        SwingUtilities.invokeLater(() -> apply(facts, settings));
    }

    /**
     * Same as {@link #update} but waits for the EDT so tests can read the labels afterwards.
     */
    void updateAndWait(DetectedFacts facts, OpenAiCompatibleSessionSettings settings) {
        if (SwingUtilities.isEventDispatchThread()) {
            apply(facts, settings);
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> apply(facts, settings));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            SwingUtilities.invokeLater(() -> apply(facts, settings));
        }
        catch (InvocationTargetException ex) {
            throw new RuntimeException(ex.getCause());
        }
    }

    private void apply(DetectedFacts facts, OpenAiCompatibleSessionSettings settings) {
        kindValue.setText(kindText(facts, settings));
        applyReachability(facts, settings);
        apiBaseValue.setText(facts == null || blank(facts.apiBase()) ? UNKNOWN : facts.apiBase());
        streamingValue.setText(yesNoUnknown(facts == null ? null : facts.streaming()));
        capabilitiesValue.setText(capabilitiesText(facts));
        windowValue.setText(windowText(facts, settings));
        overrideValue.setText(overrideText(settings));
        toolCallingValue.setText(toolCallingText(settings));
        modelCountValue.setText(facts == null ? UNKNOWN : Integer.toString(facts.models().size()));
        panel.revalidate();
        panel.repaint();
    }

    /**
     * Shows whether the last poll reached the server, overriding what {@link #update} derived from the facts. Call on
     * the EDT, after {@link #update}.
     */
    public void setReachable(boolean reachable) {
        if (reachable) {
            reachabilityValue.setText("OK");
            reachabilityValue.setToolTipText(null);
        }
        else {
            showUnreachable();
        }
    }

    private void showUnreachable() {
        reachabilityValue.setText("N/A");
        reachabilityValue.setToolTipText("Cannot reach the server at " + (blank(baseUrl) ? UNKNOWN : baseUrl));
    }

    private void applyReachability(DetectedFacts facts, OpenAiCompatibleSessionSettings settings) {
        baseUrl = settings == null ? "" : settings.effectiveBaseUrl();
        if (facts == null) {
            showUnreachable();
            return;
        }
        reachabilityValue.setText("OK");
        reachabilityValue.setToolTipText(null);
    }

    static String kindText(DetectedFacts facts, OpenAiCompatibleSessionSettings settings) {
        ServerTypeEnum chosen = settings == null ? null : settings.effectiveServerType();
        if (chosen != null && chosen != ServerTypeEnum.AUTO) {
            return displayName(toKind(chosen)) + " (chosen)";
        }
        ServerKind detected = facts == null ? null : facts.kind();
        if (detected == null) {
            return UNKNOWN;
        }
        return detected.displayName() + " (detected)";
    }

    static String windowText(DetectedFacts facts, OpenAiCompatibleSessionSettings settings) {
        Integer override = settings == null ? null : settings.effectiveContextWindowOverride();
        if (override != null && override <= 0) {
            override = null;
        }
        if (facts == null && override == null) {
            return UNKNOWN;
        }
        WindowReading reading = WindowLadder.resolve(override, facts, null,
                                                     OpenAiCompatiblePluginSettings.getAssumedWindow(),
                                                     OpenAiCompatiblePluginSettings.getTrainingLengthFactor());
        return reading.tokens() + " (" + sourceLabel(reading.source()) + ")";
    }

    static String capabilitiesText(DetectedFacts facts) {
        if (facts == null || facts.capabilities() == null) {
            return "unknown";
        }
        Boolean tools = facts.capabilities().tools();
        Boolean thinking = facts.capabilities().thinking();
        List<String> levels = facts.capabilities().reasoningLevels();
        boolean haveLevels = levels != null && !levels.isEmpty();
        if (tools == null && thinking == null && !haveLevels) {
            return "unknown";
        }
        StringBuilder text = new StringBuilder();
        if (Boolean.TRUE.equals(tools)) {
            text.append("tools");
        }
        if (Boolean.TRUE.equals(thinking)) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append("thinking");
        }
        if (haveLevels) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append("reasoning: ").append(String.join("/", levels));
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    static String overrideText(OpenAiCompatibleSessionSettings settings) {
        int override = settings == null ? 0 : settings.effectiveContextWindowOverride();
        return override <= 0 ? "auto" : Integer.toString(override);
    }

    static String toolCallingText(OpenAiCompatibleSessionSettings settings) {
        ToolCallingModeEnum mode = settings == null ? ToolCallingModeEnum.AUTO : settings.effectiveToolCallingMode();
        return switch (mode) {
            case AUTO ->
                "Auto";
            case NATIVE ->
                "Native";
            case SCHEMA ->
                "Schema";
        };
    }

    static String sourceLabel(WindowReading.Source source) {
        return switch (source) {
            case OVERRIDE ->
                "override";
            case OLLAMA_PS ->
                "Ollama /api/ps";
            case OLLAMA_NUM_CTX ->
                "Ollama num_ctx";
            case LMSTUDIO_LOADED ->
                "LM Studio loaded";
            case LLAMACPP_N_CTX ->
                "llama.cpp n_ctx";
            case VLLM_MAX_MODEL_LEN ->
                "vLLM max_model_len";
            case V1_MODELS ->
                "/v1/models";
            case SERVER_ERROR ->
                "server error";
            case UPPER_BOUND ->
                "upper bound";
            case ASSUMED ->
                "assumed";
        };
    }

    private static ServerKind toKind(ServerTypeEnum type) {
        return switch (type) {
            case OLLAMA ->
                ServerKind.OLLAMA;
            case LM_STUDIO ->
                ServerKind.LMSTUDIO;
            case LLAMA_CPP ->
                ServerKind.LLAMACPP;
            case VLLM ->
                ServerKind.VLLM;
            case OTHER, AUTO ->
                ServerKind.GENERIC;
        };
    }

    private static String displayName(ServerKind kind) {
        return kind == null ? UNKNOWN : kind.displayName();
    }

    private static String yesNoUnknown(Boolean value) {
        if (value == null) {
            return "unknown";
        }
        return value ? "yes" : "no";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    String kindValue() {
        return kindValue.getText();
    }

    String reachabilityValue() {
        return reachabilityValue.getText();
    }

    String reachabilityTooltip() {
        return reachabilityValue.getToolTipText();
    }

    String apiBaseValue() {
        return apiBaseValue.getText();
    }

    String streamingValue() {
        return streamingValue.getText();
    }

    String capabilitiesValue() {
        return capabilitiesValue.getText();
    }

    String windowValue() {
        return windowValue.getText();
    }

    String overrideValue() {
        return overrideValue.getText();
    }

    String toolCallingValue() {
        return toolCallingValue.getText();
    }

    String modelCountValue() {
        return modelCountValue.getText();
    }

    String windowSourceValue() {
        return windowSourceValue.getText();
    }

    String compactionValue() {
        return compactionValue.getText();
    }

    String prunedValue() {
        return prunedValue.getText();
    }
}
