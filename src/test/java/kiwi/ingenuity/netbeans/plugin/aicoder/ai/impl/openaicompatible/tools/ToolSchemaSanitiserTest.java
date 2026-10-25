package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.settings.OpenAiCompatiblePluginSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ToolSchemaSanitiserTest {

    private static final int DEPTH_LIMIT = 2;

    @Test
    @Timeout(5)
    void limitsFromPluginSettingsReadTheConfiguredCaps() {
        int depth = OpenAiCompatiblePluginSettings.getSchemaDepth();
        int description = OpenAiCompatiblePluginSettings.getToolDescriptionCapChars();
        int parameter = OpenAiCompatiblePluginSettings.getParameterDescriptionCapChars();
        try {
            OpenAiCompatiblePluginSettings.setSchemaDepth(3);
            OpenAiCompatiblePluginSettings.setToolDescriptionCapChars(40);
            OpenAiCompatiblePluginSettings.setParameterDescriptionCapChars(20);

            assertEquals(new ToolSchemaSanitiser.Limits(3, 40, 20), ToolSchemaSanitiser.Limits.fromPluginSettings());
        }
        finally {
            OpenAiCompatiblePluginSettings.setSchemaDepth(depth);
            OpenAiCompatiblePluginSettings.setToolDescriptionCapChars(description);
            OpenAiCompatiblePluginSettings.setParameterDescriptionCapChars(parameter);
        }
    }

    @Test
    @Timeout(5)
    void removesStrictSchemaAndAdditionalPropertiesFalse() {
        JsonObject function = json("""
            {"name":"t","description":"d","strict":true,
             "parameters":{"type":"object","$schema":"http://json-schema.org/draft-07/schema#",
               "additionalProperties":false,
               "properties":{"a":{"type":"string","additionalProperties":false}}}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 1024, 256);

        assertFalse(out.has("strict"));
        assertFalse(out.has("$schema"));
        assertFalse(out.getAsJsonObject("parameters").has("$schema"));
        assertFalse(out.getAsJsonObject("parameters").has("additionalProperties"));
        assertFalse(out.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("a").has("additionalProperties"));
    }

    @Test
    @Timeout(5)
    void nestingDeeperThanTheLimitBecomesObjectPlaceholder() {
        JsonObject function = json("""
            {"name":"t","description":"d","parameters":{"type":"object","properties":{
               "a":{"type":"object","properties":{
                 "b":{"type":"object","properties":{
                   "c":{"type":"string"}}}}}}}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 1024, 256);

        JsonObject c = out.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("a").getAsJsonObject("properties")
                .getAsJsonObject("b").getAsJsonObject("properties")
                .getAsJsonObject("c");
        assertEquals(json("{\"type\":\"object\"}"), c);
    }

    @Test
    @Timeout(5)
    void anyOfBeyondTheLimitKeepsTheFirstOption() {
        JsonObject function = json("""
            {"name":"t","description":"d","parameters":{"type":"object","properties":{
               "a":{"type":"object","properties":{
                 "b":{"anyOf":[{"type":"string"},{"type":"number"}]}}}}}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 1024, 256);

        var kept = out.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("a").getAsJsonObject("properties")
                .getAsJsonObject("b").getAsJsonArray("anyOf");
        assertEquals(1, kept.size());
        assertEquals(json("{\"type\":\"string\"}"), kept.get(0).getAsJsonObject());
    }

    @Test
    @Timeout(5)
    void oneOfBeyondTheLimitKeepsTheFirstOption() {
        JsonObject function = json("""
            {"name":"t","description":"d","parameters":{"type":"object","properties":{
               "a":{"type":"object","properties":{
                 "b":{"oneOf":[{"type":"boolean"},{"type":"string"}]}}}}}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 1024, 256);

        var kept = out.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("a").getAsJsonObject("properties")
                .getAsJsonObject("b").getAsJsonArray("oneOf");
        assertEquals(1, kept.size());
        assertEquals(json("{\"type\":\"boolean\"}"), kept.get(0).getAsJsonObject());
    }

    @Test
    @Timeout(5)
    void anyOfWithinTheLimitKeepsEveryOption() {
        JsonObject function = json("""
            {"name":"t","description":"d","parameters":{"type":"object","properties":{
               "a":{"anyOf":[{"type":"string"},{"type":"number"}]}}}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 1024, 256);

        var kept = out.getAsJsonObject("parameters").getAsJsonObject("properties")
                .getAsJsonObject("a").getAsJsonArray("anyOf");
        assertEquals(2, kept.size());
    }

    @Test
    @Timeout(5)
    void descriptionCappedAtLastSentenceWithinTheCap() {
        JsonObject function = json("""
            {"name":"t","description":"First sentence. Second sentence is much longer than the cap.",
             "parameters":{"type":"object"}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 30, 256);

        assertEquals("First sentence.", out.get("description").getAsString());
    }

    @Test
    @Timeout(5)
    void descriptionCappedAtLastWholeWord() {
        JsonObject function = json("""
            {"name":"t","description":"alpha beta gamma delta","parameters":{"type":"object"}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 15, 256);

        assertEquals("alpha beta\u2026", out.get("description").getAsString());
    }

    @Test
    @Timeout(5)
    void descriptionHardCutWhenNoBoundary() {
        JsonObject function = json("""
            {"name":"t","description":"abcdefghij","parameters":{"type":"object"}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 4, 256);

        assertEquals("abcd\u2026", out.get("description").getAsString());
    }

    @Test
    @Timeout(5)
    void parameterDescriptionsUseTheParameterCap() {
        JsonObject function = json("""
            {"name":"t","description":"ok","parameters":{"type":"object","properties":{
               "a":{"type":"string","description":"First parameter sentence. Extra words here."}}}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 1024, 30);

        assertEquals("First parameter sentence.", out.getAsJsonObject("parameters").getAsJsonObject("properties")
                     .getAsJsonObject("a").get("description").getAsString());
    }

    @Test
    @Timeout(5)
    void shortDescriptionsAreLeftAlone() {
        JsonObject function = json("""
            {"name":"t","description":"short","parameters":{"type":"object","properties":{
               "a":{"type":"string","description":"also short"}}}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 1024, 256);

        assertEquals("short", out.get("description").getAsString());
        assertEquals("also short", out.getAsJsonObject("parameters").getAsJsonObject("properties")
                     .getAsJsonObject("a").get("description").getAsString());
    }

    @Test
    @Timeout(5)
    void simpleSchemaIsUnchanged() {
        JsonObject function = json("""
            {"name":"read_file","description":"Read a file",
             "parameters":{"type":"object","properties":{"filePath":{"type":"string","description":"Path"}},
               "required":["filePath"]}}""");

        JsonObject out = ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 1024, 256);

        assertEquals(function, out);
    }

    @Test
    @Timeout(5)
    void inputIsNotMutated() {
        JsonObject function = json("""
            {"name":"t","description":"d","strict":true,"parameters":{"type":"object","additionalProperties":false}}""");
        JsonObject before = function.deepCopy();

        ToolSchemaSanitiser.sanitise(function, DEPTH_LIMIT, 1024, 256);

        assertEquals(before, function);
    }

    @Test
    @Timeout(5)
    void nullFunctionYieldsEmptyObject() {
        assertEquals(0, ToolSchemaSanitiser.sanitise(null, DEPTH_LIMIT, 1024, 256).size());
    }

    @Test
    @Timeout(5)
    void defaultOverloadRuns() {
        JsonObject function = json("""
            {"name":"t","description":"d","parameters":{"type":"object","properties":{"a":{"type":"string"}}}}""");

        assertTrue(ToolSchemaSanitiser.sanitise(function).has("parameters"));
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
