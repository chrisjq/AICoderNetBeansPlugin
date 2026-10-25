package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SessionMemoryStoreTest {

    @TempDir
    Path root;

    private SessionMemoryStore store() {
        return new SessionMemoryStore(root.resolve("memory"));
    }

    private static void touch(Path file, String isoInstant) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse(isoInstant)));
    }

    @Test
    @Timeout(10)
    void aNoteIsSavedReadReplacedAndDeleted() {
        SessionMemoryStore store = store();

        assertEquals(SessionMemoryStore.SaveResult.CREATED, store.save("prefers-tabs", "Tabs, not spaces", "Use tabs.\nAlways."));
        Optional<SessionMemoryStore.Note> note = store.read("prefers-tabs");
        assertTrue(note.isPresent());
        assertEquals("prefers-tabs", note.get().name());
        assertEquals("Tabs, not spaces", note.get().description());
        assertEquals("Use tabs.\nAlways.", note.get().content());

        assertEquals(SessionMemoryStore.SaveResult.REPLACED, store.save("prefers-tabs", "Spaces now", "Use spaces."));
        assertEquals("Use spaces.", store.read("prefers-tabs").orElseThrow().content());
        assertEquals(1, store.list().size());

        assertTrue(store.delete("prefers-tabs"));
        assertFalse(store.delete("prefers-tabs"), "a second delete finds nothing");
        assertTrue(store.read("prefers-tabs").isEmpty());
    }

    @Test
    @Timeout(10)
    void theNoteFileCarriesTheHeaderAndLeavesNoTemporaryFileBehind() throws IOException {
        SessionMemoryStore store = store();
        store.save("a-note", "About the note", "Body text");

        String text = Files.readString(root.resolve("memory").resolve("a-note.md"), StandardCharsets.UTF_8);
        assertEquals("---\nname: a-note\ndescription: About the note\n---\nBody text", text);
        try (Stream<Path> files = Files.list(root.resolve("memory"))) {
            assertEquals(List.of("a-note.md"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    @Timeout(10)
    void aNameThatCouldLeaveTheFolderOrIsNotASlugIsRejected() {
        SessionMemoryStore store = store();
        for (String bad : new String[]{"../x", "A B", "Upper", "a/b", "a\\b", "-lead", "trail-", "double--hyphen", "", " ",
            "with space", "dot.md", "a".repeat(65)}) {
            assertFalse(SessionMemoryStore.isValidName(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> store.save(bad, "d", "c"), bad);
            assertThrows(IllegalArgumentException.class, () -> store.read(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> store.delete(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> store.save(null, "d", "c"));
        assertTrue(SessionMemoryStore.isValidName("a"));
        assertTrue(SessionMemoryStore.isValidName("prefers-tabs-2"));
        assertTrue(SessionMemoryStore.isValidName("a".repeat(64)));
        assertFalse(Files.exists(root.resolve("x.md")), "nothing was written outside the folder");
    }

    @Test
    @Timeout(10)
    void descriptionAndContentCapsAreEnforced() {
        SessionMemoryStore store = store();

        store.save("ok", "d".repeat(SessionMemoryStore.MAX_DESCRIPTION_LENGTH), "c".repeat(SessionMemoryStore.MAX_CONTENT_LENGTH));
        assertThrows(IllegalArgumentException.class,
                     () -> store.save("long-description", "d".repeat(SessionMemoryStore.MAX_DESCRIPTION_LENGTH + 1), "c"));
        assertThrows(IllegalArgumentException.class,
                     () -> store.save("long-content", "d", "c".repeat(SessionMemoryStore.MAX_CONTENT_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> store.save("no-description", "  ", "c"));
        assertThrows(IllegalArgumentException.class, () -> store.save("no-content", "d", " "));
        assertEquals(1, store.list().size(), "a rejected note leaves nothing behind");
    }

    @Test
    @Timeout(10)
    void aDescriptionIsKeptOnOneLine() {
        SessionMemoryStore store = store();
        store.save("multi", "first line\nsecond   line", "c");

        assertEquals("first line second line", store.read("multi").orElseThrow().description());
    }

    @Test
    @Timeout(10)
    void listIsNewestFirstAndSkipsMalformedFiles() throws IOException {
        SessionMemoryStore store = store();
        store.save("oldest", "first", "1");
        store.save("middle", "second", "2");
        store.save("newest", "third", "3");
        Path folder = root.resolve("memory");
        touch(folder.resolve("oldest.md"), "2026-10-01T00:00:00Z");
        touch(folder.resolve("middle.md"), "2026-10-02T00:00:00Z");
        touch(folder.resolve("newest.md"), "2026-10-03T00:00:00Z");
        Files.writeString(folder.resolve("no-header.md"), "just text");
        Files.writeString(folder.resolve("wrong-name.md"), "---\nname: other\ndescription: d\n---\nbody");
        Files.writeString(folder.resolve("unterminated.md"), "---\nname: unterminated\ndescription: d\nbody");
        Files.writeString(folder.resolve("no-description.md"), "---\nname: no-description\n---\nbody");
        Files.writeString(folder.resolve("notes.txt"), "not a note");

        List<String> names = store.list().stream().map(SessionMemoryStore.Note::name).toList();

        assertEquals(List.of("newest", "middle", "oldest"), names);
        assertTrue(store.read("no-header").isEmpty());
        assertTrue(store.read("wrong-name").isEmpty());
    }

    @Test
    @Timeout(10)
    void aMissingFolderListsNothingAndIsCreatedOnTheFirstSave() {
        SessionMemoryStore store = new SessionMemoryStore(root.resolve("not").resolve("there"));

        assertTrue(store.list().isEmpty());
        assertTrue(store.read("anything").isEmpty());
        assertFalse(store.delete("anything"));
        store.save("first", "d", "c");
        assertEquals(1, store.list().size());
    }

    @Test
    @Timeout(10)
    void contentThatLooksLikeAHeaderIsKeptIntact() {
        SessionMemoryStore store = store();
        String content = "---\nname: fake\ndescription: fake\n---\nstill the body";
        store.save("tricky", "real description", content);

        SessionMemoryStore.Note note = store.read("tricky").orElseThrow();
        assertEquals("real description", note.description());
        assertEquals(content, note.content());
    }

    @Test
    @Timeout(10)
    void windowsLineEndingsInAFileAreUnderstood() throws IOException {
        Path folder = Files.createDirectories(root.resolve("memory"));
        Files.writeString(folder.resolve("crlf.md"), "---\r\nname: crlf\r\ndescription: from windows\r\n---\r\nbody");

        SessionMemoryStore.Note note = store().read("crlf").orElseThrow();

        assertEquals("from windows", note.description());
        assertEquals("body", note.content());
    }
}
