package vip.mate.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import vip.mate.llm.ProviderHttpError;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Retains the configured inner retry policy while provider SDK retries are disabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderRetryConfig.Settings.class)
public class ProviderRetryConfig {
    @Bean
    @ConditionalOnMissingBean(RetryTemplate.class)
    public RetryTemplate retryTemplate(Settings settings) {
        if (settings.getMaxAttempts() < 1)
            throw new IllegalArgumentException("Retry max attempts must be positive");
        Set<Integer> statuses = Set.copyOf(settings.getOnHttpCodes());
        SimpleRetryPolicy policy =
                new SimpleRetryPolicy(settings.getMaxAttempts(), Map.of(Exception.class, true)) {
                    @Override
                    public boolean canRetry(RetryContext context) {
                        if (!super.canRetry(context)) return false;
                        Throwable failure = context.getLastThrowable();
                        if (failure == null) return true;
                        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                        while (failure != null && visited.add(failure)) {
                            int status = ProviderHttpError.status(failure);
                            if (status != 0) return statuses.contains(status);
                            failure = failure.getCause();
                        }
                        return false;
                    }
                };
        Backoff configured = settings.getBackoff();
        if (configured.getInitialInterval() < 1
                || configured.getMultiplier() < 1
                || configured.getMaxInterval() < configured.getInitialInterval()) {
            throw new IllegalArgumentException("Invalid provider retry backoff configuration");
        }
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(configured.getInitialInterval());
        backoff.setMultiplier(configured.getMultiplier());
        backoff.setMaxInterval(configured.getMaxInterval());
        RetryTemplate retry = new RetryTemplate();
        retry.setRetryPolicy(policy);
        retry.setBackOffPolicy(backoff);
        return retry;
    }

    @ConfigurationProperties("spring.ai.retry")
    public static class Settings {
        private int maxAttempts = 2;
        private List<Integer> onHttpCodes = List.of(429, 503, 529);
        private Backoff backoff = new Backoff();

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int value) {
            maxAttempts = value;
        }

        public List<Integer> getOnHttpCodes() {
            return onHttpCodes;
        }

        public void setOnHttpCodes(List<Integer> value) {
            onHttpCodes = value;
        }

        public Backoff getBackoff() {
            return backoff;
        }

        public void setBackoff(Backoff value) {
            backoff = value;
        }
    }

    public static class Backoff {
        private long initialInterval = 3000;
        private double multiplier = 3;
        private long maxInterval = 60000;

        public long getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(long value) {
            initialInterval = value;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double value) {
            multiplier = value;
        }

        public long getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(long value) {
            maxInterval = value;
        }
    }
}
