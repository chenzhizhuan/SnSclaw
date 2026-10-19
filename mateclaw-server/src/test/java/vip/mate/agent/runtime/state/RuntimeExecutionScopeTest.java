package vip.mate.agent.runtime.state;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.agent.delegation.SubagentRunContext;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.tool.builtin.DelegationContext;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class RuntimeExecutionScopeTest {
    private static RunState state(String id, String level) {
        return new RunState(
                Map.of(
                        "chat_origin",
                        ChatOrigin.EMPTY.withConversationId(id),
                        "requested_thinking_level",
                        level));
    }

    private static void assertContext(String id, String level) {
        assertEquals(id, ChatOriginHolder.get().conversationId());
        assertEquals(level, ThinkingLevelHolder.get());
    }

    @Test
    void nestedScopesRestoreOuterAndPreviousWorkerValues() throws Exception {
        ChatOrigin previous = ChatOrigin.EMPTY.withConversationId("worker");
        ChatOriginHolder.set(previous);
        ThinkingLevelHolder.set("off");
        try {
            assertEquals(
                    "result",
                    RuntimeExecutionScope.call(
                            state("outer", "high"),
                            () -> {
                                assertContext("outer", "high");
                                RuntimeExecutionScope.call(
                                        state("inner", "low"),
                                        () -> {
                                            assertContext("inner", "low");
                                            return null;
                                        });
                                assertContext("outer", "high");
                                return "result";
                            }));
            assertContext("worker", "off");
        } finally {
            ChatOriginHolder.clear();
            ThinkingLevelHolder.clear();
        }
    }

    @Test
    void checkedExceptionRestoresWorkerAndMissingValuesClearStaleContext() throws Exception {
        ChatOriginHolder.set(ChatOrigin.EMPTY.withConversationId("stale"));
        ThinkingLevelHolder.set("max");
        try {
            Exception failure = new Exception("expected");
            assertSame(
                    failure,
                    assertThrows(
                            Exception.class,
                            () ->
                                    RuntimeExecutionScope.call(
                                            new RunState(),
                                            () -> {
                                                assertEquals(
                                                        ChatOrigin.EMPTY, ChatOriginHolder.get());
                                                assertNull(ThinkingLevelHolder.get());
                                                throw failure;
                                            })));
            assertContext("stale", "max");
        } finally {
            ChatOriginHolder.clear();
            ThinkingLevelHolder.clear();
        }
    }

    @Test
    void openScopesRestoreOnceAndRejectCrossThreadClose() throws Exception {
        ChatOriginHolder.set(ChatOrigin.EMPTY.withConversationId("worker"));
        ThinkingLevelHolder.set("off");
        var pool = Executors.newSingleThreadExecutor();
        try {
            AutoCloseable scope = RuntimeExecutionScope.open(state("run", "high"));
            assertContext("run", "high");
            assertTrue(
                    pool.submit(
                                    () -> {
                                        try {
                                            scope.close();
                                            return false;
                                        } catch (IllegalStateException expected) {
                                            return true;
                                        } catch (Exception unexpected) {
                                            throw new AssertionError(unexpected);
                                        }
                                    })
                            .get());
            assertContext("run", "high");
            scope.close();
            assertContext("worker", "off");
            ChatOriginHolder.set(ChatOrigin.EMPTY.withConversationId("later"));
            ThinkingLevelHolder.set("low");
            scope.close();
            assertContext("later", "low");
        } finally {
            pool.shutdownNow();
            ChatOriginHolder.clear();
            ThinkingLevelHolder.clear();
        }
    }

    @Test
    void openTryWithResourcesRestoresOnExceptionalNestedExit() throws Exception {
        ChatOriginHolder.clear();
        ThinkingLevelHolder.clear();
        try (AutoCloseable outer = RuntimeExecutionScope.open(state("outer", "high"))) {
            assertThrows(
                    Exception.class,
                    () -> {
                        try (AutoCloseable inner =
                                RuntimeExecutionScope.open(state("inner", "low"))) {
                            assertContext("inner", "low");
                            throw new Exception("expected");
                        }
                    });
            assertContext("outer", "high");
        }
        assertEquals(ChatOrigin.EMPTY, ChatOriginHolder.get());
        assertNull(ThinkingLevelHolder.get());
    }

    @Test
    void concurrentRunsAndReusedWorkersKeepTheirOwnContext() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        try {
            var first =
                    pool.submit(
                            () ->
                                    RuntimeExecutionScope.call(
                                            state("first", "low"),
                                            () -> {
                                                entered.countDown();
                                                assertTrue(entered.await(5, TimeUnit.SECONDS));
                                                assertContext("first", "low");
                                                return null;
                                            }));
            var second =
                    pool.submit(
                            () ->
                                    RuntimeExecutionScope.call(
                                            state("second", "high"),
                                            () -> {
                                                entered.countDown();
                                                assertTrue(entered.await(5, TimeUnit.SECONDS));
                                                assertContext("second", "high");
                                                return null;
                                            }));
            first.get();
            second.get();
            for (int i = 0; i < 8; i++)
                pool.submit(
                                () -> {
                                    assertEquals(ChatOrigin.EMPTY, ChatOriginHolder.get());
                                    assertNull(ThinkingLevelHolder.get());
                                })
                        .get();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void delegatedWorkerRetainsRestrictionsAndRestoresPreviousFrameAfterFailure() throws Exception {
        var child = new SubagentRunContext(3, "parent", "root", "child-id", Set.of("deleteFile"));
        RunState captured = new RunState(Map.of("subagent_run_context", child));
        var pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(
                            () -> {
                                var previous =
                                        new SubagentRunContext(
                                                1,
                                                "worker-parent",
                                                "worker-root",
                                                "worker",
                                                Set.of("stale"));
                                DelegationContext.push(previous);
                                try {
                                    assertThrows(
                                            IllegalStateException.class,
                                            () ->
                                                    RuntimeExecutionScope.call(
                                                            captured,
                                                            () -> {
                                                                assertEquals(
                                                                        child,
                                                                        DelegationContext
                                                                                .current());
                                                                assertEquals(
                                                                        3,
                                                                        DelegationContext
                                                                                .currentDepth());
                                                                assertEquals(
                                                                        Set.of("deleteFile"),
                                                                        DelegationContext
                                                                                .childDeniedTools());
                                                                throw new IllegalStateException(
                                                                        "callback failed");
                                                            }));
                                    assertEquals(previous, DelegationContext.current());
                                } finally {
                                    DelegationContext.exit();
                                }
                                assertEquals(SubagentRunContext.ROOT, DelegationContext.current());
                            })
                    .get();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void absentDelegationMasksWorkerFrameAndNestedScopeRestoresRunFrame() throws Exception {
        var stale = new SubagentRunContext(4, "stale", "stale-root", "stale-id", Set.of("stale"));
        DelegationContext.push(stale);
        try {
            RuntimeExecutionScope.call(
                    new RunState(),
                    () -> {
                        assertEquals(SubagentRunContext.ROOT, DelegationContext.current());
                        var child =
                                new SubagentRunContext(
                                        1, "parent", "root", "child", Set.of("denied"));
                        RuntimeExecutionScope.call(
                                new RunState(Map.of("subagent_run_context", child)),
                                () -> {
                                    assertEquals(child, DelegationContext.current());
                                    return null;
                                });
                        assertEquals(SubagentRunContext.ROOT, DelegationContext.current());
                        return null;
                    });
            assertEquals(stale, DelegationContext.current());
        } finally {
            DelegationContext.exit();
        }
    }
}
