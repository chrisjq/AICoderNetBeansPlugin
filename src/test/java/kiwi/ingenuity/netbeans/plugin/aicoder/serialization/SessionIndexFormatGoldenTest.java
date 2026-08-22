package kiwi.ingenuity.netbeans.plugin.aicoder.serialization;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.AiTypeEnum;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.AiSession;
import kiwi.ingenuity.netbeans.plugin.aicoder.ai.session.SessionInstructionsDeliveryEnum;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the on-disk session-index key names written by {@link SessionPersistenceManager}.
 * <p>
 * The key names below are literals ON PURPOSE. Reading them from {@link SessionPersistenceKeyEnum} would make this test
 * follow any rename and assert nothing — it would look like coverage while proving only that the enum equals itself. A
 * save-then-load round trip has the same weakness, because both halves move together; only a hand-written document and
 * a raw-text assertion can catch a key changing.
 * <p>
 * What it protects: the loader SKIPS any session missing id, name, createdAt or lastUsedAt rather than reporting a
 * problem. A renamed key therefore does not degrade the session list — it silently removes sessions from it, with no
 * error and a green build.
 */
class SessionIndexFormatGoldenTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsAHandWrittenSessionIndexUsingTheDocumentedKeyNames() throws IOException {
        Files.writeString(tempDir.resolve("sessions.json"), """
            [
              {
                "id": "sess-1",
                "name": "My Session",
                "aiType": "CLAUDE",
                "projectPath": "/tmp/project",
                "description": "a description",
                "createdAt": "2026-01-01T00:00:00Z",
                "lastUsedAt": "2026-01-02T00:00:00Z",
                "config": {"maxHistory": 42},
                "sessionInstructionsDelivery": "ON_FIRST_REQUEST",
                "startupInstructionsInjected": true,
                "lastInjectedInstructions": "previous text"
              }
            ]
            """, StandardCharsets.UTF_8);

        List<AiSession> loaded = new SessionPersistenceManager(tempDir).loadAll();

        assertEquals(1, loaded.size(), "a renamed required key would drop the session entirely");
        AiSession s = loaded.get(0);
        assertEquals("sess-1", s.id());
        assertEquals("My Session", s.name());
        assertEquals(AiTypeEnum.CLAUDE, s.aiType());
        assertEquals("/tmp/project", s.projectPath());
        assertEquals("a description", s.description());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), s.createdAt());
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), s.lastUsedAt());
        assertTrue(s.isStartupInstructionsInjected());
        assertEquals("previous text", s.lastInjectedInstructions());
        // Asserted last because it fails soft: an unparseable value logs a
        // warning and falls back, so without this the field could silently stop
        // being read and the test would still pass.
        assertEquals(SessionInstructionsDeliveryEnum.ON_FIRST_REQUEST,
                s.sessionInstructionsDelivery());
        // A real setting inside "config" rather than an empty object, so that
        // renaming the CONFIG key is caught here too. With {} the nested
        // settings never load, so has("config") going false would change
        // nothing observable and this test would stay green.
        assertEquals(Integer.valueOf(42), s.settings().maxHistory());
    }

    @Test
    void writesExactlyTheDocumentedKeyNames() throws IOException {
        SessionPersistenceManager mgr = new SessionPersistenceManager(tempDir);
        AiSession session = new AiSession("sess-2", "Written", "desc", AiTypeEnum.CLAUDE,
                "/tmp/wd", AiTypeEnum.CLAUDE.getSettingsCreator().create(),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
        session.setLastInjectedInstructions("text");
        mgr.save(session);

        String json = Files.readString(tempDir.resolve("sessions.json"), StandardCharsets.UTF_8);

        // Asserted against the entry's own key set, not a substring of the
        // whole document. "config" is an arbitrary settings blob, so a
        // contains() check for a top-level key like "name" or "description"
        // could be satisfied by a setting of the same name nested inside it —
        // and would then keep passing after the top-level key was renamed.
        JsonObject entry = JsonParser.parseString(json).getAsJsonArray()
                .get(0).getAsJsonObject();

        assertEquals(Set.of("id", "name", "aiType", "projectPath", "description",
                "createdAt", "lastUsedAt", "config", "sessionInstructionsDelivery",
                "startupInstructionsInjected", "lastInjectedInstructions"),
                entry.keySet());
    }
}
