package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AvailableModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextGaugePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeConfigOptionsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp.AcpJsonKeyEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.BlankSafeComboRenderer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.GuardedCombo;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Info bar for OpenCode sessions. Builds combo boxes dynamically from the {@code configOptions} array
 * returned by the ACP {@code session/new} handshake. Each user selection calls
 * {@code session/set_config_option} and repopulates all combos from the response (options are interdependent
 * — changing the model can change the available effort options).
 *
 * <p>
 * Layout: {@code [Model ▾] [Mode ▾] [Effort ▾]  [=== context gauge ===]  [⇒ Compact]}
 */
public class OpenCodeAiInfoBarExtension implements AiInfoBarExtension {

    private static final Logger LOG = Logger.getLogger(OpenCodeAiInfoBarExtension.class.getName());

    /**
     * Parses a {@code configOptions} JSON array, retaining only {@code type=select} entries. Intended for use
     * from the info bar and from tests.
     */
    public static List<OptionSpec> parseConfigOptions(JsonArray configOptions) {
        List<OptionSpec> result = new ArrayList<>();
        if (configOptions == null) {
            return result;
        }
        for (JsonElement el : configOptions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            String id = opt.has(AcpJsonKeyEnum.ID.key()) ? opt.get(AcpJsonKeyEnum.ID.key()).getAsString() : null;
            String type = opt.has(AcpJsonKeyEnum.TYPE.key()) ? opt.get(AcpJsonKeyEnum.TYPE.key()).getAsString() : null;
            if (id == null || !"select".equals(type)) {
                continue;
            }
            String currentValue = opt.has(AcpJsonKeyEnum.CURRENT_VALUE.key()) ? opt.get(AcpJsonKeyEnum.CURRENT_VALUE.key()).getAsString() : null;
            List<OptionValue> values = new ArrayList<>();
            if (opt.has(AcpJsonKeyEnum.OPTIONS.key()) && opt.get(AcpJsonKeyEnum.OPTIONS.key()).isJsonArray()) {
                for (JsonElement v : opt.getAsJsonArray(AcpJsonKeyEnum.OPTIONS.key())) {
                    if (v.isJsonObject()) {
                        JsonObject vo = v.getAsJsonObject();
                        if (vo.has(AcpJsonKeyEnum.VALUE.key())) {
                            String value = vo.get(AcpJsonKeyEnum.VALUE.key()).getAsString();
                            // Falls back to value not just when NAME is absent/null, but also when it is present and
                            // blank (e.g. {"value": "high", "name": ""}) — an ACP option whose name is a real but
                            // empty string is otherwise a blank combo row with a perfectly real backing value, which
                            // is a different case from "no value at all" (that one is what BlankSafeComboRenderer's
                            // placeholder is for, applied in buildCombo below).
                            String rawName = vo.has(AcpJsonKeyEnum.NAME.key()) && !vo.get(AcpJsonKeyEnum.NAME.key()).isJsonNull()
                                             ? vo.get(AcpJsonKeyEnum.NAME.key()).getAsString() : null;
                            String name = (rawName != null && !rawName.isBlank()) ? rawName : value;
                            values.add(new OptionValue(value, name));
                        }

                    }
                }
            }
            result.add(new OptionSpec(id, currentValue, values));
        }
        return result;
    }

    /**
     * Builds a fallback configOptions-shaped array for pre-seeding the combos before the ACP session/new
     * handshake completes. Produces Model and Mode entries only — Effort is model-dependent and genuinely
     * unknowable without a live session.
     */
    public static JsonArray buildFallbackConfigOptions(OpenCodeSessionSettings s) {
        return buildFallbackConfigOptions(s, OpenCodePluginSettings.getKnownModels());
    }

