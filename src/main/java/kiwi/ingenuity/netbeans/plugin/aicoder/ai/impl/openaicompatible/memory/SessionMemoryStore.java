package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One session's long-term memory: a folder of small note files, one {@code <name>.md} per note, each starting with a
 * header block ({@code name} and {@code description}) followed by the note's content. The plugin builds the index from
 * the headers, so nothing else is kept. No Swing; safe to use from the turn thread and from a tool call at once.
 */
public final class SessionMemoryStore {

    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 200;
    public static final int MAX_CONTENT_LENGTH = 4000;

    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final String EXTENSION = ".md";
    private static final String FENCE = "---";
    private static final String NAME_KEY = "name:";
    private static final String DESCRIPTION_KEY = "description:";

    /**
     * One saved note.
     *
     * @param modified when the note was last written
     */
    public record Note(String name, String description, String content, Instant modified) {

    }

    /**
     * What a save did.
     */
    public enum SaveResult {
        CREATED,
        REPLACED
    }

    private final Path folder;

    /**
     * @param folder the memory folder; created on the first save
     */
    public SessionMemoryStore(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    /**
     * @return true when {@code name} is a kebab-case slug of at most {@value #MAX_NAME_LENGTH} characters
     */
    public static boolean isValidName(String name) {
        return name != null && name.length() <= MAX_NAME_LENGTH && SLUG.matcher(name).matches();
    }

    /**
     * Creates or replaces a note.
     *
     * @throws IllegalArgumentException when the name, description or content is not acceptable
     * @throws UncheckedIOException when the file cannot be written
     */
    public synchronized SaveResult save(String name, String description, String content) {
        requireName(name);
        String line = oneLine(description);
        if (line.isEmpty()) {
            throw new IllegalArgumentException("description is required (one short line)");
        }
        if (line.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description is too long: " + line.length() + " characters, the limit is "
                    + MAX_DESCRIPTION_LENGTH);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content is too long: " + content.length() + " characters, the limit is "
                    + MAX_CONTENT_LENGTH + ". Save one fact per note.");
        }
        Path target = fileOf(name);
        boolean existed = Files.isRegularFile(target);
        String text = FENCE + "\n" + NAME_KEY + " " + name + "\n" + DESCRIPTION_KEY + " " + line + "\n" + FENCE + "\n"
                + content;
        try {
            Files.createDirectories(folder);
            Path temp = Files.createTempFile(folder, ".note-", ".tmp");
            try {
                Files.writeString(temp, text, StandardCharsets.UTF_8);
                moveIntoPlace(temp, target);
            }
            finally {
                Files.deleteIfExists(temp);
            }
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Could not save the note: " + ex.getMessage(), ex);
        }
        return existed ? SaveResult.REPLACED : SaveResult.CREATED;
    }

    /**
     * @return the note, or empty when there is none by that name or its file is malformed
     *
     * @throws IllegalArgumentException when {@code name} is not a valid slug
     */
    public synchronized Optional<Note> read(String name) {
        requireName(name);
        return load(fileOf(name));
    }

    /**
     * @return true when a note was removed, false when there was none by that name
     *
     * @throws IllegalArgumentException when {@code name} is not a valid slug
     */
    public synchronized boolean delete(String name) {
        requireName(name);
        try {
            return Files.deleteIfExists(fileOf(name));
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Could not delete the note: " + ex.getMessage(), ex);
        }
    }

    /**
     * @return every readable note, most recently written first; malformed files are skipped
     */
    public synchronized List<Note> list() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Note> notes = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                    .forEach(path -> load(path).ifPresent(notes::add));
        }
        catch (IOException ex) {
            return List.of();
        }
        notes.sort(Comparator.comparing(Note::modified).reversed().thenComparing(Note::name));
        return List.copyOf(notes);
    }

    private static void requireName(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("name must be lowercase words joined by hyphens (letters and digits only), "
                    + "at most " + MAX_NAME_LENGTH + " characters, for example 'prefers-tabs'");
        }
    }

    private Path fileOf(String name) {
        return folder.resolve(name + EXTENSION);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Optional<Note> load(Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(EXTENSION) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String name = fileName.substring(0, fileName.length() - EXTENSION.length());
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            return parse(name, text, modified);
        }
        catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    static Optional<Note> parse(String expectedName, String text, Instant modified) {
        int position = 0;
        String first = lineAt(text, position);
        if (first == null || !FENCE.equals(first)) {
            return Optional.empty();
        }
        position += rawLength(text, position);
        String name = null;
        String description = null;
        while (true) {
            String line = lineAt(text, position);
            if (line == null) {
                return Optional.empty();
            }
            position += rawLength(text, position);
            if (FENCE.equals(line)) {
                break;
            }
            if (line.startsWith(NAME_KEY)) {
                name = line.substring(NAME_KEY.length()).trim();
            }
            else if (line.startsWith(DESCRIPTION_KEY)) {
                description = line.substring(DESCRIPTION_KEY.length()).trim();
            }
        }
        if (!expectedName.equals(name) || description == null || description.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Note(name, description, text.substring(position), modified));
    }

    /**
     * The line starting at {@code from} without its terminator, or null at the end of the text.
     */
    private static String lineAt(String text, int from) {
        if (from >= text.length()) {
            return null;
        }
        int end = text.indexOf('\n', from);
        String line = end < 0 ? text.substring(from) : text.substring(from, end);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * The length of the line starting at {@code from} including its terminator.
     */
    private static int rawLength(String text, int from) {
        int end = text.indexOf('\n', from);
        return end < 0 ? text.length() - from : end - from + 1;
    }
}
