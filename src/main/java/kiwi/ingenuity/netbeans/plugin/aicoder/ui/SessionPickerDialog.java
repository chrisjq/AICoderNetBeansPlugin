package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui.AiTopComponent;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.SessionPersistenceManager;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.windows.Mode;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

public class SessionPickerDialog extends JDialog {

    private static final Logger LOG = Logger.getLogger(SessionPickerDialog.class.getName());

    /**
     * Upper bound on how many sessions can be created at once.
     */
    private static final int MAX_CREATE_COUNT = 50;

    public static void show(SessionPersistenceManager spm) {
        SessionPickerDialog d = new SessionPickerDialog(spm);
        d.setLocationRelativeTo(WindowManager.getDefault().getMainWindow());
        d.setVisible(true);
    }

    private static boolean isProjectOpen(String projectPath) {
        if (projectPath == null) {
            return false;
        }
        return Arrays.stream(OpenProjects.getDefault().getOpenProjects())
                .anyMatch(p -> p.getProjectDirectory().getPath().equals(projectPath));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private final SessionPersistenceManager spm;
    private final DefaultListModel<AiSession> listModel = new DefaultListModel<>();
    private final JList<AiSession> sessionList = new JList<>(listModel);
    private final JTextField nameField = new JTextField(20);
    private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(1, 1, MAX_CREATE_COUNT, 1));
    private final JComboBox<String> projectCombo = new JComboBox<>();
    private final JComboBox<AiTypeEnum> aiTypeCombo = new JComboBox<>();

    private SessionPickerDialog(SessionPersistenceManager spm) {
        super(WindowManager.getDefault().getMainWindow(), "AI Sessions", true);
        this.spm = spm;
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        add(buildLeftPanel(), BorderLayout.CENTER);
        add(buildRightPanel(), BorderLayout.EAST);
        add(buildButtonPanel(), BorderLayout.SOUTH);

        loadSessions();
        populateProjects();
        new kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeRegistry().getEnabled()
                .stream()
                .map(kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiTypeSettings::type)
                .forEach(aiTypeCombo::addItem);
        if (aiTypeCombo.getItemCount() > 0) {
            AiTypeEnum lastType = kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings.getLastSessionAiType();
            aiTypeCombo.setSelectedItem(lastType != null ? lastType : AiTypeEnum.GitHubCoPilot);
        }
        pack();
        setMinimumSize(new Dimension(600, 380));
    }

    private JPanel buildLeftPanel() {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(new JLabel("Existing sessions:"), BorderLayout.NORTH);
        sessionList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        sessionList.setCellRenderer(new SessionCellRenderer());
        p.add(new JScrollPane(sessionList), BorderLayout.CENTER);
        return p;
    }

