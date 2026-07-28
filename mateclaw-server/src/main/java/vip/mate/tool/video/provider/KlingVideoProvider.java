package vip.mate.tool.video.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.task.AsyncTaskService.TaskPollResult;
import vip.mate.tool.video.*;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 快手可灵 Kling 视频生成 Provider
 * <p>
 * API 文档: https://docs.qingque.cn/d/home/eZQBXy5cfgN4H-YSZjCE1c_5w
 * 鉴权方式: JWT (access_key + secret_key 签发短时 token)
 *
 * @author SnSclaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlingVideoProvider implements VideoGenerationProvider {

    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://api.klingai.com";
    private static final String DEFAULT_MODEL = "kling-v1.6-pro";

    @Override
    public String id() {
        return "kling";
    }

    @Override
    public String label() {
        return "快手可灵 Kling";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 250;
    }

    @Override
    public Set<VideoCapability> capabilities() {
        return Set.of(VideoCapability.GENERATE, VideoCapability.IMAGE_TO_VIDEO);
    }

    @Override
    public VideoProviderCapabilities detailedCapabilities() {
        return VideoProviderCapabilities.builder()
                .modes(capabilities())
                .aspectRatios(List.of("16:9", "9:16", "1:1"))
                .supportedDurations(List.of(5, 10))
                .maxDurationSeconds(10)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("kling-v1.6-pro", "kling-v2.0-master"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return StringUtils.hasText(config.getKlingAccessKey())
                && StringUtils.hasText(config.getKlingSecretKey());
    }

    @Override
    public VideoSubmitResult submit(VideoGenerationRequest request, SystemSettingsDTO config) {
        try {
            String token = generateJwtToken(config.getKlingAccessKey(), config.getKlingSecretKey());
            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;

            String endpoint = request.getMode() == VideoCapability.IMAGE_TO_VIDEO
                    ? "/v1/videos/image2video" : "/v1/videos/text2video";

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model_name", model);
            body.put("prompt", request.getPrompt());

            if (request.getMode() == VideoCapability.IMAGE_TO_VIDEO && request.getImageUrl() != null) {
                body.put("image", request.getImageUrl());
            }
            if (request.getAspectRatio() != null) {
                body.put("aspect_ratio", request.getAspectRatio());
            }
            if (request.getDurationSeconds() != null) {
                body.put("duration", String.valueOf(request.getDurationSeconds()));
            }

            HttpResponse response = HttpRequest.post(BASE_URL + endpoint)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(30_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            if (result.path("code").asInt() == 0 && result.has("data")) {
                String taskId = result.path("data").path("task_id").asText();
                log.info("[Kling] Submitted task: {} (model={})", taskId, model);
                // 复合 ID 编码 endpoint 类型
                String compositeId = endpoint + "|" + taskId;
                return VideoSubmitResult.success(compositeId, id());
            } else {
                String errMsg = result.has("message") ? result.get("message").asText()
                        : "HTTP " + response.getStatus();
                log.warn("[Kling] Submit failed: {}", errMsg);
                return VideoSubmitResult.failure(id(), errMsg);
            }
        } catch (Exception e) {
            log.error("[Kling] Submit error: {}", e.getMessage(), e);
            return VideoSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        String[] parts = providerTaskId.split("\\|", 2);
        if (parts.length != 2) {
            return TaskPollResult.failed("无效的任务 ID 格式");
        }
        String endpoint = parts[0];
        String taskId = parts[1];

        try {
            String token = generateJwtToken(config.getKlingAccessKey(), config.getKlingSecretKey());

            HttpResponse response = HttpRequest.get(BASE_URL + endpoint + "/" + taskId)
                    .header("Authorization", "Bearer " + token)
                    .timeout(15_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());
            JsonNode data = result.path("data");
            String taskStatus = data.path("task_status").asText();

            return switch (taskStatus) {
                case "succeed" -> {
                    JsonNode works = data.path("task_result").path("videos");
                    String videoUrl = null;
                    if (works.isArray() && !works.isEmpty()) {
                        videoUrl = works.get(0).path("url").asText(null);
                    }
                    yield TaskPollResult.succeeded(videoUrl, null, data.toString());
                }
                case "failed" -> TaskPollResult.failed(
                        data.has("task_status_msg") ? data.get("task_status_msg").asText() : "任务失败");
                case "processing" -> TaskPollResult.running(null);
                default -> TaskPollResult.pending(null);
            };
        } catch (Exception e) {
            log.error("[Kling] Poll error for task {}: {}", taskId, e.getMessage());
            return null;
        }
    }

    /**
     * 使用 access_key + secret_key 签发短时 JWT token
     */
    private String generateJwtToken(String accessKey, String secretKey) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .header().add("alg", "HS256").add("typ", "JWT").and()
                .claims(Map.of(
                        "iss", accessKey,
                        "exp", (now / 1000) + 1800, // 30 分钟过期
                        "nbf", (now / 1000) - 5
                ))
                .signWith(Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
