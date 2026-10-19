package vip.mate.agent.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

class SpringToolSchemaAdapterTest {
    private static ToolCallback callback(String schema) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("scoped_search")
                                .description("Search workspace")
                                .inputSchema(schema)
                                .build());
        return callback;
    }

    @Test
    void preservesNamesDescriptionsNumbersNullableAndNestedSchemasWithoutExecution() {
        ToolCallback callback =
                callback(
                        "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":[\"integer\",\"null\"],\"minimum\":2147483648,\"enum\":[2147483649,null]},\"nested\":{\"type\":\"object\",\"properties\":{\"ratio\":{\"minimum\":0.25}}}}}");
        var schemas = SpringToolSchemaAdapter.convert(List.of(callback));
        var schema = schemas.get(0);
        assertEquals("scoped_search", schema.getName());
        assertEquals("Search workspace", schema.getDescription());
        Map<?, ?> properties = (Map<?, ?>) schema.getParameters().get("properties");
        Map<?, ?> count = (Map<?, ?>) properties.get("count");
        assertEquals(2147483648L, count.get("minimum"));
        assertEquals(List.of("integer", "null"), count.get("type"));
        List<?> values = (List<?>) count.get("enum");
        assertEquals(2147483649L, values.get(0));
        assertNull(values.get(1));
        Map<?, ?> nested = (Map<?, ?>) ((Map<?, ?>) properties.get("nested")).get("properties");
        assertEquals(0.25, ((Map<?, ?>) nested.get("ratio")).get("minimum"));
        assertThrows(UnsupportedOperationException.class, schemas::clear);
        verify(callback, never()).call(anyString());
    }

    @Test
    void rejectsDuplicateKeysTrailingTokensAndNonObjectRoots() {
        for (String schema :
                List.of(
                        "{\"type\":\"object\",\"type\":\"string\"}",
                        "{} {}",
                        "[]",
                        "null",
                        "42",
                        "{\"properties\":{\"x\":{\"minimum\":1,\"minimum\":2}}}")) {
            ToolCallback callback = callback(schema);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SpringToolSchemaAdapter.convert(List.of(callback)),
                    schema);
            verify(callback, never()).call(anyString());
        }
    }
}
