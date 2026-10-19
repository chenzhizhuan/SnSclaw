package vip.mate.llm.cache;

import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.chat.messages.MessageType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.llm.model.ModelProtocol;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RFC-014 prompt cache 策略与序列化器的核心单测。 */
class PromptCacheStrategyTest {

    private static final SystemAndTailCacheStrategy STRATEGY = new SystemAndTailCacheStrategy();

    private static CachePlanContext ctxFor(List<Message> msgs, ModelProtocol protocol,
                                           int totalTokens, int turns) {
        return new CachePlanContext(new Prompt(msgs), protocol, totalTokens, turns,
                /*minPromptTokens=*/1024, /*maxBreakpoints=*/4,
                /*includeToolsBlock=*/true, CacheTtl.DEFAULT_5M);
    }

    @Test
    @DisplayName("短对话不缓存：低于 minPromptTokens 直接 NoOp")
    void shortConversationNotCached() {
        var ctx = ctxFor(List.of(new SystemMessage("hi"), new UserMessage("hello")),
                ModelProtocol.ANTHROPIC_MESSAGES, /*tokens=*/300, /*turns=*/2);
        assertFalse(STRATEGY.shouldCache(ctx));
        assertTrue(STRATEGY.plan(ctx).isEmpty());
    }

    @Test
    @DisplayName("不支持的协议不缓存：DashScope/OpenAI 兼容直接 NoOp")
    void unsupportedProtocolNotCached() {
        var ctx = ctxFor(List.of(new SystemMessage("a".repeat(2048))),
                ModelProtocol.DASHSCOPE_NATIVE, /*tokens=*/4096, /*turns=*/3);
        assertFalse(STRATEGY.shouldCache(ctx));
    }

    @Test
    @DisplayName("长 system + 对话 → 至少一个 SYSTEM_TAIL 断点")
    void longSystemEarnsBreakpoint() {
        var msgs = List.of(
                (Message) new SystemMessage("a".repeat(1024)),
                new UserMessage("first question"),
                new AssistantMessage("first answer"),
                new UserMessage("second question"),
                new AssistantMessage("second answer"),
                new UserMessage("third question")
        );
        var ctx = ctxFor(msgs, ModelProtocol.ANTHROPIC_MESSAGES, /*tokens=*/4096, /*turns=*/3);
        var plan = STRATEGY.plan(ctx);
        assertFalse(plan.isEmpty());
        assertTrue(plan.breakpoints().stream().anyMatch(b -> b.kind() == BreakpointKind.SYSTEM_TAIL));
        // tools 段、messages 倒数第三、最后 user 都应在内
        assertTrue(plan.size() >= 2);
        assertTrue(plan.size() <= 4);
    }

    @Test
    @DisplayName("断点上限受 maxBreakpoints 约束")
    void breakpointCountCapped() {
        var msgs = List.of(
                (Message) new SystemMessage("a".repeat(1024)),
                new UserMessage("q1"), new AssistantMessage("a1"),
                new UserMessage("q2"), new AssistantMessage("a2"),
                new UserMessage("q3")
        );
        var capped = new CachePlanContext(new Prompt(msgs), ModelProtocol.ANTHROPIC_MESSAGES,
                /*tokens=*/4096, /*turns=*/3, 1024, /*maxBreakpoints=*/2, true, CacheTtl.DEFAULT_5M);
        assertEquals(2, STRATEGY.plan(capped).size());
    }

    @Test
    @DisplayName("AnthropicCacheOptionsFactory: enabled 状态下产出非 DISABLED 配置")
    void anthropicFactoryEmitsCacheOptions() {
        var props = new CacheProperties();
        props.setEnabled(true);
        props.setIncludeToolsBlock(true);
        props.setTtl(CacheProperties.Ttl.EXTENDED_1H);
        var factory = new AnthropicCacheOptionsFactory(props);
        var opts = factory.build();
        assertNotEquals(AnthropicCacheStrategy.NONE, opts.getStrategy());
        assertEquals(AnthropicCacheStrategy.CONVERSATION_HISTORY,
                opts.getStrategy());
        // ttl 映射到 SYSTEM/USER ONE_HOUR
        var ttlMap = opts.getMessageTypeTtl();
        assertEquals(AnthropicCacheTtl.ONE_HOUR,
                ttlMap.get(MessageType.SYSTEM));
    }

    @Test
    @DisplayName("AnthropicCacheOptionsFactory: disabled → NONE strategy")
    void anthropicFactoryDisabledFastPath() {
        var props = new CacheProperties();
        props.setEnabled(false);
        var factory = new AnthropicCacheOptionsFactory(props);
        assertEquals(AnthropicCacheStrategy.NONE, factory.build().getStrategy());
    }

    @Test
    @DisplayName("AdaptiveCacheStrategy: 连续 miss 后降级，hit 后立即恢复")
    void adaptiveDegradesAndRecovers() {
        var inner = new SystemAndTailCacheStrategy();
        var adaptive = new AdaptiveCacheStrategy(inner, /*missThreshold=*/3, /*coolDownMs=*/60_000);
        var ctx = ctxFor(List.of(new SystemMessage("a".repeat(1024)),
                        new UserMessage("q1"), new AssistantMessage("a1"),
                        new UserMessage("q2")),
                ModelProtocol.ANTHROPIC_MESSAGES, 4096, 2);

        assertTrue(adaptive.shouldCache(ctx));
        adaptive.recordMiss(); adaptive.recordMiss();
        assertTrue(adaptive.shouldCache(ctx), "未达阈值不应降级");
        adaptive.recordMiss();
        assertFalse(adaptive.shouldCache(ctx), "达阈值应降级");
        adaptive.recordHit();
        assertTrue(adaptive.shouldCache(ctx), "命中后应立即恢复");
    }

    @Test
    @DisplayName("CacheUsageExtractor: 从 record 风格的 native usage 抽取 cache 字段")
    void cacheUsageExtractorReflectsRecordAccessor() {
        // 用一个匿名 record 模拟 AnthropicApi.Usage 形态
        record FakeNativeUsage(Integer cacheReadInputTokens, Integer cacheCreationInputTokens) {}
        var fakeUsage = new org.springframework.ai.chat.metadata.DefaultUsage(
                100, 50, 150, new FakeNativeUsage(40, 60));
        var tokens = CacheUsageExtractor.extract(fakeUsage);
        assertEquals(40, tokens.cacheReadTokens());
        assertEquals(60, tokens.cacheWriteTokens());
    }

    @Test
    @DisplayName("CacheUsageExtractor: 不支持的 native usage 返回 EMPTY")
    void cacheUsageExtractorEmptyForUnknownProvider() {
        var openAiLikeUsage = new org.springframework.ai.chat.metadata.DefaultUsage(100, 50, 150, "no cache here");
        var tokens = CacheUsageExtractor.extract(openAiLikeUsage);
        assertSame(CacheUsageExtractor.CacheTokens.EMPTY, tokens);
    }
}
