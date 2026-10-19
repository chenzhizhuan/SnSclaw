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
 * Production posture: with {@code mateclaw.openapi.expose-ui=false} the Swagger
 * UI / OpenAPI document paths must NOT be anonymously reachable. They fall under
 * an explicit {@code hasRole('ADMIN')} rule in {@link SecurityConfig}, so an
 * unauthenticated request is rejected by the authentication entry point (401)
 * instead of leaking the full API surface.
 *
 * <p>Uses a real embedded servlet container ({@code RANDOM_PORT}) because the app
 * registers a WebSocket endpoint that requires a servlet {@code ServerContainer},
 * which the MockMvc-only environment does not provide.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.openapi.expose-ui=false"
        }
)
class OpenApiLockedDownAccessTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("Anonymous OpenAPI JSON is blocked (401) when expose-ui=false")
    void anonymousApiDocsBlocked() throws Exception {
        int status = getStatus("/v3/api-docs");
        assertEquals(401, status);
    }

    @Test
    @DisplayName("Anonymous Swagger UI is blocked (401) when expose-ui=false")
    void anonymousSwaggerUiBlocked() throws Exception {
        int status = getStatus("/swagger-ui/index.html");
        assertEquals(401, status);
    }

    @Test
    @DisplayName("Anonymous generated-file download is blocked (401)")
    void anonymousGeneratedFileDownloadBlocked() throws Exception {
        int status = getStatus("/api/v1/files/generated/00000000-0000-0000-0000-000000000000");
        assertEquals(401, status);
    }

    @Test
    @DisplayName("A genuinely public endpoint stays reachable when Swagger is locked")
    void publicEndpointStillReachable() throws Exception {
        // GET /api/v1/settings/language is permitAll (first-paint i18n); proves
        // the lockdown is scoped to the OpenAPI paths, not a blanket denial.
        int status = getStatus("/api/v1/settings/language");
        assertEquals(200, status);
    }
    private int getStatus(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
