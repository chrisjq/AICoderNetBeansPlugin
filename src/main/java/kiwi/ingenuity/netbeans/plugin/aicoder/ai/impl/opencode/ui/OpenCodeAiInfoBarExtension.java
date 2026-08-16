package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiSessionHost;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypePropertyBus;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextGaugePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiImplementation;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeConfigOptionsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeUsageEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.events.OpenCodeModelsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodeSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiInfoBarExtension;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.events.AiProcessImplEvent;

/**
 * Info bar for OpenCode sessions. Builds combo boxes dynamically from the
 * {@code configOptions} array returned by the ACP {@code session/new}
 * handshake. Each user selection calls {@code session/set_config_option} and
 * repopulates all combos from the response (options are interdependent —
 * changing the model can change the available effort options).
 *
 * <p>
 * Layout: {@code [Model ▾] [Mode ▾] [Effort ▾]  [=== context gauge ===]}
 */
public class OpenCodeAiInfoBarExtension implements AiInfoBarExtension {

    /**
     * Parses a {@code configOptions} JSON array, retaining only
     * {@code type=select} entries. Intended for use from the info bar and from
     * tests.
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
            String id = opt.has("id") ? opt.get("id").getAsString() : null;
            String type = opt.has("type") ? opt.get("type").getAsString() : null;
            if (id == null || !"select".equals(type)) {
                continue;
            }
            String currentValue = opt.has("currentValue") ? opt.get("currentValue").getAsString() : null;
            List<OptionValue> values = new ArrayList<>();
            if (opt.has("options") && opt.get("options").isJsonArray()) {
                for (JsonElement v : opt.getAsJsonArray("options")) {
                    if (v.isJsonObject()) {
                        JsonObject vo = v.getAsJsonObject();
                        if (vo.has("value")) {
                            String value = vo.get("value").getAsString();
                            String name = vo.has("name") && !vo.get("name").isJsonNull()
                                    ? vo.get("name").getAsString() : value;
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
     * Builds a fallback configOptions-shaped array for pre-seeding the combos
     * before the ACP session/new handshake completes. Produces Model and Mode
     * entries only — Effort is model-dependent and genuinely unknowable without
     * a live session.
     */
    static JsonArray buildFallbackConfigOptions(OpenCodeSessionSettings s) {
        String currentModel = (s != null && s.model() != null && !s.model().isBlank())
                ? s.model() : OpenCodePluginSettings.getModel();
        String currentMode = (s != null && s.mode() != null && !s.mode().isBlank())
                ? s.mode() : OpenCodePluginSettings.getMode();
        JsonArray arr = new JsonArray();
        arr.add(buildSelectOption("model", currentModel, OpenCodePluginSettings.getKnownModels()));
        arr.add(buildSelectOption("mode", currentMode, new String[]{"build", "plan"}));
        return arr;
    }

    private static JsonObject buildSelectOption(String id, String currentValue, String[] values) {
        JsonObject opt = new JsonObject();
        opt.addProperty("id", id);
        opt.addProperty("type", "select");
        opt.addProperty("currentValue", currentValue);
        JsonArray options = new JsonArray();
        for (String v : values) {
            JsonObject o = new JsonObject();
            o.addProperty("value", v);
            o.addProperty("name", v);
            options.add(o);
        }
        opt.add("options", options);
        return opt;
    }

