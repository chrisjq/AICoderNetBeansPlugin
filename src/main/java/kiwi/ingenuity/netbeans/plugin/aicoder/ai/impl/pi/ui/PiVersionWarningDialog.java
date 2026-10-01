package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import kiwi.ingenuity.netbeans.plugin.aicoder.Installer;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.pi.PiVersionCheck;
import kiwi.ingenuity.netbeans.plugin.aicoder.utils.BrowserUtil;

/**
 * "Is pi &lt;version&gt; working correctly with the AI Coder plugin?" dialog, opened from the info bar's
 * version-warning button and the Options → Pi tab's Verify… button, with the exact Yes / Unsure / No
 * behaviour.
 */
public final class PiVersionWarningDialog {

    public static void show(Component parent, PiVersionCheck check, Runnable onVerified, Runnable onMarkedNotWorking) {
        String version = check.installedVersion();
        String message = "Is pi " + version + " working correctly with the AI Coder plugin?";
        Object[] options = {"Yes, verified", "Unsure", "No"};
        int choice = JOptionPane.showOptionDialog(parent, message, "Verify pi version",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        switch (choice) {
            case 0 ->
                onVerified.run();
            case 2 -> {
                onMarkedNotWorking.run();
                showNotWorkingFollowUp(parent, check);
            }
            default -> {
            }
        }
    }

    private static void showNotWorkingFollowUp(Component parent, PiVersionCheck check) {
        String version = check.installedVersion();
        String issueUrl = bugReportUrl();

        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(new JLabel("<html>Please file a bug: pi " + version
                             + " was marked as not working with this plugin.</html>"), BorderLayout.NORTH);

        JButton linkButton = new JButton(issueUrl.isBlank() ? "Report a bug" : issueUrl);
        linkButton.addActionListener(e -> {
            if (!issueUrl.isBlank()) {
                BrowserUtil.openUrl(issueUrl);
            }
        });
        panel.add(linkButton, BorderLayout.CENTER);

        JButton copyButton = new JButton("Copy");
        copyButton.addActionListener(e -> {
            String toCopy = issueUrl.isBlank() ? ("pi " + version) : issueUrl;
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(toCopy), null);
        });
        panel.add(copyButton, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(parent, panel, "Please file a bug", JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Built from {@link Installer#HOMEPAGE}, same "never null, blank on failure" idiom as
     * {@code Installer.releaseUrl()}. Package-private so the dialog's tests can verify the exact URL shape
     * without opening a real dialog.
     */
    static String bugReportUrl() {
        String home = Installer.HOMEPAGE;
        if (home == null || home.isBlank()) {
            return "";
        }
        String base = home.endsWith("/") ? home.substring(0, home.length() - 1) : home;
        return base + "/issues/new";
    }

    private PiVersionWarningDialog() {
    }
}
