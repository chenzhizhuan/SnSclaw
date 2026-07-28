package vip.mate.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.channel.model.ChannelSessionEntity;
import vip.mate.channel.repository.ChannelSessionMapper;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道会话存储
 * <p>
 * 实现 proactive send 机制，缓存各渠道的会话标识映射。
 * 每次收到用户消息时自动更新，将 conversationId 映射到平台推送所需的标识。
 * <p>
 * 内存 + DB 双层持久化：
 * - 内存层（ConcurrentHashMap）提供快速查询
 * - DB 层（mate_channel_session 表）保证重启后恢复
 *
 * @author SnSclaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelSessionStore {

    private final ChannelSessionMapper sessionMapper;

    /** 内存缓存：conversationId -> ChannelSessionEntity */
    private final ConcurrentHashMap<String, ChannelSessionEntity> cache = new ConcurrentHashMap<>();

    /** 缓存最大容量 */
    private static final int MAX_CACHE_SIZE = 10000;

    /** 会话过期时间（天） */
    private static final int SESSION_TTL_DAYS = 30;

    /**
     * 应用启动时从 DB 加载所有会话到内存
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        List<ChannelSessionEntity> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChannelSessionEntity>().orderByDesc(ChannelSessionEntity::getLastActiveTime));
        for (ChannelSessionEntity session : sessions) {
            cache.put(session.getConversationId(), session);
        }
        log.info("ChannelSessionStore initialized: loaded {} sessions from DB", sessions.size());
    }

    /**
     * 保存或更新会话标识（收到用户消息时调用）
     *
     * @param conversationId 会话ID（如 dingtalk:xxx）
     * @param channelType    渠道类型
     * @param targetId       推送目标标识（sessionWebhook / chat_id / channel_id）
     * @param senderId       发送者ID
     * @param senderName     发送者名称
     * @param channelId      渠道配置ID
     */
    public void saveOrUpdate(String conversationId, String channelType, String targetId,
                             String senderId, String senderName, Long channelId) {
        LocalDateTime now = LocalDateTime.now();

        ChannelSessionEntity existing = cache.get(conversationId);
        if (existing != null) {
            // 更新内存和 DB
            existing.setTargetId(targetId);
            existing.setSenderId(senderId);
            existing.setSenderName(senderName);
            existing.setChannelId(channelId);
            existing.setLastActiveTime(now);
            sessionMapper.updateById(existing);
            log.debug("Updated channel session: conversationId={}, targetId={}", conversationId, targetId);
        } else {
            // 先查 DB（可能是上次启动后的新记录）
            ChannelSessionEntity dbEntity = sessionMapper.selectOne(
                    new LambdaQueryWrapper<ChannelSessionEntity>()
                            .eq(ChannelSessionEntity::getConversationId, conversationId));

            if (dbEntity != null) {
                dbEntity.setTargetId(targetId);
                dbEntity.setSenderId(senderId);
                dbEntity.setSenderName(senderName);
                dbEntity.setChannelId(channelId);
                dbEntity.setLastActiveTime(now);
                sessionMapper.updateById(dbEntity);
                cache.put(conversationId, dbEntity);
                log.debug("Updated channel session from DB: conversationId={}", conversationId);
            } else {
                // 新建
                ChannelSessionEntity entity = new ChannelSessionEntity();
                entity.setConversationId(conversationId);
                entity.setChannelType(channelType);
                entity.setTargetId(targetId);
                entity.setSenderId(senderId);
                entity.setSenderName(senderName);
                entity.setChannelId(channelId);
                entity.setLastActiveTime(now);
                sessionMapper.insert(entity);
                cache.put(conversationId, entity);
                log.debug("Created channel session: conversationId={}, targetId={}", conversationId, targetId);

                // 容量保护：超过上限时淘汰最久未活跃的会话
                evictIfNeeded();
            }
        }
    }

    /**
     * 淘汰过期和超量的缓存条目
     */
    private void evictIfNeeded() {
        if (cache.size() <= MAX_CACHE_SIZE) {
            return;
        }

        // 先淘汰过期条目（超过 TTL 天未活跃的）
        LocalDateTime cutoff = LocalDateTime.now().minusDays(SESSION_TTL_DAYS);
        cache.entrySet().removeIf(entry -> {
            ChannelSessionEntity session = entry.getValue();
            if (session.getLastActiveTime() != null && session.getLastActiveTime().isBefore(cutoff)) {
                log.debug("Evicting expired session: conversationId={}, lastActive={}",
                        entry.getKey(), session.getLastActiveTime());
                return true;
            }
            return false;
        });

        // 仍超量则按 lastActiveTime 淘汰最老的 10%
        if (cache.size() > MAX_CACHE_SIZE) {
            int toEvict = cache.size() - (int)(MAX_CACHE_SIZE * 0.9);
            cache.entrySet().stream()
                    .sorted(Comparator.comparing(
                            e -> e.getValue().getLastActiveTime() != null
                                    ? e.getValue().getLastActiveTime()
                                    : LocalDateTime.MIN))
                    .limit(toEvict)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(key -> {
                        log.debug("Evicting LRU session: conversationId={}", key);
                        cache.remove(key);
                    });
        }
    }

    /**
     * 根据 conversationId 获取推送目标标识
     *
     * @return targetId，不存在则返回 null
     */
    public String getTargetId(String conversationId) {
        ChannelSessionEntity entity = cache.get(conversationId);
        return entity != null ? entity.getTargetId() : null;
    }

    /**
     * 根据 conversationId 获取完整会话信息
     */
    public ChannelSessionEntity getSession(String conversationId) {
        return cache.get(conversationId);
    }

    /**
     * 获取指定渠道类型的所有会话
     */
    public List<ChannelSessionEntity> listByChannelType(String channelType) {
        return cache.values().stream()
                .filter(s -> channelType.equals(s.getChannelType()))
                .toList();
    }

    /**
     * 获取指定渠道配置ID的所有会话
     */
    public List<ChannelSessionEntity> listByChannelId(Long channelId) {
        return cache.values().stream()
                .filter(s -> channelId.equals(s.getChannelId()))
                .toList();
    }

    /**
     * 删除会话
     */
    public void remove(String conversationId) {
        ChannelSessionEntity removed = cache.remove(conversationId);
        if (removed != null) {
            sessionMapper.deleteById(removed.getId());
            log.debug("Removed channel session: conversationId={}", conversationId);
        }
    }
}
