package vip.mate.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Local/dev posture: with {@code mateclaw.openapi.expose-ui=true} (the base
 * {@code application.yml} default) the Swagger UI / OpenAPI document stays
 * anonymously reachable so developers can browse and debug without a login.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.openapi.expose-ui=true"
        }
)
class OpenApiExposedAccessTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("Anonymous OpenAPI JSON is reachable (200) when expose-ui=true")
    void anonymousApiDocsReachable() throws Exception {
        int status = getStatus("/v3/api-docs");
        assertEquals(200, status);
    }
    private int getStatus(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
