package kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile;

import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFileDirEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TmpMarkerExpander;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile.TempFileRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Expansion of @tmp.&lt;filename&gt; markers to absolute paths. Every marker resolves only against the SUBMITTING
 * session's own temp directory, treating the marker name as hostile input throughout: malformed, traversing, or
 * cross-session names are left exactly as written rather than expanded.
 */
class TmpMarkerExpanderTest {

    private static AiSession session(String id) {
        return new AiSession(id, "T", null, AiTypeEnum.CLAUDE, null, null,
                Instant.now(), Instant.now());
    }

    /**
     * The expansion TmpMarkerExpander emits for {@code file}, as a REAL path.
     * <p>
     * The expander resolves symlinks deliberately — its containment check compares {@code candidate.toRealPath()}
     * against the session tmp dir's real path — so the expected text must resolve them too. Comparing against the
     * unresolved {@code @TempDir} path passes on Linux and FAILS on macOS, where JUnit's temp directory lives under
     * {@code /var/folders/...} and {@code /var} is a symlink to {@code /private/var}. Asserting the unresolved path
     * would be asserting the platform, not the behaviour.
     */
    private static String expansionOf(Path file) throws IOException {
        return "@" + file.toRealPath();
    }

    @TempDir
    Path root; // stands in for ~/.ai-coder

    @BeforeEach
    void isolate() {
        TempFileRegistry.resetForTests();
        TempFileRegistry.overrideBasePath = root;
    }

    @AfterEach
    void tearDown() {
        TempFileRegistry.resetForTests();
    }

    @Test
    void expand_resolvesMarkerToAbsolutePath() throws IOException {
        AiSession s = session("ses-a");
        Path file = TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png").path();
        String marker = "@tmp." + file.getFileName();

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand("look at " + marker, s);

        assertEquals("look at " + expansionOf(file), result.expandedText());
        assertTrue(result.missingFiles().isEmpty());
    }

    /**
     * THE REGRESSION THIS TASK RISKED. Pasted images now land in {@code tmp/pasted_images/} rather than the tmp root.
     * The marker carries no directory, so an expander that only looked in the root would report every pasted image as
     * missing, tell the user "Could not find pasted file", and send the raw marker to the agent — image pasting broken
     * outright by what looked like a one-line change.
     */
    @Test
    void expand_resolvesMarkerForFileInASubdirectory() throws IOException {
        AiSession s = session("ses-a");
        Path file = TempFileRegistry.createTempFile(s.id(), TempFileDirEnum.PASTED_IMAGES.dirName(),
                "ai-coder-paste", ".png").path();
        String marker = "@tmp." + file.getFileName();

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand("look at " + marker, s);

        assertEquals("look at " + expansionOf(file), result.expandedText());
        assertTrue(result.missingFiles().isEmpty(), "a file in an enumerated subdirectory must resolve");
    }

    /**
     * The containment guard is the traversal defence, so confirm rather than assume it still passes a file one level
     * down: a subdirectory of the tmp root still startsWith the root, so a legitimate spooled file is not silently
     * refused as an escape.
     */
    @Test
    void expand_subdirectoryFileStillSatisfiesTheContainmentGuard() throws IOException {
        AiSession s = session("ses-a");
        Path file = TempFileRegistry.createTempFile(s.id(), TempFileDirEnum.TOOL_RESULTS.dirName(),
                "git-diff", ".log").path();

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand("@tmp." + file.getFileName(), s);

        assertEquals(expansionOf(file), result.expandedText(),
                "containment must accept a subdirectory, not reject it as an escape");
        assertTrue(result.missingFiles().isEmpty());
    }

    /**
     * Root-first, so anything minted before the subdirectories existed still resolves — including a marker left unsent
     * in the input box across an upgrade.
     */
    @Test
    void expand_resolvesMarkerForFileStillInTheTmpRoot() throws IOException {
        AiSession s = session("ses-a");
        Path file = TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png").path();

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand("@tmp." + file.getFileName(), s);

        assertEquals(expansionOf(file), result.expandedText(), "a file still in the root must keep resolving");
        assertTrue(result.missingFiles().isEmpty());
    }

    /**
     * DUPLICATE NAMES ACROSS DIRECTORIES: the root wins.
     *
     * <p>
     * The marker cannot distinguish them, so the tie has to break somewhere and it must be deterministic. Root first is
     * the compatible choice; among subdirectories the order is alphabetical by directory name rather than enum
     * declaration order, so reordering the enum — a harmless-looking edit — cannot silently change which file a marker
     * resolves to.</p>
     */
    @Test
    void expand_sameNameInRootAndSubdirectory_rootWins() throws IOException {
        AiSession s = session("ses-a");
        Path inRoot = TempFileRegistry.createTempFile(s, "dup", ".png").path();
        String name = inRoot.getFileName().toString();
        Path subDir = inRoot.getParent().resolve(TempFileDirEnum.PASTED_IMAGES.dirName());
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve(name), "shadow");

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand("@tmp." + name, s);