    static JsonArray buildFallbackConfigOptions(OpenCodeSessionSettings s, String[] models) {
        String currentModel = (s != null && s.model() != null && !s.model().isBlank())
                              ? s.model() : OpenCodePluginSettings.getModel();
        String currentMode = (s != null && s.mode() != null && !s.mode().isBlank())
                             ? s.mode() : OpenCodePluginSettings.getMode();
        JsonArray arr = new JsonArray();
        arr.add(buildSelectOption("model", currentModel, models));
        arr.add(buildSelectOption("mode", currentMode, new String[]{"build", "plan"}));
        return arr;
    }

    private static JsonObject buildSelectOption(String id, String currentValue, String[] values) {
        JsonObject opt = new JsonObject();
        opt.addProperty(AcpJsonKeyEnum.ID.key(), id);
        opt.addProperty(AcpJsonKeyEnum.TYPE.key(), "select");
        opt.addProperty(AcpJsonKeyEnum.CURRENT_VALUE.key(), currentValue);
        JsonArray options = new JsonArray();
        for (String v : values) {
            JsonObject o = new JsonObject();
            o.addProperty(AcpJsonKeyEnum.VALUE.key(), v);
            o.addProperty(AcpJsonKeyEnum.NAME.key(), v);
            options.add(o);
        }
        opt.add(AcpJsonKeyEnum.OPTIONS.key(), options);
        return opt;
    }

