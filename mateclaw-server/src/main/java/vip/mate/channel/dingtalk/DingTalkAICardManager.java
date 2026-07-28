package vip.mate.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉 AI Card 管理器
 * <p>
 * 钉钉 AI Card 流式卡片管理器，管理卡片的完整生命周期：
 * - Token 管理（缓存 + 预刷新）
 * - 卡片创建与投放（createAndDeliver）
 * - 流式内容更新（streaming update，500ms 节流）
 * - 卡片状态管理（PROCESSING → FINISHED / FAILED）
 * <p>
 * 使用钉钉开放平台 Card API：
 * - POST /v1.0/card/instances/createAndDeliver — 创建并投放卡片
 * - PUT  /v1.0/card/streaming             — 流式追加内容
 * <p>
 * 卡片状态持久化在内存中，服务重启后丢失可接受。
 *
 * @author SnSclaw Team
 */
@Slf4j
public class DingTalkAICardManager {

    private static final String API_BASE = "https://api.dingtalk.com";

    /** 创建并投放卡片 */
    private static final String CREATE_AND_DELIVER_URL = API_BASE + "/v1.0/card/instances/createAndDeliver";

    /** 流式更新卡片内容 */
    private static final String STREAMING_URL = API_BASE + "/v1.0/card/streaming";

    /** 流式更新节流间隔（毫秒） */
    private static final long THROTTLE_INTERVAL_MS = 500;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;

    /** 缓存的 access_token */
    private volatile String accessToken;
    /** token 过期时间 (epoch ms) */
    private volatile long tokenExpireTime;

    /** 活跃卡片：outTrackId → CardInstance */
    private final ConcurrentHashMap<String, CardInstance> activeCards = new ConcurrentHashMap<>();

    /**
     * 卡片实例状态
     */
    static class CardInstance {
        final String outTrackId;
        volatile long lastUpdateTime;
        volatile String accumulatedContent;
        volatile boolean finished;

        CardInstance(String outTrackId) {
            this.outTrackId = outTrackId;
            this.lastUpdateTime = 0;
            this.accumulatedContent = "";
            this.finished = false;
        }
    }

    public DingTalkAICardManager(HttpClient httpClient, ObjectMapper objectMapper,
                                  String clientId, String clientSecret) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    // ==================== Token 管理 ====================

