package vip.mate.llm.chatmodel;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.MimeTypeUtils;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures relay ownership before the SDK moves a request to its worker thread. */
final class OpenAiWirePolicyChatModel implements ChatModel {
    private final ChatModel delegate;
    private final RetryTemplate retry;
    private final PinnedOpenAiHttpClient transport;

    OpenAiWirePolicyChatModel(ChatModel delegate, RetryTemplate retry, PinnedOpenAiHttpClient transport) {
        this.delegate = delegate;
        this.retry = retry;
        this.transport = transport;
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt prepared = prepare(prompt);
        return retry == null
                ? delegate.call(prepared)
                : retry.execute(context -> delegate.call(prepared));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Prompt prepared = prepare(prompt);
        OpenAiChatOptions.Builder options = ((OpenAiChatOptions) prepared.getOptions()).mutate();
        // Streaming is bounded by the configured read/idle timeout, not a whole-response deadline.
        options.timeout(Duration.ZERO);
        if (transport == null)
            return delegate.stream(new Prompt(prepared.getInstructions(), options.build()));
        OpenAiChatOptions streamingOptions = options.build();
        return Flux.defer(() -> {
            var scope = transport.startStream();
            Map<String, String> headers = new LinkedHashMap<>();
            if (streamingOptions.getCustomHeaders() != null)
                headers.putAll(streamingOptions.getCustomHeaders());
            headers.keySet().removeIf(PinnedOpenAiHttpClient.STREAM_HEADER::equalsIgnoreCase);
            headers.put(PinnedOpenAiHttpClient.STREAM_HEADER, scope.token());
            try {
                Prompt scoped = new Prompt(prepared.getInstructions(),
                        streamingOptions.mutate().customHeaders(headers).build());
                return delegate.stream(scoped)
                        .doOnCancel(scope::cancel)
                        .doFinally(signal -> scope.close());
            } catch (RuntimeException | Error failure) {
                scope.cancel();
                scope.close();
                throw failure;
            }
        });
    }

    static OpenAiChatOptions mergeOptions(ChatOptions defaults, ChatOptions runtime) {
        OpenAiChatOptions.Builder merged =
                defaults instanceof OpenAiChatOptions options
                        ? options.mutate()
                        : OpenAiChatOptions.builder();
        if (runtime != null) merged.combineWith(runtime.mutate());
        // combineWith treats empty lists as absent; explicit runtime callbacks replace defaults.
        if (runtime instanceof ToolCallingChatOptions tools && tools.getToolCallbacks() != null)
            merged.toolCallbacks(tools.getToolCallbacks());
        return merged.build();
    }

    private static List<Message> prepareMedia(List<Message> messages) {
        List<Message> prepared = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!(message instanceof UserMessage user)
                    || user.getMedia().stream()
                            .noneMatch(media -> "video".equals(media.getMimeType().getType()))) {
                prepared.add(message);
                continue;
            }
            List<Media> mediaParts = new ArrayList<>(user.getMedia().size());
            for (Media media : user.getMedia()) {
                if (!"video".equals(media.getMimeType().getType())) {
                    mediaParts.add(media);
                    continue;
                }
                Object data = media.getData();
                String url =
                        data instanceof byte[] bytes
                                ? "data:"
                                        + media.getMimeType()
                                        + ";base64,"
                                        + Base64.getEncoder().encodeToString(bytes)
                                : (String) data;
                // Keep typed media separate from ordinary text while the SDK carries the video URL.
                Media.Builder transport =
                        Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(url);
                if (media.getId() != null) transport.id(media.getId());
                if (media.getName() != null) transport.name(media.getName());
                mediaParts.add(transport.build());
            }
            prepared.add(
                    UserMessage.builder()
                            .text(user.getText())
                            .metadata(user.getMetadata())
                            .media(mediaParts)
                            .build());
        }
        return prepared;
    }

    private Prompt prepare(Prompt prompt) {
        OpenAiChatOptions options = mergeOptions(delegate.getOptions(), prompt.getOptions());
        if (!AssistantThinkingRelay.isToken(options.getUser()))
            return new Prompt(prepareMedia(prompt.getInstructions()), options);
        var relay = AssistantThinkingRelay.take(options.getUser());
        var builder = options.mutate();
        builder.user(relay == null ? null : relay.originalUser());
        if (relay != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("thinkings", relay.thinkings());
            payload.put("originalUser", relay.originalUser());
            Map<String, Object> extra =
                    options.getExtraBody() == null
                            ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(options.getExtraBody());
            extra.put(OpenAiRequestRewriter.RELAY_FIELD, payload);
            builder.extraBody(extra);
        }
        return new Prompt(prepareMedia(prompt.getInstructions()), builder.build());
    }
}
