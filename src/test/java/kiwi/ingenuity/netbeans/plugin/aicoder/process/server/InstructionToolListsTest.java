package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings.AiSessionSettings;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpInstructionOptionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpSectionEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.session.AbstractAiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolConfig;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure.McpToolGroupEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.AbstractActionTool;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.McpToolInterface;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * The sentences of the global header and the initialize stub that LIST several tools name only the tools a session can
 * see, and disappear when it can see none of them. With nothing hidden the original wording is unchanged — the
 * "unhidden" tests pin the exact original text, not merely agreement between two code paths.
 */
class InstructionToolListsTest {

    private static final Set<McpInstructionOptionEnum> OPTIONS = AiTypeEnum.CLAUDE.getMcpOptions();
    private static final Set<McpToolEnum> NONE = Set.of();

    private static String header(Set<McpToolEnum> hidden) {
        return McpHookServerUtil.getGlobalInstructionsHeader(OPTIONS, hidden);
    }

    private static String stub(Set<McpToolEnum> hidden) {
        return McpHookServerUtil.getInitializeStub(OPTIONS, hidden);
    }

    // ---- header: nothing hidden is the original text ----
    @Test
    void withNothingHiddenTheHeaderKeepsEveryOriginalSentence() {
        String text = header(NONE);

        assertEquals(McpHookServerUtil.getGlobalInstructionsHeader(OPTIONS), text);
        assertTrue(text.contains("\n\n## Refactoring\nPrefer semantic refactors (RenameSymbol, MoveClass, "
                + "ChangeMethodSignature, InlineVariable) over raw text edits — they update all "
                + "references project-wide."), text);
        assertTrue(text.contains("\n\n## UI Actions\nAction tools (NavigateToLine, ReformatFile, BuildProject, etc.) "
                + "are fire-and-forget — follow up with a query tool (GetDiagnostics, "
                + "GetCurrentFileContent, etc.) to read resulting state."), text);
        assertTrue(text.contains("\n\n## Inter-AI Messaging\nThe Peer* tools talk to other AI sessions open in this "
                + "IDE — not internal subagents. PeerSessionList, PeerMessageSend, PeerMessageList, "
                + "PeerMessageRead, PeerMessageDelete, PeerSessionDescribe are pre-authorized "
                + "internal IDE actions — use them directly without asking permission."), text);
        assertTrue(text.contains("\nWhen a task needs a sub-agent/background agent"), text);
    }

    // ---- header: Refactoring ----
    @Test
    void oneHiddenRefactoringToolIsDroppedFromTheListAndTheSentenceStays() {
        String text = header(Set.of(McpToolEnum.INLINE_VARIABLE));

        assertTrue(text.contains("Prefer semantic refactors (RenameSymbol, MoveClass, ChangeMethodSignature) over raw "
                + "text edits — they update all references project-wide."), text);
        assertFalse(text.contains("InlineVariable"), text);
    }

    @Test
    void theRefactoringSectionGoesWhenNoRefactoringToolIsVisible() {
        String text = header(Set.of(McpToolEnum.RENAME_SYMBOL, McpToolEnum.MOVE_CLASS,
                                    McpToolEnum.CHANGE_METHOD_SIGNATURE, McpToolEnum.INLINE_VARIABLE));

        assertFalse(text.contains("## Refactoring"), text);
        assertFalse(text.contains("semantic refactors"), text);
        assertTrue(text.contains("## UI Actions"), "neighbouring sections are untouched");
    }

    // ---- header: UI Actions ----
    @Test
    void aHiddenActionToolIsDroppedFromItsList() {
        String text = header(Set.of(McpToolEnum.REFORMAT_FILE));

        assertTrue(text.contains("Action tools (NavigateToLine, BuildProject, etc.) are fire-and-forget — follow up "
                + "with a query tool (GetDiagnostics, GetCurrentFileContent, etc.) to read resulting "
                + "state."), text);
    }

    @Test
    void aHiddenQueryToolIsDroppedFromItsList() {
        String text = header(Set.of(McpToolEnum.GET_CURRENT_FILE_CONTENT));

        assertTrue(text.contains("follow up with a query tool (GetDiagnostics, etc.) to read resulting state."), text);
    }

