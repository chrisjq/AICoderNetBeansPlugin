package kiwi.ingenuity.netbeans.plugin.aicoder.ai;

import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The text injected into every turn must not point the model at tools its session cannot use: the inter-AI blurb and
 * the plugin edit tools are only named while the MCP groups behind them are on.
 */
class ContextProviderToolExposureTest {

    private static final String EDIT_TOOLS_CLAUSE = "tools (or the plugin's ApplyEdit/WriteFile) — NEVER with Bash (sed, echo, ";
    private static final String EDIT_TOOLS_CLAUSE_WITHOUT_PLUGIN_TOOLS = "tools — NEVER with Bash (sed, echo, ";

    private static AiSession session(McpToolGroupEnum... disabled) {
        AiSession session = AiSession.create(null, AiTypeEnum.CLAUDE);
        session.settings().setAllowInterAiComms(Boolean.TRUE);
        session.settings().setMcpToolConfig(McpToolConfig.withDisabled(Set.of(disabled)));
        return session;
    }

    private static ContextProvider providerFor(AiSession session) {
        ContextProvider provider = new ContextProvider(fo -> {
        });
        provider.setSession(session);
        return provider;
    }

    @Test
    void theInterAiBlurbIsPresentWhileTheBehaviourSettingAndThePeerGroupAreOn() {
        String identity = providerFor(session()).buildIdentityBlock();

        assertTrue(identity.contains("inter-AI tools"), identity);
        assertTrue(identity.contains("PeerMessageSend"), identity);
    }

    @Test
    void theInterAiBlurbIsAbsentWhenThePeerGroupIsSwitchedOffEvenWithCommsOn() {
        AiSession session = session(McpToolGroupEnum.PEER_MESSAGING);
        assertTrue(session.settings().effectiveAllowInterAiComms(), "the behaviour setting itself is still on");

        String identity = providerFor(session).buildIdentityBlock();

        assertFalse(identity.contains("inter-AI"), identity);
        assertFalse(identity.contains("PeerMessageSend"), identity);
        assertFalse(identity.contains("PeerSessionList"), identity);
    }

    @Test
    void theInterAiBlurbIsAbsentWhenCommsIsOffEvenWithThePeerGroupOn() {
        AiSession session = session();
        session.settings().setAllowInterAiComms(Boolean.FALSE);

        assertFalse(providerFor(session).buildIdentityBlock().contains("inter-AI"));
    }

    @Test
    void theBaselineNamesThePluginEditToolsWhileFileEditingIsExposed() {
        ContextProvider provider = providerFor(session());

        assertTrue(provider.buildProjectBaseline().contains(EDIT_TOOLS_CLAUSE), "unchanged when nothing is hidden");
        assertTrue(provider.buildPreamble("hi", null).contains(EDIT_TOOLS_CLAUSE));
    }

    @Test
    void theBaselineStillNamesThemForAContextProviderWithNoSession() {
        ContextProvider provider = new ContextProvider(fo -> {
        });

        assertTrue(provider.buildProjectBaseline().contains(EDIT_TOOLS_CLAUSE));
    }

    @Test
    void theBaselineDoesNotNameApplyEditOrWriteFileWhenFileEditingIsSwitchedOff() {
        ContextProvider provider = providerFor(session(McpToolGroupEnum.FILE_EDITING));

        String baseline = provider.buildProjectBaseline();
        assertFalse(baseline.contains("ApplyEdit"), baseline);
        assertFalse(baseline.contains("WriteFile"), baseline);
        assertTrue(baseline.contains(EDIT_TOOLS_CLAUSE_WITHOUT_PLUGIN_TOOLS), baseline);

        String preamble = provider.buildPreamble("hi", null);
        assertFalse(preamble.contains("ApplyEdit"), preamble);
        assertFalse(preamble.contains("WriteFile"), preamble);
    }
}
