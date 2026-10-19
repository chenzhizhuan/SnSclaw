package vip.mate.llm.dashscope;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Native text embeddings with response validation and input-order preservation. */
public final class NativeDashScopeEmbeddingModel implements EmbeddingModel {
    private static final int MAX_BATCH_SIZE = 10;
    static final String PATH = "/api/v1/services/embeddings/text-embedding/text-embedding";
    private final NativeDashScopeTransport transport;
    private final MetadataMode metadataMode;
    private final EmbeddingOptions options;
    private final String textType;

    public NativeDashScopeEmbeddingModel(
            NativeDashScopeTransport transport,
            MetadataMode metadataMode,
            EmbeddingOptions options) {
        this(transport, metadataMode, options, "document");
    }

    public NativeDashScopeEmbeddingModel(
            NativeDashScopeTransport transport,
            MetadataMode metadataMode,
            EmbeddingOptions options,
            String textType) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.metadataMode = Objects.requireNonNull(metadataMode, "metadataMode");
        this.options = Objects.requireNonNull(options, "options");
        if (options.getModel() == null || options.getModel().isBlank()) {
            throw new IllegalArgumentException("DashScope embedding model is required");
        }
        if (!"document".equals(textType) && !"query".equals(textType)) {
            throw new IllegalArgumentException(
                    "DashScope embedding text type must be document or query");
        }
        this.textType = textType;
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return Objects.requireNonNull(document, "document").getFormattedContent(metadataMode);
    }

    @Override
    public int dimensions() {
        return options.getDimensions() == null
                ? EmbeddingModel.super.dimensions()
                : options.getDimensions();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        String model = options.getModel();
        Integer dimension = options.getDimensions();
        if (request.getOptions() != null) {
            if (request.getOptions().getModel() != null) model = request.getOptions().getModel();
            if (request.getOptions().getDimensions() != null)
                dimension = request.getOptions().getDimensions();
        }
        if (dimension != null && dimension <= 0)
            throw new IllegalArgumentException("Embedding dimension must be positive");
        if (request.getInstructions().isEmpty()) return new EmbeddingResponse(List.of());
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("text_type", textType);
        if (dimension != null) parameters.put("dimension", dimension);
        TreeMap<Integer, Embedding> ordered = new TreeMap<>();
        Integer actualDimension = dimension;
        List<String> requestIds = new ArrayList<>();
        List<Map<String, Object>> usageDetails = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        int totalTokens = 0;
        long cacheRead = 0;
        long cacheWritten = 0;
        int reasoningTokens = 0;
        for (int offset = 0; offset < request.getInstructions().size(); offset += MAX_BATCH_SIZE) {
            List<String> texts =
                    request.getInstructions()
                            .subList(
                                    offset,
                                    Math.min(
                                            offset + MAX_BATCH_SIZE,
                                            request.getInstructions().size()));
            JsonNode result =
                    transport.exchange(
                            PATH,
                            Map.of(
                                    "model",
                                    model,
                                    "input",
                                    Map.of("texts", texts),
                                    "parameters",
                                    parameters));
            JsonNode entries = result.path("output").path("embeddings");
            if (!entries.isArray() || entries.size() != texts.size()) {
                throw invalid("Provider returned a different number of embedding vectors", result);
            }
            for (JsonNode entry : entries) {
                JsonNode indexNode = entry.path("text_index");
                int index = indexNode.asInt(-1);
                JsonNode values = entry.path("embedding");
                int globalIndex = offset + index;
                if (!indexNode.isIntegralNumber()
                        || !indexNode.canConvertToInt()
                        || index < 0
                        || index >= entries.size()
                        || ordered.containsKey(globalIndex)
                        || !values.isArray()
                        || values.isEmpty()) {
                    throw invalid("Provider returned an invalid embedding index or vector", result);
                }
                if (actualDimension == null) actualDimension = values.size();
                if (values.size() != actualDimension) {
                    throw invalid("Provider returned inconsistent embedding dimensions", result);
                }
                float[] vector = new float[values.size()];
                for (int i = 0; i < vector.length; i++) {
                    if (!values.get(i).isNumber())
                        throw invalid("Provider returned a nonnumeric embedding value", result);
                    vector[i] = values.get(i).floatValue();
                    if (!Float.isFinite(vector[i]))
                        throw invalid("Provider returned a nonfinite embedding value", result);
                }
                ordered.put(globalIndex, new Embedding(vector, globalIndex));
            }
            if (result.hasNonNull("request_id")) requestIds.add(result.get("request_id").asText());
            DefaultUsage batchUsage = NativeDashScopeChatModel.usage(result.path("usage"));
            inputTokens = Math.addExact(inputTokens, batchUsage.getPromptTokens());
            outputTokens = Math.addExact(outputTokens, batchUsage.getCompletionTokens());
            totalTokens = Math.addExact(totalTokens, batchUsage.getTotalTokens());
            if (batchUsage.getCacheReadInputTokens() != null)
                cacheRead = Math.addExact(cacheRead, batchUsage.getCacheReadInputTokens());
            if (batchUsage.getCacheWriteInputTokens() != null)
                cacheWritten = Math.addExact(cacheWritten, batchUsage.getCacheWriteInputTokens());
            NativeDashScopeUsage nativeUsage = (NativeDashScopeUsage) batchUsage.getNativeUsage();
            reasoningTokens =
                    Math.addExact(
                            reasoningTokens,
                            nativeUsage.completionTokenDetails().reasoningTokens());
            usageDetails.add(nativeUsage.details());
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (!requestIds.isEmpty()) {
            metadata.put("id", requestIds.getFirst());
            metadata.put("requestIds", List.copyOf(requestIds));
        }
        NativeDashScopeUsage nativeUsage =
                new NativeDashScopeUsage(
                        inputTokens,
                        outputTokens,
                        totalTokens,
                        cacheRead,
                        cacheWritten,
                        new NativeDashScopeUsage.CompletionTokenDetails(reasoningTokens),
                        Map.of("batches", List.copyOf(usageDetails)));
        DefaultUsage usage =
                new DefaultUsage(
                        inputTokens,
                        outputTokens,
                        totalTokens,
                        nativeUsage,
                        cacheRead,
                        cacheWritten);
        return new EmbeddingResponse(
                new ArrayList<>(ordered.values()),
                new EmbeddingResponseMetadata(model, usage, metadata));
    }

    private static NativeDashScopeException invalid(String message, JsonNode result) {
        return new NativeDashScopeException(
                200, "InvalidResponse", message, result.path("request_id").asText(null));
    }
}
