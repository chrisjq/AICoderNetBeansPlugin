package kiwi.ingenuity.netbeans.plugin.aicoder.ai.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.ConfirmEvent;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.events.PermissionDecision;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.NotificationUtil;

class ConfirmPanel extends JPanel {

    private static final Color BORDER_COLOR = new Color(0xff, 0xb8, 0x6c);

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * The question put to the user, as "Allow &lt;Tool&gt;: &lt;details&gt;".
     * <p>
     * The tool name matters here more than anywhere else in the UI: this panel is the approval point, and the verb is
     * the thing actually being approved. A shell confirm arrives with {@code displayText} set to the bare command —
     * "opencode --help 2>&1" — which on its own does not say whether the AI wants to RUN that or merely read a file of
     * that name. The outcome line already names the tool ("Execute: … — accepted"), so without this the question and
     * its answer were formatted differently and only the answer said what happened.
     * <p>
     * Pure so the wording can be tested without a running IDE, matching {@code AiTopComponent.buildConfirmLabel}.
     */
    static String buildConfirmPrompt(String toolName, String displayText) {
        String body = displayText != null && !displayText.isBlank()
                ? displayText.trim() : "(no details)";
        // The colon is always present, so an unnamed confirm still reads as a prompt rather than
        // running the words together — "Allow: rm -rf /tmp/scratch", not "Allow rm -rf ...".
        String label = NotificationUtil.toolNameLabel(toolName);
        return label.isEmpty() ? "Allow: " + body : "Allow " + label + body;
    }

    /**
     * The prompt for a multi-file change set: a header naming how many files, then one line per file in the order the
     * AI supplied. Every file is named individually rather than counted, because the count alone does not tell the user
     * what they would be approving.
     *
     * <p>
     * Pure so the wording can be tested without a running IDE, matching {@link #buildConfirmPrompt}.</p>
     */
    static String buildMultiConfirmPrompt(java.util.List<String> renderedPaths) {
        StringBuilder sb = new StringBuilder("Allow MultiEdit: ")
                .append(renderedPaths.size())
                .append(renderedPaths.size() == 1 ? " file" : " files");
        for (String p : renderedPaths) {
            sb.append('\n').append("  ").append(p);
        }
        return sb.toString();
    }

    private boolean responded = false;
    private final JButton yesBtn;
    private final JButton noBtn;

    ConfirmPanel(ConfirmEvent event) {
        this(buildConfirmPrompt(event.toolName(), event.displayText()), "Yes", "No", event.response());
    }

    /**
     * The same inline confirm item, driven by a bare response future and explicit button labels, so a flow that has no
     * {@link ConfirmEvent} can reuse this widget rather than growing a near-identical one. The multi-file review uses
     * it for its main-panel affordance: "Accept Diffs" starts stepping through the per-file diffs, "Reject" declines
     * the whole change set without opening any.
     */
    ConfirmPanel(String prompt, String acceptLabel, String rejectLabel,
            java.util.concurrent.CompletableFuture<PermissionDecision> response) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        // Align with the messages around us. Children are LEFT_ALIGNMENT below,
        // but without this the panel itself defaults to CENTER_ALIGNMENT and the
        // enclosing BoxLayout indents the whole panel relative to left-aligned
        // message bubbles. See also getMaximumSize().
        setAlignmentX(Component.LEFT_ALIGNMENT);
        // Tab should reach Yes/No, not stop on the prompt text first. The text
        // stays focusable so it can be clicked and copied.
        setFocusTraversalPolicyProvider(true);
        setFocusTraversalPolicy(new SkipTextFocusTraversalPolicy());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(4, 8, 6, 4)));

        WrappingHtmlLabel label = new WrappingHtmlLabel(prompt);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        add(label);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.setOpaque(false);
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        yesBtn = new JButton(acceptLabel);
        noBtn = new JButton(rejectLabel);
        yesBtn.addActionListener(e -> respond(response, true));
        noBtn.addActionListener(e -> respond(response, false));
        btnRow.add(yesBtn);
        btnRow.add(noBtn);
        add(btnRow);

        // Disable buttons when the future is resolved externally (timeout or Stop)
        response.whenComplete((d, ex) -> SwingUtilities.invokeLater(() -> {
            if (!responded) {
                responded = true;
                yesBtn.setEnabled(false);
                noBtn.setEnabled(false);
            }
        }));
    }

    @Override
    public Dimension getMaximumSize() {
        // Fill the available width. A vertical BoxLayout stretches a child up to
        // its maximum width only, and the default for this panel is its preferred
        // width — which leaves the prompt wrapping in a narrow column with empty
        // space beside it. Height stays natural so we do not absorb slack.
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private void respond(java.util.concurrent.CompletableFuture<PermissionDecision> response, boolean allow) {
        if (responded) {
            return;
        }
        responded = true;
        yesBtn.setEnabled(false);
        noBtn.setEnabled(false);
        response.complete(allow ? PermissionDecision.allowed() : PermissionDecision.denied(null));
    }
}