    /**
     * 获取有效的 access_token（带缓存和预刷新）
     */
    public String ensureAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken;
        }
        return refreshAccessToken();
    }

    private synchronized String refreshAccessToken() {
        // Double-check after acquiring lock
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return accessToken;
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "appKey", clientId,
                    "appSecret", clientSecret
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/v1.0/oauth2/accessToken"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            this.accessToken = (String) result.get("accessToken");
            Object expireIn = result.get("expireIn");
            int seconds = expireIn instanceof Number n ? n.intValue() : 7200;
            // 提前 5 分钟刷新
            this.tokenExpireTime = System.currentTimeMillis() + (seconds - 300) * 1000L;

            log.info("[dingtalk-card] access_token refreshed, expires in {}s", seconds);
            return this.accessToken;
        } catch (Exception e) {
            log.error("[dingtalk-card] Failed to refresh access_token: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 卡片创建 ====================

    /**
     * 创建并投放 AI 卡片（显示"思考中..."状态）
     *
     * @param cardTemplateId 卡片模板 ID
     * @param conversationId 钉钉会话 ID（openConversationId）
     * @param chatType       会话类型（"1" 单聊，"2" 群聊）
     * @param robotCode      机器人编码
     * @return outTrackId（卡片实例追踪 ID），失败返回 null
     */
    public String createAndDeliverCard(String cardTemplateId, String conversationId,
                                        String chatType, String robotCode) {
        String token = ensureAccessToken();
        if (token == null) {
            log.error("[dingtalk-card] Cannot create card: no access_token");
            return null;
        }

        String outTrackId = UUID.randomUUID().toString();

        try {
            // 卡片数据：初始显示"思考中..."
            Map<String, String> cardData = Map.of(
                    "content", "思考中...",
                    "status", "PROCESSING"
            );

            Map<String, Object> body = new HashMap<>();
            body.put("cardTemplateId", cardTemplateId);
            body.put("outTrackId", outTrackId);
            body.put("cardData", Map.of("cardParamMap", cardData));
            body.put("callbackType", "STREAM");

            if ("1".equals(chatType)) {
                // 单聊：通过 IM_ROBOT 投放
                body.put("openSpaceId", "dtv1.card//IM_ROBOT." + robotCode);
                body.put("imRobotOpenDeliverModel", Map.of(
                        "spaceType", "IM_ROBOT",
                        "robotCode", robotCode
                ));
            } else {
                // 群聊：通过 IM_GROUP 投放，需要 openConversationId
                body.put("openSpaceId", "dtv1.card//IM_GROUP." + conversationId);
                body.put("imGroupOpenDeliverModel", Map.of(
                        "robotCode", robotCode
                ));
            }

            body.put("openDynamicDataConfig", Map.of(
                    "dynamicDataSourceConfigs", java.util.List.of(Map.of(
                            "constParams", Map.of(
                                    "content", "思考中..."
                            )
                    ))
            ));

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CREATE_AND_DELIVER_URL))
                    .header("Content-Type", "application/json")
                    .header("x-acs-dingtalk-access-token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // 注册活跃卡片
                activeCards.put(outTrackId, new CardInstance(outTrackId));
                log.info("[dingtalk-card] Card created: outTrackId={}, conversationId={}", outTrackId, conversationId);
                return outTrackId;
            } else {
                log.error("[dingtalk-card] Create card failed: status={}, body={}", response.statusCode(), response.body());
                return null;
            }
        } catch (Exception e) {
            log.error("[dingtalk-card] Failed to create card: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 流式更新 ====================

    /**
     * 追加流式内容到卡片
     * <p>
     * 内部做 500ms 节流：内容先累积到 accumulatedContent，
     * 只有距上次更新超过 THROTTLE_INTERVAL_MS 才真正调用 API。
     *
     * @param outTrackId 卡片追踪 ID
     * @param contentDelta 本次增量内容
     * @param forceFlush   是否强制刷新（不等待节流，用于最后一次更新）
     */
    public void appendContent(String outTrackId, String contentDelta, boolean forceFlush) {
        CardInstance card = activeCards.get(outTrackId);
        if (card == null || card.finished) {
            log.debug("[dingtalk-card] Card not found or already finished: {}", outTrackId);
            return;
        }

        // 累积内容
        synchronized (card) {
            card.accumulatedContent += contentDelta;
        }

        long now = System.currentTimeMillis();
        boolean shouldFlush = forceFlush || (now - card.lastUpdateTime >= THROTTLE_INTERVAL_MS);

        if (shouldFlush) {
            String contentToSend;
            synchronized (card) {
                contentToSend = card.accumulatedContent;
            }
            doStreamingUpdate(outTrackId, contentToSend, false, null);
            card.lastUpdateTime = now;
        }
    }

    /**
     * 标记卡片完成（FINISHED 状态），发送最终内容
     *
     * @param outTrackId 卡片追踪 ID
     * @param finalContent 最终完整内容
     */
    public void finishCard(String outTrackId, String finalContent) {
        CardInstance card = activeCards.get(outTrackId);
        if (card == null) {
            log.debug("[dingtalk-card] Card not found for finish: {}", outTrackId);
            return;
        }

        card.finished = true;
        doStreamingUpdate(outTrackId, finalContent, true, "FINISHED");
        activeCards.remove(outTrackId);
        log.info("[dingtalk-card] Card finished: outTrackId={}", outTrackId);
    }

    /**
     * 标记卡片失败（FAILED 状态）
     *
     * @param outTrackId 卡片追踪 ID
     * @param errorMessage 错误信息
     */
    public void failCard(String outTrackId, String errorMessage) {
        CardInstance card = activeCards.get(outTrackId);
        if (card == null) {
            log.debug("[dingtalk-card] Card not found for fail: {}", outTrackId);
            return;
        }

        card.finished = true;
        String content = card.accumulatedContent.isEmpty()
                ? "处理失败：" + errorMessage
                : card.accumulatedContent + "\n\n⚠️ " + errorMessage;
        doStreamingUpdate(outTrackId, content, true, "FAILED");
        activeCards.remove(outTrackId);
        log.warn("[dingtalk-card] Card failed: outTrackId={}, error={}", outTrackId, errorMessage);
    }

    /**
     * 调用钉钉流式更新 API
     *
     * @param outTrackId 卡片追踪 ID
     * @param content    当前完整内容（非增量）
     * @param isFinish   是否为最终更新
     * @param status     卡片状态（FINISHED / FAILED），非最终更新时为 null
     */
    private void doStreamingUpdate(String outTrackId, String content, boolean isFinish, String status) {
        String token = ensureAccessToken();
        if (token == null) {
            log.error("[dingtalk-card] Cannot update card: no access_token");
            return;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("outTrackId", outTrackId);

            // 更新的 key
            String key = "content";

            if (isFinish) {
                body.put("isFull", true);
                body.put("isFinalize", true);
                body.put("guid", UUID.randomUUID().toString());
                body.put("key", key);
                body.put("value", content);
            } else {
                body.put("isFull", true);
                body.put("isFinalize", false);
                body.put("guid", UUID.randomUUID().toString());
                body.put("key", key);
                body.put("value", content);
            }

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(STREAMING_URL))
                    .header("Content-Type", "application/json")
                    .header("x-acs-dingtalk-access-token", token)
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[dingtalk-card] Streaming update failed: status={}, body={}",
                        response.statusCode(), response.body());
            } else {
                log.debug("[dingtalk-card] Streaming update: outTrackId={}, contentLen={}, finish={}",
                        outTrackId, content.length(), isFinish);
            }
        } catch (Exception e) {
            log.error("[dingtalk-card] Failed to do streaming update: {}", e.getMessage(), e);
        }
    }

    // ==================== 查询 ====================

    /**
     * 获取活跃卡片数量
     */
    public int getActiveCardCount() {
        return activeCards.size();
    }

    /**
     * 检查是否有活跃卡片
     */
    public boolean hasActiveCard(String outTrackId) {
        CardInstance card = activeCards.get(outTrackId);
        return card != null && !card.finished;
    }

    /**
     * 清理所有活跃卡片（渠道停止时调用）
     */
    public void cleanup() {
        activeCards.clear();
        log.info("[dingtalk-card] Cleaned up {} active cards", activeCards.size());
    }
}