    private static void cacheDiscoveredModels(JsonArray configOptions) {
        if (configOptions == null) {
            return;
        }
        for (JsonElement el : configOptions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if (!"model".equals(opt.has("id") ? opt.get("id").getAsString() : null)) {
                continue;
            }
            if (!opt.has("options") || !opt.get("options").isJsonArray()) {
                return;
            }
            List<String> models = new ArrayList<>();
            for (JsonElement v : opt.getAsJsonArray("options")) {
                if (v.isJsonObject() && v.getAsJsonObject().has("value")) {
                    models.add(v.getAsJsonObject().get("value").getAsString());
                }
            }
            if (!models.isEmpty()) {
                String[] arr = models.toArray(new String[0]);
                OpenCodePluginSettings.setDiscoveredModels(arr);
                OpenCodeAiImplementation.modelCatalog().publish(models);
                AiTypePropertyBus.getInstance().fire(AiTypeEnum.OPENCODE, new OpenCodeModelsEvent(models));
            }
            return;
        }
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
            if (id.equals(o.has("id") ? o.get("id").getAsString() : null)) {
                return o.has("currentValue") ? o.get("currentValue").getAsString() : null;
            }
        }
        return null;
    }

    private final OpenCodeAiProcessManager manager;
    private final OpenCodeSessionSettings settings;
    private final AiSessionHost host;
    final JPanel comboPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final ContextGaugePanel gauge = new ContextGaugePanel();

    public OpenCodeAiInfoBarExtension(OpenCodeAiProcessManager manager, OpenCodeSessionSettings settings, AiSessionHost host) {
        this.manager = manager;
        this.settings = settings;
        this.host = host;
        comboPanel.setOpaque(false);
    }

    @Override
    public List<JComponent> createComponents() {
        JsonArray initial = manager.configOptions();
        applyConfigOptions(initial != null ? initial : buildFallbackConfigOptions(settings));
        return List.of(comboPanel, gauge.component());
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof OpenCodeModelsEvent && !manager.isSessionLive()) {
            // Model list updated while no live ACP session — rebuild the fallback combos
            // so the model combo reflects the newly-discovered list.
            SwingUtilities.invokeLater(() -> applyConfigOptions(buildFallbackConfigOptions(settings)));
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof OpenCodeConfigOptionsEvent co) {
            cacheDiscoveredModels(co.configOptions());
            applyConfigOptions(co.configOptions());
        }
        else if (event instanceof OpenCodeUsageEvent usage) {
            onUsageUpdate(usage.used(), usage.size());
        }
    }

    private void onUsageUpdate(int used, int size) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> onUsageUpdate(used, size));
            return;
        }
        gauge.update(used, size);
    }

    void applyConfigOptions(JsonArray configOptions) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> applyConfigOptions(configOptions));
            return;
        }
        comboPanel.removeAll();
        for (OptionSpec spec : parseConfigOptions(configOptions)) {
            comboPanel.add(buildCombo(spec));
        }
        comboPanel.revalidate();
        comboPanel.repaint();
    }

    private JComboBox<String> buildCombo(OptionSpec spec) {
        JComboBox<String> combo = new JComboBox<>();
        boolean[] programmatic = {true};
        for (String name : spec.displayNames()) {
            combo.addItem(name);
        }
        if (spec.currentValue() != null) {
            combo.setSelectedItem(spec.displayForValue(spec.currentValue()));
        }
        programmatic[0] = false;
        combo.addActionListener(e -> {
            if (programmatic[0]) {
                return;
            }
            Object sel = combo.getSelectedItem();
            if (sel == null) {
                return;
            }
            programmatic[0] = true;
            handleConfigChange(spec, spec.valueForDisplay(sel.toString()),
                    () -> programmatic[0] = false);
        });
        return combo;
    }

    /**
     * Handles a user-initiated combo selection change. When a live ACP session
     * exists the change is sent via {@code session/set_config_option} and the
     * combos are repopulated from the authoritative response. When no session
     * is active the change is written directly to the session settings and
     * persisted via the host so the choice is honoured by the next
     * {@code session/new}.
     */
    void handleConfigChange(OptionSpec spec, String value, Runnable onComplete) {
        if (value != null && value.equals(spec.currentValue())) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        if (manager.isSessionLive()) {
            manager.setConfigOption(spec.id(), value)
                    .thenAccept(opts -> {
                        // Write the agent's applied value (may differ from requested on rejection).
                        String applied = extractCurrentValue(opts, spec.id());
                        if (applied != null) {
                            applyToSettings(spec.id(), applied);
                        }
                        SwingUtilities.invokeLater(() -> applyConfigOptions(opts));
                    })
                    .whenComplete((v, ex) -> {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    });
        }
        else {
            applyToSettings(spec.id(), value);
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    void applyToSettings(String configId, String value) {
        if (settings == null) {
            return;
        }
        switch (configId) {
            case "model" ->
                settings.setModel(value);
            case "mode" ->
                settings.setMode(value);
            case "effort" ->
                settings.setEffort(value);
            default -> {
                return;
            }
        }
        AiSessionHost h = host;
        if (h != null) {
            h.updateSessionSettings(settings);
        }
    }

    /**
     * A parsed representation of one {@code configOptions} entry. Retains both
     * the underlying {@code value} sent to {@code session/set_config_option}
     * and the human-friendly {@code name} shown in the combo.
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
         * The underlying value to send for a chosen display name. Falls back to
         * the display name itself when it matches no known option (e.g. an
         * editable/custom entry).
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
         * The display name for an underlying value. Falls back to the value
         * itself when it matches no known option.
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
     * One selectable option: the {@code value} sent to the agent and the
     * {@code name} displayed to the user.
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
