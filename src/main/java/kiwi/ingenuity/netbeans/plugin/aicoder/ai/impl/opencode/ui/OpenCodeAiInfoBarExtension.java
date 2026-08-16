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
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AiPropertyEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.http.context.ContextGaugePanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeAiProcessManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeConfigOptionsEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.OpenCodeUsageEvent;
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

    private final OpenCodeAiProcessManager manager;
    private final JPanel comboPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final ContextGaugePanel gauge = new ContextGaugePanel();

    public OpenCodeAiInfoBarExtension(OpenCodeAiProcessManager manager) {
        this.manager = manager;
        comboPanel.setOpaque(false);
    }

    @Override
    public List<JComponent> createComponents() {
        JsonArray initial = manager.configOptions();
        if (initial != null) {
            applyConfigOptions(initial);
        }
        return List.of(comboPanel, gauge.component());
    }

    @Override
    public void onPropertyEvent(AiPropertyEvent event) {
    }

    @Override
    public void onAiProcessImplEvent(AiProcessImplEvent event) {
        if (event instanceof OpenCodeConfigOptionsEvent co) {
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

    private void applyConfigOptions(JsonArray configOptions) {
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
            manager.setConfigOption(spec.id(), spec.valueForDisplay(sel.toString()))
                    .thenAccept(opts -> SwingUtilities.invokeLater(() -> applyConfigOptions(opts)))
                    .whenComplete((v, ex) -> programmatic[0] = false);
        });
        return combo;
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
