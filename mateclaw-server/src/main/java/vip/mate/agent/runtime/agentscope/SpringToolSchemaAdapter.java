package vip.mate.agent.runtime.agentscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.model.ToolSchema;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects scoped Spring tool definitions without registering executable callbacks. */
public final class SpringToolSchemaAdapter {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private SpringToolSchemaAdapter() {}

    public static List<ToolSchema> convert(List<ToolCallback> callbacks) {
        Objects.requireNonNull(callbacks);
        return callbacks.stream()
                .map(
                        callback -> {
                            ToolDefinition definition =
                                    Objects.requireNonNull(callback.getToolDefinition());
                            try {
                                JsonNode root = JSON.readTree(definition.inputSchema());
                                if (root == null || !root.isObject())
                                    throw new IllegalArgumentException(
                                            "Tool schema must be a JSON object: "
                                                    + definition.name());
                                Map<String, Object> parameters =
                                        JSON.convertValue(root, new TypeReference<>() {});
                                return ToolSchema.builder()
                                        .name(definition.name())
                                        .description(definition.description())
                                        .parameters(parameters)
                                        .build();
                            } catch (JsonProcessingException error) {
                                throw new IllegalArgumentException(
                                        "Invalid tool schema: " + definition.name(), error);
                            }
                        })
                .toList();
    }
}
