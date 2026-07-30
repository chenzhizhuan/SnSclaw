package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.tts.TtsService;
import vip.mate.workspace.conversation.ConversationService;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Issue #526: a redelivered IM message must not start a second agent turn.
 *
 * <p>Platforms redeliver when an ack is late, lost, or non-200. Before the
 * router claimed inbound identities, every redelivery ran a full independent
 * turn — the user got the same answer twice and the conversation gained a
 * duplicate user/assistant pair. These tests pin the claim at {@code enqueue},
 * which is the one path every channel goes through.
 */
class ChannelMessageRouterInboundDedupTest {

    @Test
    @DisplayName("a redelivered message with the same id runs the turn only once")
    void redeliveryIsDropped() {
        Fixture f = new Fixture();

        // Spaced past the debounce window so a NON-deduped redelivery would
        // start its own turn — otherwise the merger alone would produce one
        // turn and the assertion would prove nothing.
        f.enqueueSpaced("dt-msg-1", "你好");
        f.enqueueSpaced("dt-msg-1", "你好");
        f.enqueueSpaced("dt-msg-1", "你好");

        f.awaitTurns(1);
    }

    @Test
    @DisplayName("distinct message ids each get their own turn")
    void distinctMessagesEachRun() {
        Fixture f = new Fixture();

        f.enqueueSpaced("dt-msg-1", "你好");
        f.enqueueSpaced("dt-msg-2", "再问一次");

        f.awaitTurns(2);
    }

    @Test
    @DisplayName("no platform id and no timestamp fails open rather than dropping a real message")
    void missingIdentityFailsOpen() {
        Fixture f = new Fixture();

        f.enqueueWithoutIdentity("你好");
        Fixture.sleep(ChannelMessageRouter.DEBOUNCE_MS * 3);
        f.enqueueWithoutIdentity("你好");

        // Both must get through: with nothing stable to key on, silently
        // swallowing the second message would lose real user input.
        f.awaitTurns(2);
    }

    @Test
    @DisplayName("the same platform id on a different channel is not a duplicate")
    void identitiesAreScopedPerChannel() {
        Fixture f = new Fixture();

        f.enqueue(1L, "shared-id", "你好");
        f.enqueue(2L, "shared-id", "你好");

        f.awaitTurns(2);
    }

    // ==================== helpers ====================

    private static final class Fixture {
        final ConversationService conversationService = mock(ConversationService.class);
        final ChannelService channelService = mock(ChannelService.class);
        final ChannelAdapter adapter = mock(ChannelAdapter.class);
        final ChannelMessageRouter router;

        Fixture() {
            AgentService agentService = mock(AgentService.class);
            ChannelSessionStore channelSessionStore = mock(ChannelSessionStore.class);
            ApprovalWorkflowService approvalService = mock(ApprovalWorkflowService.class);
            ApprovalNotificationService approvalNotificationService = mock(ApprovalNotificationService.class);
            ConversationCompletionPublisher completionPublisher = mock(ConversationCompletionPublisher.class);
            TtsService ttsService = mock(TtsService.class);
            ChatStreamTracker streamTracker = mock(ChatStreamTracker.class);
            ChannelChatOriginFactory chatOriginFactory = mock(ChannelChatOriginFactory.class);
            ChannelErrorClassifier errorClassifier = mock(ChannelErrorClassifier.class);

            router = new ChannelMessageRouter(agentService, conversationService,
                    channelService, channelSessionStore, approvalService, approvalNotificationService,
                    completionPublisher, ttsService, new ObjectMapper(), streamTracker,
                    chatOriginFactory, errorClassifier,
                    new InboundMessageDeduplicator(new ChannelDedupProperties()));

            when(adapter.getChannelType()).thenReturn("dingtalk");
            // Channel lookups return a live, agent-bound row for any id.
            when(channelService.getChannel(anyLong())).thenAnswer(inv -> channel(inv.getArgument(0)));
        }

        private static ChannelEntity channel(Long id) {
            ChannelEntity e = new ChannelEntity();
            e.setId(id);
            e.setName("dingtalk-" + id);
            e.setChannelType("dingtalk");
            e.setEnabled(true);
            e.setAgentId(100L);
            e.setWorkspaceId(1L);
            return e;
        }

        void enqueue(String messageId, String content) {
            enqueue(1L, messageId, content);
        }

        /**
         * Enqueue, then wait out the debounce window so the next send cannot be
         * merged into this one. Dedup and the merger both collapse traffic into
         * fewer turns; spacing them apart is what makes the assertion attribute
         * the collapse to dedup.
         */
        void enqueueSpaced(String messageId, String content) {
            enqueue(1L, messageId, content);
            sleep(ChannelMessageRouter.DEBOUNCE_MS * 3);
        }

        void enqueue(Long channelId, String messageId, String content) {
            router.enqueue(ChannelMessage.builder()
                    .messageId(messageId)
                    .channelType("dingtalk")
                    .senderId("alice")
                    .content(content)
                    .replyToken("reply-1")
                    .timestamp(LocalDateTime.of(2026, 1, 1, 0, 0))
                    .build(), adapter, channel(channelId));
        }

        void enqueueWithoutIdentity(String content) {
            router.enqueue(ChannelMessage.builder()
                    .channelType("dingtalk")
                    .senderId("alice")
                    .content(content)
                    .replyToken("reply-1")
                    .build(), adapter, channel(1L));
        }

        /**
         * Count turns by the get-or-create every processed message performs
         * before the agent runs. Polls past the debounce + queue handoff, then
         * holds steady long enough that a late extra turn would still fail the
         * count.
         */
        void awaitTurns(int expected) {
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (turnCount() >= expected) {
                    break;
                }
                sleep(50);
            }
            // A duplicate would arrive one debounce window behind the original;
            // wait it out before asserting the exact count.
            sleep(ChannelMessageRouter.DEBOUNCE_MS * 2);
            verify(conversationService, times(expected)).getOrCreateSharedConversation(
                    anyString(), eq(100L), anyLong(), isNull(), isNull());
        }

        private int turnCount() {
            return mockingDetails(conversationService).getInvocations().stream()
                    .filter(i -> "getOrCreateSharedConversation".equals(i.getMethod().getName()))
                    .filter(i -> i.getArguments().length == 5)
                    .toList()
                    .size();
        }

        static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
