package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolPropertyEnum;
import org.netbeans.modules.refactoring.api.Problem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the {@code commitWithWarning} problem-handling logic added to {@code RefactoringProvider}:
 * walking the full {@link Problem} chain, classifying fatal vs. warning, and deciding whether to block. This logic is
 * pure — it only ever constructs and reads real {@link Problem} objects, which have a public constructor, so none of
 * it needs a live NetBeans project. The engine-driving half ({@code runRefactoringInternal} actually calling
 * preCheck/prepare/doRefactoring) still does, and has the same test gap the single-file success path already has.
 * <p>
 * {@code flattenProblems}, {@code blockedMessageOrNull}, {@code buildProblemSuffix} and {@code RefactoringRunResult}
 * are package-private specifically so this test can call them directly — see the test-seam comment above them in
 * {@code RefactoringProvider}.
 */
class RefactoringProviderProblemHandlingTest {

    private static Problem chain(Problem... problems) {
        for (int i = 0; i < problems.length - 1; i++) {
            problems[i].setNext(problems[i + 1]);
        }
        return problems[0];
    }

    // ---- flattenProblems: the whole chain, not just the head ----

    @Test
    void flattenProblemsWalksTheWholeChainNotJustTheHead() {
        Problem head = chain(
                new Problem(false, "first"),
                new Problem(true, "second"),
                new Problem(false, "third"));

        List<Problem> flat = RefactoringProvider.flattenProblems(head);

        assertEquals(3, flat.size(), "all three problems in the chain must be collected, not just the head");
        assertEquals("first", flat.get(0).getMessage());
        assertEquals("second", flat.get(1).getMessage());
        assertEquals("third", flat.get(2).getMessage());
    }

    @Test
    void flattenProblemsOfNullIsEmptyNotNull() {
        assertTrue(RefactoringProvider.flattenProblems(null).isEmpty());
    }

    // ---- blockedMessageOrNull: fatal always blocks, regardless of the flag ----

    @Test
    void fatalProblemBlocksEvenWithCommitWithWarningTrue() {
        List<Problem> problems = RefactoringProvider.flattenProblems(new Problem(true, "cannot be fixed"));

        String blocked = RefactoringProvider.blockedMessageOrNull(problems, true);

        assertTrue(blocked != null, "a fatal problem must block even when commitWithWarning is true");
        assertTrue(blocked.contains("cannot be fixed"));
    }

    @Test
    void fatalRefusalDoesNotMentionTheFlag() {
        // The flag has no effect on a fatal problem, so suggesting it here would invite a retry that cannot work —
        // exactly the misleading-advice case this feature is otherwise trying to remove from tool messages.
        List<Problem> problems = RefactoringProvider.flattenProblems(new Problem(true, "broken"));

        String blocked = RefactoringProvider.blockedMessageOrNull(problems, false);

        assertFalse(blocked.contains(McpToolPropertyEnum.COMMIT_WITH_WARNING.key()),
                "a fatal refusal must not mention the flag: " + blocked);
    }

    // ---- blockedMessageOrNull: non-fatal problems are the flag's whole reason to exist ----

    @Test
    void nonFatalProblemBlocksWhenCommitWithWarningIsFalse() {
        List<Problem> problems = RefactoringProvider.flattenProblems(new Problem(false, "just advice"));

        String blocked = RefactoringProvider.blockedMessageOrNull(problems, false);

        assertTrue(blocked != null, "a non-fatal problem must still block by default");
        assertTrue(blocked.contains("just advice"));
    }

    @Test
    void nonFatalProblemProceedsWhenCommitWithWarningIsTrue() {
        List<Problem> problems = RefactoringProvider.flattenProblems(new Problem(false, "just advice"));

        String blocked = RefactoringProvider.blockedMessageOrNull(problems, true);

        assertNull(blocked, "commitWithWarning=true must let a non-fatal-only batch of problems proceed");
    }

