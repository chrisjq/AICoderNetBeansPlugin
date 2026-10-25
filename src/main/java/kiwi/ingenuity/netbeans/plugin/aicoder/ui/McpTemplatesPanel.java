package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.FlowLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.LegacyToolGroups;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolCategoryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.McpTemplatePersistenceManager;

/**
 * Editor for per-session MCP tool exposure templates.
 */
public final class McpTemplatesPanel extends TemplatePanel<McpTemplate> {

    private final McpTemplatePersistenceManager mcpTemplates;
    private final McpEditor editor;
    private final EnumMap<McpToolGroupEnum, JCheckBox> switches = new EnumMap<>(McpToolGroupEnum.class);
    private final JLabel buildQueueState = new JLabel();

    public McpTemplatesPanel(McpTemplatePersistenceManager mcpTemplates, Runnable onTemplateChanged) {
        this(new McpEditor(), mcpTemplates, onTemplateChanged);
    }

    private McpTemplatesPanel(McpEditor editor, McpTemplatePersistenceManager mcpTemplates, Runnable onTemplateChanged) {
        super(editor, null, onTemplateChanged,
              new String[]{"Name", "Updated", "Created"},
              McpTemplate::name, McpTemplate::updatedAt, McpTemplate::createdAt);
        this.editor = editor;
        this.mcpTemplates = mcpTemplates;
        buildEditor();
        initPanel();
    }

    private void buildEditor() {
        editor.setLayout(new javax.swing.BoxLayout(editor, javax.swing.BoxLayout.Y_AXIS));
        EnumMap<McpToolCategoryEnum, JPanel> categories = new EnumMap<>(McpToolCategoryEnum.class);
        for (McpToolCategoryEnum category : McpToolCategoryEnum.values()) {
            if (category == McpToolCategoryEnum.ALWAYS_ON) {
                continue;
            }
            JPanel categoryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            categoryPanel.setBorder(BorderFactory.createTitledBorder(category.label()));
            categories.put(category, categoryPanel);
            editor.add(categoryPanel);
        }
        for (McpToolGroupEnum group : McpToolGroupEnum.values()) {
            if (group == McpToolGroupEnum.ALWAYS_ON) {
                continue;
            }
            JPanel categoryPanel = categories.get(group.category());
            if (group == McpToolGroupEnum.BUILD_QUEUE) {
                categoryPanel.add(buildQueueState);
            }
            else if (group.switchable()) {
                JCheckBox box = new JCheckBox(group.label());
                box.addActionListener(e -> updateBuildQueueState());
                switches.put(group, box);
                categoryPanel.add(box);
            }
        }
        updateBuildQueueState();
    }

    private void updateBuildQueueState() {
        boolean enabled = McpToolGroupEnum.BUILD_SYSTEM_GROUPS.stream().anyMatch(group -> {
            JCheckBox box = switches.get(group);
            return box != null && box.isSelected();
        });
        buildQueueState.setText(McpToolGroupEnum.BUILD_QUEUE.label() + " (derived: " + (enabled ? "on" : "off") + ")");
    }

    private McpToolConfig config() {
        EnumSet<McpToolGroupEnum> disabled = EnumSet.noneOf(McpToolGroupEnum.class);
        switches.forEach((group, box) -> {
            if (!box.isSelected()) {
                disabled.add(group);
            }
        });
        return McpToolConfig.withDisabled(disabled);
    }

    /**
     * The Default's configuration from the last load, for seeding "New" and the empty editor without going back to the
     * store. Written on the store thread, read on the event dispatch thread.
     */
    private volatile McpToolConfig loadedDefaultConfig;

    /**
     * Runs on the store thread: no Swing here.
     */
    @Override
    protected List<McpTemplate> loadData() throws IOException {
        List<McpTemplate> loaded = new ArrayList<>(mcpTemplates.loadMcpTemplates());
        loaded.sort(Comparator.comparing(McpTemplate::isDefault).reversed()
                .thenComparing(McpTemplate::name, String.CASE_INSENSITIVE_ORDER));
        loadedDefaultConfig = loaded.stream().filter(McpTemplate::isDefault).map(McpTemplate::config)
                .findFirst().orElse(null);
        return loaded;
    }

    @Override
    protected void saveData(McpTemplate old, String name) throws IOException {
        prepareSave(old, name).run();
    }

    /**
     * The switches are read here, on the event dispatch thread; only the store write is deferred to the store thread.
     */
    @Override
    protected StoreWrite prepareSave(McpTemplate old, String name) {
        McpTemplate value = old == null
                            ? McpTemplate.create(name, config())
                            : old.withNameAndConfig(name, config());
        return () -> mcpTemplates.save(value);
    }

    @Override
    protected void deleteData(McpTemplate value) throws IOException {
        mcpTemplates.deleteMcpTemplate(value.id());
    }

    @Override
    protected boolean canDelete(McpTemplate value) {
        return value != null && !value.isDefault() && !value.isMinimal();
    }

    @Override
    protected void loadEditor(McpTemplate value) {
        applyConfigToSwitches(value == null ? defaultSeedConfig() : value.config());
    }

    @Override
    protected void clearEditor() {
        super.clearEditor();
        applyConfigToSwitches(defaultSeedConfig());
    }

    private void applyConfigToSwitches(McpToolConfig config) {
        switches.forEach((group, box) -> box.setSelected(!config.isDisabled(group)));
        updateBuildQueueState();
    }

    /**
     * The configuration a new template starts from: the (possibly user-edited) Default as of the last load, rather than
     * silently enabling every group. Until a load has completed (or if none succeeded) it is the derived legacy
     * defaults, and the empty editor is reseeded as soon as a load succeeds.
     */
    private McpToolConfig defaultSeedConfig() {
        McpToolConfig cached = loadedDefaultConfig;
        return cached != null ? cached : LegacyToolGroups.derive(null);
    }

    private static final class McpEditor extends JPanel {
    }
}
