package kiwi.ingenuity.netbeans.plugin.aicoder.process.server;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * A path the filesystem cannot represent must be DENIED by the scope check, not thrown out of it.
 * <p>
 * This layer runs before any tool handler, so an unguarded {@code Path.of} here escaped as a JSON-RPC
 * {@code -32603 Internal error} for EVERY tool taking a file path, not just the one being called. That is why an
 * earlier guard added inside {@code EditorContextProvider.getFileInfo} passed its own unit test while the live MCP
 * call kept failing — the throw happened upstream of the handler. A reviewer reproduced it three times before the
 * cause was found here.
 * <p>
 * Denying is correct on the merits rather than merely safe: a string the platform cannot turn into a path is not
 * inside any registered project directory.
 */
class FileScopeMalformedPathTest {

    /**
     * Assembled from a char at runtime. NEVER write the control character straight into this source file.
     * <p>
     * A raw one makes git classify the whole file as BINARY — no diffs, no reviewable history — and invites an editor
     * or formatter to drop it silently, which would leave this class green while testing nothing. A
     * {@code &#92;u0000} escape is no better, because Java resolves unicode escapes before lexing and it becomes the
     * same raw byte. Concatenating {@code (char) 0} keeps the file plain ASCII while the string stays byte-identical
     * at runtime.
     */
    private static final String NUL_PATH = "/tmp/project/file" + ((char) 0) + ".txt";

    /**
     * Establishes the premise instead of assuming it. If {@code Path.of} ever stopped rejecting this string, every
     * assertion below would pass for the wrong reason and this class would be silently vacuous.
     */
    @Test
    void fixtureCheck_thePathIsGenuinelyUnrepresentable() {
        assertThrows(InvalidPathException.class, () -> Path.of(NUL_PATH),
                "if this no longer throws, the rest of this class proves nothing");
    }

    @Test
    void isWithinProjectDirsDeniesAnUnrepresentablePathInsteadOfThrowing() {
        SessionFileScopeRegistry registry = new SessionFileScopeRegistry();
        registry.registerScope("s1", AiTypeEnum.CLAUDE, List.of(new File("/tmp/project")), true);

        boolean allowed = assertDoesNotThrow(() -> registry.isWithinProjectDirs("s1", NUL_PATH),
                "a malformed path must be refused by the gate, not escape as an internal error");

        assertFalse(allowed, "an unrepresentable path is inside no project directory");
    }

    @Test
    void isUnderAnyOpenProjectDeniesAnUnrepresentablePathInsteadOfThrowing() {
        SessionFileScopeRegistry registry = new SessionFileScopeRegistry();

        boolean allowed = assertDoesNotThrow(() -> registry.isUnderAnyOpenProject(NUL_PATH));

        assertFalse(allowed);
    }

    @Test
    void isOwnSessionConfigFileDeniesAnUnrepresentablePathInsteadOfThrowing() {
        SessionFileScopeRegistry registry = new SessionFileScopeRegistry();
        registry.registerScope("s1", AiTypeEnum.CLAUDE, List.of(), false);

        boolean allowed = assertDoesNotThrow(() -> registry.isOwnSessionConfigFile("s1", NUL_PATH));

        assertFalse(allowed);
    }

    /**
     * The negative control. Without it the assertions above would also pass if the gate refused EVERYTHING — which
     * would deny legitimate in-scope paths and break every file tool in the plugin.
     */
    @Test
    void anOrdinaryPathIsStillEvaluatedNormally() {
        SessionFileScopeRegistry registry = new SessionFileScopeRegistry();
        registry.registerScope("s1", AiTypeEnum.CLAUDE, List.of(new File("/tmp")), true);

        boolean allowed = assertDoesNotThrow(() -> registry.isWithinProjectDirs("s1", "/tmp/ordinary.txt"),
                "a well-formed path must still be evaluated, not swept up by the malformed-path guard");

        // Asserting the RESULT, not merely the absence of a throw. Without this the control passes under the very
        // failure it exists to catch — a guard that over-denies would still "not throw", and the blanket-refusal
        // regression this test is meant to block would sail through it.
        assertTrue(allowed, "an in-scope path must still be ALLOWED, not collaterally denied by the guard");
    }
}
