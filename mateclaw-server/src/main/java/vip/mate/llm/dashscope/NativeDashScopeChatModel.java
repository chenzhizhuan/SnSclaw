package vip.mate.llm.dashscope;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Native provider boundary; the agent runtime owns all tool execution and approvals. */
public final class NativeDashScopeChatModel implements ChatModel {
    static final String TEXT_PATH = "/api/v1/services/aigc/text-generation/generation";
    static final String MULTIMODAL_PATH = "/api/v1/services/aigc/multimodal-generation/generation";
    private final NativeDashScopeTransport transport;
    private final NativeDashScopeChatOptions options;

    public NativeDashScopeChatModel(
            NativeDashScopeTransport transport, NativeDashScopeChatOptions options) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.options = Objects.requireNonNull(options, "options");
        if (options.getModel() == null || options.getModel().isBlank()) {
            throw new IllegalArgumentException("DashScope model is required");
        }
    }

    @Override
    public NativeDashScopeChatOptions getOptions() {
        return options;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Request request = request(prompt, false);
        return new ResponseDecoder(request.model(), false)
                .decode(transport.exchange(request.path(), request.body()));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(
                () -> {
                    Request request = request(prompt, true);
                    ResponseDecoder decoder = new ResponseDecoder(request.model(), true);
                    return transport.stream(request.path(), request.body())
                            .map(decoder::decode)
                            .concatWith(
                                    Flux.defer(
                                            () ->
                                                    decoder.hasPendingTools()
                                                            ? Flux.error(
                                                                    new NativeDashScopeException(
                                                                            200,
                                                                            "InvalidResponse",
                                                                            "Tool stream ended"
                                                                                    + " before its"
                                                                                    + " completion"
                                                                                    + " marker",
                                                                            null))
                                                            : Flux.empty()));
                });
    }

    private Request request(Prompt prompt, boolean stream) {
        NativeDashScopeChatOptions.Builder merged = options.mutate();
        if (prompt.getOptions() != null) merged.combineWith(prompt.getOptions().mutate());
        if (prompt.getOptions() instanceof ToolCallingChatOptions tools
                && tools.getToolCallbacks() != null) {
            merged.toolCallbacks(tools.getToolCallbacks());
        }
        NativeDashScopeChatOptions runtime = merged.build();
        boolean multimodal =
                runtime.getModel().toLowerCase(Locale.ROOT).contains("vl")
                        || runtime.getModel().toLowerCase(Locale.ROOT).contains("omni")
                        || prompt.getInstructions().stream()
                                .anyMatch(
                                        m ->
                                                m instanceof UserMessage user
                                                        && !user.getMedia().isEmpty());
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            if (message instanceof ToolResponseMessage tools) {
                for (ToolResponseMessage.ToolResponse tool : tools.getResponses()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("role", "tool");
                    entry.put("tool_call_id", tool.id());
                    entry.put("name", tool.name());
                    entry.put("content", tool.responseData());
                    messages.add(entry);
                }
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.getMessageType().getValue());
            Object content = message.getText() == null ? "" : message.getText();
            if (message instanceof UserMessage user && !user.getMedia().isEmpty()) {
                content = mediaContent(user);
            } else if (message.getMetadata().containsKey("cache_control")) {
                content = List.of(textPart(message));
            }
            entry.put("content", content);
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                entry.put(
                        "tool_calls",
                        assistant.getToolCalls().stream()
                                .map(
                                        tool ->
                                                Map.of(
                                                        "id",
                                                        tool.id(),
                                                        "type",
                                                        tool.type(),
                                                        "function",
                                                        Map.of(
                                                                "name",
                                                                tool.name(),
                                                                "arguments",
                                                                tool.arguments())))
                                .toList());
            }
            messages.add(entry);
        }
        Map<String, Object> parameters = new LinkedHashMap<>(runtime.getNativeParameters());
        put(parameters, "temperature", runtime.getTemperature());
        put(parameters, "max_tokens", runtime.getMaxTokens());
        put(parameters, "top_p", runtime.getTopP());
        put(parameters, "top_k", runtime.getTopK());
        // The native API uses repetition/presence penalties and has no frequency_penalty
        // equivalent.
        put(parameters, "presence_penalty", runtime.getPresencePenalty());
        put(parameters, "stop", runtime.getStopSequences());
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolCallback callback :
                runtime.getToolCallbacks() == null
                        ? List.<ToolCallback>of()
                        : runtime.getToolCallbacks()) {
            var definition = callback.getToolDefinition();
            try {
                Object schema =
                        NativeDashScopeTransport.JSON.readValue(
                                definition.inputSchema(),
                                new TypeReference<Map<String, Object>>() {});
                tools.add(
                        Map.of(
                                "type",
                                "function",
                                "function",
                                Map.of(
                                        "name",
                                        definition.name(),
                                        "description",
                                        definition.description(),
                                        "parameters",
                                        schema)));
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Invalid input schema for tool " + definition.name(), e);
            }
        }
        if (!tools.isEmpty()) parameters.put("tools", tools);
        parameters.put("result_format", "message");
        if (stream) parameters.put("incremental_output", true);
        return new Request(
                multimodal ? MULTIMODAL_PATH : TEXT_PATH,
                runtime.getModel(),
                Map.of(
                        "model",
                        runtime.getModel(),
                        "input",
                        Map.of("messages", messages),
                        "parameters",
                        parameters));
    }

    private static List<Map<String, Object>> mediaContent(UserMessage user) {
        List<Map<String, Object>> content = new ArrayList<>();
        List<String> videoFrames = new ArrayList<>();
        boolean frameSequence =
                "VIDEO".equalsIgnoreCase(String.valueOf(user.getMetadata().get("messageFormat")));
        for (Media media : user.getMedia()) {
            String mime = media.getMimeType().toString();
            String url = mediaUrl(media);
            if (mime.startsWith("audio/")) content.add(Map.of("audio", url));
            else if (mime.startsWith("video/")) content.add(Map.of("video", url));
            else if (frameSequence) videoFrames.add(url);
            else content.add(Map.of("image", url));
        }
        if (!videoFrames.isEmpty()) content.add(Map.of("video", videoFrames));
        content.add(textPart(user));
        return content;
    }

    private static String mediaUrl(Media media) {
        Object data = media.getData();
        if (data instanceof String url) return url;
        if (data instanceof URI uri) return uri.toString();
        if (data instanceof byte[] bytes) {
            return "data:"
                    + media.getMimeType()
                    + ";base64,"
                    + Base64.getEncoder().encodeToString(bytes);
        }
        throw new IllegalArgumentException(
                "Unsupported DashScope media data type: " + data.getClass().getSimpleName());
    }

    private static Map<String, Object> textPart(Message message) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", message.getText() == null ? "" : message.getText());
        put(part, "cache_control", message.getMetadata().get("cache_control"));
        return part;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    static DefaultUsage usage(JsonNode usage) {
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(input + output);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (promptDetails.isMissingNode()) promptDetails = usage.path("prompt_token_details");
        Long cached = nullableLong(promptDetails.path("cached_tokens"));
        Long written = nullableLong(promptDetails.path("cache_creation_input_tokens"));
        if (written == null)
            written =
                    nullableLong(
                            promptDetails.path("cache_creation").path("ephemeral_5m_input_tokens"));
        int reasoning = usage.path("output_tokens_details").path("reasoning_tokens").asInt(0);
        Map<String, Object> details =
                usage.isObject()
                        ? NativeDashScopeTransport.JSON.convertValue(
                                usage, new TypeReference<Map<String, Object>>() {})
                        : Map.of();
        NativeDashScopeUsage nativeUsage =
                new NativeDashScopeUsage(
                        input,
                        output,
                        total,
                        cached,
                        written,
                        new NativeDashScopeUsage.CompletionTokenDetails(reasoning),
                        details);
        return new DefaultUsage(input, output, total, nativeUsage, cached, written);
    }

    private static Long nullableLong(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private record Request(String path, String model, Map<String, Object> body) {}

    private static final class ResponseDecoder {
        private final String model;
        private final boolean stream;
        private final Map<Integer, Map<Integer, ToolFragment>> pendingTools = new TreeMap<>();
        private DefaultUsage lastUsage = new DefaultUsage(0, 0);
        private String requestId = "";

        ResponseDecoder(String model, boolean stream) {
            this.model = model;
            this.stream = stream;
        }

        boolean hasPendingTools() {
            return !pendingTools.isEmpty();
        }

        ChatResponse decode(JsonNode envelope) {
            if (envelope.hasNonNull("request_id")) requestId = envelope.get("request_id").asText();
            if (envelope.hasNonNull("usage")) lastUsage = usage(envelope.get("usage"));
            List<Generation> generations = new ArrayList<>();
            JsonNode choices = envelope.path("output").path("choices");
            if (!stream && (!choices.isArray() || choices.isEmpty())) {
                throw new NativeDashScopeException(
                        200,
                        "InvalidResponse",
                        "Synchronous provider response has no choices",
                        requestId);
            }
            if (choices.isArray()) {
                int offset = 0;
                for (JsonNode choice : choices) {
                    int choiceIndex = choice.path("index").asInt(offset++);
                    JsonNode message = choice.path("message");
                    String finish = choice.path("finish_reason").asText("");
                    List<AssistantMessage.ToolCall> tools =
                            tools(choiceIndex, message.path("tool_calls"), finish);
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("id", requestId);
                    metadata.put("role", message.path("role").asText("assistant"));
                    metadata.put("finishReason", finish);
                    metadata.put("reasoningContent", message.path("reasoning_content").asText(""));
                    JsonNode search = envelope.path("output").path("search_info");
                    metadata.put(
                            "search_info",
                            search.isMissingNode() || search.isNull()
                                    ? ""
                                    : NativeDashScopeTransport.JSON.convertValue(
                                            search, Object.class));
                    AssistantMessage output =
                            AssistantMessage.builder()
                                    .content(text(message.path("content")))
                                    .properties(metadata)
                                    .toolCalls(tools)
                                    .build();
                    generations.add(
                            new Generation(
                                    output,
                                    ChatGenerationMetadata.builder().finishReason(finish).build()));
                }
            } else if (!envelope.hasNonNull("usage")) {
                throw new NativeDashScopeException(
                        200,
                        "InvalidResponse",
                        "Provider response has no choices or usage",
                        requestId);
            }
            return new ChatResponse(
                    generations,
                    ChatResponseMetadata.builder()
                            .id(requestId)
                            .model(model)
                            .usage(lastUsage)
                            .build());
        }

        private List<AssistantMessage.ToolCall> tools(int choice, JsonNode calls, String finish) {
            Map<Integer, ToolFragment> fragments =
                    pendingTools.computeIfAbsent(choice, ignored -> new TreeMap<>());
            if (calls.isArray()) {
                int offset = 0;
                for (JsonNode call : calls) {
                    int index = call.path("index").asInt(offset++);
                    ToolFragment fragment =
                            fragments.computeIfAbsent(index, ignored -> new ToolFragment());
                    fragment.accept(call);
                }
            }
            if (!stream || !finish.isEmpty() && !"null".equals(finish)) {
                pendingTools.remove(choice);
                return fragments.values().stream().map(ToolFragment::complete).toList();
            }
            if (fragments.isEmpty()) pendingTools.remove(choice);
            return List.of();
        }

        private static String text(JsonNode content) {
            if (content.isTextual()) return content.asText();
            StringBuilder text = new StringBuilder();
            if (content.isArray())
                for (JsonNode part : content) text.append(part.path("text").asText(""));
            return text.toString();
        }
    }

    private static final class ToolFragment {
        private String id = "";
        private String type = "function";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        void accept(JsonNode call) {
            if (call.hasNonNull("id") && !call.get("id").asText().isEmpty())
                id = call.get("id").asText();
            if (call.hasNonNull("type")) type = call.get("type").asText();
            String part = call.path("function").path("name").asText("");
            if (!part.equals(name.toString())) name.append(part);
            arguments.append(call.path("function").path("arguments").asText(""));
        }

        AssistantMessage.ToolCall complete() {
            try {
                if (id.isBlank()
                        || name.isEmpty()
                        || !NativeDashScopeTransport.JSON
                                .readTree(arguments.toString())
                                .isObject()) {
                    throw new IllegalArgumentException("Missing tool identity or object arguments");
                }
            } catch (IOException | IllegalArgumentException | NullPointerException e) {
                throw new NativeDashScopeException(
                        200, "InvalidResponse", "Provider returned an incomplete tool call", null);
            }
            return new AssistantMessage.ToolCall(id, type, name.toString(), arguments.toString());
        }
    }
}
