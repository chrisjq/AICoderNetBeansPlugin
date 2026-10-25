package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.detect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.openaicompatible.client.CompatibilityFlags;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SessionDecisionsTest {

    @Test
    @Timeout(5)
    void staleGenerationIsDiscarded() {
        String current = DetectedFacts.generation("http://localhost:11434", "llama", "hash-a");
        SessionDecisions decisions = new SessionDecisions(current);
        DetectedFacts facts = snapshot(current, ServerKind.OLLAMA);
        assertTrue(decisions.accept(facts));

        DetectedFacts stale = snapshot(DetectedFacts.generation("http://other", "llama", "hash-a"), ServerKind.GENERIC);
        assertFalse(decisions.accept(stale));
        assertEquals(facts, decisions.facts());

        decisions.generation(DetectedFacts.generation("http://localhost:11434", "mistral", "hash-a"));
        assertNull(decisions.facts());

        SessionDecisions.Combined combined = SessionDecisions.combine(facts, decisions);
        assertNull(combined.facts());
    }

    @Test
    @Timeout(5)
    void resetClearsDecisionsOnly() {
        String generation = DetectedFacts.generation("http://localhost:1234", "model", "hash-b");
        SessionDecisions decisions = new SessionDecisions(generation);
        DetectedFacts facts = snapshot(generation, ServerKind.LMSTUDIO);
        decisions.accept(facts);
        decisions.turnOff(CompatibilityFlags.STREAM_OPTIONS);
        decisions.turnOff(CompatibilityFlags.RESPONSE_FORMAT);
        decisions.recordNonStreaming();
        decisions.learnedWindow(4096);

        decisions.reset();

        assertEquals(facts, decisions.facts());
        assertEquals(generation, decisions.generation());
        assertTrue(decisions.supportsStreamUsage());
        assertTrue(decisions.supportsJsonSchemaResponseFormat());
        assertFalse(decisions.usesMaxCompletionTokens());
        assertNull(decisions.learnedWindow());
        assertNull(decisions.streaming());

        SessionDecisions.Combined combined = SessionDecisions.combine(facts, decisions);
        assertEquals(facts, combined.facts());
        assertTrue(combined.flags().supportsStreamUsage());
        assertNull(combined.learnedWindow());
        assertNull(combined.streaming());
    }

    @Test
    @Timeout(5)
    void combineSnapshotsDecisionsAndWithCopiesKeepTheRest() {
        String generation = DetectedFacts.generation("http://localhost:8080", "model", "hash-c");
        List<WindowReading> readings = new ArrayList<>();
        readings.add(new WindowReading(8192, WindowReading.Source.OLLAMA_PS, true));
        DetectedFacts facts = DetectedFacts.of(generation)
                .withKind(ServerKind.OLLAMA)
                .withApiBase("http://localhost:8080/v1")
                .withStreaming(Boolean.TRUE)
                .withCapabilities(new DetectedFacts.Capabilities(Boolean.TRUE, Boolean.FALSE))
                .withWindowReadings(readings)
                .withV1WindowFieldPresent(true)
                .withOllamaTrainingContextLength(32768)
                .withModels(List.of("model"))
                .withTimestamp(Instant.parse("2026-10-23T00:00:00Z"));
        readings.clear();

        assertEquals(ServerKind.OLLAMA, facts.kind());
        assertEquals("OLLAMA", facts.kindName());
        assertEquals(8192, facts.ollamaPsContextLength());
        assertEquals(32768, facts.ollamaTrainingContextLength());
        assertTrue(facts.v1ModelsHasWindowField());
        assertEquals(List.of("model"), facts.models());
        assertEquals(1, facts.windowReadings().size());

        DetectedFacts copied = facts.withKind(ServerKind.VLLM);
        assertEquals(ServerKind.OLLAMA, facts.kind());
        assertEquals(facts.apiBase(), copied.apiBase());
        assertEquals(facts.windowReadings(), copied.windowReadings());

        SessionDecisions decisions = new SessionDecisions(generation);
        decisions.learnedWindow(2048);
        decisions.turnOff(CompatibilityFlags.MAX_TOKENS);
        SessionDecisions.Combined combined = SessionDecisions.combine(facts, decisions);
        decisions.turnOff(CompatibilityFlags.STREAM_OPTIONS);

        assertTrue(combined.flags().supportsStreamUsage());
        assertTrue(combined.flags().usesMaxCompletionTokens());
        assertEquals(2048, combined.learnedWindow());
        assertEquals(Boolean.TRUE, combined.streaming());
        assertFalse(decisions.supportsStreamUsage());
    }

    @Test
    @Timeout(5)
    void aGenericOrUnknownServerStartsFromTheConservativeBaseline() {
        String generation = DetectedFacts.generation("https://api.example.com", "model", "hash-d");
        SessionDecisions decisions = new SessionDecisions(generation);

        SessionDecisions.Combined unknown = SessionDecisions.combine(null, decisions);
        assertFalse(unknown.flags().supportsReasoningEffort());
        assertFalse(unknown.flags().supportsJsonSchemaResponseFormat());
        assertTrue(unknown.flags().supportsStreamUsage());
        assertFalse(unknown.flags().usesMaxCompletionTokens());

        SessionDecisions.Combined generic = SessionDecisions.combine(snapshot(generation, ServerKind.GENERIC), decisions);
        assertFalse(generic.flags().supportsReasoningEffort());
        assertFalse(generic.flags().supportsJsonSchemaResponseFormat());

        SessionDecisions.Combined known = SessionDecisions.combine(snapshot(generation, ServerKind.VLLM), decisions);
        assertTrue(known.flags().supportsReasoningEffort());
        assertTrue(known.flags().supportsJsonSchemaResponseFormat());

        assertTrue(decisions.supportsReasoningEffort());
        assertTrue(decisions.supportsJsonSchemaResponseFormat());
        assertFalse(SessionDecisions.combine(null, null).flags().supportsReasoningEffort());
    }

    @Test
    @Timeout(5)
    void aRequestRecordsWhatItLearnedButNotWhatTheBaselineWithheld() {
        String generation = DetectedFacts.generation("https://api.example.com", "model", "hash-e");
        SessionDecisions decisions = new SessionDecisions(generation);

        SessionDecisions.RequestFlags request = decisions.beginRequest(null);
        request.flags().turnOff(CompatibilityFlags.STREAM_OPTIONS);
        request.flags().turnOff(CompatibilityFlags.MAX_TOKENS);
        assertTrue(decisions.supportsStreamUsage(), "nothing is recorded before finish");
        request.finish();

        assertFalse(decisions.supportsStreamUsage());
        assertTrue(decisions.usesMaxCompletionTokens());
        assertTrue(decisions.supportsReasoningEffort(), "the baseline's withheld field is not a rejection");
        assertTrue(decisions.supportsJsonSchemaResponseFormat());

        SessionDecisions.RequestFlags known = decisions.beginRequest(snapshot(generation, ServerKind.OLLAMA));
        assertFalse(known.flags().supportsStreamUsage(), "what was learned carries into the next request");
        assertTrue(known.flags().supportsReasoningEffort());
        known.flags().dropAllOptional();
        known.finish();
        assertFalse(decisions.supportsReasoningEffort());
        assertFalse(decisions.supportsJsonSchemaResponseFormat());
    }

    @Test
    @Timeout(5)
    void anotherRequestIsNotChangedByALaterTurnOff() {
        String generation = DetectedFacts.generation("http://localhost:11434", "model", "hash-f");
        SessionDecisions decisions = new SessionDecisions(generation);
        DetectedFacts firstFacts = snapshot(generation, ServerKind.OLLAMA);
        decisions.accept(firstFacts);
        SessionDecisions.Combined firstSnapshot = SessionDecisions.combine(firstFacts, decisions);
        SessionDecisions.RequestFlags first = decisions.beginRequestSnapshot(firstSnapshot);

        decisions.turnOff(CompatibilityFlags.STREAM_OPTIONS);
        DetectedFacts nextFacts = snapshot(generation, ServerKind.VLLM);
        decisions.accept(nextFacts);

        assertTrue(first.flags().supportsStreamUsage());
        assertEquals(ServerKind.OLLAMA, firstSnapshot.facts().kind());
        SessionDecisions.Combined nextSnapshot = SessionDecisions.combine(decisions.facts(), decisions);
        assertEquals(ServerKind.VLLM, nextSnapshot.facts().kind());
        assertFalse(nextSnapshot.flags().supportsStreamUsage());
    }

    @Test
    @Timeout(5)
    void nonStreamingIsRecordedOnceAndShownInTheFactsAndTheGetter() {
        String generation = DetectedFacts.generation("http://localhost:1234", "model", "hash-g");
        SessionDecisions decisions = new SessionDecisions(generation);
        assertNull(decisions.streaming());

        DetectedFacts facts = snapshot(generation, ServerKind.LMSTUDIO).withStreaming(Boolean.TRUE);
        decisions.accept(facts);
        assertEquals(Boolean.TRUE, decisions.streaming());
        assertEquals(Boolean.TRUE, decisions.facts().streaming());

        assertTrue(decisions.recordNonStreaming());
        assertFalse(decisions.recordNonStreaming());
        assertEquals(Boolean.FALSE, decisions.streaming());
        assertEquals(Boolean.FALSE, decisions.facts().streaming());
        assertEquals(Boolean.FALSE, SessionDecisions.combine(facts, decisions).streaming());

        decisions.reset();
        assertEquals(Boolean.TRUE, decisions.streaming());
        assertTrue(decisions.recordNonStreaming(), "after a reset it is noted again");
    }

    private static DetectedFacts snapshot(String generation, ServerKind kind) {
        return DetectedFacts.of(generation).withKind(kind).withApiBase("http://localhost/v1");
    }
}
