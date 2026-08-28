package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GetFileInfo's MIME reporting.
 * <p>
 * The branch that matters most in practice — NetBeans resolving {@code text/x-java} through its MIMEResolver chain —
 * needs a running IDE and cannot be reached here, so it is verified live rather than faked with a stub FileObject. What
 * IS pinned here is the decision logic around it, which is where the defect would actually live: the
 * {@code content/unknown} sentinel, and the guarantee that the fallback never throws and never returns null.
 */
class EditorContextProviderMimeTypeTest {

    @TempDir
    Path tempDir;

    /**
     * The created-time EMISSION branch, pinned on every platform.
     * <p>
     * Reachable in production only on Windows and macOS, so on Linux the suite proved created time is absent but never
     * that it is emitted when it should be — deleting the whole block would have passed here. A reviewer confirmed that
     * gap explicitly rather than letting it be assumed. Testing the pure fragment closes it: both branches now run
     * wherever the suite runs.
     */
    @Test
    void createdSuffixIsEmittedWhenTheFilesystemSuppliesAValue() {
        String suffix = EditorContextProvider.createdSuffix(1_700_000_000_000L);

        assertTrue(suffix.startsWith(", created "),
                "a real birth time must be reported using the field's own separator: " + suffix);
        assertTrue(suffix.contains("20"), "the formatted date must actually appear: " + suffix);
    }

    /**
     * The WIRING, not just the decision.
     * <p>
     * Extracting {@code createdSuffix} pinned its body on every platform, but not the fact that {@code appendTimes}
     * actually calls it and appends the result — the capability was read from a static inside the method, so the
     * emitting branch stayed unreachable on Linux and deleting the whole block still passed here. A reviewer drew that
     * distinction precisely. Passing the flag in makes the call observable anywhere.
     */
    @Test
    void appendTimesActuallyInvokesCreatedSuffixWhenThePlatformSupportsIt() throws Exception {
        Path file = Files.writeString(tempDir.resolve("wiring.txt"), "x");
        long created = Files.readAttributes(file, BasicFileAttributes.class).creationTime().toMillis();
        Assumptions.assumeTrue(created > 0,
                "this filesystem supplied no creation time, so there is no emission to observe");

        StringBuilder sb = new StringBuilder();
        EditorContextProvider.appendTimes(sb, file, true);

        assertTrue(sb.toString().contains(", created "),
                "appendTimes must call createdSuffix and append its result: " + sb);
    }

    /**
     * The other half: with the capability off, appendTimes must emit no created field at all. Together these two pin
     * the branch that deleting the block would otherwise slip past on this platform.
     */
    @Test
    void appendTimesEmitsNoCreatedFieldWhenThePlatformLacksBirthTime() throws Exception {
        Path file = Files.writeString(tempDir.resolve("wiring-off.txt"), "x");

        StringBuilder sb = new StringBuilder();
        EditorContextProvider.appendTimes(sb, file, false);

        assertFalse(sb.toString().contains(", created "),
                "no created field may be emitted where the platform has no birth time: " + sb);
        assertTrue(sb.toString().contains(", modified "),
                "the modified time must still be reported — this proves the method ran at all: " + sb);
    }

    /**
     * The filesystem supplied nothing — FAT, a network volume, or a platform that simply has no birth time. Omitted
     * rather than explained, because absence is the expected state and a placeholder would appear on every result.
     * <p>
     * The PLATFORM half of that decision is pinned separately by
     * {@code appendTimesEmitsNoCreatedFieldWhenThePlatformLacksBirthTime} — the two questions live in different
     * methods precisely so each stays reachable in a test here.
     */
    @Test
    void createdSuffixIsEmptyWhenTheFilesystemSuppliesNoValue() {
        assertEquals("", EditorContextProvider.createdSuffix(0L));
        assertEquals("", EditorContextProvider.createdSuffix(-1L));
    }

    /**
     * The sentinel is the whole point. {@code getMIMEType()} returns {@code content/unknown} rather than null when it
     * cannot decide, so treating it as a real answer would both report it as the file's type AND suppress the
     * probeContentType fallback that might have identified the file correctly.
     */
    @Test
    void netBeansUnknownSentinelIsNotTreatedAsAnAnswer() {
        assertFalse(EditorContextProvider.isUsableMimeType("content/unknown"),
                "content/unknown is NetBeans saying it does not know, not a MIME type");
    }

    @Test
    void nullAndBlankAreNotAnswersEither() {
        assertFalse(EditorContextProvider.isUsableMimeType(null));
        assertFalse(EditorContextProvider.isUsableMimeType(""));
        assertFalse(EditorContextProvider.isUsableMimeType("   "));
    }

    /**
     * The negative control: without this, the assertions above would also pass if every type were rejected, which would
     * silently reduce the tool to always reporting the fallback.
     */
    @Test
    void aRealTypeIsAccepted() {
        assertTrue(EditorContextProvider.isUsableMimeType("text/x-java"),
                "the IDE's own type for a Java source file must be used as-is");
        assertTrue(EditorContextProvider.isUsableMimeType("text/plain"));
    }

    /**
     * With no FileObject the JDK fallback runs. Its exact answer is environment-dependent — on Linux
     * {@code probeContentType} relies on installed FileTypeDetectors and frequently returns null — so the contract
     * asserted here is the one the tool actually relies on: an answer always comes back, and it is never null.
     */
    @Test
    void fallbackAlwaysYieldsAValueForARealFile() throws Exception {
        Path file = Files.writeString(tempDir.resolve("notes.txt"), "hello");

        String type = EditorContextProvider.mimeType(null, file);

        assertNotNull(type, "a missing type must degrade to a value, never null");
        assertFalse(type.isBlank(), "a blank type would render as a dangling 'type ' in the result");
    }

    /**
     * A path that does not exist must not throw out of the tool. Nothing in GetFileInfo may fail the whole call because
     * one metadata field could not be resolved.
     */
    @Test
    void missingFileDegradesToUnknownRatherThanThrowing() {
        String type = EditorContextProvider.mimeType(null, tempDir.resolve("does-not-exist.qqq"));

        assertEquals("unknown", type,
                "an unresolvable type is reported as unknown, not raised as an error");
    }

    /**
     * An extension no detector recognises exercises the end of the chain: NetBeans absent, probe declines, and the tool
     * still has to say something.
     */
    @Test
    void unrecognisedExtensionStillReturnsAValue() throws Exception {
        Path file = Files.writeString(tempDir.resolve("data.zzzzz"), "x");

        String type = EditorContextProvider.mimeType(null, file);

        assertNotNull(type);
        assertFalse(type.isBlank());
    }
}
