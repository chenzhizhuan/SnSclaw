package vip.mate.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;

import org.junit.jupiter.api.Test;

import vip.mate.llm.dashscope.NativeDashScopeException;

import java.util.Map;
import java.util.Optional;

class ProviderHttpErrorTest {
    @Test
    void openAiSdkRetainsStatusBodyAndRetryHeaders() {
        var error =
                RateLimitException.builder()
                        .headers(Headers.builder().put("Retry-After", "7").build())
                        .error(
                                ErrorObject.builder()
                                        .code("engine_overloaded")
                                        .param(Optional.empty())
                                        .message("Busy")
                                        .type("capacity")
                                        .build())
                        .build();
        assertEquals(429, ProviderHttpError.status(error));
        assertEquals("7", ProviderHttpError.headers(error).getFirst("retry-after"));
        assertTrue(ProviderHttpError.body(error).contains("engine_overloaded"));
    }

    @Test
    void anthropicSdkRetainsStatusBodyAndRetryHeaders() {
        var error =
                com.anthropic.errors.RateLimitException.builder()
                        .headers(
                                com.anthropic.core.http.Headers.builder()
                                        .put("retry-after", "9")
                                        .build())
                        .body(
                                com.anthropic.core.JsonValue.from(
                                        Map.of("error", Map.of("type", "rate_limit_error"))))
                        .build();
        assertEquals(429, ProviderHttpError.status(error));
        assertEquals("9", ProviderHttpError.headers(error).getFirst("retry-after"));
        assertTrue(ProviderHttpError.body(error).contains("rate_limit_error"));
    }

    @Test
    void nativeStatusAndNonHttpFailuresAreDistinct() {
        assertEquals(
                503,
                ProviderHttpError.status(new NativeDashScopeException(503, "Busy", "retry", "id")));
        assertEquals(0, ProviderHttpError.status(new IllegalArgumentException("bad arguments")));
        assertNull(ProviderHttpError.headers(new IllegalArgumentException("bad arguments")));
    }
}
