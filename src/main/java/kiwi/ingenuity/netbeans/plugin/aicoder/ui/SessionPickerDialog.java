package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect.DetectedFacts;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatibleSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleBasicPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.ui.OpenAiCompatibleDetectedPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.SessionInstructionsDeliveryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiTypeSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.SpecialInstructionTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiTopComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.FormLayoutUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.server.McpToolsDocumentation;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.LegacyToolGroups;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.McpTemplatePersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ui.DialogPlacement;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.BrowserUtil;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.ProjectPathUtil;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.windows.Mode;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

/**
 * Modal manager for existing sessions and their reusable creation templates.
 */
public class SessionPickerDialog extends JDialog {

    private static final Logger LOG = Logger.getLogger(SessionPickerDialog.class.getName());
    private static final int MAX_CREATE_COUNT = 50;
    private static String MCP_TOOLS_HELP_HTML = null;

    private static final String DIALOG_BOUNDS_KEY = "ai-manager";

    public static void show(SessionPersistenceManager spm) {
        SessionPickerDialog dialog = new SessionPickerDialog(spm);
        DialogBounds.restore(DIALOG_BOUNDS_KEY, dialog, 820, 560);
        DialogPlacement.centre(dialog, WindowManager.getDefault().getMainWindow());
        dialog.setVisible(true);
    }

    private static boolean isProjectOpen(String path) {
        return ProjectPathUtil.isOpenProjectPath(path);
    }

    static String projectDisplayLabel(String path, List<String> openProjectPaths) {
        String name = java.nio.file.Path.of(path).getFileName().toString();
        long matchingNames = openProjectPaths.stream()
                .map(p -> java.nio.file.Path.of(p).getFileName().toString())
                .filter(name::equals)
                .count();
        return matchingNames > 1 ? path : name;
    }

    private static GridBagConstraints constraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static void addRow(JPanel p, GridBagConstraints c, int row, String label, Component value) {
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.weightx = 0;
        p.add(FormLayoutUtil.label(label), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(value, c);
    }

    private final SessionPersistenceManager spm;
    private final SessionPickerOperations operations;
    private final LoadEpochGuard loadEpoch = new LoadEpochGuard();
    private final TemplatePersistenceManager templates = new TemplatePersistenceManager();
    private final McpTemplatePersistenceManager mcpTemplates = new McpTemplatePersistenceManager();
    private final SessionTableModel sessions = new SessionTableModel();
    private final JTable sessionTable = new JTable(sessions);
    private final JLabel sessionsStatusLabel = new JLabel(" ");
    private JButton openButton;
    private JButton deleteButton;
    private final List<String> openProjectPaths = new ArrayList<>();
    private final PropertyChangeListener openProjectsListener = evt -> {
        if (OpenProjects.PROPERTY_OPEN_PROJECTS.equals(evt.getPropertyName())) {
            refreshOpenProjects();
        }
    };
    private final JTextField nameField = new JTextField(20);
    private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(1, 1, MAX_CREATE_COUNT, 1));
    private final JComboBox<String> projectCombo = new JComboBox<>();
    private final JComboBox<AiTypeEnum> aiTypeCombo = new JComboBox<>();
    private final JLabel ollamaDeprecationNote = new JLabel("Ollama (Local) is now off by default and deprecated — enable it in Tools > Options > Advanced > Ollama (Local), or use 'OpenAI Compatible'.");
    private final JComboBox<ConfigTemplate> configCombo = new JComboBox<>();
    private final JComboBox<McpTemplate> mcpTemplateCombo = new JComboBox<>();
    private final JComboBox<SpecialInstructionTemplate> instructionsCombo = new JComboBox<>();
    private final JCheckBox injectOnCreate = new JCheckBox("Inject Session Instructions on Create");
    private final JCheckBox closeAfterCreate = new JCheckBox("Close after action", true);
    private final JCheckBox closeAfterOpen = new JCheckBox("Close after action", true);
    private JButton createButton;
    private final JPanel typeSettingsHolder = new JPanel(new BorderLayout());
    private AiSessionCreateSettingsPanel<AiSessionSettings> typeSettingsPanel;
    private long templateLoadEpoch;
    private boolean templatesLoading;
    private boolean disposed;

