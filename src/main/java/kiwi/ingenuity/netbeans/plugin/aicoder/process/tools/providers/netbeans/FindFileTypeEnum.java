package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * What {@code FindFile} matches: regular files or directories.
 * <p>
 * Modelled on {@link GitStashActionEnum} so the accepted values are declared once instead of being repeated across the
 * schema text, the validation list, the "must be one of" error and the walk's own filter. An enum whose constants carry
 * their own predicate also removes the failure this codebase keeps meeting: a value accepted by validation but never
 * honoured downstream, which reports success while quietly doing something else.
 * <p>
 * Lives beside {@code FindFileProvider} rather than with the tool because tools depend on providers and never the
 * reverse.
 */
public enum FindFileTypeEnum {
    FILE("file", "Match regular files only", "file(s)", "files"),
    DIR("dir", "Match directories only, including empty ones", "directory(ies)", "directories");

    /**
     * Applied when the caller omits the type. Files are the overwhelmingly common request and were the tool's only
     * behaviour before directories were supported, so defaulting here keeps every existing caller working unchanged.
     */
    public static final FindFileTypeEnum DEFAULT = FILE;

    /**
     * Resolves a caller-supplied type, ignoring case and surrounding space.
     *
     * @param raw the incoming argument; may be null or blank
     * @return the matching constant, {@link #DEFAULT} when raw is null or blank, or null when raw is a non-empty value
     * that is not a type — callers must reject that rather than falling back to the default, or a misspelled
     * {@code dir} would silently search files instead and report a confident empty result.
     */
    public static FindFileTypeEnum from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String needle = raw.strip().toLowerCase(Locale.ROOT);
        for (FindFileTypeEnum type : values()) {
            if (type.type.equals(needle)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Comma-separated types, marking the default — for schema text and errors.
     */
    public static String typeList() {
        return Arrays.stream(values())
                .map(type -> type == DEFAULT ? type.type + " (default)" : type.type)
                .collect(Collectors.joining(", "));
    }

    private final String type;
    private final String description;
    private final String countedNoun;
    private final String pluralNoun;

    FindFileTypeEnum(String type, String description, String countedNoun, String pluralNoun) {
        this.type = type;
        this.description = description;
        this.countedNoun = countedNoun;
        this.pluralNoun = pluralNoun;
    }

    /**
     * Noun for the result header, e.g. "Found 3 directory(ies)". The result text has to follow the type, or a directory
     * search reporting "Found 3 file(s)" would read as a bug in the tool.
     */
    public String countedNoun() {
        return countedNoun;
    }

    /**
     * Noun for the no-results message, e.g. "No directories found matching: x".
     */
    public String pluralNoun() {
        return pluralNoun;
    }

    /**
     * Whether this path is the kind of thing the caller asked for. Directories are matched whether or not they contain
     * anything — an empty directory is still a directory the caller was looking for.
     */
    public boolean matches(Path path) {
        return this == DIR ? Files.isDirectory(path) : Files.isRegularFile(path);
    }

    public String type() {
        return type;
    }

    public String description() {
        return description;
    }

}
