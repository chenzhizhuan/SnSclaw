package vip.mate.config;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.RateLimitException;

import org.junit.jupiter.api.Test;

import vip.mate.llm.dashscope.NativeDashScopeException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class ProviderRetryConfigTest {
    private ProviderRetryConfig.Settings settings() {
        var settings = new ProviderRetryConfig.Settings();
        settings.getBackoff().setInitialInterval(1);
        settings.getBackoff().setMaxInterval(2);
        return settings;
    }

    @Test
    void transientStatusRetriesExactlyConfiguredAttempts() {
        var retry = new ProviderRetryConfig().retryTemplate(settings());
        var attempts = new AtomicInteger();
        assertEquals(
                "ok",
                retry.execute(
                        context -> {
                            if (attempts.incrementAndGet() == 1)
                                throw new NativeDashScopeException(503, "Busy", "retry", null);
                            return "ok";
                        }));
        assertEquals(2, attempts.get());
    }

    @Test
    void nonRetryableStatusAndExplicitOneAttemptNeverReplay() {
        for (int status : List.of(400, 401, 404)) {
            var retry = new ProviderRetryConfig().retryTemplate(settings());
            var attempts = new AtomicInteger();
            assertThrows(
                    NativeDashScopeException.class,
                    () ->
                            retry.execute(
                                    context -> {
                                        attempts.incrementAndGet();
                                        throw new NativeDashScopeException(
                                                status, "Invalid", "fail", null);
                                    }));
            assertEquals(1, attempts.get());
        }
        var settings = settings();
        settings.setMaxAttempts(1);
        var attempts = new AtomicInteger();
        assertThrows(
                NativeDashScopeException.class,
                () ->
                        new ProviderRetryConfig()
                                .retryTemplate(settings)
                                .execute(
                                        context -> {
                                            attempts.incrementAndGet();
                                            throw new NativeDashScopeException(
                                                    429, "RateLimit", "fail", null);
                                        }));
        assertEquals(1, attempts.get());
    }

    @Test
    void configuredStatusListControlsRetriesThroughCauseChain() {
        var settings = settings();
        settings.setOnHttpCodes(List.of(529));
        var attempts = new AtomicInteger();
        var retry = new ProviderRetryConfig().retryTemplate(settings);
        assertEquals(
                "ok",
                retry.execute(
                        context -> {
                            if (attempts.incrementAndGet() == 1)
                                throw new IllegalStateException(
                                        "wrapper",
                                        new NativeDashScopeException(
                                                529, "Overloaded", "retry", null));
                            return "ok";
                        }));
        assertEquals(2, attempts.get());
        attempts.set(0);
        assertThrows(
                NativeDashScopeException.class,
                () ->
                        retry.execute(
                                context -> {
                                    attempts.incrementAndGet();
                                    throw new NativeDashScopeException(
                                            503, "Busy", "excluded", null);
                                }));
        assertEquals(1, attempts.get());
    }

    @Test
    void wrappedSdkRateLimitRetainsConfiguredRetryPolicy() {
        var retry = new ProviderRetryConfig().retryTemplate(settings());
        var attempts = new AtomicInteger();
        var failure =
                RateLimitException.builder()
                        .headers(Headers.builder().put("retry-after", "1").build())
                        .body(JsonValue.from(Map.of("error", Map.of("type", "rate_limit_error"))))
                        .build();
        assertEquals(
                "ok",
                retry.execute(
                        context -> {
                            if (attempts.incrementAndGet() == 1)
                                throw new IllegalStateException("SDK failed", failure);
                            return "ok";
                        }));
        assertEquals(2, attempts.get());
    }
}
