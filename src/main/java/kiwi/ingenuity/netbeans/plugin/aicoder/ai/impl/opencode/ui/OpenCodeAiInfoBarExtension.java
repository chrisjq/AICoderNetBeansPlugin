package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
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

    private static final Logger LOG = Logger.getLogger(OpenCodeAiInfoBarExtension.class.getName());

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

    /**
     * Refreshes a configOptions-shaped array's option lists from
     * {@code refreshed} while keeping each entry's {@code currentValue} exactly
     * as it was in {@code displayed} — the state already on screen.
     *
     * <p>
     * A model-discovery broadcast is keyed by AiType, not by session (see class
     * javadoc), so every idle session's info bar receives it regardless of
     * which session actually did the discovering. Re-deriving currentValue from
     * settings/global here — instead of keeping what is already displayed — is
     * what silently resets an unrelated idle session's model combo to the
     * global default the moment any other session finishes discovery.
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
            String id = opt.has("id") ? opt.get("id").getAsString() : null;
            String preserved = id != null ? extractCurrentValue(displayed, id) : null;
            if (preserved != null) {
                opt.addProperty("currentValue", preserved);
            }
        }
        return refreshed;
    }

    /**
     * Returns a copy of {@code configOptions} with the {@code id} entry's
     * {@code currentValue} replaced by {@code value}. Copies rather than
     * mutates in place — the array may be shared with the process manager's own
     * cached configOptions.
     */
    private static JsonArray withCurrentValue(JsonArray configOptions, String id, String value) {
        JsonArray copy = configOptions.deepCopy();
        for (JsonElement el : copy) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject opt = el.getAsJsonObject();
            if (id.equals(opt.has("id") ? opt.get("id").getAsString() : null)) {
                opt.addProperty("currentValue", value);
                break;
            }
        }
        return copy;
    }

    private final OpenCodeAiProcessManager manager;
    private final OpenCodeSessionSettings settings;
    private final AiSessionHost host;
    final JPanel comboPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final ContextGaugePanel gauge = new ContextGaugePanel();
    /**
     * The configOptions array most recently handed to applyConfigOptions().
     */
    private volatile JsonArray lastKnownConfigOptions;

    public OpenCodeAiInfoBarExtension(OpenCodeAiProcessManager manager, OpenCodeSessionSettings settings, AiSessionHost host) {
        this.manager = manager;
        this.settings = settings;
        this.host = host;
        comboPanel.setOpaque(false);
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode info bar [{0}] <init>: settings#={1} model={2}",
                    new Object[]{manager.getSessionId(),
                        settings != null ? System.identityHashCode(settings) : null,
                        settings != null ? settings.model() : null});
        }
    }

    @Override
    public List<JComponent> createComponents() {
        JsonArray initial = manager.configOptions();
        JsonArray toApply = initial != null ? initial : buildFallbackConfigOptions(settings);
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode info bar [{0}] createComponents: source={1} settings#={2} model={3}",
                    new Object[]{manager.getSessionId(), initial != null ? "manager.configOptions()" : "fallback",
                        settings != null ? System.identityHashCode(settings) : null,
                        extractCurrentValue(toApply, "model")});
        }
        recordAndApply(toApply);
        return List.of(comboPanel, gauge.component());
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
        if (event instanceof OpenCodeModelsEvent && !manager.isSessionLive()) {
            // Model list updated while no live ACP session — refresh the option
            // list only and keep whatever selection is already displayed; see
            // preserveSelections() for why re-deriving currentValue here is wrong.
            JsonArray displayed = lastKnownConfigOptions;
            JsonArray refreshed = buildFallbackConfigOptions(settings);
            JsonArray merged = preserveSelections(displayed, refreshed);
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode info bar [{0}] onPropertyEvent(OpenCodeModelsEvent): "
                        + "lastKnown model={1} refreshed model={2} merged model={3}",
                        new Object[]{manager.getSessionId(), extractCurrentValue(displayed, "model"),
                            extractCurrentValue(refreshed, "model"), extractCurrentValue(merged, "model")});
            }
            SwingUtilities.invokeLater(() -> recordAndApply(merged));
        }
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof OpenCodeConfigOptionsEvent co) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode info bar [{0}] onAiProcessImplEvent(OpenCodeConfigOptionsEvent): "
                        + "incoming model={1}",
                        new Object[]{manager.getSessionId(), extractCurrentValue(co.configOptions(), "model")});
            }
            cacheDiscoveredModels(co.configOptions());
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
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO, "OpenCode info bar [{0}] applyConfigOptions: applied model={1}",
                    new Object[]{manager.getSessionId(), extractCurrentValue(configOptions, "model")});
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
                            new Object[]{manager.getSessionId(), spec.id(), spec.currentValue(),
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
                        new Object[]{manager.getSessionId(), spec.id(), requestedDisplay, actual});
            }
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
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO,
                    "OpenCode info bar [{0}] handleConfigChange: branch={1} settings#={2} id={3} value={4}",
                    new Object[]{manager.getSessionId(), manager.isSessionLive() ? "live" : "idle",
                        settings != null ? System.identityHashCode(settings) : null, spec.id(), value});
        }
        if (manager.isSessionLive()) {
            manager.setConfigOption(spec.id(), value)
                    .thenAccept(opts -> {
                        // Write the agent's applied value (may differ from requested on rejection).
                        String applied = extractCurrentValue(opts, spec.id());
                        if (applied != null) {
                            applyToSettings(spec.id(), applied);
                        }
                        SwingUtilities.invokeLater(() -> recordAndApply(opts));
                    })
                    .whenComplete((v, ex) -> {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    });
        }
        else {
            applyToSettings(spec.id(), value);
            // Without this, lastKnownConfigOptions keeps the pre-change
            // currentValue. The next OpenCodeModelsEvent broadcast (from any
            // other session's discovery — see preserveSelections()) would then
            // "preserve" that stale value right back over the pick just made,
            // silently reverting it while settings itself stays correct.
            JsonArray current = lastKnownConfigOptions;
            if (current != null) {
                if (PluginSettings.isDebugJson()) {
                    LOG.log(Level.INFO,
                            "OpenCode info bar [{0}] handleConfigChange (idle): patching lastKnownConfigOptions "
                            + "\"{1}\" -> \"{2}\"",
                            new Object[]{manager.getSessionId(), spec.id(), value});
                }
                recordAndApply(withCurrentValue(current, spec.id(), value));
            }
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    void applyToSettings(String configId, String value) {
        if (settings == null) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO, "OpenCode info bar [{0}] applyToSettings: settings is null — write dropped, "
                        + "id={1} value={2}", new Object[]{manager.getSessionId(), configId, value});
            }
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
        if (PluginSettings.isDebugJson()) {
            LOG.log(Level.INFO,
                    "OpenCode info bar [{0}] applyToSettings: settings#={1} id={2} value={3} "
                    + "settings.model() afterwards={4}",
                    new Object[]{manager.getSessionId(), System.identityHashCode(settings), configId, value,
                        settings.model()});
        }
        AiSessionHost h = host;
        if (h != null) {
            if (PluginSettings.isDebugJson()) {
                LOG.log(Level.INFO,
                        "OpenCode info bar [{0}] applyToSettings: calling host.updateSessionSettings with "
                        + "settings#={1}",
                        new Object[]{manager.getSessionId(), System.identityHashCode(settings)});
            }
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
