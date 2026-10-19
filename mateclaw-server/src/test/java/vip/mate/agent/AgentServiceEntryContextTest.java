package vip.mate.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import reactor.core.publisher.Flux;

import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class AgentServiceEntryContextTest {
    private static final ChatOrigin PARENT = ChatOrigin.EMPTY.withConversationId("parent");
    private static final ChatOrigin CHILD = ChatOrigin.EMPTY.withConversationId("child");
    private static final ChatOrigin WORKER = ChatOrigin.EMPTY.withConversationId("worker");

    private AgentService service(BaseAgent agent) {
        var mapper = mock(AgentMapper.class);
        var entity = new AgentEntity();
        entity.setEnabled(true);
        entity.setDefaultThinkingLevel("low");
        when(mapper.selectById(1L)).thenReturn(entity);
        var properties = new MemoryProperties();
        properties.setLifecycleMediatorEnabled(false);
        var service =
                new AgentService(
                        mapper,
                        null,
                        mock(MemoryRecallTracker.class),
                        null,
                        properties,
                        null,
                        mock(ConversationMapper.class));
        ReflectionTestUtils.setField(
                service,
                "agentInstances",
                new ConcurrentHashMap<>(Map.of(1L, new ConcurrentHashMap<>(Map.of("", agent)))));
        return service;
    }

    @Test
    void nestedUsageSuccessRestoresParentAndDoesNotClearCompletionWorker() throws Exception {
        nested(false);
    }

    @Test
    void nestedUsageFailureRestoresParentAndDoesNotClearCompletionWorker() throws Exception {
        nested(true);
    }

    private void nested(boolean error) throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var completed = new CountDownLatch(1);
        var workerFailure = new AtomicReference<Throwable>();
        BaseAgent agent =
                mock(
                        BaseAgent.class,
                        withSettings().extraInterfaces(StructuredStreamCapable.class));
        when(((StructuredStreamCapable) agent)
                        .chatStructuredStream(anyString(), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(CHILD, ChatOriginHolder.get());
                            assertEquals("low", ThinkingLevelHolder.get());
                            return Flux.<AgentService.StreamDelta>create(
                                    sink ->
                                            worker.execute(
                                                    () -> {
                                                        ChatOriginHolder.set(WORKER);
                                                        ThinkingLevelHolder.set("worker-thinking");
                                                        try {
                                                            if (error)
                                                                sink.error(
                                                                        new IllegalStateException(
                                                                                "child-failure"));
                                                            else {
                                                                sink.next(
                                                                        new AgentService
                                                                                .StreamDelta(
                                                                                "child-answer",
                                                                                null));
                                                                sink.complete();
                                                            }
                                                            assertEquals(
                                                                    WORKER,
                                                                    ChatOriginHolder.get(),
                                                                    "child completion must not"
                                                                        + " clear unrelated worker"
                                                                        + " origin");
                                                            assertEquals(
                                                                    "worker-thinking",
                                                                    ThinkingLevelHolder.get(),
                                                                    "child completion must not"
                                                                        + " clear unrelated worker"
                                                                        + " thinking");
                                                        } catch (Throwable failure) {
                                                            workerFailure.set(failure);
                                                        } finally {
                                                            ChatOriginHolder.clear();
                                                            ThinkingLevelHolder.clear();
                                                            completed.countDown();
                                                        }
                                                    }));
                        });
        ChatOriginHolder.set(PARENT);
        ThinkingLevelHolder.set("high");
        try {
            var service = service(agent);
            if (error)
                assertEquals(
                        "child-failure",
                        assertThrows(
                                        IllegalStateException.class,
                                        () -> service.chatWithUsage(1L, "task", "child", CHILD))
                                .getMessage());
            else
                assertEquals(
                        "child-answer",
                        service.chatWithUsage(1L, "task", "child", CHILD).content());
            assertTrue(completed.await(3, TimeUnit.SECONDS));
            assertEquals(
                    PARENT,
                    ChatOriginHolder.get(),
                    "nested sync child must restore parent caller origin");
            assertEquals(
                    "high",
                    ThinkingLevelHolder.get(),
                    "nested sync child must restore parent caller thinking");
            if (workerFailure.get() != null) throw new AssertionError(workerFailure.get());
        } finally {
            ChatOriginHolder.clear();
            ThinkingLevelHolder.clear();
            worker.shutdownNow();
        }
    }

    @Test
    void synchronousEntriesRestoreParentOnSuccessAndFailure() {
        for (boolean error : new boolean[] {false, true}) {
            BaseAgent agent = mock(BaseAgent.class);
            org.mockito.stubbing.Answer<String> invoke =
                    invocation -> {
                        assertEquals(CHILD, ChatOriginHolder.get());
                        if (error) throw new IllegalStateException("sync-child-failure");
                        return "child-answer";
                    };
            when(agent.chat(anyString(), anyString())).thenAnswer(invoke);
            when(agent.execute(anyString(), anyString())).thenAnswer(invoke);
            when(agent.chatWithReplay(anyString(), anyString(), anyString())).thenAnswer(invoke);
            var service = service(agent);
            ChatOriginHolder.set(PARENT);
            ThinkingLevelHolder.set("high");
            try {
                List<Supplier<String>> entries =
                        List.of(
                                () -> service.chat(1L, "task", "child", CHILD),
                                () -> service.execute(1L, "task", "child", CHILD),
                                () -> service.chatWithReplay(1L, "task", "child", "{}", CHILD));
                for (var entry : entries) {
                    if (error) assertThrows(IllegalStateException.class, entry::get);
                    else assertEquals("child-answer", entry.get());
                    assertEquals(PARENT, ChatOriginHolder.get());
                    assertEquals("high", ThinkingLevelHolder.get());
                }
            } finally {
                ChatOriginHolder.clear();
                ThinkingLevelHolder.clear();
            }
        }
    }

    @Test
    void structuredSubscriptionUsesCapturedContextAndRestoresItsDifferentSubscriberThread()
            throws Exception {
        BaseAgent agent =
                mock(
                        BaseAgent.class,
                        withSettings().extraInterfaces(StructuredStreamCapable.class));
        when(((StructuredStreamCapable) agent)
                        .chatStructuredStream(anyString(), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(CHILD, ChatOriginHolder.get());
                            assertEquals("low", ThinkingLevelHolder.get());
                            return Flux.just(new AgentService.StreamDelta("child-answer", null));
                        });
        ChatOriginHolder.set(PARENT);
        ThinkingLevelHolder.set("high");
        var pool = Executors.newSingleThreadExecutor();
        try {
            var stream = service(agent).chatStructuredStream(1L, "task", "child", "", null, CHILD);
            assertEquals(PARENT, ChatOriginHolder.get());
            assertEquals("high", ThinkingLevelHolder.get());
            pool.submit(
                            () -> {
                                ChatOriginHolder.set(WORKER);
                                ThinkingLevelHolder.set("worker-thinking");
                                try {
                                    assertEquals("child-answer", stream.blockFirst().content());
                                    assertEquals(WORKER, ChatOriginHolder.get());
                                    assertEquals("worker-thinking", ThinkingLevelHolder.get());
                                } finally {
                                    ChatOriginHolder.clear();
                                    ThinkingLevelHolder.clear();
                                }
                            })
                    .get(3, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            ChatOriginHolder.clear();
            ThinkingLevelHolder.clear();
        }
    }

    @Test
    void nativePlainAndReplayStreamsRestoreTheirSubscriberContext() {
        for (boolean error : new boolean[] {false, true}) {
            BaseAgent agent = mock(BaseAgent.class);
            when(agent.chatStream(anyString(), anyString()))
                    .thenAnswer(
                            invocation -> {
                                assertEquals(CHILD, ChatOriginHolder.get());
                                assertEquals("high", ThinkingLevelHolder.get());
                                return error
                                        ? Flux.error(new IllegalStateException("stream-failure"))
                                        : Flux.just("answer");
                            });
            when(agent.chatWithReplayStream(anyString(), anyString(), anyString(), anyString()))
                    .thenAnswer(
                            invocation -> {
                                assertEquals(CHILD, ChatOriginHolder.get());
                                assertEquals("high", ThinkingLevelHolder.get());
                                return error
                                        ? Flux.error(new IllegalStateException("stream-failure"))
                                        : Flux.just(new AgentService.StreamDelta("answer", null));
                            });
            var service = service(agent);
            ChatOriginHolder.set(PARENT);
            ThinkingLevelHolder.set("high");
            try {
                List<Flux<?>> streams =
                        List.of(
                                service.chatStream(1L, "task", "child", CHILD),
                                service.chatWithReplayStream(1L, "task", "child", "{}", "", CHILD));
                for (var stream : streams) {
                    if (error) assertThrows(IllegalStateException.class, stream::blockLast);
                    else assertNotNull(stream.blockLast());
                    assertEquals(PARENT, ChatOriginHolder.get());
                    assertEquals("high", ThinkingLevelHolder.get());
                }
            } finally {
                ChatOriginHolder.clear();
                ThinkingLevelHolder.clear();
            }
        }
    }

    @Test
    void unstructuredFallbackClearsThinkingOnlyDuringAgentStateCapture() {
        BaseAgent agent = mock(BaseAgent.class);
        when(agent.chatStream(anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            assertEquals(CHILD, ChatOriginHolder.get());
                            assertNull(ThinkingLevelHolder.get());
                            return Flux.just("fallback-answer");
                        });
        ChatOriginHolder.set(PARENT);
        ThinkingLevelHolder.set("high");
        try {
            assertEquals(
                    "fallback-answer",
                    service(agent).chatWithUsage(1L, "task", "child", CHILD).content());
            assertEquals(PARENT, ChatOriginHolder.get());
            assertEquals("high", ThinkingLevelHolder.get());
        } finally {
            ChatOriginHolder.clear();
            ThinkingLevelHolder.clear();
        }
    }

    @Test
    void creatingStructuredFluxDoesNotChangeCallerContext() {
        BaseAgent agent =
                mock(
                        BaseAgent.class,
                        withSettings().extraInterfaces(StructuredStreamCapable.class));
        ChatOriginHolder.set(PARENT);
        ThinkingLevelHolder.set("high");
        try {
            assertNotNull(
                    service(agent).chatStructuredStream(1L, "task", "child", "", null, CHILD));
            assertEquals(PARENT, ChatOriginHolder.get());
            assertEquals("high", ThinkingLevelHolder.get());
            verifyNoInteractions((StructuredStreamCapable) agent);
        } finally {
            ChatOriginHolder.clear();
            ThinkingLevelHolder.clear();
        }
    }
}
