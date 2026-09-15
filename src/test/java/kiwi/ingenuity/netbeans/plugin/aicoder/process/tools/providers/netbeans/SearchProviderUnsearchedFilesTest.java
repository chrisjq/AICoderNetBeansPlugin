package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.providers.netbeans;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchProviderUnsearchedFilesTest {

    @Test
    void aFileUnderNestedRootsIsSearchedOnce(@TempDir Path tmp) throws IOException {
        // Live v1.4.15: an aggregator's project directory and its child module's src/main/java were both walked, so
        // every child file was reported once per enclosing root.
        Path childRoot = Files.createDirectories(tmp.resolve("child/src/main/java"));
        Path shared = Files.writeString(childRoot.resolve("Shared.java"), "class Shared {}");
        Path parentOnly = Files.writeString(tmp.resolve("Parent.java"), "class Parent {}");
        Set<Path> searched = new LinkedHashSet<>();

        assertEquals(List.of(shared, parentOnly), SearchProvider.unsearchedFiles(List.of(shared, parentOnly), searched));
        assertEquals(List.of(), SearchProvider.unsearchedFiles(List.of(shared), searched));
    }

    @Test
    void theSameFileReachedThroughADifferentSpellingIsStillSearchedOnce(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(Files.createDirectories(tmp.resolve("a")).resolve("X.java"), "class X {}");
        Set<Path> searched = new LinkedHashSet<>();

        assertEquals(1, SearchProvider.unsearchedFiles(List.of(file), searched).size());
        assertEquals(0, SearchProvider.unsearchedFiles(List.of(tmp.resolve("a/../a/X.java")), searched).size());
    }
}
