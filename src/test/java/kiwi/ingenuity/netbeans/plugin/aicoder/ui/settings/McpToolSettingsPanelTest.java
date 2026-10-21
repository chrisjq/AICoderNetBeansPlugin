package kiwi.ingenuity.netbeans.plugin.aicoder.ui.settings;

import java.awt.Component;
import java.awt.Container;
import java.time.Instant;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import kiwi.ingenuity.netbeans.plugin.aicoder.DatabaseAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.WebRequestAccessOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.McpTemplate;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolCategoryEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class McpToolSettingsPanelTest {

    @Test
    void groupConfigSettingsAreHiddenWhileTheGroupIsOffAndShownAgainWhenOn() throws Exception {
        McpToolSettingsPanel panel = createPanel();
        assertTrue(panel.areConfigSettingsVisibleFor(McpToolGroupEnum.WEB));

        SwingUtilities.invokeAndWait(() -> panel.applyConfig(McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.WEB, true)));
        assertFalse(panel.areConfigSettingsVisibleFor(McpToolGroupEnum.WEB));

        SwingUtilities.invokeAndWait(() -> panel.applyConfig(McpToolConfig.ALL_ENABLED));
        assertTrue(panel.areConfigSettingsVisibleFor(McpToolGroupEnum.WEB));
    }

    @Test
    void configValuesAreKeptWhileTheirGroupIsHidden() throws Exception {
        McpToolSettingsPanel panel = createPanel();
        AiSessionSettings settings = new AiSessionSettings();
        settings.setAllowWebRequests(true);
        settings.setAllowWebRequestAccess(WebRequestAccessOptionEnum.LOCALHOST, true);
        settings.setAllowDatabaseAccess(true);
        settings.setAllowDatabaseAccessOption(DatabaseAccessOptionEnum.SELECT, true);
        settings.setDatabaseRowLimit(123);
        SwingUtilities.invokeAndWait(() -> panel.load(settings));

        SwingUtilities.invokeAndWait(() -> panel.applyConfig(McpToolConfig.ALL_ENABLED
                .with(McpToolGroupEnum.WEB, true)
                .with(McpToolGroupEnum.DATABASE, true)));

        assertFalse(panel.areConfigSettingsVisibleFor(McpToolGroupEnum.WEB));
        assertFalse(panel.areConfigSettingsVisibleFor(McpToolGroupEnum.DATABASE));
        assertTrue(panel.isAllowWebRequestsSelected(), "the on/off value must survive hiding");
        assertTrue(panel.isWebOptionSelected(WebRequestAccessOptionEnum.LOCALHOST),
                   "a detailed option must survive hiding");
        assertTrue(panel.isDatabaseOptionSelected(DatabaseAccessOptionEnum.SELECT),
                   "a detailed database option must survive hiding");
        assertEquals(123, panel.getDatabaseRowLimit(), "the row limit must survive hiding");
    }

    @Test
    void gitConfigSettingsAreVisibleWhileEitherGitGroupIsOn() throws Exception {
        McpToolSettingsPanel panel = createPanel();

        SwingUtilities.invokeAndWait(() -> panel.applyConfig(McpToolConfig.ALL_ENABLED
                .with(McpToolGroupEnum.GIT_READ, true)
                .with(McpToolGroupEnum.GIT_WRITE, true)));
        assertFalse(panel.areConfigSettingsVisibleFor(McpToolGroupEnum.GIT_READ));
        assertFalse(panel.areConfigSettingsVisibleFor(McpToolGroupEnum.GIT_WRITE));

        SwingUtilities.invokeAndWait(() -> panel.applyConfig(McpToolConfig.ALL_ENABLED
                .with(McpToolGroupEnum.GIT_WRITE, true)));
        assertTrue(panel.areConfigSettingsVisibleFor(McpToolGroupEnum.GIT_READ),
                   "the shared git holder stays visible while either git group is on");
    }

    @Test
    void duplicateTemplateNamesHaveDistinctChoicesThatKeepTheirObjects() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        McpTemplate first = new McpTemplate("first", "Restricted",
                                            McpToolConfig.withDisabled(List.of(McpToolGroupEnum.WEB)), now, now);
        McpTemplate second = new McpTemplate("second", "Restricted",
                                             McpToolConfig.withDisabled(List.of(McpToolGroupEnum.DATABASE)), now, now);

        McpToolSettingsPanel.TemplateChoice[] choices = McpToolSettingsPanel.templateChoices(List.of(first, second));

        assertEquals("Restricted (1)", choices[0].toString());
        assertEquals("Restricted (2)", choices[1].toString());
        assertEquals(first, choices[0].template());
        assertEquals(second, choices[1].template());
    }

    @Test
    void templateChoicesSortDefaultFirstThenByNameSoDefaultIsPreselected() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        McpTemplate zeta = new McpTemplate("zeta", "Zeta", McpToolConfig.ALL_ENABLED, now, now);
        McpTemplate alpha = new McpTemplate("alpha", "Alpha", McpToolConfig.ALL_ENABLED, now, now);
        McpTemplate defaultTemplate = McpTemplate.createDefault(McpToolConfig.ALL_ENABLED);

        McpToolSettingsPanel.TemplateChoice[] choices
                = McpToolSettingsPanel.templateChoices(List.of(zeta, alpha, defaultTemplate));

        assertTrue(choices[0].template().isDefault(),
                   "the Default template must come first, because choices[0] is what the chooser preselects");
        assertEquals("Alpha", choices[1].toString());
        assertEquals("Zeta", choices[2].toString());
    }

    @Test
    void aDifferentTemplateDoesNotApplyWhenConfirmationIsDeclined() throws Exception {
        McpToolSettingsPanel panel = createPanel();
        McpToolConfig initial = panel.selectedConfig();
        McpToolSettingsPanel.TemplateChoice choice = templateChoice("Restricted",
                                                                    McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.WEB, true));

        McpToolSettingsPanel.templateApplyConfirmation = (parent, selected) -> false;
        try {
            SwingUtilities.invokeAndWait(() -> panel.applyTemplate(choice));

            assertEquals(initial, panel.selectedConfig());
        }
        finally {
            McpToolSettingsPanel.resetTemplateApplyConfirmationForTests();
        }
    }

    @Test
    void aDifferentTemplateAppliesWhenConfirmationIsAccepted() throws Exception {
        McpToolSettingsPanel panel = createPanel();
        McpToolConfig selected = McpToolConfig.ALL_ENABLED.with(McpToolGroupEnum.WEB, true);
        McpToolSettingsPanel.TemplateChoice choice = templateChoice("Restricted", selected);

        McpToolSettingsPanel.templateApplyConfirmation = (parent, template) -> true;
        try {
            SwingUtilities.invokeAndWait(() -> panel.applyTemplate(choice));

            assertEquals(selected, panel.selectedConfig());
        }
        finally {
            McpToolSettingsPanel.resetTemplateApplyConfirmationForTests();
        }
    }

    @Test
    void anEqualTemplateAppliesWithoutAskingForConfirmation() throws Exception {
        McpToolSettingsPanel panel = createPanel();
        McpToolSettingsPanel.TemplateChoice choice = templateChoice("Current", panel.selectedConfig());

        McpToolSettingsPanel.templateApplyConfirmation = (parent, template) -> {
            throw new AssertionError("equal config must not prompt");
        };
        try {
            SwingUtilities.invokeAndWait(() -> panel.applyTemplate(choice));

            assertEquals(McpToolConfig.ALL_ENABLED, panel.selectedConfig());
        }
        finally {
            McpToolSettingsPanel.resetTemplateApplyConfirmationForTests();
        }
    }

    private static McpToolSettingsPanel.TemplateChoice templateChoice(String name, McpToolConfig config) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new McpToolSettingsPanel.TemplateChoice(new McpTemplate(name.toLowerCase(), name, config, now, now), name);
    }

    @Test
    void alwaysOnGroupAndCategoryAreNotShownInThePanel() throws Exception {
        McpToolSettingsPanel panel = createPanel();

        assertFalse(containsText(panel, McpToolGroupEnum.ALWAYS_ON.label()), "the always-on group must not be shown");
        assertFalse(containsText(panel, McpToolCategoryEnum.ALWAYS_ON.label()),
                    "the always-on category must not be shown");
    }

    private static boolean containsText(Component root, String text) {
        if (root instanceof AbstractButton button && text.equals(button.getText())) {
            return true;
        }
        if (root instanceof JLabel label && text.equals(label.getText())) {
            return true;
        }
        if (root instanceof JComponent component && component.getBorder() instanceof TitledBorder border
                && text.equals(border.getTitle())) {
            return true;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsText(child, text)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static McpToolSettingsPanel createPanel() throws Exception {
        final McpToolSettingsPanel[] panel = new McpToolSettingsPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new McpToolSettingsPanel());
        return panel[0];
    }
}