        assertEquals(expansionOf(inRoot), result.expandedText(), "the root copy must win");
    }

    /**
     * And among subdirectories the winner is the alphabetically-first directory name, independent of the order the enum
     * happens to declare them in.
     */
    @Test
    void expand_sameNameInTwoSubdirectories_alphabeticallyFirstDirWins() throws IOException {
        AiSession s = session("ses-a");
        Path seed = TempFileRegistry.createTempFile(s, "dup", ".png").path();
        String name = seed.getFileName().toString();
        Path tmpRoot = seed.getParent();
        Files.delete(seed); // force the search past the root

        List<String> dirs = Arrays.stream(TempFileDirEnum.values())
                .map(TempFileDirEnum::dirName).sorted().toList();
        for (String dir : dirs) {
            Path d = tmpRoot.resolve(dir);
            Files.createDirectories(d);
            Files.writeString(d.resolve(name), dir);
        }

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand("@tmp." + name, s);

        assertEquals(expansionOf(tmpRoot.resolve(dirs.get(0)).resolve(name)), result.expandedText(),
                "the alphabetically-first directory must win, regardless of enum declaration order");
    }

    @Test
    void expand_leavesOriginalTextUnaffected_forDisplayAndHistory() {
        // TmpMarkerExpander never mutates its input — the caller (AiTopComponent) keeps
        // the original `text` for conversationPanel.addUserMessage/history and only
        // sends Result.expandedText() to the agent. This pins that expand() returns a
        // NEW string rather than something that could be mistaken for the same one.
        AiSession s = session("ses-a");
        Path file = TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png").path();
        String original = "see @tmp." + file.getFileName();

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(original, s);

        assertEquals("see @tmp." + file.getFileName(), original, "input string must be untouched");
        assertFalse(result.expandedText().equals(original), "expansion must actually change the text sent to the agent");
    }

    @Test
    void expand_multipleMarkersInOneMessage_allExpand() throws IOException {
        AiSession s = session("ses-a");
        Path first = TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png").path();
        Path second = TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png").path();

        String text = "@tmp." + first.getFileName() + " and @tmp." + second.getFileName();
        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, s);

        assertEquals(expansionOf(first) + " and " + expansionOf(second), result.expandedText());
    }

    @Test
    void expand_markerMidSentence_expands() throws IOException {
        AiSession s = session("ses-a");
        Path file = TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png").path();

        String text = "Please look at @tmp." + file.getFileName() + " and tell me what you see.";
        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, s);

        assertEquals("Please look at " + expansionOf(file) + " and tell me what you see.",
                result.expandedText());
    }

    @Test
    void expand_preExistingAbsoluteMarker_isUntouched() {
        AiSession s = session("ses-a");
        String text = "@/home/user/project/Foo.java please review";

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, s);

        assertEquals(text, result.expandedText());
        assertTrue(result.missingFiles().isEmpty());
    }

    @Test
    void expand_proseNotFollowedByValidFilenameChars_isNotMangled() {
        AiSession s = session("ses-a");
        String text = "the @tmp. directory fills up over time";

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, s);

        assertEquals(text, result.expandedText());
        assertTrue(result.missingFiles().isEmpty());
    }

    @Test
    void expand_traversalWithSeparatorInName_refusedAndLeftLiteral() {
        AiSession s = session("ses-a");
        TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png"); // ensure the session's tmp dir exists

        // The regex's character class excludes '/', so this never even matches as a
        // marker attempt beyond "@tmp." — everything after the slash is plain text.
        String text = "@tmp./../../etc/passwd";
        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, s);

        assertEquals(text, result.expandedText());
        assertTrue(result.missingFiles().isEmpty());
    }

    @Test
    void expand_traversalViaDoubleDotInName_refusedAndLeftLiteral() {
        AiSession s = session("ses-a");
        TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png"); // ensure the session's tmp dir exists
        String text = "@tmp.notes..txt";

        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, s);

        assertEquals(text, result.expandedText());
        assertTrue(result.missingFiles().isEmpty(), "a rejected traversal attempt is not a 'missing file'");
    }

    @Test
    void expand_missingFile_leavesMarkerAndReportsIt() {
        AiSession s = session("ses-a");
        // Ensure the session's tmp dir exists, but the named file does not.
        TempFileRegistry.createTempFile(s, "ai-coder-paste", ".png");

        String text = "@tmp.already-deleted-file.png";
        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, s);

        assertEquals(text, result.expandedText());
        assertEquals(List.of("already-deleted-file.png"), result.missingFiles());
    }

    @Test
    void expand_noTmpDirAtAllForSession_leavesMarkerAndReportsIt() {
        AiSession s = session("ses-never-pasted-anything");

        String text = "@tmp.some-file.png";
        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, s);

        assertEquals(text, result.expandedText());
        assertEquals(List.of("some-file.png"), result.missingFiles());
    }

    @Test
    void expand_markerNamingAnotherSessionsFile_doesNotExpand() {
        AiSession sessionA = session("ses-a");
        AiSession sessionB = session("ses-b");
        Path bFile = TempFileRegistry.createTempFile(sessionB, "ai-coder-paste", ".png").path();
        // sessionA needs its own tmp dir to exist for this to be a meaningful test of
        // cross-session containment rather than the "no tmp dir at all" case.
        TempFileRegistry.createTempFile(sessionA, "ai-coder-paste", ".png");

        String text = "@tmp." + bFile.getFileName();
        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, sessionA);

        assertEquals(text, result.expandedText(), "session A must not resolve session B's temp file");
        assertEquals(List.of(bFile.getFileName().toString()), result.missingFiles());
    }

    @Test
    void expand_nullOrBlankInput_returnsUnchanged() {
        AiSession s = session("ses-a");
        assertEquals(null, TmpMarkerExpander.expand(null, s).expandedText());
        TmpMarkerExpander.Result empty = TmpMarkerExpander.expand("", s);
        assertEquals("", empty.expandedText());
        assertTrue(empty.missingFiles().isEmpty());
    }

    @Test
    void expand_nullSession_leavesTextUnchanged() {
        String text = "@tmp.whatever.png";
        TmpMarkerExpander.Result result = TmpMarkerExpander.expand(text, null);
        assertEquals(text, result.expandedText());
        assertTrue(result.missingFiles().isEmpty());
    }
}
