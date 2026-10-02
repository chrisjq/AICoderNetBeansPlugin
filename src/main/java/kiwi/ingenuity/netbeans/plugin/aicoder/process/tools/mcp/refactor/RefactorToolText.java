package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.mcp.refactor;

/**
 * Shared AI-facing text for the refactoring tools (ChangeMethodSignature, InlineVariable, MoveClass,
 * RenameSymbol, MoveFile), so the wording can't drift between them.
 */
public final class RefactorToolText {

    public static final String COMMIT_WITH_WARNING_DESCRIPTION
                               = "Apply the refactoring even if it reports only non-fatal warnings. Default false.";

    private RefactorToolText() {
    }
}
