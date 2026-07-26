package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
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
import kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeRegistry;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.SessionInstructionsDeliveryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionCreateSettingsPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiTypeSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.ConfigTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.SpecialInstructionTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiTopComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;
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

    public static void show(SessionPersistenceManager spm) {
        SessionPickerDialog dialog = new SessionPickerDialog(spm);
        dialog.setLocationRelativeTo(WindowManager.getDefault().getMainWindow());
        dialog.setVisible(true);
    }

    private static boolean isProjectOpen(String path) {
        return path != null && Arrays.stream(OpenProjects.getDefault().getOpenProjects()).anyMatch(p -> p.getProjectDirectory().getPath().equals(path));
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
        p.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(value, c);
    }

    private final SessionPersistenceManager spm;
    private final TemplatePersistenceManager templates = new TemplatePersistenceManager();
    private final SessionTableModel sessions = new SessionTableModel();
    private final JTable sessionTable = new JTable(sessions);
    private final JTextField nameField = new JTextField(20);
    private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(1, 1, MAX_CREATE_COUNT, 1));
    private final JComboBox<String> projectCombo = new JComboBox<>();
    private final JComboBox<AiTypeEnum> aiTypeCombo = new JComboBox<>();
    private final JComboBox<ConfigTemplate> configCombo = new JComboBox<>();
    private final JComboBox<SpecialInstructionTemplate> instructionsCombo = new JComboBox<>();
    private final JCheckBox injectOnCreate = new JCheckBox("Inject Special Instructions on Create");
    private final JCheckBox closeAfterCreate = new JCheckBox("Close after action", true);
    private final JCheckBox closeAfterOpen = new JCheckBox("Close after action", true);
    private JButton createButton;
    private final JPanel typeSettingsHolder = new JPanel(new BorderLayout());
    private AiSessionCreateSettingsPanel<AiSessionSettings> typeSettingsPanel;

    private SessionPickerDialog(SessionPersistenceManager spm) {
        super(WindowManager.getDefault().getMainWindow(), "AI Manager", true);
        this.spm = spm;
        setLayout(new BorderLayout());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        aiTypeCombo.setRenderer(new AiTypeRenderer());
        configCombo.setRenderer(new TemplateRenderer<>(ConfigTemplate::name, "Default"));
        instructionsCombo.setRenderer(new TemplateRenderer<>(SpecialInstructionTemplate::name, "None"));
        instructionsCombo.addActionListener(e -> updateInstructionInjectionControl());
        aiTypeCombo.addActionListener(e -> replaceTypeSettingsPanel());
        updateInstructionInjectionControl();
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Existing Sessions", buildExistingTab());
        tabs.addTab("Create Session", buildCreateTab());
        tabs.addTab("Templates", buildTemplatesTab());
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
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton open = new JButton("Open");
        open.addActionListener(e -> onOpenSelected());
        JButton delete = new JButton("Delete");
        delete.addActionListener(e -> onDelete());
        actions.add(open);
        actions.add(closeAfterOpen);
        actions.add(delete);
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
        form.add(typeSettingsHolder, c);
        addRow(form, c, 5, "Config Template:", configCombo);
        addRow(form, c, 6, "Special Instructions:", instructionsCombo);
        c.gridx = 0;
        c.gridy = 7;
        c.gridwidth = 2;
        form.add(injectOnCreate, c);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        createButton = new JButton("Create & Open");
        createButton.addActionListener(e -> onCreateNew());
        actions.add(createButton);
        actions.add(closeAfterCreate);
        c.gridy = 8;
        form.add(actions, c);
        c.gridy = 9;
        c.weighty = 1;
        form.add(new JPanel(), c);
        return new JScrollPane(form);
    }

    private Component buildTemplatesTab() {
        JTabbedPane nested = new JTabbedPane();
        nested.addTab("Config Templates", new ConfigTemplatesPanel(templates, this::refreshTemplates));
        nested.addTab("Special Instructions", new InstructionTemplatesPanel(templates, this::refreshTemplates));
        return nested;
    }

    private void populateTypes() {
        new AiTypeRegistry().getEnabled().stream().map(AiTypeSettings::type).forEach(aiTypeCombo::addItem);
        AiTypeEnum last = PluginSettings.getLastSessionAiType();
        if (last != null) {
            aiTypeCombo.setSelectedItem(last);
        }
        replaceTypeSettingsPanel();
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
        defaults.applyDefaultSettingsFromGlobal();
        type.getSettingsCreator().applyDefaultSettingsFromGlobal(defaults);
        typeSettingsPanel.load(defaults);
        typeSettingsPanel.startLoading();
        JComponent component = typeSettingsPanel.component();
        typeSettingsHolder.add(component, BorderLayout.CENTER);
        typeSettingsHolder.revalidate();
        typeSettingsHolder.repaint();
        if (isShowing()) {
            pack();
        }
    }

    private void populateProjects() {
        for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            projectCombo.addItem(project.getProjectDirectory().getPath());
        }
        Project main = OpenProjects.getDefault().getMainProject();
        if (main != null) {
            projectCombo.setSelectedItem(main.getProjectDirectory().getPath());
        }
    }

    private void loadSessions() {
        try {
            sessions.setRows(spm.loadAll());
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not load sessions", e);
        }
    }

    private void refreshTemplates() {
        Object config = configCombo.getSelectedItem(), instruction = instructionsCombo.getSelectedItem();
        configCombo.removeAllItems();
        configCombo.addItem(null);
        instructionsCombo.removeAllItems();
        instructionsCombo.addItem(null);
        try {
            templates.saveConfigDefaultsIfEmpty().forEach(configCombo::addItem);
            templates.saveSpecialInstructionDefaultsIfEmpty().forEach(instructionsCombo::addItem);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not load templates", e);
        }
        configCombo.setSelectedItem(config);
        instructionsCombo.setSelectedItem(instruction);
        updateInstructionInjectionControl();
    }

    private void onCreateNew() {
        String project = (String) projectCombo.getSelectedItem();
        AiTypeEnum type = (AiTypeEnum) aiTypeCombo.getSelectedItem();
        String baseName = nameField.getText().trim();
        if (project == null || type == null || baseName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Name, open project, and AI type are required.", "Create session", JOptionPane.WARNING_MESSAGE);
            return;
        }
        PluginSettings.setLastSessionAiType(type);
        ConfigTemplate config = (ConfigTemplate) configCombo.getSelectedItem();
        SpecialInstructionTemplate instruction = (SpecialInstructionTemplate) instructionsCombo.getSelectedItem();
        AiSessionSettings typeSettingsSnapshot = type.createDefaultSettings();
        typeSettingsSnapshot.applyDefaultSettingsFromGlobal();
        type.getSettingsCreator().applyDefaultSettingsFromGlobal(typeSettingsSnapshot);
        if (typeSettingsPanel != null) {
            typeSettingsPanel.applyTo(typeSettingsSnapshot);
        }
        JsonObject typeSettingsJson = new JsonObject();
        typeSettingsSnapshot.populateJsonObject(typeSettingsJson);
        boolean startup = instruction != null && injectOnCreate.isSelected();
        boolean close = closeAfterCreate.isSelected();
        int count = (Integer) countSpinner.getValue();
        setCreateEnabled(false);
        new Thread(() -> {
            List<AiSession> created = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                AiSession session = AiSession.create(project, type).withName(count == 1 ? baseName : baseName + "_" + i);
                type.getSettingsCreator().update(session.settings(), typeSettingsJson);
                if (config != null) {
                    config.applyTo(session.settings());
                }
                if (instruction != null) {
                    session.settings().setSessionInstructions(instruction.body());
                }
                session.setSessionInstructionsDelivery(startup ? SessionInstructionsDeliveryEnum.ON_START : SessionInstructionsDeliveryEnum.ON_FIRST_REQUEST);
                try {
                    spm.save(session);
                    created.add(session);
                }
                catch (IOException ex) {
                    LOG.log(Level.WARNING, "Could not save session " + session.name(), ex);
                }
            }
            SwingUtilities.invokeLater(() -> {
                if (!created.isEmpty()) {
                    created.forEach(this::openSession);
                    loadSessions();
                    if (close) {
                        dispose();
                    }
                }
                setCreateEnabled(true);
            });
        }, "aicoder-session-create").start();
    }

    private void setCreateEnabled(boolean enabled) {
        createButton.setEnabled(enabled);
        nameField.setEnabled(enabled);
        countSpinner.setEnabled(enabled);
        projectCombo.setEnabled(enabled);
        aiTypeCombo.setEnabled(enabled);
        configCombo.setEnabled(enabled);
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
            JOptionPane.showMessageDialog(this, "The project for the following sessions is not open:\n" + names + "\n\nOpen the project(s) first.", "Project not open", JOptionPane.WARNING_MESSAGE);
        }
        if (eligible.isEmpty()) {
            return;
        }
        eligible.forEach(this::openSession);
        if (closeAfterOpen.isSelected()) {
            dispose();
        }
    }

    private void onDelete() {
        List<AiSession> selected = selectedSessions();
        if (selected.isEmpty()) {
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Delete selected sessions and their history permanently?", "Delete Sessions", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        for (AiSession session : selected) try {
            spm.delete(session.id());
            closeTab(session.id());
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not delete session", e);
        }
        loadSessions();
    }

    private void closeTab(String id) {
        for (TopComponent tc : new ArrayList<>(TopComponent.getRegistry().getOpened())) {
            if (tc instanceof AiTopComponent ai && id.equals(ai.getSession().id())) {
                ai.closeWithoutPrompt();
                return;
            }
        }
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
        if (typeSettingsPanel != null) {
            typeSettingsPanel.dispose();
            typeSettingsPanel = null;
        }
        super.dispose();
    }
}