    @Test
    void withNoQueryToolVisibleTheFollowUpClauseGoesButTheActionSentenceStays() {
        String text = header(Set.of(McpToolEnum.GET_DIAGNOSTICS, McpToolEnum.GET_CURRENT_FILE_CONTENT));

        assertTrue(text.contains("Action tools (NavigateToLine, ReformatFile, BuildProject, etc.) are "
                + "fire-and-forget."), text);
        assertFalse(text.contains("follow up"), text);
    }

    @Test
    void theUiActionsSectionGoesWhenNoActionToolIsVisible() {
        String text = header(Set.of(McpToolEnum.NAVIGATE_TO_LINE, McpToolEnum.REFORMAT_FILE,
                                    McpToolEnum.BUILD_PROJECT));

        assertFalse(text.contains("## UI Actions"), text);
        assertFalse(text.contains("fire-and-forget"), text);
    }

    // ---- header: Inter-AI messaging ----
    @Test
    void oneHiddenPeerToolIsDroppedFromTheListAndTheVerbStaysPlural() {
        String text = header(Set.of(McpToolEnum.PEER_MESSAGE_DELETE));

        assertTrue(text.contains("PeerSessionList, PeerMessageSend, PeerMessageList, PeerMessageRead, "
                + "PeerSessionDescribe are pre-authorized internal IDE actions"), text);
    }

    @Test
    void aSingleRemainingPeerToolReadsAsSingular() {
        String text = header(Set.of(McpToolEnum.PEER_SESSION_LIST, McpToolEnum.PEER_MESSAGE_SEND,
                                    McpToolEnum.PEER_MESSAGE_READ, McpToolEnum.PEER_MESSAGE_DELETE,
                                    McpToolEnum.PEER_SESSION_DESCRIBE));

        assertTrue(text.contains("PeerMessageList is a pre-authorized internal IDE action — use it directly without "
                + "asking permission."), text);
    }

    @Test
    void theMessagingSectionAndTheSubAgentAdviceGoWhenNoPeerToolIsVisible() {
        String text = header(Set.of(McpToolEnum.PEER_SESSION_LIST, McpToolEnum.PEER_MESSAGE_SEND,
                                    McpToolEnum.PEER_MESSAGE_LIST, McpToolEnum.PEER_MESSAGE_READ,
                                    McpToolEnum.PEER_MESSAGE_DELETE, McpToolEnum.PEER_SESSION_DESCRIBE));

        assertFalse(text.contains("Inter-AI Messaging"), text);
        assertFalse(text.contains("Peer*"), text);
        assertFalse(text.contains("sub-agent"), "the advice names PeerSessionList/PeerMessageSend: " + text);
        assertTrue(text.contains("## MCP Tool Errors"), "unrelated sections remain");
    }

    @Test
    void hidingEitherToolTheSubAgentAdviceNamesRemovesThatAdviceOnly() {
        String text = header(Set.of(McpToolEnum.PEER_MESSAGE_SEND));

        assertFalse(text.contains("sub-agent"), text);
        assertTrue(text.contains("## Inter-AI Messaging"), "the other peer tools are still listed");
    }

    // ---- initialize stub: nothing hidden is the original text ----
    @Test
    void withNothingHiddenTheStubKeepsEveryOriginalLine() {
        String text = stub(NONE);

        assertEquals(McpHookServerUtil.getInitializeStub(OPTIONS), text);
        assertTrue(text.contains("- Build/test: BuildMavenProject (default: mvn package -DskipTests, options "
                + "override this) / RunMavenTests (default: mvn test) — or the Gradle/Ant "
                + "equivalents, or BuildProject (fire-and-forget, no options) — NOT Bash mvn\n"), text);
        assertTrue(text.contains("- Read/search: GetFileContent / SearchInFiles / SearchSymbols — NOT the Read tool "
                + "or Bash grep/rg\n"), text);
        assertTrue(text.contains("- Edit: ApplyEdit / WriteFile (or Edit/Write) — these route through the "
                + "Accept/Reject diff panel; NEVER edit project files with Bash\n"), text);
        assertTrue(text.contains("- Git: GetGitStatus / GetGitDiff / GitAdd / GitCommit — NOT Bash git\n"), text);
    }

