package vip.mate.llm.chatmodel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class OpenAiMediaTimeoutTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESPONSE =
            "{\"id\":\"r\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";

    private OpenAiCompatibleChatModelBuilder builder(HttpServer server) {
        ModelProviderService service = mock(ModelProviderService.class);
        when(service.isProviderConfigured("local")).thenReturn(true);
        when(service.readProviderGenerateKwargs(any())).thenReturn(Map.of());
        return new OpenAiCompatibleChatModelBuilder(service, null, null, null);
    }

    private ModelProviderEntity provider(HttpServer server) {
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId("local");
        provider.setRequireApiKey(false);
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return provider;
    }

    private ModelConfigEntity model(int timeout) {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setModelName("alias");
        model.setRequestTimeoutSeconds(timeout);
        return model;
    }

    @Test
    void actualVideoMediaBecomesVideoUrlWithoutChangingOrdinaryDataUrlText() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    request.set(JSON.readTree(exchange.getRequestBody()));
                    byte[] bytes = RESPONSE.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            Media video =
                    Media.builder()
                            .mimeType(new MimeType("video", "mp4"))
                            .data(new byte[] {120})
                            .build();
            UserMessage user =
                    UserMessage.builder().text("data:video/mp4;base64,eA==").media(video).build();
            builder(server).build(model(3), provider(server), null).call(new Prompt(List.of(user)));
            JsonNode parts = request.get().at("/messages/0/content");
            assertEquals("text", parts.get(0).path("type").asText());
            assertEquals("data:video/mp4;base64,eA==", parts.get(0).path("text").asText());
            assertEquals("video_url", parts.get(1).path("type").asText());
            assertEquals("data:video/mp4;base64,eA==", parts.get(1).path("video_url").asText());
            assertEquals("video/mp4", user.getMedia().getFirst().getMimeType().toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void continuousSseSurvivesLongerThanConfiguredIdleTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        for (int i = 0; i < 8; i++) {
                            String event =
                                    "data:"
                                        + " {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n";
                            exchange.getResponseBody()
                                    .write(event.getBytes(StandardCharsets.UTF_8));
                            exchange.getResponseBody().flush();
                            Thread.sleep(250);
                        }
                        String end =
                                "data:"
                                    + " {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                    + "data: [DONE]\n\n";
                        exchange.getResponseBody().write(end.getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var chat = builder(server).build(model(1), provider(server), null);
            var responses =
                    chat.stream(new Prompt("Question")).collectList().block(Duration.ofSeconds(8));
            assertNotNull(responses);
            String text =
                    responses.stream()
                            .flatMap(response -> response.getResults().stream())
                            .map(generation -> generation.getOutput().getText())
                            .filter(value -> value != null)
                            .reduce("", String::concat);
            assertEquals("xxxxxxxx", text);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitNullReasoningContentFallsBackToReasoningAliasForSyncAndStream() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    JsonNode request = JSON.readTree(exchange.getRequestBody());
                    String body;
                    if (request.path("stream").asBoolean()) {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        body =
                                "data:"
                                    + " {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\",\"reasoning_content\":null,\"reasoning\":\"think\"},\"finish_reason\":null}]}\n\n"
                                    + "data:"
                                    + " {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                    + "data: [DONE]\n\n";
                    } else {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        body =
                                RESPONSE.replace(
                                        "\"content\":\"ok\"",
                                        "\"content\":\"ok\",\"reasoning_content\":null,\"reasoning\":\"think\"");
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var chat = builder(server).build(model(3), provider(server), null);
            var response = chat.call(new Prompt("Question"));
            assertEquals(
                    "think",
                    response.getResult().getOutput().getMetadata().get("reasoningContent"));
            var responses =
                    chat.stream(new Prompt("Question")).collectList().block(Duration.ofSeconds(5));
            assertNotNull(responses);
            assertEquals(
                    "think",
                    responses
                            .getLast()
                            .getResult()
                            .getOutput()
                            .getMetadata()
                            .get("reasoningContent"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalledSseStillHitsConfiguredReadGapTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        String event =
                                "data:"
                                    + " {\"id\":\"r\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"alias\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"first\"},\"finish_reason\":null}]}\n\n";
                        exchange.getResponseBody().write(event.getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                        Thread.sleep(2000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var chat = builder(server).build(model(1), provider(server), null);
            RuntimeException failure =
                    assertThrows(
                            RuntimeException.class,
                            () ->
                                    chat.stream(new Prompt("Question"))
                                            .collectList()
                                            .block(Duration.ofSeconds(5)));
            boolean readTimeout = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause())
                if (cause instanceof java.net.SocketTimeoutException) readTimeout = true;
            assertTrue(
                    readTimeout,
                    "Stream should fail from socket idle timeout rather than overall deadline");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void continuousJsonBodyCanOutlastConfiguredReadTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
                        for (int start = 0; start < body.length; start += 25) {
                            exchange.getResponseBody()
                                    .write(body, start, Math.min(25, body.length - start));
                            exchange.getResponseBody().flush();
                            Thread.sleep(200);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            var chat = builder(server).build(model(1), provider(server), null);
            assertEquals("ok", chat.call(new Prompt("question")).getResult().getOutput().getText());
        } finally {
            server.stop(0);
        }
    }
}
