package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import java.awt.BorderLayout;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiModelCatalog;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.claude.settings.ClaudePluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.githubcopilot.settings.GithubCopilotPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.grok.settings.GrokPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.ollama.settings.OllamaPluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.settings.OpenCodePluginSettings;

/**
 * Reusable session-create panel for AI types with a model setting.
 */
public class ModelCreateSettingsPanel<E extends AiModelSessionSettings>
        implements AiSessionCreateSettingsPanel<E> {

    private static final Map<AiTypeEnum, String> LAST_SELECTED_MODELS = new EnumMap<>(AiTypeEnum.class);

    private static List<String> getKnownModels(AiTypeEnum aiType) {
        if (aiType == null) {
            return List.of();
        }
        switch (aiType) {
            case CLAUDE:
                return Arrays.asList(ClaudePluginSettings.getKnownModels());
            case GROK:
                return Arrays.asList(GrokPluginSettings.getKnownModels());
            case GitHubCoPilot:
                return Arrays.asList(GithubCopilotPluginSettings.getKnownModels());
            case OLLAMA_LOCAL:
                return Arrays.asList(OllamaPluginSettings.getKnownModels());
            case OPENCODE:
                return Arrays.asList(OpenCodePluginSettings.getKnownModels());
            default:
                return List.of();
        }
    }

    private static String getDefaultModel(AiTypeEnum aiType) {
        if (aiType == null) {
            return null;
        }
        switch (aiType) {
            case CLAUDE:
                return ClaudePluginSettings.getModel();
            case GROK:
                return GrokPluginSettings.getModel();
            case GitHubCoPilot:
                return GithubCopilotPluginSettings.getModel();
            case OLLAMA_LOCAL:
                return OllamaPluginSettings.getModel();
            case OPENCODE:
                return OpenCodePluginSettings.getModel();
            default:
                return null;
        }
    }
    private final JPanel panel = new JPanel(new BorderLayout(6, 0));
    private final JComboBox<String> model = new JComboBox<>();
    private final AiModelCatalog catalog;
    private final AiTypeEnum aiType;
    private final Consumer<List<String>> catalogListener = this::replaceModels;
    private boolean updating;
    private final Function<E, String> modelReader;
    private final BiConsumer<E, String> modelWriter;

    public ModelCreateSettingsPanel(AiTypeEnum aiType, AiModelCatalog catalog, Function<E, String> modelReader,
            BiConsumer<E, String> modelWriter) {
        this.catalog = catalog;
        this.aiType = aiType;
        this.modelReader = modelReader;
        this.modelWriter = modelWriter;
        model.setEditable(true);
        model.addActionListener(e -> rememberUserSelection());
        panel.add(new JLabel("Model:"), BorderLayout.WEST);
        panel.add(model, BorderLayout.CENTER);
        catalog.addListener(catalogListener);
        List<String> cached = catalog.getCachedModels();
        if (cached == null || cached.isEmpty()) {
            cached = getKnownModels(aiType);
        }
        replaceModels(cached);
    }

    @Override
    public JComponent component() {
        return panel;
    }

    @Override
    public void load(E settings) {
        boolean wasUpdating = updating;
        updating = true;
        try {
            String targetModel = modelReader.apply(settings);
            if (targetModel == null || targetModel.isBlank()) {
                synchronized (LAST_SELECTED_MODELS) {
                    targetModel = LAST_SELECTED_MODELS.get(aiType);
                }
            }
            if (targetModel == null || targetModel.isBlank()) {
                targetModel = getDefaultModel(aiType);
            }
            if (targetModel != null && !targetModel.isBlank()) {
                setSelectedModel(targetModel);
            }
            else if (model.getItemCount() > 0) {
                model.setSelectedIndex(0);
            }
        }
        finally {
            updating = wasUpdating;
        }
    }

    @Override
    public void applyTo(E settings) {
        String value = getSelectedModel();
        modelWriter.accept(settings, value);
    }

    @Override
    public void dispose() {
        catalog.removeListener(catalogListener);
    }

    private String getSelectedModel() {
        Object selected = model.getSelectedItem();
        if (selected != null && !selected.toString().isBlank()) {
            return selected.toString().trim();
        }
        Object item = model.isEditable() && model.getEditor() != null ? model.getEditor().getItem() : null;
        if (item != null && !item.toString().isBlank()) {
            return item.toString().trim();
        }
        return null;
    }

    private void setSelectedModel(String targetModel) {
        if (targetModel == null || targetModel.isBlank()) {
            return;
        }
        String trimmed = targetModel.trim();
        boolean wasUpdating = updating;
        updating = true;
        try {
            boolean found = false;
            for (int i = 0; i < model.getItemCount(); i++) {
                if (trimmed.equals(model.getItemAt(i))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                model.addItem(trimmed);
            }
            model.setSelectedItem(trimmed);
            if (model.getEditor() != null) {
                model.getEditor().setItem(trimmed);
            }
        }
        finally {
            updating = wasUpdating;
        }
    }

    private void rememberUserSelection() {
        if (updating) {
            return;
        }
        String selected = getSelectedModel();
        synchronized (LAST_SELECTED_MODELS) {
            if (selected == null || selected.isBlank()) {
                LAST_SELECTED_MODELS.remove(aiType);
            }
            else {
                LAST_SELECTED_MODELS.put(aiType, selected);
            }
        }
    }

    private void replaceModels(List<String> models) {
        List<String> listToUse = (models == null || models.isEmpty()) ? getKnownModels(aiType) : models;
        if (listToUse == null || listToUse.isEmpty()) {
            return;
        }
        Runnable update = () -> {
            boolean wasUpdating = updating;
            updating = true;
            try {
                String selectedStr = getSelectedModel();
                model.removeAllItems();
                listToUse.forEach(model::addItem);
                if (selectedStr != null) {
                    setSelectedModel(selectedStr);
                }
                else {
                    String remembered;
                    synchronized (LAST_SELECTED_MODELS) {
                        remembered = LAST_SELECTED_MODELS.get(aiType);
                    }
                    if (remembered != null && !remembered.isBlank()) {
                        setSelectedModel(remembered);
                    }
                    else {
                        String def = getDefaultModel(aiType);
                        if (def != null && listToUse.contains(def)) {
                            setSelectedModel(def);
                        }
                        else if (model.getItemCount() > 0) {
                            model.setSelectedIndex(0);
                        }
                    }
                }
            }
            finally {
                updating = wasUpdating;
            }
        };
        if (SwingUtilities.isEventDispatchThread() || !panel.isShowing()) {
            update.run();
        }
        else {
            SwingUtilities.invokeLater(update);
        }
    }

}