    private JPanel buildRightPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("New session"),
                BorderFactory.createEmptyBorder(32, 8, 8, 8)));
        p.setPreferredSize(new Dimension(228, 0));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        p.add(new JLabel("Name (required):"), c);
        c.gridy = 1;
        p.add(nameField, c);
        c.gridy = 2;
        p.add(new JLabel("Count:"), c);
        c.gridy = 3;
        p.add(countSpinner, c);
        c.gridy = 4;
        p.add(new JLabel("Project:"), c);
        c.gridy = 5;
        p.add(projectCombo, c);
        c.gridy = 6;
        p.add(new JLabel("AI type:"), c);
        c.gridy = 7;
        p.add(aiTypeCombo, c);
        c.gridy = 8;
        JButton createBtn = new JButton("Create & Open");
        createBtn.addActionListener(e -> onCreateNew());
        p.add(createBtn, c);
        // Vertical filler: absorbs extra height so the fields stay anchored at
        // the top instead of being centred when the panel is taller than its
        // content.
        c.gridy = 9;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        p.add(new JLabel(), c);
        return p;
    }

    private JPanel buildButtonPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton openBtn = new JButton("Open");
        openBtn.addActionListener(e -> onOpenSelected());
        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> onDelete());
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        p.add(openBtn);
        p.add(deleteBtn);
        p.add(cancelBtn);
        return p;
    }

    private void loadSessions() {
        listModel.clear();
        try {
            spm.loadAll().forEach(listModel::addElement);
        }
        catch (IOException e) {
            LOG.log(Level.WARNING, "Could not load sessions", e);
        }
    }

    private void populateProjects() {
        projectCombo.removeAllItems();
        Project[] projects = OpenProjects.getDefault().getOpenProjects();
        for (Project proj : projects) {
            projectCombo.addItem(proj.getProjectDirectory().getPath());
        }
        Project main = OpenProjects.getDefault().getMainProject();
        if (main != null) {
            projectCombo.setSelectedItem(main.getProjectDirectory().getPath());
        }
    }

    private void onCreateNew() {
        String projectPath = (String) projectCombo.getSelectedItem();
        if (projectPath == null || projectPath.isBlank()) {
            JOptionPane.showMessageDialog(this, "Please select an open project.", "No project", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AiTypeEnum chosenType = (AiTypeEnum) aiTypeCombo.getSelectedItem();
        if (chosenType == null) {
            JOptionPane.showMessageDialog(this, "Please select an AI type.", "No AI type", JOptionPane.WARNING_MESSAGE);
            return;
        }
        kiwi.ingenuity.netbeans.plugin.aicoder.PluginSettings.setLastSessionAiType(chosenType);

        String typedName = nameField.getText().trim();
        if (typedName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a name for the AI session.", "Name required", JOptionPane.WARNING_MESSAGE);
            nameField.requestFocusInWindow();
            return;
        }

        int count = (Integer) countSpinner.getValue();
        if (count < 1) {
            count = 1;
        }

        // count == 1 -> use the typed name as-is; count > 1 -> suffix each with _1.._N.
        List<AiSession> created = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String name = count == 1 ? typedName : typedName + "_" + i;
            AiSession session = AiSession.create(projectPath, chosenType).withName(name);
            try {
                spm.save(session);
                created.add(session);
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not save new session " + name, e);
            }
        }

        if (created.isEmpty()) {
            return;
        }
        dispose();
        created.forEach(this::openSession);
    }

    private void onOpenSelected() {
        List<AiSession> selected = sessionList.getSelectedValuesList();
        if (selected.isEmpty()) {
            return;
        }
        List<AiSession> blocked = selected.stream()
                .filter(s -> s.projectPath() != null && !isProjectOpen(s.projectPath()))
                .toList();
        if (!blocked.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (AiSession s : blocked) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(s.name());
            }
            JOptionPane.showMessageDialog(this,
                    "The project for the following sessions is not open:\n" + names + "\n\nOpen the project(s) first.",
                    "Project not open", JOptionPane.WARNING_MESSAGE);
            if (blocked.size() == selected.size()) {
                return;
            }
        }
        List<AiSession> toOpen = selected.stream()
                .filter(s -> s.projectPath() == null || isProjectOpen(s.projectPath()))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
        dispose();
        toOpen.forEach(this::openSession);
    }

    private void onDelete() {
        List<AiSession> selected = sessionList.getSelectedValuesList();
        if (selected.isEmpty()) {
            return;
        }
        String message = selected.size() == 1
                ? "Delete \"" + selected.get(0).name() + "\" and its history permanently?"
                : "Delete these " + selected.size() + " sessions and their history permanently?";
        int confirm = JOptionPane.showConfirmDialog(this, message,
                selected.size() == 1 ? "Delete Session" : "Delete Sessions",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        for (AiSession s : selected) {
            String deletedId = s.id();
            try {
                spm.delete(deletedId);
            }
            catch (IOException e) {
                LOG.log(Level.WARNING, "Could not delete session " + deletedId, e);
            }
            for (TopComponent tc : new ArrayList<>(TopComponent.getRegistry().getOpened())) {
                if (tc instanceof AiTopComponent ctc && deletedId.equals(ctc.getSession().id())) {
                    ctc.closeWithoutPrompt();
                    break;
                }
            }
        }
        loadSessions();
    }

    private void openSession(AiSession session) {
        SwingUtilities.invokeLater(() -> {
            for (TopComponent existing : TopComponent.getRegistry().getOpened()) {
                if (existing instanceof AiTopComponent ctc
                        && session.id().equals(ctc.getSession().id())) {
                    existing.requestActive();
                    return;
                }
            }
            AiTopComponent tc = new AiTopComponent(session, spm);
            Mode outputMode = WindowManager.getDefault().findMode("output");
            if (outputMode != null) {
                outputMode.dockInto(tc);
            }
            tc.open();
            tc.requestActive();
        });
    }

    private static class SessionCellRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof AiSession s) {
                String project = s.projectPath() != null
                        ? Path.of(s.projectPath()).getFileName().toString()
                        : "—";
                String typeTag = "<font color='#888888'>[" + s.aiType().displayName() + "]</font>";
                String desc = s.description() != null && !s.description().isBlank()
                        ? " — " + escapeHtml(s.description().substring(0, Math.min(s.description().length(), 40)))
                        : "";
                setText("<html><b>" + escapeHtml(s.name()) + "</b>" + desc + "  " + typeTag + "  <font color='gray'>[" + escapeHtml(project) + "]</font></html>");
                boolean open = s.projectPath() == null || isProjectOpen(s.projectPath());
                if (!open && !isSelected) {
                    setForeground(UIManager.getColor("Label.disabledForeground"));
                }
                setEnabled(open);
            }
            return this;
        }
    }
}
