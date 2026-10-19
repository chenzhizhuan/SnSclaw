package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Subscription-local wire state for signed thinking and reasoning deltas. */
final class AnthropicThinkingScope {
    static final ObjectMapper JSON = new ObjectMapper();
    final ProviderCallCancellation cancellation = new ProviderCallCancellation();
    final List<String> systemTexts = new ArrayList<>();
    final List<List<Map<String, Object>>> history = new ArrayList<>();
    final ConcurrentLinkedQueue<String> deltas = new ConcurrentLinkedQueue<>();
    private final Map<Integer, Map<String, Object>> blocks = new TreeMap<>();
    private Long cacheRead;
    private Long cacheWrite;

    AnthropicThinkingScope(Prompt prompt) {
        prompt.getInstructions()
                .forEach(
                        message -> {
                            if (message instanceof SystemMessage system)
                                systemTexts.add(system.getText());
                            if (message instanceof AssistantMessage assistant) {
                                Object value =
                                        assistant
                                                .getMetadata()
                                                .get(AnthropicSdkChatModel.THINKING_BLOCKS);
                                List<Map<String, Object>> saved =
                                        value == null
                                                ? List.of()
                                                : JSON.convertValue(
                                                        value, new TypeReference<>() {});
                                saved.forEach(
                                        block -> {
                                            String type = String.valueOf(block.get("type"));
                                            if ("thinking".equals(type)
                                                    && block.get("thinking") instanceof String
                                                    && block.get("signature")
                                                            instanceof String signature
                                                    && !signature.isBlank()) return;
                                            if ("redacted_thinking".equals(type)
                                                    && block.get("data") instanceof String) return;
                                            throw new IllegalArgumentException(
                                                    "Invalid signed Anthropic thinking history");
                                        });
                                history.add(saved);
                            }
                        });
    }

    synchronized void accept(JsonNode event) {
        String type = event.path("type").asText();
        int index = event.path("index").asInt();
        if ("message".equals(type)) {
            int i = 0;
            for (JsonNode block : event.path("content")) captureBlock(i++, block);
            usage(event.path("usage"));
        } else if ("message_start".equals(type)) {
            usage(event.path("message").path("usage"));
        } else if ("message_delta".equals(type)) {
            usage(event.path("usage"));
        } else if ("content_block_start".equals(type)) {
            captureBlock(index, event.path("content_block"));
        } else if ("content_block_delta".equals(type)) {
            JsonNode delta = event.path("delta");
            String deltaType = delta.path("type").asText();
            Map<String, Object> block = blocks.get(index);
            if ("thinking_delta".equals(deltaType)) {
                String thinking = delta.path("thinking").asText("");
                deltas.add(thinking);
                if (block != null)
                    block.put("thinking", block.getOrDefault("thinking", "") + thinking);
            } else if ("signature_delta".equals(deltaType) && block != null) {
                block.put(
                        "signature",
                        block.getOrDefault("signature", "") + delta.path("signature").asText(""));
            }
        }
    }

    private void captureBlock(int index, JsonNode block) {
        String type = block.path("type").asText();
        if (!"thinking".equals(type) && !"redacted_thinking".equals(type)) return;
        blocks.put(
                index,
                JSON.convertValue(block, new TypeReference<LinkedHashMap<String, Object>>() {}));
    }

    private void usage(JsonNode usage) {
        if (usage.hasNonNull("cache_read_input_tokens"))
            cacheRead = usage.get("cache_read_input_tokens").asLong();
        if (usage.hasNonNull("cache_creation_input_tokens"))
            cacheWrite = usage.get("cache_creation_input_tokens").asLong();
    }

    synchronized List<Map<String, Object>> thinkingBlocks() {
        return blocks.values().stream()
                .filter(
                        block ->
                                "redacted_thinking".equals(block.get("type"))
                                        || block.get("signature") instanceof String signature
                                                && !signature.isBlank())
                .map(Map::copyOf)
                .toList();
    }

    synchronized String thinkingText() {
        return blocks.values().stream()
                .filter(block -> "thinking".equals(block.get("type")))
                .map(block -> String.valueOf(block.getOrDefault("thinking", "")))
                .reduce("", String::concat);
    }

    synchronized Long cacheRead() {
        return cacheRead;
    }

    synchronized Long cacheWrite() {
        return cacheWrite;
    }
}
