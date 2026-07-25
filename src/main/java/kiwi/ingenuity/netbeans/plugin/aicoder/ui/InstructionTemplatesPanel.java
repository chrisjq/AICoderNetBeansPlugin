package kiwi.ingenuity.netbeans.plugin.aicoder.ui;

import java.awt.Dimension;
import java.io.IOException;
import java.util.List;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.SpecialInstructionTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.serialization.TemplatePersistenceManager;

public final class InstructionTemplatesPanel extends TemplatePanel<SpecialInstructionTemplate> {

    private static JTextArea createBodyArea() {
        JTextArea area = new JTextArea(8, 28);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setMinimumSize(new Dimension(0, 200));
        return area;
    }

    private final JTextArea body;

    public InstructionTemplatesPanel(TemplatePersistenceManager templates, Runnable onTemplateChanged) {
        this(createBodyArea(), templates, onTemplateChanged);
    }

    private static JScrollPane createBodyScrollPane(JTextArea body) {
        JScrollPane scrollPane = new JScrollPane(body);
        scrollPane.setMinimumSize(new Dimension(0, 200));
        scrollPane.setPreferredSize(new Dimension(0, 200));
        return scrollPane;
    }

    private InstructionTemplatesPanel(JTextArea body, TemplatePersistenceManager templates, Runnable onTemplateChanged) {
        super(createBodyScrollPane(body), templates, onTemplateChanged,
                new String[]{"Name", "Updated", "Created"},
                SpecialInstructionTemplate::name,
                SpecialInstructionTemplate::updatedAt,
                SpecialInstructionTemplate::createdAt);
        this.body = body;
        initPanel();
    }

    @Override
    protected List<SpecialInstructionTemplate> loadData() throws IOException {
        return templates.saveSpecialInstructionDefaultsIfEmpty();
    }

    @Override
    protected void saveData(SpecialInstructionTemplate old, String name) throws IOException {
        templates.save(old == null
                ? SpecialInstructionTemplate.create(name, body.getText().trim())
                : old.withNameAndBody(name, body.getText().trim()));
    }

    @Override
    protected void deleteData(SpecialInstructionTemplate value) throws IOException {
        templates.deleteSpecialInstructionTemplate(value.id());
    }

    @Override
    protected void loadEditor(SpecialInstructionTemplate value) {
        if (body != null && value != null) {
            body.setText(value.body());
        }
    }

    @Override
    protected void clearEditor() {
        super.clearEditor();
        if (body != null) {
            body.setText("");
        }
    }
}
