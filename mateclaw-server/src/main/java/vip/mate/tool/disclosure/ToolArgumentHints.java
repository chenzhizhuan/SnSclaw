package vip.mate.tool.disclosure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Bounded discovery hints; the complete schema remains authoritative. */
public final class ToolArgumentHints {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_HINT_LENGTH = 320;

    private ToolArgumentHints() {}

    public static String compactRequiredArguments(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) return "";
        try {
            JsonNode schema = MAPPER.readTree(inputSchema);
            JsonNode required = schema.path("required");
            if (!required.isArray()) return "";
            List<String> names = new ArrayList<>();
            List<String> details = new ArrayList<>();
            for (JsonNode nameNode : required) {
                if (!nameNode.isTextual()) continue;
                String name = nameNode.asText();
                JsonNode property = schema.path("properties").path(name);
                String type = property.path("type").isTextual()
                        ? property.path("type").asText() : "value";
                names.add(compact(name, 48) + ":" + compact(type, 24));
                JsonNode choices = property.path("enum");
                if (choices.isArray() && !choices.isEmpty()) {
                    details.add(compact(name, 48) + " enum=" + compact(choices.toString(), 96));
                } else if (property.path("description").isTextual()) {
                    details.add(compact(name, 48) + ": " + compact(property.path("description").asText(), 64));
                }
            }
            String result = String.join("; ", names);
            if (!details.isEmpty()) result += " | " + String.join("; ", details);
            return compact(result, MAX_HINT_LENGTH);
        } catch (Exception ignored) {
            // Malformed third-party schemas must not make discovery unavailable.
            return "";
        }
    }

    private static String compact(String value, int maximum) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maximum ? normalized
                : normalized.substring(0, maximum - 3) + "...";
    }
}
