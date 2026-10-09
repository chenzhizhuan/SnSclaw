package vip.mate.decision;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import vip.mate.decision.api.DecisionMode;
import vip.mate.decision.api.DecisionType;
import vip.mate.decision.config.DecisionProperties;
import vip.mate.decision.core.DecisionService;
import vip.mate.decision.provider.LlmDecisionProvider;
import vip.mate.decision.provider.RuleDecisionProvider;
import vip.mate.decision.record.DecisionRecordStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionWiringTest {
    @Test void beansBindDefaultsAndScenarioOverridesWithoutModelInfrastructure() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("mate.decision.scenarios.AGENT_ROUTING=OFF")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var p = context.getBean(DecisionProperties.class);
                    assertEquals(DecisionMode.SHADOW, p.getMode());
                    assertEquals(DecisionMode.OFF, p.modeFor(DecisionType.AGENT_ROUTING));
                    assertNotNull(context.getBean(DecisionService.class));
                });
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DecisionProperties.class)
    @Import({DecisionService.class, RuleDecisionProvider.class, LlmDecisionProvider.class})
    static class Config {
        @Bean DecisionRecordStore store() { return mock(DecisionRecordStore.class); }
        @Bean MeterRegistry metrics() { return new SimpleMeterRegistry(); }
    }
}
