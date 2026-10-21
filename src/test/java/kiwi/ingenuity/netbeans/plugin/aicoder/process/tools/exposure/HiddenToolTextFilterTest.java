package kiwi.ingenuity.netbeans.plugin.aicoder.process.tools.exposure;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.process.McpToolEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.Test;

class HiddenToolTextFilterTest {

    private static final Set<McpToolEnum> SEARCH = EnumSet.of(McpToolEnum.SEARCH_IN_FILES);

    private static String filter(String text, McpToolEnum... hidden) {
        return HiddenToolTextFilter.omitSentencesReferencing(text, EnumSet.copyOf(List.of(hidden)));
    }

    @Test
    void removesOnlyTheReferencingSentence() {
        assertEquals("Then edit the file.",
                     HiddenToolTextFilter.omitSentencesReferencing("Use SearchInFiles to find text. Then edit the file.", SEARCH));
    }

    @Test
    void leavesOtherSentencesOnTheSameLineVerbatim() {
        assertEquals("First. Last.", filter("First. Use FindUsages here. Last.", McpToolEnum.FIND_USAGES));
        assertEquals("First.  Last.", filter("First.  Use FindUsages here.  Last.", McpToolEnum.FIND_USAGES),
                     "the separator kept sentences carry must not be normalised");
    }

    @Test
    void droppingTheLastSentenceTrimsTheSeparatorBeforeIt() {
        assertEquals("Keep this.", filter("Keep this. Drop SearchInFiles.", McpToolEnum.SEARCH_IN_FILES));
    }

    @Test
    void dropsALineThatBecomesEmpty() {
        assertEquals("A line.\nB line.\n",
                     filter("A line.\nSearchInFiles -> INSTEAD OF Bash grep\nB line.\n", McpToolEnum.SEARCH_IN_FILES));
    }

    @Test
    void doesNotReflowOrReindentRemainingLinesAndKeepsCrlf() {
        assertEquals("  indented keep\r\n  other\r\n",
                     filter("  indented keep\r\nDrop FindFile now\r\n  other\r\n", McpToolEnum.FIND_FILE));
    }

    @Test
    void blankLinesAreKept() {
        assertEquals("a\n\nb", filter("a\n\nUse FindFile.\nb", McpToolEnum.FIND_FILE));
    }

    @Test
    void droppingTheUnterminatedLastLineTakesItsTerminatorToo() {
        assertEquals("Keep.", filter("Keep.\nDrop FindFile", McpToolEnum.FIND_FILE));
    }

    @Test
    void matchesWholeWordsOnly() {
        assertEquals("CleanAndBuildProject cleans.",
                     filter("BuildProject builds.\nCleanAndBuildProject cleans.", McpToolEnum.BUILD_PROJECT));
        assertEquals("BuildProjects is not the tool.",
                     filter("BuildProjects is not the tool.", McpToolEnum.BUILD_PROJECT));
    }

    @Test
    void matchesNextToPunctuation() {
        assertEquals("Keep.", filter("See (FindFile), then go. Keep.", McpToolEnum.FIND_FILE));
        assertEquals("Keep.", filter("Call FindFile. Keep.", McpToolEnum.FIND_FILE));
    }

    @Test
    void matchesAQualifiedToolName() {
        assertEquals("Keep.",
                     filter("Call mcp__aicoder-nb-ki-plugin__FindUsages first. Keep.", McpToolEnum.FIND_USAGES));
    }

    @Test
    void removesSentencesForEveryHiddenTool() {
        assertEquals("Neutral line.\nKeep. Also keep.",
                     filter("Neutral line.\nKeep. Use FindFile. Also keep. Try GitPush.\nOnly GitAdd here",
                            McpToolEnum.FIND_FILE, McpToolEnum.GIT_PUSH, McpToolEnum.GIT_ADD));
    }

    @Test
    void anAbbreviationDoesNotEndTheSentence() {
        assertEquals("Keep.", filter("Prefer this (e.g. FindUsages) over grep. Keep.", McpToolEnum.FIND_USAGES));
        assertEquals("Keep.", filter("Prefer this, i.e. FindUsages, over grep. Keep.", McpToolEnum.FIND_USAGES));
    }

    @Test
    void nullAndEmptyTextAreReturnedUnchanged() {
        assertNull(HiddenToolTextFilter.omitSentencesReferencing(null, SEARCH));
        assertEquals("", HiddenToolTextFilter.omitSentencesReferencing("", SEARCH));
    }

