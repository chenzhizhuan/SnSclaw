package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pin the static "## About You" identity block appended to every agent's
 * system prompt. This is the cache-stable half of the self-introspection
 * feature — it answers "who are you / what are you based on", while the
 * volatile model line lives in RuntimeContextInjector.
 */
class AgentGraphBuilderIdentityBlockTest {

    @Test
    @DisplayName("identity block names SnSclaw and the core tech stack")
    void identityBlockMentionsPlatformAndStack() {
        String block = AgentGraphBuilder.ABOUT_YOU_BLOCK;

        assertTrue(block.contains("## About You"), "missing heading: " + block);
        assertTrue(block.contains("SnSclaw"), "must name the platform: " + block);
        assertTrue(block.contains("Java 21"), "must name the Java baseline: " + block);
        assertTrue(block.contains("Spring Boot 4"), "must name the application framework: " + block);
        assertTrue(block.contains("Spring AI 2"), "must name the model integration: " + block);
        assertTrue(block.contains("AgentScope 2"), "must name the agent runtime: " + block);
    }
}
