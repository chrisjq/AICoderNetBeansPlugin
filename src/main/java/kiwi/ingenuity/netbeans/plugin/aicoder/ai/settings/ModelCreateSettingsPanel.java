package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import java.awt.BorderLayout;
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

/**
 * Reusable session-create panel for AI types with a model setting.
 *
 * <p>
 * Knows nothing about any particular AI. Each AI's own create panel supplies
 * its model list and default by overriding {@link #knownModels()} and
 * {@link #defaultModel()}; this class previously switched over every
 * {@link AiTypeEnum} value and imported all six settings classes, so adding an
 * AI meant editing this file too.
 */
public abstract class ModelCreateSettingsPanel<E extends AiModelSessionSettings>
        implements AiSessionCreateSettingsPanel<E> {

    private static final Map<AiTypeEnum, String> LAST_SELECTED_MODELS = new EnumMap<>(AiTypeEnum.class);

    private final JPanel panel = new JPanel(new BorderLayout(0, 4));
    private final JPanel modelRow = new JPanel(new BorderLayout(6, 0));
    /**
     * Created on first use by {@link #content()}. An always-present empty panel
     * would contribute its own gaps to the four AIs that only offer a model,
     * shifting their layout for nothing; created lazily, those four lay out
     * exactly as they did when this class was only ever the model row.
     */
    private JPanel content;
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
        modelRow.add(new JLabel("Model:"), BorderLayout.WEST);
        modelRow.add(model, BorderLayout.CENTER);
        panel.add(modelRow, BorderLayout.NORTH);
        catalog.addListener(catalogListener);
        List<String> cached = catalog.getCachedModels();
        if (cached == null || cached.isEmpty()) {
            cached = knownModels();
        }
        replaceModels(cached);
    }

    /**
     * Models to offer before the catalog has discovered any, i.e. this AI's
     * built-in list.
     *
     * <p>
     * <b>Called from this class's constructor</b>, so an implementation must
     * not read state declared in the subclass — that state is not assigned yet
     * and would still be null. Returning values from the AI's settings class,
     * which is what every implementation does, is safe.
     *
     * @return known models, never null; empty is acceptable
     */
    protected abstract List<String> knownModels();

    /**
     * Model to select when the session has none stored and nothing was
     * previously chosen for this AI. Same constructor-time restriction as
     * {@link #knownModels()} applies.
     *
     * @return the default model, or null if this AI has none
     */
    protected abstract String defaultModel();

    @Override
    public JComponent component() {
        return panel;
    }

    /**
     * Area below the model row for a subclass to add its own controls, e.g.
     * OpenCode's build/plan mode or Ollama's base URL. Lets an AI that needs
     * extra fields still extend this panel instead of wrapping it — a wrapper
     * has to redeclare {@code component}/{@code load}/{@code applyTo}/
     * {@code dispose} purely to forward them.
     *
     * <p>
     * A subclass adding controls here will usually also override
     * {@link #load(AiModelSessionSettings)} and
     * {@link #applyTo(AiModelSessionSettings)}, calling {@code super} so the
     * model itself is still read and written.
     *
     * @return the content panel, created on first call
     */
    protected final JPanel content() {
        if (content == null) {
            content = new JPanel(new BorderLayout(6, 4));
            panel.add(content, BorderLayout.CENTER);
            panel.revalidate();
        }
        return content;
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
                targetModel = defaultModel();
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
        List<String> listToUse = (models == null || models.isEmpty()) ? knownModels() : models;
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
                        String def = defaultModel();
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