    private SessionPickerDialog(SessionPersistenceManager spm) {
        super(WindowManager.getDefault().getMainWindow(), "AI Manager", true);
        this.spm = spm;
        this.operations = new SessionPickerOperations(spm, this::closeAndDeleteTab);
        setLayout(new BorderLayout());
        OpenProjects.getDefault().addPropertyChangeListener(openProjectsListener);
        projectCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof String path) {
                    setText(projectDisplayLabel(path, openProjectPaths));
                }
                return component;
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        aiTypeCombo.setRenderer(new AiTypeRenderer());
        configCombo.setRenderer(new DuplicateAwareTemplateRenderer<>(configCombo, ConfigTemplate::name, "Default"));
        mcpTemplateCombo.setRenderer(new DuplicateAwareTemplateRenderer<>(mcpTemplateCombo,
                                                                          SessionPickerDialog::mcpTemplateLabel,
                                                                          "Default"));
        instructionsCombo.setRenderer(new TemplateRenderer<>(SpecialInstructionTemplate::name, "None"));
        instructionsCombo.addActionListener(e -> updateInstructionInjectionControl());
        aiTypeCombo.addActionListener(e -> {
            updateOllamaDeprecationNote();
            replaceTypeSettingsPanel();
        });
        updateInstructionInjectionControl();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Existing Sessions", buildExistingTab());
        tabs.addTab("Create Session", buildCreateTab());
        tabs.addTab("Templates", buildTemplatesTab());
        tabs.addTab("Help", buildHelpTab());
        add(tabs, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        bottom.add(cancel);
        add(bottom, BorderLayout.SOUTH);
        loadSessions();
        populateProjects();
        populateTypes();
        refreshTemplates();
        setPreferredSize(new Dimension(850, 600));
        setMinimumSize(new Dimension(820, 560));
        pack();
    }

