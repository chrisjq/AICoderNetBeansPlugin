package kiwi.ingenuity.netbeans.plugin.aicoder.ai.settings;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable reusable special-instruction text.
 */
public record SpecialInstructionTemplate(String id, String name, String body,
        Instant createdAt, Instant updatedAt) {

    public static SpecialInstructionTemplate create(String name, String body) {
        Instant now = Instant.now();
        return new SpecialInstructionTemplate(UUID.randomUUID().toString(), name, body, now, now);
    }

    public static SpecialInstructionTemplate fromJson(JsonObject value) {
        return new SpecialInstructionTemplate(value.get("id").getAsString(),
                value.get("name").getAsString(), value.get("body").getAsString(),
                Instant.parse(value.get("createdAt").getAsString()),
                Instant.parse(value.get("updatedAt").getAsString()));
    }

    public SpecialInstructionTemplate withNameAndBody(String newName, String newBody) {
        return new SpecialInstructionTemplate(id, newName, newBody, createdAt, Instant.now());
    }

    public JsonObject toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("name", name);
        value.addProperty("body", body);
        value.addProperty("createdAt", createdAt.toString());
        value.addProperty("updatedAt", updatedAt.toString());
        return value;
    }

}
