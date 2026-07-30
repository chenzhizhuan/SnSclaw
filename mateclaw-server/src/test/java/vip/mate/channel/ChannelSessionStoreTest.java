package vip.mate.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.model.ChannelSessionEntity;
import vip.mate.channel.repository.ChannelSessionMapper;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Issue #526: deleting a conversation removes the {@code mate_channel_session}
 * row inside the DB cascade, but the cache is this class's private state. If
 * the entry survives, the next inbound message updates a primary key that no
 * longer exists — 0 rows, no re-insert — and the channel session stays missing.
 */
class ChannelSessionStoreTest {

    private ChannelSessionMapper mapper;
    private ChannelSessionStore store;

    @BeforeEach
    void setUp() {
        mapper = mock(ChannelSessionMapper.class);
        store = new ChannelSessionStore(mapper);
    }

    @Test
    @DisplayName("deleting the conversation evicts the cached session")
    void conversationDeleteEvictsCache() {
        when(mapper.selectOne(any())).thenReturn(null);
        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-1", "alice", "Alice", 1L);
        assertNotNull(store.getSession("dingtalk:1:alice"));

        store.onConversationDeleted(new ConversationDeletedEvent("dingtalk:1:alice"));

        assertNull(store.getSession("dingtalk:1:alice"),
                "a phantom entry would keep updating a row that no longer exists");
    }

    @Test
    @DisplayName("a cached entry pointing at a deleted row self-heals into a fresh insert")
    void staleCacheEntryIsRecreated() {
        when(mapper.selectOne(any())).thenReturn(null);
        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-1", "alice", "Alice", 1L);
        verify(mapper, times(1)).insert(any(ChannelSessionEntity.class));

        // The row is deleted behind our back (console delete on another node,
        // or an event listener that never ran) — updateById now affects 0 rows.
        when(mapper.updateById(any(ChannelSessionEntity.class))).thenReturn(0);

        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-2", "alice", "Alice", 1L);

        // Must not stop at the failed update: re-insert so proactive push and
        // cron channel resolution keep working.
        verify(mapper, times(2)).insert(any(ChannelSessionEntity.class));
        assertEquals("hook-2", store.getTargetId("dingtalk:1:alice"));
    }

    @Test
    @DisplayName("a successful update does not fall through to an insert")
    void liveRowIsUpdatedInPlace() {
        when(mapper.selectOne(any())).thenReturn(null);
        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-1", "alice", "Alice", 1L);
        when(mapper.updateById(any(ChannelSessionEntity.class))).thenReturn(1);

        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-2", "alice", "Alice", 1L);

        verify(mapper, times(1)).insert(any(ChannelSessionEntity.class));
        assertEquals("hook-2", store.getTargetId("dingtalk:1:alice"));
    }

    @Test
    @DisplayName("remove() clears both layers")
    void removeClearsCacheAndRow() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.delete(any())).thenReturn(1);
        store.saveOrUpdate("dingtalk:1:alice", "dingtalk", "hook-1", "alice", "Alice", 1L);

        assertEquals(1, store.remove("dingtalk:1:alice"));
        assertNull(store.getSession("dingtalk:1:alice"));
        verify(mapper).delete(any());
    }
}