    // ---- stub: Read/search, Edit, Git ----
    @Test
    void aHiddenToolIsDroppedFromTheReadSearchLine() {
        assertTrue(stub(Set.of(McpToolEnum.SEARCH_IN_FILES)).contains(
                "- Read/search: GetFileContent / SearchSymbols — NOT the Read tool or Bash grep/rg\n"));
    }

    @Test
    void theReadSearchLineGoesWhenNoneOfItsToolsIsVisible() {
        String text = stub(Set.of(McpToolEnum.GET_FILE_CONTENT, McpToolEnum.SEARCH_IN_FILES,
                                  McpToolEnum.SEARCH_SYMBOLS));

        assertFalse(text.contains("- Read/search:"), text);
        assertTrue(text.contains("- Git:"), "other lines are untouched");
    }

    @Test
    void aHiddenToolIsDroppedFromTheEditLineAndTheRestOfTheLineStays() {
        assertTrue(stub(Set.of(McpToolEnum.WRITE_FILE)).contains(
                "- Edit: ApplyEdit (or Edit/Write) — these route through the Accept/Reject diff panel; NEVER edit "
                + "project files with Bash\n"));
    }

    @Test
    void theEditLineGoesWhenNoEditToolIsVisible() {
        assertFalse(stub(Set.of(McpToolEnum.APPLY_EDIT, McpToolEnum.WRITE_FILE)).contains("- Edit:"));
    }

    @Test
    void aHiddenToolIsDroppedFromTheGitLine() {
        assertTrue(stub(Set.of(McpToolEnum.GIT_COMMIT)).contains(
                "- Git: GetGitStatus / GetGitDiff / GitAdd — NOT Bash git\n"));
    }

    @Test
    void theGitLineGoesWhenNoGitToolIsVisible() {
        assertFalse(stub(Set.of(McpToolEnum.GET_GIT_STATUS, McpToolEnum.GET_GIT_DIFF, McpToolEnum.GIT_ADD,
                                McpToolEnum.GIT_COMMIT)).contains("- Git:"));
    }

    // ---- stub: Build/test ----
    @Test
    void oneHiddenMavenToolLeavesTheOtherAndTheAlternatives() {
        assertTrue(stub(Set.of(McpToolEnum.BUILD_MAVEN_PROJECT)).contains(
                "- Build/test: RunMavenTests (default: mvn test) — or the Gradle/Ant equivalents, or BuildProject "
                + "(fire-and-forget, no options) — NOT Bash mvn\n"));
    }

    @Test
    void withNoMavenToolVisibleTheAlternativesLeadTheLine() {
        assertTrue(stub(Set.of(McpToolEnum.BUILD_MAVEN_PROJECT, McpToolEnum.RUN_MAVEN_TESTS)).contains(
                "- Build/test: the Gradle/Ant equivalents, or BuildProject (fire-and-forget, no options) — NOT Bash "
                + "mvn\n"));
    }

    @Test
    void theGradleAntPhraseGoesOnlyWhenEveryGradleAndAntToolIsHidden() {
        Set<McpToolEnum> someGradleAnt = Set.of(McpToolEnum.BUILD_GRADLE_PROJECT, McpToolEnum.RUN_ANT_TESTS);
        assertTrue(stub(someGradleAnt).contains("or the Gradle/Ant equivalents"), "other Gradle/Ant tools remain");

        Set<McpToolEnum> allGradleAnt = Set.of(McpToolEnum.BUILD_GRADLE_PROJECT,
                                               McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT, McpToolEnum.RUN_GRADLE_TESTS,
                                               McpToolEnum.BUILD_ANT_PROJECT,
                                               McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT, McpToolEnum.RUN_ANT_TESTS);
        assertTrue(stub(allGradleAnt).contains(
                "- Build/test: BuildMavenProject (default: mvn package -DskipTests, options override this) / "
                + "RunMavenTests (default: mvn test) — or BuildProject (fire-and-forget, no options) — NOT Bash mvn\n"));
    }

