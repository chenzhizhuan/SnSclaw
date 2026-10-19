package vip.mate.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;

class ServletJsonContractTest {
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        Jackson2AutoConfiguration.class
    })
    @Import({JacksonConfig.class, Endpoint.class})
    static class Application {}

    @RestController
    static class Endpoint {
        @GetMapping("/contract")
        public Payload contract() {
            return new Payload(
                    9223372036854775806L,
                    9223372036854775805L,
                    LocalDateTime.of(2026, 10, 9, 12, 30));
        }

        @PostMapping("/contract")
        public Input echo(@RequestBody Input input) {
            return input;
        }
    }

    enum State {
        ACTIVE
    }

    record Payload(long id, Long parentId, LocalDateTime date) {}

    record Input(long id, State state, LocalDateTime date) {}

    @Test
    void servletPreservesSnowflakeAndIsoDateContracts() throws Exception {
        try (var context =
                new SpringApplicationBuilder(Application.class)
                        .properties(
                                "spring.main.banner-mode=off",
                                "spring.http.converters.preferred-json-mapper=jackson2")
                        .run("--server.port=0")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            var response =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + port
                                                                    + "/contract"))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            var json = new ObjectMapper().readTree(response.body());
            assertTrue(json.get("id").isString(), response.body());
            assertEquals("9223372036854775806", json.get("id").asString());
            assertEquals("9223372036854775805", json.get("parentId").asString());
            assertTrue(json.get("date").asString().startsWith("2026-10-09T12:30"));
            var posted =
                    HttpClient.newHttpClient()
                            .send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            "http://127.0.0.1:"
                                                                    + port
                                                                    + "/contract"))
                                            .header("Content-Type", "application/json")
                                            .POST(
                                                    HttpRequest.BodyPublishers.ofString(
                                                            "{\"id\":\"9223372036854775806\",\"state\":\"FUTURE_STATE\",\"date\":\"2026-10-09T12:30:00\"}"))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, posted.statusCode(), posted.body());
            var echo = new ObjectMapper().readTree(posted.body());
            assertEquals("9223372036854775806", echo.get("id").asString());
            assertTrue(echo.get("state").isNull());
            assertEquals("2026-10-09T12:30:00", echo.get("date").asString());
        }
    }
}
