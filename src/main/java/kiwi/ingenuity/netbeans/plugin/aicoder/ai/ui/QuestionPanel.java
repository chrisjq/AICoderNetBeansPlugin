package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.AskUserQuestionEvent;

/**
 * Renders an AskUserQuestion tool call inline in the conversation.
 * Single-select questions auto-submit; multi-select questions require a Submit
 * button.
 */
class QuestionPanel extends JPanel {

    private static final Color BORDER_COLOR = new Color(0x89, 0xb4, 0xfa);

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private final AskUserQuestionEvent event;

    /**
     * Per-question tracking
     */
    private final List<String> selectedLabels = new ArrayList<>();   // one slot per question
    private final List<List<JCheckBox>> checkBoxLists = new ArrayList<>();
    private final List<List<JButton>> buttonLists = new ArrayList<>();

    private int totalQuestions;
    private boolean submitted = false;

    QuestionPanel(AskUserQuestionEvent event) {
        this.event = event;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 8, 6, 4)));

        JsonArray questions = event.questions();
        totalQuestions = questions.size();
        boolean hasMultiSelect = false;

        for (int i = 0; i < totalQuestions; i++) {
            selectedLabels.add(null);
            checkBoxLists.add(new ArrayList<>());
            buttonLists.add(new ArrayList<>());

            JsonElement el = questions.get(i);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject q = el.getAsJsonObject();

            String questionText = q.has("question") ? q.get("question").getAsString() : "";
            String header = q.has("header") ? q.get("header").getAsString() : null;
            JsonArray options = q.has("options") && q.get("options").isJsonArray()
                    ? q.getAsJsonArray("options") : new JsonArray();
            boolean multiSelect = q.has("multiSelect") && q.get("multiSelect").getAsBoolean();
            if (multiSelect) {
                hasMultiSelect = true;
            }

            if (i > 0) {
                add(Box.createVerticalStrut(6));
            }

            // Question label
            String labelHtml = "<html><b>"
                    + (header != null ? "[" + esc(header) + "] " : "")
                    + esc(questionText) + "</b></html>";
            JLabel qLabel = new JLabel(labelHtml);
            qLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            qLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
            add(qLabel);

            if (multiSelect) {
                for (JsonElement optEl : options) {
                    if (!optEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject opt = optEl.getAsJsonObject();
                    String label = opt.has("label") ? opt.get("label").getAsString() : "";
                    String desc = opt.has("description") ? opt.get("description").getAsString() : "";
                    JCheckBox cb = new JCheckBox(label);
                    if (!desc.isEmpty()) {
                        cb.setToolTipText(desc);
                    }
                    cb.setOpaque(false);
                    cb.setAlignmentX(Component.LEFT_ALIGNMENT);
                    add(cb);
                    checkBoxLists.get(i).add(cb);
                }
            }
            else {
                JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
                btnRow.setOpaque(false);
                btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                final int qi = i;
                for (JsonElement optEl : options) {
                    if (!optEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject opt = optEl.getAsJsonObject();
                    String label = opt.has("label") ? opt.get("label").getAsString() : "";
                    String desc = opt.has("description") ? opt.get("description").getAsString() : "";
                    JButton btn = new JButton(label);
                    if (!desc.isEmpty()) {
                        btn.setToolTipText(desc);
                    }
                    btn.addActionListener(e -> onSingleSelect(qi, label, btn));
                    btnRow.add(btn);
                    buttonLists.get(qi).add(btn);
                }
                add(btnRow);
            }
        }

        // Show Submit button for multi-select or multi-question scenarios
        if (hasMultiSelect || totalQuestions > 1) {
            add(Box.createVerticalStrut(6));
            JPanel submitRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            submitRow.setOpaque(false);
            submitRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JButton submitBtn = new JButton("Submit");
            submitBtn.addActionListener(e -> submit());
            submitRow.add(submitBtn);
            add(submitRow);
        }
        // Single-question single-select: auto-submits on button click (handled in onSingleSelect)
    }

    private void onSingleSelect(int qi, String label, JButton clicked) {
        if (submitted) {
            return;
        }
        selectedLabels.set(qi, label);
        // Visual feedback: mark selected, dim others
        for (JButton b : buttonLists.get(qi)) {
            if (b == clicked) {
                b.setText("✓ " + label);
            }
            else {
                b.setEnabled(false);
            }
        }
        // Auto-submit for single question with no multi-select
        if (totalQuestions == 1) {
            doSubmit();
        }
    }

    private void submit() {
        if (submitted) {
            return;
        }
        doSubmit();
    }

    private void doSubmit() {
        submitted = true;
        String answer = buildAnswer();
        event.response().complete(answer);
        // Disable all interactive controls
        setAllEnabled(false);
    }

    private String buildAnswer() {
        JsonArray questions = event.questions();
        if (totalQuestions == 1) {
            if (!questions.get(0).isJsonObject()) {
                return "(error)";
            }
            return buildSingleAnswer(0, questions.get(0).getAsJsonObject());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < totalQuestions; i++) {
            JsonElement el = questions.get(i);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject q = el.getAsJsonObject();
            String header = q.has("header") ? q.get("header").getAsString()
                    : q.has("question") ? q.get("question").getAsString() : ("Q" + (i + 1));
            sb.append(header).append(": ").append(buildSingleAnswer(i, q)).append("\n");
        }
        return sb.toString().strip();
    }

    private String buildSingleAnswer(int qi, JsonObject q) {
        boolean multiSelect = q.has("multiSelect") && q.get("multiSelect").getAsBoolean();
        if (multiSelect) {
            JsonArray options = q.has("options") ? q.getAsJsonArray("options") : new JsonArray();
            Set<String> chosen = new LinkedHashSet<>();
            List<JCheckBox> cbs = checkBoxLists.get(qi);
            for (int j = 0; j < cbs.size() && j < options.size(); j++) {
                if (cbs.get(j).isSelected()) {
                    JsonObject opt = options.get(j).getAsJsonObject();
                    chosen.add(opt.has("label") ? opt.get("label").getAsString() : "");
                }
            }
            return chosen.isEmpty() ? "(none selected)" : String.join(", ", chosen);
        }
        String sel = selectedLabels.get(qi);
        return sel != null ? sel : "(no selection)";
    }

    private void setAllEnabled(boolean enabled) {
        for (List<JButton> btns : buttonLists) {
            for (JButton b : btns) {
                b.setEnabled(enabled);
            }
        }
        for (List<JCheckBox> cbs : checkBoxLists) {
            for (JCheckBox cb : cbs) {
                cb.setEnabled(enabled);
            }
        }
    }

}