    private Component buildExistingTab() {
        sessionTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        sessionTable.setAutoCreateRowSorter(true);
        sessionTable.setDefaultRenderer(Object.class, new SessionRenderer());
        sessionTable.setPreferredScrollableViewportSize(new Dimension(780, 320));
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.add(new JScrollPane(sessionTable), BorderLayout.CENTER);
        panel.add(sessionsStatusLabel, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        openButton = new JButton("Open");
        openButton.addActionListener(e -> onOpenSelected());
        deleteButton = new JButton("Delete");
        deleteButton.addActionListener(e -> onDelete());
        actions.add(openButton);
        actions.add(closeAfterOpen);
        actions.add(deleteButton);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildCreateTab() {
        ScrollablePanel form = new ScrollablePanel(new GridBagLayout());
        GridBagConstraints c = constraints();
        addRow(form, c, 0, "Name (required):", nameField);
        addRow(form, c, 1, "Count:", countSpinner);
        addRow(form, c, 2, "Project:", projectCombo);
        addRow(form, c, 3, "AI type:", aiTypeCombo);
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 2;
        c.weightx = 1;
        ollamaDeprecationNote.setVisible(false);
        form.add(ollamaDeprecationNote, c);
        c.gridy = 5;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        form.add(typeSettingsHolder, c);
        addRow(form, c, 6, "Config Template:", configCombo);
        addRow(form, c, 7, "MCP Template:", mcpTemplateCombo);
        addRow(form, c, 8, "Session Instructions:", instructionsCombo);
        c.gridx = 0;
        c.gridy = 9;
        c.gridwidth = 2;
        form.add(injectOnCreate, c);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        createButton = new JButton("Create & Open");
        createButton.addActionListener(e -> onCreateNew());
        actions.add(createButton);
        actions.add(closeAfterCreate);
        c.gridy = 10;
        form.add(actions, c);
        c.gridy = 11;
        c.weighty = 1;
        form.add(new JPanel(), c);
        return new JScrollPane(form);
    }

    private Component buildTemplatesTab() {
        JTabbedPane nested = new JTabbedPane();
        nested.addTab("MCP Templates", new McpTemplatesPanel(mcpTemplates, this::refreshTemplates));
        nested.addTab("Config Templates", new ConfigTemplatesPanel(templates, this::refreshTemplates));
        nested.addTab("Session Instructions", new InstructionTemplatesPanel(templates, this::refreshTemplates));
        return nested;
    }

    private Component buildHelpTab() {
        JTabbedPane nested = new JTabbedPane();
        if (MCP_TOOLS_HELP_HTML == null) {
            MCP_TOOLS_HELP_HTML = McpToolsDocumentation.buildHtml();
        }
        JEditorPane mcpTools = new JEditorPane("text/html", MCP_TOOLS_HELP_HTML);
        mcpTools.setEditable(false);
        mcpTools.setCaretPosition(0);
        nested.addTab("MCP Tools", new JScrollPane(mcpTools));
        nested.addTab("About", buildAboutTab());
        return nested;
    }

    /**
     * Plugin identity, every value taken from the one place that already defines it rather than restated here: the
     * version and homepage are filtered into version.properties from the pom, and the name is the bundle key the Plugin
     * Manager displays. A literal copy in this panel would be the copy that goes stale.
     */
    private Component buildAboutTab() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = constraints();
        addRow(form, c, 0, "Plugin Name:", new JLabel(Installer.displayName()));
        addRow(form, c, 1, "Plugin Version:", new JLabel(Installer.VERSION));
        addRow(form, c, 2, "Homepage:", buildLink(Installer.HOMEPAGE));
        addRow(form, c, 3, "Version Homepage (If Available):", buildLink(Installer.releaseUrl()));
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 2;
        c.weightx = 1;
        c.weighty = 1;
        form.add(new JPanel(), c);
        return new JScrollPane(form);
    }

    /**
     * A URL as a clickable link, or plain text when there is nothing to open - an underlined label that did nothing on
     * click would be worse than an honest label.
     */
    private Component buildLink(String url) {
        if (url == null || url.isBlank()) {
            return new JLabel("(not available)");
        }
        JLabel link = new JLabel("<html><a href=\"" + url + "\">" + url + "</a></html>");
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(url);
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                BrowserUtil.openUrl(url);
            }
        });
        return link;
    }

    private void populateTypes() {
        new AiTypeRegistry().getEnabled().stream().map(AiTypeSettings::type).forEach(aiTypeCombo::addItem);
        AiTypeEnum last = PluginSettings.getLastSessionAiType();
        if (fallbackType(last, PluginSettings.isAiEnabled(AiTypeEnum.OLLAMA_LOCAL)) != last) {
            aiTypeCombo.setSelectedItem(fallbackType(last, PluginSettings.isAiEnabled(AiTypeEnum.OLLAMA_LOCAL)));
        }
        else if (last != null) {
            aiTypeCombo.setSelectedItem(last);
        }
        updateOllamaDeprecationNote();
        replaceTypeSettingsPanel();
    }

    private void updateOllamaDeprecationNote() {
        AiTypeEnum selected = (AiTypeEnum) aiTypeCombo.getSelectedItem();
        ollamaDeprecationNote.setVisible(showOllamaDeprecationNote(selected,
                                                                   PluginSettings.isAiEnabled(AiTypeEnum.OLLAMA_LOCAL)));
    }

    static AiTypeEnum fallbackType(AiTypeEnum last, boolean ollamaEnabled) {
        return last == AiTypeEnum.OLLAMA_LOCAL && !ollamaEnabled
               ? AiTypeEnum.OPENAI_COMPATIBLE
               : last;
    }

    static boolean showOllamaDeprecationNote(AiTypeEnum selected, boolean ollamaEnabled) {
        return selected == AiTypeEnum.OLLAMA_LOCAL && !ollamaEnabled;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void replaceTypeSettingsPanel() {
        if (typeSettingsPanel != null) {
            typeSettingsPanel.dispose();
            typeSettingsHolder.removeAll();
        }
        AiTypeEnum type = (AiTypeEnum) aiTypeCombo.getSelectedItem();
        if (type == null) {
            return;
        }
        typeSettingsPanel = (AiSessionCreateSettingsPanel) type.getSettingsCreator().createSettingsPanel();
        AiSessionSettings defaults = type.createDefaultSettings();
        type.getSettingsCreator().applyDefaultSettingsFromGlobal(defaults);
        typeSettingsPanel.load(defaults);
        typeSettingsPanel.startLoading();
        JComponent component = typeSettingsPanel.component();
        typeSettingsHolder.add(component, BorderLayout.CENTER);
        if (type == AiTypeEnum.OPENAI_COMPATIBLE && (Object) typeSettingsPanel instanceof OpenAiCompatibleBasicPanel basic) {
            OpenAiCompatibleDetectedPanel detected = new OpenAiCompatibleDetectedPanel();
            basic.setTestResultListener(result -> {
                OpenAiCompatibleSessionSettings snapshot = new OpenAiCompatibleSessionSettings();
                basic.applyTo(snapshot);
                DetectedFacts facts = DetectedFacts.of("test-connection")
                        .withApiBase(result.apiBase())
                        .withModels(result.models());
                if (result.serverKind() != null) {
                    facts = facts.withKind(result.serverKind());
                }
                String selectedModel = snapshot.effectiveModel();
                facts = facts.withCapabilities(new DetectedFacts.Capabilities(null, null,
                                                                              selectedModel == null ? null : result.reasoningLevelsByModel().get(selectedModel)));
                detected.update(facts, snapshot);
            });
            typeSettingsHolder.add(detected.component(), BorderLayout.SOUTH);
        }
        typeSettingsHolder.revalidate();
        typeSettingsHolder.repaint();
        if (isShowing()) {
            pack();
        }
    }

    private void populateProjects() {
        List<String> paths = new ArrayList<>();
        for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            paths.add(project.getProjectDirectory().getPath());
        }
        openProjectPaths.clear();
        openProjectPaths.addAll(ProjectPathUtil.distinctShortestPaths(paths));
        openProjectPaths.sort((left, right) -> projectDisplayLabel(left, openProjectPaths)
                .compareToIgnoreCase(projectDisplayLabel(right, openProjectPaths)));
        projectCombo.removeAllItems();
        openProjectPaths.forEach(projectCombo::addItem);
        Project main = OpenProjects.getDefault().getMainProject();
        if (main != null) {
            String mainPath = main.getProjectDirectory().getPath();
            openProjectPaths.stream()
                    .filter(path -> ProjectPathUtil.samePath(path, mainPath))
                    .findFirst()
                    .ifPresent(projectCombo::setSelectedItem);
        }
        sessions.setOpenProjectDirs(ProjectPathUtil.openProjectDirs());
    }

    private void refreshOpenProjects() {
        populateProjects();
        sessionTable.repaint();
    }

    private void loadSessions() {
        loadSessions(null);
    }

    /**
     * SessionPersistenceManager.loadAll takes a cross-process file lock with no timeout, so it must not run on the EDT:
     * another IDE instance holding the lock would freeze this one for as long as the picker stayed open. Returns at
     * once — the dialog shows a loading state immediately, then the table populates (or an error is reported) once the
     * background load completes.
     *
     * @param pendingMessage a message to keep showing once the load succeeds (e.g. a delete failure this reload is
     * following up on) instead of clearing the status label — a load failure still overrides it, since that is the more
     * current problem
     */
    private void loadSessions(String pendingMessage) {
        long epoch = loadEpoch.next();
        setSessionsActionsEnabled(false);
        sessionsStatusLabel.setText(pendingMessage != null ? pendingMessage : "Loading sessions…");
        operations.loadAll().whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
            // A later loadSessions call (e.g. a quick delete-then-reload) may have already started and could
            // finish first; applying this stale result on top of it would show outdated data. Disposing the
            // dialog while a load is in flight must not touch it either.
            if (!loadEpoch.isCurrent(epoch) || !isDisplayable()) {
                return;
            }
            if (error != null) {
                LOG.log(Level.WARNING, "Could not load sessions", error);
                sessionsStatusLabel.setText("Could not load sessions: " + error.getMessage());
                sessions.setRows(List.of());
            }
            else {
                sessionsStatusLabel.setText(pendingMessage != null ? pendingMessage : " ");
                sessions.setRows(result);
            }
            setSessionsActionsEnabled(true);
        }));
    }

    private void setSessionsActionsEnabled(boolean enabled) {
        openButton.setEnabled(enabled);
        deleteButton.setEnabled(enabled);
    }

    private void refreshTemplates() {
        long epoch = ++templateLoadEpoch;
        templatesLoading = true;
        setCreateEnabled(false);
        String selectedConfigId = configCombo.getSelectedItem() instanceof ConfigTemplate value ? value.id() : null;
        String selectedMcpId = mcpTemplateCombo.getSelectedItem() instanceof McpTemplate value ? value.id() : null;
        String selectedInstructionId = instructionsCombo.getSelectedItem() instanceof SpecialInstructionTemplate value ? value.id() : null;
        CompletableFuture<TemplateData> load;
        try {
            load = CompletableFuture.supplyAsync(() -> loadTemplateData(templates, mcpTemplates), SessionPickerOperations.executor());
        }
        catch (RejectedExecutionException ex) {
            // The shared pool was retired (plugin shutdown). Report it exactly like any other load failure rather
            // than letting it escape refreshTemplates() as an unchecked throwable.
            load = CompletableFuture.failedFuture(new java.util.concurrent.CompletionException(ex));
        }
        load.whenComplete((data, error) -> SwingUtilities.invokeLater(() -> {
            if (epoch != templateLoadEpoch || disposed) {
                return;
            }
            templatesLoading = false;
            configCombo.removeAllItems();
            mcpTemplateCombo.removeAllItems();
            instructionsCombo.removeAllItems();
            instructionsCombo.addItem(null);
            if (data != null) {
                populate(configCombo, data.configs(), ConfigTemplate::isDefault, t -> false, ConfigTemplate::name);
                populate(mcpTemplateCombo, data.mcps(), McpTemplate::isDefault, McpTemplate::isMinimal,
                         SessionPickerDialog::mcpTemplateLabel);
                data.instructions().forEach(instructionsCombo::addItem);
            }
            selectTemplateById(configCombo, selectedConfigId);
            selectTemplateById(mcpTemplateCombo, selectedMcpId);
            selectInstructionById(selectedInstructionId);
            updateInstructionInjectionControl();
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                                  ? error.getCause() : error;
                LOG.log(Level.WARNING, "Could not load templates", cause);
                DialogPlacement.showMessageDialog(this, "Could not load templates: " + cause.getMessage(),
                                                  "Templates", JOptionPane.ERROR_MESSAGE);
            }
            else if (data != null && !data.failures().isEmpty()) {
                String details = data.failures().stream()
                        .map(failure -> failure.store().label() + ": " + failure.message())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                DialogPlacement.showMessageDialog(this,
                                                  "Could not load the following template store(s), which are shown with "
                                                  + "their defaults instead:\n" + details,
                                                  "Templates", JOptionPane.WARNING_MESSAGE);
            }
            // Only an empty config or MCP list disables Create: a failed instruction store does not, and neither
            // does a failed config/MCP store, whose combo falls back to a single in-memory Default below.
            setCreateEnabled(configCombo.getItemCount() > 0 && mcpTemplateCombo.getItemCount() > 0);
        }));
    }

    /**
     * Loads the three template stores independently, so a failure in one leaves the others usable. A store that fails
     * is reported in {@link TemplateData#failures()}, and the config/MCP lists carry a single in-memory Default so the
     * combo is never empty and Create is enabled. A failure never empties a combo it did not touch.
     * <p>
     * Static and package-private so it can be tested without a dialog. Does blocking file I/O: call it off the EDT.
     */
    static TemplateData loadTemplateData(TemplatePersistenceManager templates, McpTemplatePersistenceManager mcpTemplates) {
        List<ConfigTemplate> configs;
        List<McpTemplate> mcps;
        List<SpecialInstructionTemplate> instructions;
        List<StoreFailure> failures = new ArrayList<>();
        try {
            configs = templates.saveConfigDefaultsIfEmpty();
        }
        catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not load the config template store", ex);
            failures.add(new StoreFailure(TemplateStoreEnum.CONFIG, reasonOf(ex)));
            configs = List.of(inMemoryDefaultConfig());
        }
        try {
            mcps = mcpTemplates.loadMcpTemplates();
        }
        catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not load the MCP template store", ex);
            failures.add(new StoreFailure(TemplateStoreEnum.MCP, reasonOf(ex)));
            mcps = List.of(inMemoryDefaultMcp());
        }
        try {
            instructions = templates.saveSpecialInstructionDefaultsIfEmpty();
        }
        catch (IOException ex) {
            LOG.log(Level.WARNING, "Could not load the instruction template store", ex);
            failures.add(new StoreFailure(TemplateStoreEnum.INSTRUCTION, reasonOf(ex)));
            instructions = List.of();
        }
        return new TemplateData(configs, mcps, instructions, List.copyOf(failures));
    }

    /**
     * An in-memory Default config template for the picker's fallback when the store cannot be read: seeded from the
     * effective global settings, exactly as {@code TemplatePersistenceManager} seeds a fresh store, and never written.
     */
    private static ConfigTemplate inMemoryDefaultConfig() {
        AiSessionSettings settings = new AiSessionSettings();
        settings.applyDefaultSettingsFromGlobal();
        return ConfigTemplate.createDefault(settings);
    }

    /**
     * The MCP picker's fallback when the MCP store cannot be read: the Default derived from the legacy settings, which
     * is what the store itself would seed, and never written.
     */
    static McpTemplate inMemoryDefaultMcp() {
        return McpTemplate.createDefault(LegacyToolGroups.derive(null));
    }

    private static String reasonOf(IOException ex) {
        String message = ex.getMessage();
        return message != null && !message.isBlank() ? message : ex.getClass().getSimpleName();
    }

    private static <T> void populate(JComboBox<T> combo, List<T> items,
                                     java.util.function.Predicate<T> isDefault,
                                     java.util.function.Predicate<T> isMinimal,
                                     java.util.function.Function<T, String> nameOf) {
        ordered(items, isDefault, isMinimal, nameOf).forEach(combo::addItem);
    }

    /**
     * The order a template combo is populated in: Default first, then Minimal, then everything else by case-insensitive
     * name. Package-private and static so the ordering can be tested without a combo.
     */
    static <T> List<T> ordered(List<T> items,
                               java.util.function.Predicate<T> isDefault,
                               java.util.function.Predicate<T> isMinimal,
                               java.util.function.Function<T, String> nameOf) {
        return items.stream()
                .sorted(Comparator.comparingInt((T item) -> isDefault.test(item) ? 0 : isMinimal.test(item) ? 1 : 2)
                        .thenComparing(nameOf, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private void selectTemplateById(JComboBox<?> combo, String id) {
        if (id != null) {
            for (int i = 0; i < combo.getItemCount(); i++) {
                Object value = combo.getItemAt(i);
                if (value instanceof ConfigTemplate config && id.equals(config.id())
                        || value instanceof McpTemplate mcp && id.equals(mcp.id())) {
                    combo.setSelectedIndex(i);
                    return;
                }
            }
        }
        if (combo.getItemCount() > 0) {
            combo.setSelectedIndex(0);
        }
    }

    private void selectInstructionById(String id) {
        if (id != null) {
            for (int i = 0; i < instructionsCombo.getItemCount(); i++) {
                SpecialInstructionTemplate value = (SpecialInstructionTemplate) instructionsCombo.getItemAt(i);
                if (value != null && id.equals(value.id())) {
                    instructionsCombo.setSelectedIndex(i);
                    return;
                }
            }
        }
        instructionsCombo.setSelectedIndex(0);
    }

    /**
     * The outcome of {@link #loadTemplateData}: three independently-loaded lists and the stores that failed. A failing
     * store leaves its list usable rather than null.
     */
    record TemplateData(List<ConfigTemplate> configs,
                        List<McpTemplate> mcps,
                        List<SpecialInstructionTemplate> instructions,
                        List<StoreFailure> failures) {

    }

    /**
     * One template store that could not be read, with the reason it gave.
     */
    record StoreFailure(TemplateStoreEnum store, String message) {

    }

    /**
     * The three template stores, named so a failure can say which one it was.
     */
    enum TemplateStoreEnum {
        CONFIG("Config"),
        MCP("MCP"),
        INSTRUCTION("Session Instructions");

        private final String label;

        TemplateStoreEnum(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * Renders a template combo, appending " (n)" to a name shared by more than one entry, mirroring
     * {@code McpToolSettingsPanel.templateChoices}: two templates can legitimately carry the same name, and identical
     * labels in a combo are indistinguishable. The suffix is the entry's 1-based position among same-named entries, in
     * combo order — the combo is sorted Default-first then by name, so the order (and the suffix) is stable.
     */
    private static final class DuplicateAwareTemplateRenderer<T> extends DefaultListCellRenderer {

        private final JComboBox<T> combo;
        private final java.util.function.Function<T, String> nameOf;
        private final String empty;

        DuplicateAwareTemplateRenderer(JComboBox<T> combo, java.util.function.Function<T, String> nameOf, String empty) {
            this.combo = combo;
            this.nameOf = nameOf;
            this.empty = empty;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            setText(value == null ? empty : label((T) value));
            return this;
        }

        private String label(T value) {
            String name = nameOf.apply(value);
            List<String> names = new ArrayList<>();
            int position = 0;
            for (int i = 0; i < combo.getItemCount(); i++) {
                T candidate = combo.getItemAt(i);
                if (candidate != null) {
                    names.add(nameOf.apply(candidate));
                    if (candidate == value) {
                        position = names.size();
                    }
                }
            }
            return duplicateLabel(name, names, position);
        }
    }

    /**
     * The display label for the entry at {@code position} (1-based) in {@code names}: the bare name when it is unique,
     * otherwise " (n)" appended so same-named entries are distinguishable. Mirrors
     * {@code McpToolSettingsPanel.templateChoices}. Package-private and static so it can be tested without a combo.
     */
    static String duplicateLabel(String name, List<String> names, int position) {
        long total = names.stream().filter(name::equals).count();
        return total > 1 ? name + " (" + position + ")" : name;
    }

    /**
     * The base name an MCP template is shown under: the built-in Minimal is labelled "Minimal (built-in)" so a user
     * template the user happens to have named "Minimal" stays distinguishable from it. Every other template keeps its
     * own name, and the duplicate-aware renderer adds a numeric suffix if two of them still collide.
     */
    static String mcpTemplateLabel(McpTemplate template) {
        return template.isMinimal() ? template.name() + " (built-in)" : template.name();
    }

    static void applyTemplates(AiSession session, ConfigTemplate config, McpTemplate mcpTemplate) {
        if (config == null) {
            throw new IllegalArgumentException("A config template is required");
        }
        if (mcpTemplate == null) {
            throw new IllegalArgumentException("An MCP template is required");
        }
        config.applyTo(session.settings());
        session.settings().setMcpToolConfig(mcpTemplate.config());
    }

    private void onCreateNew() {
        String project = (String) projectCombo.getSelectedItem();
        AiTypeEnum type = (AiTypeEnum) aiTypeCombo.getSelectedItem();
        String baseName = nameField.getText().trim();
        if (project == null || type == null || baseName.isEmpty()) {
            DialogPlacement.showMessageDialog(this, "Name, open project, and AI type are required.", "Create session", JOptionPane.WARNING_MESSAGE);
            return;
        }
        PluginSettings.setLastSessionAiType(type);
        ConfigTemplate config = (ConfigTemplate) configCombo.getSelectedItem();
        if (config == null) {
            try {
                config = templates.getDefault();
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not load the default config template", e);
                DialogPlacement.showMessageDialog(this, "Could not load the default config template: " + e.getMessage(),
                                                  "Create session", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        McpTemplate mcpTemplate = (McpTemplate) mcpTemplateCombo.getSelectedItem();
        if (mcpTemplate == null) {
            try {
                mcpTemplate = mcpTemplates.getDefault();
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not load the default MCP template", e);
                DialogPlacement.showMessageDialog(this, "Could not load the default MCP template: " + e.getMessage(),
                                                  "Create session", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        SpecialInstructionTemplate instruction = (SpecialInstructionTemplate) instructionsCombo.getSelectedItem();
        AiSessionSettings typeSettingsSnapshot = type.createDefaultSettings();
        type.getSettingsCreator().applyDefaultSettingsFromGlobal(typeSettingsSnapshot);
        if (typeSettingsPanel != null) {
            typeSettingsPanel.applyTo(typeSettingsSnapshot);
        }
        JsonObject typeSettingsJson = new JsonObject();
        typeSettingsSnapshot.populateJsonObject(typeSettingsJson);
        boolean startup = instruction != null && injectOnCreate.isSelected();
        boolean close = closeAfterCreate.isSelected();
        int count = (Integer) countSpinner.getValue();
        List<AiSession> toCreate = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            AiSession session = AiSession.create(project, type).withName(count == 1 ? baseName : baseName + "_" + i);
            type.getSettingsCreator().update(session.settings(), typeSettingsJson);
            applyTemplates(session, config, mcpTemplate);
            if (instruction != null) {
                session.settings().setSessionInstructions(instruction.body());
            }
            session.setSessionInstructionsDelivery(startup ? SessionInstructionsDeliveryEnum.ON_START : SessionInstructionsDeliveryEnum.ON_FIRST_REQUEST);
            toCreate.add(session);
        }
        setCreateEnabled(false);
        operations.createAll(toCreate).whenComplete((created, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                LOG.log(Level.WARNING, "Could not create sessions", error);
                sessionsStatusLabel.setText("Could not create sessions: " + error.getMessage());
            }
            else if (!created.isEmpty()) {
                created.forEach(this::openSession);
                loadSessions();
                if (close) {
                    dispose();
                }
            }
            setCreateEnabled(true);
        }));
    }

    private void setCreateEnabled(boolean enabled) {
        createButton.setEnabled(enabled && !templatesLoading);
        nameField.setEnabled(enabled);
        countSpinner.setEnabled(enabled);
        projectCombo.setEnabled(enabled);
        aiTypeCombo.setEnabled(enabled);
        configCombo.setEnabled(enabled);
        mcpTemplateCombo.setEnabled(enabled);
        instructionsCombo.setEnabled(enabled);
        injectOnCreate.setEnabled(enabled && instructionsCombo.getSelectedItem() != null);
    }

    private void updateInstructionInjectionControl() {
        boolean visible = instructionsCombo.getSelectedItem() != null;
        injectOnCreate.setVisible(visible);
        injectOnCreate.setEnabled(visible && instructionsCombo.isEnabled());
        if (!visible) {
            injectOnCreate.setSelected(false);
        }
    }

    private List<AiSession> selectedSessions() {
        return sessionTable.getSelectedRows() == null ? List.of() : Arrays.stream(sessionTable.getSelectedRows()).map(sessionTable::convertRowIndexToModel).mapToObj(sessions::row).toList();
    }

    private void onOpenSelected() {
        List<AiSession> selected = selectedSessions();
        List<AiSession> blocked = selected.stream().filter(s -> s.projectPath() != null && !isProjectOpen(s.projectPath())).toList();
        List<AiSession> eligible = selected.stream().filter(s -> s.projectPath() == null || isProjectOpen(s.projectPath())).sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();
        if (!blocked.isEmpty()) {
            String names = blocked.stream().map(AiSession::name).sorted(String.CASE_INSENSITIVE_ORDER).reduce((a, b) -> a + ", " + b).orElse("");
            DialogPlacement.showMessageDialog(this, "The project for the following sessions is not open:\n" + names + "\n\nOpen the project(s) first.", "Project not open", JOptionPane.WARNING_MESSAGE);
        }
        if (eligible.isEmpty()) {
            return;
        }
        eligible.forEach(this::openSession);
        if (closeAfterOpen.isSelected()) {
            dispose();
        }
    }

    /**
     * Confirms on the EDT, then hands the actual deletes to {@link #operations}, which runs them off it: for a session
     * with no open tab, {@code SessionDeletion.delete} falls through to {@code SessionPersistenceManager.delete}, which
     * takes the same untimed cross-process file lock as loadSessions.
     */
    private void onDelete() {
        List<AiSession> selected = selectedSessions();
        if (selected.isEmpty()) {
            return;
        }
        if (DialogPlacement.showConfirmDialog(this, "Delete selected sessions and their history permanently?", "Delete Sessions", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        setSessionsActionsEnabled(false);
        operations.deleteAll(selected).whenComplete((failedNames, error) -> SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) {
                return;
            }
            String message;
            if (error != null) {
                LOG.log(Level.WARNING, "Could not delete sessions", error);
                message = "Could not delete sessions: " + error.getMessage();
            }
            else if (!failedNames.isEmpty()) {
                message = "Could not delete one or more sessions: " + String.join(", ", failedNames);
            }
            else {
                message = null;
            }
            loadSessions(message);
        }));
    }

    /**
     * Called off the EDT (from {@link #operations}' delete task). The TopComponent lookup and, if found, the tab close
     * must themselves run on the EDT, so this hops there and back rather than running on the caller's thread — the same
     * reason {@link AiTopComponent#closeAndDelete} must be called on the EDT. Nothing on the EDT ever blocks waiting
     * for this method's caller (the delete task's future is only ever consumed via whenComplete, never get()), so this
     * cannot deadlock against it.
     */
    private CompletableFuture<Void> closeAndDeleteTab(String id) {
        AtomicReference<CompletableFuture<Void>> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                for (TopComponent tc : new ArrayList<>(TopComponent.getRegistry().getOpened())) {
                    if (tc instanceof AiTopComponent ai && id.equals(ai.getSession().id())) {
                        result.set(ai.closeAndDelete());
                        return;
                    }
                }
            });
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch (InvocationTargetException e) {
            LOG.log(Level.WARNING, "Could not check for an open tab for session " + id, e);
        }
        return result.get();
    }

    private void openSession(AiSession session) {
        Runnable task = () -> {
            for (TopComponent tc : TopComponent.getRegistry().getOpened()) {
                if (tc instanceof AiTopComponent ai && session.id().equals(ai.getSession().id())) {
                    tc.requestActive();
                    return;
                }
            }
            AiTopComponent tc = new AiTopComponent(session, spm);
            Mode mode = WindowManager.getDefault().findMode("output");
            if (mode != null) {
                mode.dockInto(tc);
            }
            tc.open();
            tc.requestActive();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        }
        else {
            SwingUtilities.invokeLater(task);
        }
    }

    @Override
    public void dispose() {
        disposed = true;
        ++templateLoadEpoch;
        DialogBounds.remember(DIALOG_BOUNDS_KEY, this);
        OpenProjects.getDefault().removePropertyChangeListener(openProjectsListener);
        if (typeSettingsPanel != null) {
            typeSettingsPanel.dispose();
            typeSettingsPanel = null;
        }
        super.dispose();
    }
}