    @Test
    void anEmptyHiddenSetReturnsTheSameInstance() {
        String text = "Use SearchInFiles to find text.";
        assertSame(text, HiddenToolTextFilter.omitSentencesReferencing(text, EnumSet.noneOf(McpToolEnum.class)));
    }

    @Test
    void textWithNoReferenceIsReturnedIdentical() {
        String text = "  Plain text.\r\nWith several. Sentences here.\n\nAnd a gap.\n";
        assertSame(text, HiddenToolTextFilter.omitSentencesReferencing(text, SEARCH));
    }

    @Test
    void hiddenFromIsWhatTheVisibleMapLacks() {
        java.util.Map<McpToolEnum, String> all = new java.util.EnumMap<>(McpToolEnum.class);
        all.put(McpToolEnum.FIND_FILE, "a");
        all.put(McpToolEnum.GIT_PUSH, "b");
        all.put(McpToolEnum.GET_FILE_CONTENT, "c");
        java.util.Map<McpToolEnum, String> visible = new java.util.EnumMap<>(McpToolEnum.class);
        visible.put(McpToolEnum.GET_FILE_CONTENT, "c");

        assertEquals(EnumSet.of(McpToolEnum.FIND_FILE, McpToolEnum.GIT_PUSH),
                     HiddenToolTextFilter.hiddenFrom(all, visible));
        assertEquals(EnumSet.noneOf(McpToolEnum.class), HiddenToolTextFilter.hiddenFrom(all, all));
    }

    private static com.google.gson.JsonObject schemaWithReferences() {
        com.google.gson.JsonObject filePathProperty = new com.google.gson.JsonObject();
        filePathProperty.addProperty("type", "string");
        filePathProperty.addProperty("description", "A path. Call FindFile first to locate it.");
        com.google.gson.JsonObject descriptionProperty = new com.google.gson.JsonObject();
        descriptionProperty.addProperty("type", "string");
        descriptionProperty.addProperty("description", "Free text. Mention GitPush when relevant.");
        com.google.gson.JsonObject properties = new com.google.gson.JsonObject();
        properties.add("filePath", filePathProperty);
        properties.add("description", descriptionProperty);
        com.google.gson.JsonObject inputSchema = new com.google.gson.JsonObject();
        inputSchema.addProperty("type", "object");
        inputSchema.add("properties", properties);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("filePath");
        inputSchema.add("required", required);
        com.google.gson.JsonObject tool = new com.google.gson.JsonObject();
        tool.addProperty("name", "GetFileContent");
        tool.addProperty("description", "Reads a file. Prefer FindFile when unsure. Returns text.");
        tool.add("inputSchema", inputSchema);
        return tool;
    }

    @Test
    void schemaDescriptionsLoseSentencesNamingHiddenToolsAtEveryDepth() {
        com.google.gson.JsonObject filtered = HiddenToolTextFilter.omitHiddenReferencesInDescriptions(
                schemaWithReferences(), EnumSet.of(McpToolEnum.FIND_FILE, McpToolEnum.GIT_PUSH));

        assertEquals("Reads a file. Returns text.", filtered.get("description").getAsString());
        com.google.gson.JsonObject properties = filtered.getAsJsonObject("inputSchema").getAsJsonObject("properties");
        assertEquals("A path.", properties.getAsJsonObject("filePath").get("description").getAsString());
        // A PARAMETER that is itself called "description" is an object: it is descended into, not mistaken for text.
        assertEquals("Free text.", properties.getAsJsonObject("description").get("description").getAsString());
        assertEquals("string", properties.getAsJsonObject("description").get("type").getAsString());
        assertEquals("GetFileContent", filtered.get("name").getAsString(), "names are not descriptions");
        assertEquals("filePath", filtered.getAsJsonObject("inputSchema").getAsJsonArray("required").get(0).getAsString());
    }

    @Test
    void filteringASchemaNeverMutatesTheOriginal() {
        com.google.gson.JsonObject original = schemaWithReferences();
        String before = original.toString();

        HiddenToolTextFilter.omitHiddenReferencesInDescriptions(original, EnumSet.of(McpToolEnum.FIND_FILE));

        assertEquals(before, original.toString(), "a handler may hand out a schema it keeps");
    }

    @Test
    void aSchemaWithNothingHiddenComesBackEqual() {
        com.google.gson.JsonObject original = schemaWithReferences();

        assertEquals(original, HiddenToolTextFilter.omitHiddenReferencesInDescriptions(
                     original, EnumSet.noneOf(McpToolEnum.class)));
        assertEquals(original, HiddenToolTextFilter.omitHiddenReferencesInDescriptions(original, null));
    }
}