    @Test
    void theGenericBuildToolIsDroppedWhenHidden() {
        assertTrue(stub(Set.of(McpToolEnum.BUILD_PROJECT)).contains(
                "RunMavenTests (default: mvn test) — or the Gradle/Ant equivalents — NOT Bash mvn\n"));
    }

    @Test
    void theBuildTestLineGoesWhenNothingItNamesIsVisible() {
        Set<McpToolEnum> everyBuildTool = Set.of(McpToolEnum.BUILD_MAVEN_PROJECT, McpToolEnum.RUN_MAVEN_TESTS,
                                                 McpToolEnum.BUILD_GRADLE_PROJECT,
                                                 McpToolEnum.CLEAN_AND_BUILD_GRADLE_PROJECT,
                                                 McpToolEnum.RUN_GRADLE_TESTS, McpToolEnum.BUILD_ANT_PROJECT,
                                                 McpToolEnum.CLEAN_AND_BUILD_ANT_PROJECT, McpToolEnum.RUN_ANT_TESTS,
                                                 McpToolEnum.BUILD_PROJECT);

        String text = stub(everyBuildTool);

        assertFalse(text.contains("- Build/test:"), text);
        assertTrue(text.contains("- Read/search:"), "other lines are untouched");
    }

    // ---- initialize stub: the opening "It exposes …" sentence ----
    private static final String FULL_OPENING = "It exposes a full set of tools for working in the live IDE: file edits "
            + "applied through the NetBeans Accept/Reject diff panel, semantic refactors "
            + "(rename/move/inline/change-signature), build & test, full git, "
            + "project-wide search, and inter-AI messaging.";

    private static Set<McpToolEnum> allHiddenExcept(McpToolEnum... visible) {
        Set<McpToolEnum> hidden = java.util.EnumSet.allOf(McpToolEnum.class);
        hidden.remove(McpToolEnum.GET_INSTRUCTIONS);
        for (McpToolEnum tool : visible) {
            hidden.remove(tool);
        }
        return hidden;
    }

    @Test
    void withNothingHiddenTheOpeningSentenceIsTheOriginalOne() {
        assertTrue(stub(NONE).contains(FULL_OPENING), stub(NONE));
        assertTrue(McpHookServerUtil.getInitializeStub(
                Set.of(McpInstructionOptionEnum.HEADER, McpInstructionOptionEnum.ONLY_MCP_TOOL_ACCESS), NONE)
                .contains("It exposes a full set of tools for working in the live IDE: file edits, semantic refactors, "
                        + "build & test, full git, project-wide search, and inter-AI messaging."));
    }

    @Test
    void aFamilyWithNoVisibleToolDropsItsClauseAndTheSentenceStopsClaimingAFullSet() {
        Set<McpToolEnum> refactoringHidden = Set.of(McpToolEnum.RENAME_SYMBOL, McpToolEnum.MOVE_CLASS,
                                                    McpToolEnum.INLINE_VARIABLE, McpToolEnum.CHANGE_METHOD_SIGNATURE);

        assertTrue(stub(refactoringHidden).contains(
                "It exposes a set of tools for working in the live IDE: file edits applied through the NetBeans "
                + "Accept/Reject diff panel, build & test, full git, project-wide search, and inter-AI messaging."));
    }

    @Test
    void oneVisibleToolKeepsItsFamilysClause() {
        Set<McpToolEnum> allButOneRefactoring = Set.of(McpToolEnum.RENAME_SYMBOL, McpToolEnum.MOVE_CLASS,
                                                       McpToolEnum.INLINE_VARIABLE);

        assertTrue(stub(allButOneRefactoring).contains(FULL_OPENING), "ChangeMethodSignature is still visible");
    }