    @Test
    void noProblemsAtAllNeverBlocksRegardlessOfTheFlag() {
        assertNull(RefactoringProvider.blockedMessageOrNull(List.of(), false));
        assertNull(RefactoringProvider.blockedMessageOrNull(List.of(), true));
    }

    @Test
    void warningsOnlyRefusalNamesTheFlagAndHowToProceed() {
        List<Problem> problems = RefactoringProvider.flattenProblems(new Problem(false, "advice"));

        String blocked = RefactoringProvider.blockedMessageOrNull(problems, false);

        assertTrue(blocked.contains(McpToolPropertyEnum.COMMIT_WITH_WARNING.key()),
                "a warnings-only refusal must name the flag by its enum key: " + blocked);
        assertTrue(blocked.toLowerCase().contains("true"),
                "it must say the flag can be set to true to proceed: " + blocked);
    }

    // ---- mixed fatal + non-fatal: still ALL reported, and the fatal framing wins ----

    @Test
    void mixedFatalAndNonFatalReportsBothAndDoesNotMentionTheFlag() {
        List<Problem> problems = RefactoringProvider.flattenProblems(
                chain(new Problem(false, "minor warning"), new Problem(true, "major fault")));

        String blocked = RefactoringProvider.blockedMessageOrNull(problems, true);

        assertTrue(blocked != null, "any fatal problem blocks even when other problems in the same batch are not");
        assertTrue(blocked.contains("minor warning"), "the non-fatal problem must still be reported: " + blocked);
        assertTrue(blocked.contains("major fault"), "the fatal problem must be reported: " + blocked);
        assertFalse(blocked.contains(McpToolPropertyEnum.COMMIT_WITH_WARNING.key()),
                "a batch containing any fatal problem is not a warnings-only refusal: " + blocked);
    }

    @Test
    void eachProblemLineIsTaggedWithItsOwnSeverity() {
        List<Problem> problems = RefactoringProvider.flattenProblems(
                chain(new Problem(true, "fault"), new Problem(false, "warning")));

        String blocked = RefactoringProvider.blockedMessageOrNull(problems, false);

        assertTrue(blocked.contains("[FATAL] fault"), blocked);
        assertTrue(blocked.contains("[WARNING] warning"), blocked);
    }

    // ---- buildProblemSuffix: warnings survive on a successful commit, never silently swallowed ----

    @Test
    void toleratedWarningsAreReportedInTheCommitSuffix() {
        List<Problem> tolerated = RefactoringProvider.flattenProblems(new Problem(false, "tolerated advice"));

        String suffix = RefactoringProvider.buildProblemSuffix(tolerated, List.of());

        assertTrue(suffix != null, "a tolerated warning must produce a suffix, never silently disappear");
        assertTrue(suffix.contains("tolerated advice"));
        assertTrue(suffix.contains(McpToolPropertyEnum.COMMIT_WITH_WARNING.key()),
                "the suffix should say these were tolerated BY the flag, for context: " + suffix);
    }

    @Test
    void postCommitProblemsAreReportedAsAlreadyAppliedNotAsBlocked() {
        List<Problem> postCommit = RefactoringProvider.flattenProblems(new Problem(true, "engine complained after writing"));

        String suffix = RefactoringProvider.buildProblemSuffix(List.of(), postCommit);

        assertTrue(suffix != null);
        assertTrue(suffix.contains("engine complained after writing"));
        assertTrue(suffix.toLowerCase().contains("already"),
                "a post-commit problem must read as already applied, never as nothing having happened: " + suffix);
    }

    @Test
    void cleanCommitWithNoProblemsAtAllGetsNoSuffix() {
        assertNull(RefactoringProvider.buildProblemSuffix(List.of(), List.of()));
    }

    // ---- the flag's own default ----

    @Test
    void commitWithWarningDefaultsToFalseWhenArgumentIsAbsent() {
        var args = new kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.ToolRequestArguments(new com.google.gson.JsonObject());

        assertFalse(args.bool(McpToolPropertyEnum.COMMIT_WITH_WARNING.key()),
                "an absent commitWithWarning must read as false, matching every other boolean flag in this codebase");
    }
}