    private static String extractCurrentValue(JsonArray opts, String id) {
        if (opts == null) {
            return null;
        }
        for (JsonElement el : opts) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (id.equals(o.has(AcpJsonKeyEnum.ID.key()) ? o.get(AcpJsonKeyEnum.ID.key()).getAsString() : null)) {
                return o.has(AcpJsonKeyEnum.CURRENT_VALUE.key()) ? o.get(AcpJsonKeyEnum.CURRENT_VALUE.key()).getAsString() : null;
            }
        }
        return null;
    }

    /**
     * Refreshes a configOptions-shaped array's option lists from {@code refreshed} while keeping each entry's
     * {@code currentValue} exactly as it was in {@code displayed} — the state already on screen.
     *
     * <p>
     * A model-discovery broadcast is keyed by AiType, not by session (see class javadoc), so every idle
     * session's info bar receives it regardless of which session actually did the discovering. Re-deriving
     * currentValue from settings/global here — instead of keeping what is already displayed — is what
     * silently resets an unrelated idle session's model combo to the global default the moment any other
     * session finishes discovery.
     */
    static JsonArray preserveSelections(JsonArray displayed, JsonArray refreshed) {
        if (refreshed == null) {
            return displayed;
        }
        if (displayed == null) {
            return refreshed;
        }
        for (JsonElement el : refreshed) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            String id = opt.has(AcpJsonKeyEnum.ID.key()) ? opt.get(AcpJsonKeyEnum.ID.key()).getAsString() : null;
            String preserved = id != null ? extractCurrentValue(displayed, id) : null;
            if (preserved != null) {
                opt.addProperty(AcpJsonKeyEnum.CURRENT_VALUE.key(), preserved);
            }
        }
        return refreshed;
    }

    /**
     * Returns a copy of {@code configOptions} with the {@code id} entry's {@code currentValue} replaced by
     * {@code value}. Copies rather than mutates in place — the array may be shared with the process manager's
     * own cached configOptions.
     */
    private static JsonArray withCurrentValue(JsonArray configOptions, String id, String value) {
        JsonArray copy = configOptions.deepCopy();
        for (JsonElement el : copy) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if (id.equals(opt.has(AcpJsonKeyEnum.ID.key()) ? opt.get(AcpJsonKeyEnum.ID.key()).getAsString() : null)) {
                opt.addProperty(AcpJsonKeyEnum.CURRENT_VALUE.key(), value);
                break;
            }
        }
        return copy;
    }

    private final OpenCodeSessionSettings settings;
    private BiConsumer<String, String> configChangeListener;
    private final Runnable compactListener;
    final JPanel comboPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final ContextGaugePanel gauge = new ContextGaugePanel();
    private final JButton compactButton;
    /**
     * The configOptions array most recently handed to applyConfigOptions().
     */
    private volatile JsonArray lastKnownConfigOptions;
    /**
     * True unless {@link #onBusyChanged} last reported busy. Reapplied by {@link #applyConfigOptions} when it
     * rebuilds the combos, so a repopulation while a turn runs cannot silently re-enable what the busy
     * contract locked.
     */
    private volatile boolean actionControlsEnabled = true;

    public OpenCodeAiInfoBarExtension(JsonArray initialConfigOptions, OpenCodeSessionSettings settings,
                                      BiConsumer<String, String> configChangeListener, Runnable compactListener) {
        this.settings = settings;
        this.configChangeListener = configChangeListener;
        this.compactListener = compactListener;
        this.lastKnownConfigOptions = initialConfigOptions;
        comboPanel.setOpaque(false);
        compactButton = new JButton("⇒ Compact");
        compactButton.setFont(compactButton.getFont().deriveFont(11f));
        compactButton.setToolTipText("Compact conversation to reduce context window usage");
        compactButton.addActionListener(e -> this.compactListener.run());
    }

    @Override
    public List<JComponent> createComponents() {
        JsonArray toApply = lastKnownConfigOptions != null
                            ? lastKnownConfigOptions : buildFallbackConfigOptions(settings);
        recordAndApply(toApply);
        return List.of(comboPanel, gauge.component(), compactButton);
    }

    @Override
    public void onBusyChanged(boolean busy) {
        actionControlsEnabled = !busy;
        setActionControlsEnabled(actionControlsEnabled);
    }

    private void setActionControlsEnabled(boolean enabled) {
        for (Component c : comboPanel.getComponents()) {
            if (c instanceof JComboBox<?> combo) {
                combo.setEnabled(enabled);
            }
        }
        compactButton.setEnabled(enabled);
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof AvailableModelsEvent modelsEvent) {
            JsonArray displayed = lastKnownConfigOptions;
            JsonArray refreshed = buildFallbackConfigOptions(settings,
                    modelsEvent.models().toArray(new String[0]));
            recordAndApply(preserveSelections(displayed, refreshed));
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof OpenCodeConfigOptionsEvent co) {
            lastKnownConfigOptions = co.configOptions();

            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode info bar [{0}] onAiProcessImplEvent(OpenCodeConfigOptionsEvent): "
                                    + "incoming model={1}",
                        new Object[]{"OpenCode", extractCurrentValue(co.configOptions(), "model")});
            }

            recordAndApply(co.configOptions());
        }
        else if (event instanceof OpenCodeUsageEvent usage) {
            onUsageUpdate(usage.used(), usage.size());
        }
    }

    /**
     * Tracks the most recently applied configOptions, then applies it.
     */
    void recordAndApply(JsonArray configOptions) {
        lastKnownConfigOptions = configOptions;
        applyConfigOptions(configOptions);
    }

    private void onUsageUpdate(int used, int size) {
        gauge.update(used, size);
    }

    void applyConfigOptions(JsonArray configOptions) {
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode info bar [{0}] applyConfigOptions: applied model={1}",
                    new Object[]{"OpenCode", extractCurrentValue(configOptions, "model")});
        }
        comboPanel.removeAll();
        for (OptionSpec spec : parseConfigOptions(configOptions)) {
            comboPanel.add(buildCombo(spec));
        }
        comboPanel.revalidate();
        comboPanel.repaint();
        setActionControlsEnabled(actionControlsEnabled);
    }

    private JComboBox<String> buildCombo(OptionSpec spec) {
        JComboBox<String> combo = new JComboBox<>();
        if ("effort".equals(spec.id())) {
            // Model/mode always have a real, chosen display name — only effort can carry the ACP "no
            // effort set" sentinel (a blank value; see parseConfigOptions) that needs the placeholder.
            combo.setRenderer(new BlankSafeComboRenderer());
        }
        GuardedCombo<String> guarded = new GuardedCombo<>(combo);
        guarded.runProgrammatic(() -> {
            for (String name : spec.displayNames()) {
                combo.addItem(name);
            }
            if (spec.currentValue() != null) {
                String requestedDisplay = spec.displayForValue(spec.currentValue());
                if (!spec.displayNames().contains(requestedDisplay)) {
                    // The stored value has no match in this option list — e.g. a
                    // fallback list seeded before discovery ever added it, or a
                    // discovery broadcast whose value strings use a different form
                    // than what was persisted. Insert it so the user's actual
                    // choice stays visible instead of setSelectedItem silently
                    // refusing the value and leaving whatever item is first shown
                    // as though it were selected.
                    if (PluginSettings.isDebugJson()) {
                        LOG.log(Level.INFO,
                                "OpenCode info bar [{0}] combo \"{1}\": stored value \"{2}\" not found "
                                + "in discovered options {3} — inserting it",
                                new Object[]{"OpenCode", spec.id(), spec.currentValue(),
                                             spec.options().stream().map(OptionValue::value).toList()});
                    }
                    combo.insertItemAt(requestedDisplay, 0);
                }
                combo.setSelectedItem(requestedDisplay);
                // Kept permanently, not just for this investigation: a combo silently
                // refusing a selection (e.g. Nimbus/GTK look-and-feel quirks, or a
                // display-name collision) is exactly the kind of failure that would
                // stay invisible without this check.
                Object actual = combo.getSelectedItem();
                if (!requestedDisplay.equals(actual)) {
                    LOG.log(Level.WARNING,
                            "OpenCode info bar [{0}] combo \"{1}\": setSelectedItem(\"{2}\") did not take — "
                            + "getSelectedItem() returned \"{3}\"",
                            new Object[]{"OpenCode", spec.id(), requestedDisplay, actual});
                }
            }
        });
        guarded.addActionListener(e -> {
            Object sel = combo.getSelectedItem();
            if (sel == null) {
                return;
            }
            configChangeListener.accept(spec.id(), spec.valueForDisplay(sel.toString()));
        });
        return combo;
    }

    /**
     * A parsed representation of one {@code configOptions} entry. Retains both the underlying {@code value}
     * sent to {@code session/set_config_option} and the human-friendly {@code name} shown in the combo.
     */
    public static final class OptionSpec {

        private final String id;
        private final String currentValue;
        private final List<OptionValue> options;

        public OptionSpec(String id, String currentValue, List<OptionValue> options) {
            this.id = id;
            this.currentValue = currentValue;
            this.options = options;
        }

        public String id() {
            return id;
        }

        public String currentValue() {
            return currentValue;
        }

        public List<OptionValue> options() {
            return options;
        }

        /**
         * Display names in option order, for populating the combo.
         */
        public List<String> displayNames() {
            List<String> names = new ArrayList<>();
            for (OptionValue o : options) {
                names.add(o.name());
            }
            return names;
        }

        /**
         * The underlying value to send for a chosen display name. Falls back to the display name itself when
         * it matches no known option (e.g. an editable/custom entry).
         */
        public String valueForDisplay(String displayName) {
            for (OptionValue o : options) {
                if (o.name().equals(displayName)) {
                    return o.value();
                }
            }
            return displayName;
        }

        /**
         * The display name for an underlying value. Falls back to the value itself when it matches no known
         * option.
         */
        public String displayForValue(String value) {
            for (OptionValue o : options) {
                if (o.value().equals(value)) {
                    return o.name();
                }
            }
            return value;
        }
    }

    /**
     * One selectable option: the {@code value} sent to the agent and the {@code name} displayed to the user.
     */
    public static final class OptionValue {

        private final String value;
        private final String name;

        public OptionValue(String value, String name) {
            this.value = value;
            this.name = name;
        }

        public String value() {
            return value;
        }

        public String name() {
            return name;
        }
    }
}