    @Test
    void theLastClauseDroppingKeepsTheListGrammatical() {
        Set<McpToolEnum> peersHidden = Set.of(McpToolEnum.PEER_SESSION_LIST, McpToolEnum.PEER_MESSAGE_SEND,
                                              McpToolEnum.PEER_MESSAGE_LIST, McpToolEnum.PEER_MESSAGE_READ,
                                              McpToolEnum.PEER_MESSAGE_DELETE, McpToolEnum.PEER_SESSION_DESCRIBE);

        assertTrue(stub(peersHidden).contains(
                "It exposes a set of tools for working in the live IDE: file edits applied through the NetBeans "
                + "Accept/Reject diff panel, semantic refactors (rename/move/inline/change-signature), build & test, "
                + "full git, and project-wide search."));
    }

    @Test
    void twoClausesReadAsAAndB() {
        String text = stub(allHiddenExcept(McpToolEnum.APPLY_EDIT, McpToolEnum.SEARCH_IN_FILES));

        assertTrue(text.contains("It exposes a set of tools for working in the live IDE: file edits applied through the "
                + "NetBeans Accept/Reject diff panel and project-wide search."), text);
    }

    @Test
    void oneClauseStandsAlone() {
        String text = stub(allHiddenExcept(McpToolEnum.PEER_MESSAGE_LIST));

        assertTrue(text.contains("It exposes a set of tools for working in the live IDE: inter-AI messaging."), text);
    }

    @Test
    void withNoFamilyVisibleTheSentenceDoesNotListAnything() {
        String text = stub(allHiddenExcept());

        assertTrue(text.contains("It exposes a set of tools for working in the live IDE."), text);
        assertFalse(text.contains("working in the live IDE:"), text);
    }

    // ---- GetInstructions path ----
    private static final class StubTool extends AbstractActionTool {

        StubTool(McpToolEnum tool) {
            super(McpSectionEnum.PLUGIN, tool.toolName(), "stub", tool.toolName() + " -> stub line");
        }

        @Override
        public boolean isMutating() {
            return false;
        }

        @Override
        public String handle(ToolRequestArguments args, AbstractAiSession session) {
            return "ran";
        }
    }

    private static Map<McpToolEnum, McpToolInterface> peerHandlers() {
        Map<McpToolEnum, McpToolInterface> handlers = new EnumMap<>(McpToolEnum.class);
        for (McpToolEnum tool : new McpToolEnum[]{McpToolEnum.GET_INSTRUCTIONS, McpToolEnum.PEER_SESSION_LIST,
            McpToolEnum.PEER_MESSAGE_SEND, McpToolEnum.PEER_MESSAGE_LIST, McpToolEnum.PEER_MESSAGE_READ,
            McpToolEnum.PEER_MESSAGE_DELETE, McpToolEnum.PEER_SESSION_DESCRIBE}) {
            handlers.put(tool, new StubTool(tool));
        }
        return handlers;
    }

    private static AiSessionSettings settings(McpToolConfig config) {
        AiSessionSettings settings = new AiSessionSettings(null, null, true, null, true, null, null, null);
        settings.setMcpToolConfig(config);
        return settings;
    }

    @Test
    void aSessionWithPeerMessagingOffGetsNoMessagingSectionAnywhereInItsGuide() {
        String text = McpInstructionRegistry.buildFullInstructions(AiTypeEnum.CLAUDE, peerHandlers(),
                                                                   settings(McpToolConfig.withDisabled(Set.of(McpToolGroupEnum.PEER_MESSAGING))));

        assertFalse(text.contains("Inter-AI Messaging"), text);
        assertFalse(text.contains("PeerMessageSend"), text);
        assertFalse(text.contains("sub-agent"), text);
        assertTrue(text.contains("## Refactoring"), "tools outside the handler map are not treated as hidden");
        assertTrue(text.contains("## Policy"), "the rest of the header is intact");
    }

    @Test
    void aSessionWithEverythingOnKeepsTheOriginalMessagingSectionInItsGuide() {
        String text = McpInstructionRegistry.buildFullInstructions(AiTypeEnum.CLAUDE, peerHandlers(),
                                                                   settings(McpToolConfig.ALL_ENABLED));

        assertTrue(text.contains("PeerSessionList, PeerMessageSend, PeerMessageList, PeerMessageRead, "
                + "PeerMessageDelete, PeerSessionDescribe are pre-authorized internal IDE actions"),
                   text);
        assertTrue(text.contains("sub-agent"), text);
    }
}
