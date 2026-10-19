package vip.mate.llm.dashscope;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

class NativeDashScopeEmbeddingModelTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private NativeDashScopeEmbeddingModel model;
    private String response;
    private NativeDashScopeTransport transport;
    private Function<JsonNode, String> responseFactory;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    JsonNode incoming = mapper.readTree(exchange.getRequestBody());
                    request.set(incoming);
                    requests.add(incoming);
                    path.set(exchange.getRequestURI().getPath());
                    byte[] bytes =
                            (responseFactory == null ? response : responseFactory.apply(incoming))
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        transport =
                new NativeDashScopeTransport(
                        "test-key",
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        HttpClient.newHttpClient(),
                        Duration.ofSeconds(3));
        model =
                new NativeDashScopeEmbeddingModel(
                        transport,
                        MetadataMode.EMBED,
                        EmbeddingOptions.builder()
                                .model("text-embedding-v3")
                                .dimensions(2)
                                .build());
        response =
                """
                {"request_id":"e","output":{"embeddings":[
                {"text_index":1,"embedding":[0.3,0.4]},
                {"text_index":0,"embedding":[0.1,0.2]}]},"usage":{"total_tokens":7}}
                """;
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void vectorsFollowInputOrderAndNativeDimensionParameter() {
        EmbeddingResponse result =
                model.call(
                        new EmbeddingRequest(
                                List.of("first", "second"),
                                EmbeddingOptions.builder()
                                        .model("text-embedding-v4")
                                        .dimensions(2)
                                        .build()));
        assertEquals(2, result.getResults().size());
        assertEquals(0, result.getResults().get(0).getIndex());
        assertArrayEquals(new float[] {0.1f, 0.2f}, result.getResults().get(0).getOutput());
        assertArrayEquals(new float[] {0.3f, 0.4f}, result.getResults().get(1).getOutput());
        assertEquals("text-embedding-v4", result.getMetadata().getModel());
        assertEquals(7, result.getMetadata().getUsage().getTotalTokens());
        assertEquals("/api/v1/services/embeddings/text-embedding/text-embedding", path.get());
        assertEquals("document", request.get().at("/parameters/text_type").asText());
        assertEquals(2, request.get().at("/parameters/dimension").asInt());
        assertEquals("first", request.get().at("/input/texts/0").asText());
    }

    @Test
    void missingVectorIsAnErrorRatherThanSilentlyLosingAChunk() {
        response = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[0.1,0.2]}]}}";
        assertThrows(
                RuntimeException.class,
                () -> model.call(new EmbeddingRequest(List.of("one", "two"), null)));
    }

    @Test
    void duplicateIndexIsRejected() {
        response =
                "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[0.1,0.2]},{\"text_index\":0,\"embedding\":[0.3,0.4]}]}}";
        assertThrows(
                RuntimeException.class,
                () -> model.call(new EmbeddingRequest(List.of("one", "two"), null)));
    }

    @Test
    void documentEmbeddingIncludesConfiguredMetadata() {
        response = "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":[0.1,0.2]}]}}";
        assertArrayEquals(
                new float[] {0.1f, 0.2f},
                model.embed(new Document("Body", Map.of("topic", "Test"))));
        assertTrue(request.get().at("/input/texts/0").asText().contains("Test"));
    }

    @Test
    void sixteenInputsSplitIntoNativeBatchesAndRebaseShuffledIndices() {
        responseFactory = incoming -> batchResponse(incoming, 2);
        List<String> inputs = IntStream.range(0, 16).mapToObj(i -> "text" + i).toList();
        EmbeddingResponse result = model.call(new EmbeddingRequest(inputs, null));
        assertEquals(2, requests.size());
        assertEquals(10, requests.get(0).at("/input/texts").size());
        assertEquals(6, requests.get(1).at("/input/texts").size());
        assertEquals(16, result.getResults().size());
        for (int i = 0; i < 16; i++) {
            assertEquals(i, result.getResults().get(i).getIndex());
            assertArrayEquals(new float[] {i, i + 0.5f}, result.getResults().get(i).getOutput());
        }
        assertEquals(16, result.getMetadata().getUsage().getTotalTokens());
    }

    @Test
    void inferredDimensionsMustAgreeAcrossAllNativeBatches() {
        model =
                new NativeDashScopeEmbeddingModel(
                        transport,
                        MetadataMode.EMBED,
                        EmbeddingOptions.builder().model("text-embedding-v4").build());
        responseFactory = incoming -> batchResponse(incoming, requests.size() == 1 ? 2 : 3);
        List<String> inputs = IntStream.range(0, 16).mapToObj(i -> "text" + i).toList();
        assertThrows(
                NativeDashScopeException.class,
                () -> model.call(new EmbeddingRequest(inputs, null)));
    }

    private String batchResponse(JsonNode incoming, int dimension) {
        JsonNode texts = incoming.at("/input/texts");
        List<Map<String, Object>> vectors = new ArrayList<>();
        for (int i = texts.size() - 1; i >= 0; i--) {
            int number = Integer.parseInt(texts.get(i).asText().substring(4));
            List<Double> vector =
                    IntStream.range(0, dimension).mapToObj(j -> number + j * 0.5).toList();
            vectors.add(Map.of("text_index", i, "embedding", vector));
        }
        try {
            return mapper.writeValueAsString(
                    Map.of(
                            "request_id",
                            "batch-" + requests.size(),
                            "output",
                            Map.of("embeddings", vectors),
                            "usage",
                            Map.of("total_tokens", texts.size())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
