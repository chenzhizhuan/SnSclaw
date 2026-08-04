package vip.mate.tool.video.provider;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.task.AsyncTaskService.TaskPollResult;
import vip.mate.tool.video.*;

import java.util.List;
import java.util.Set;

/**
 * fal.ai 视频生成 Provider — 通过 fal.ai 中转访问 Kling、Runway、Luma、MiniMax 等模型
 * <p>
 * API 文档: https://fal.ai/docs
 *
 * @author SnSclaw
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FalVideoProvider implements VideoGenerationProvider {

    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://queue.fal.run";
    private static final String STATUS_URL = "https://queue.fal.run";
    private static final String DEFAULT_MODEL = "fal-ai/kling-video/v1.6/pro/text-to-video";

    @Override
    public String id() {
        return "fal";
    }

    @Override
    public String label() {
        return "fal.ai (Kling/Runway/Luma)";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 300;
    }

    @Override
    public Set<VideoCapability> capabilities() {
        return Set.of(VideoCapability.GENERATE, VideoCapability.IMAGE_TO_VIDEO);
    }

    @Override
    public VideoProviderCapabilities detailedCapabilities() {
        return VideoProviderCapabilities.builder()
                .modes(capabilities())
                .aspectRatios(List.of("16:9", "9:16", "1:1", "3:4", "4:3"))
                .supportedDurations(List.of(5, 10))
                .maxDurationSeconds(10)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of(
                        "fal-ai/kling-video/v1.6/pro/text-to-video",
                        "fal-ai/runway-gen3/turbo/text-to-video",
                        "fal-ai/luma-dream-machine",
                        "fal-ai/minimax/video-01-live"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return StringUtils.hasText(config.getFalApiKey());
    }

    @Override
    public VideoSubmitResult submit(VideoGenerationRequest request, SystemSettingsDTO config) {
        String apiKey = config.getFalApiKey();

        try {
            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;
            ObjectNode body = objectMapper.createObjectNode();
            body.put("prompt", request.getPrompt());

            if (request.getMode() == VideoCapability.IMAGE_TO_VIDEO && request.getImageUrl() != null) {
                body.put("image_url", request.getImageUrl());
            }
            if (request.getAspectRatio() != null) {
                body.put("aspect_ratio", request.getAspectRatio());
            }
            if (request.getDurationSeconds() != null) {
                body.put("duration", String.valueOf(request.getDurationSeconds()));
            }

            HttpResponse response = HttpRequest.post(BASE_URL + "/" + model)
                    .header("Authorization", "Key " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(30_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            if (response.getStatus() == 200 && result.has("request_id")) {
                String requestId = result.get("request_id").asText();
                // 把 model 编码到 taskId 中，轮询时需要
                String compositeId = model + "|" + requestId;
                log.info("[fal.ai] Submitted task: {} (model={})", requestId, model);
                return VideoSubmitResult.success(compositeId, id());
            } else {
                String errMsg = result.has("detail") ? result.get("detail").asText()
                        : "HTTP " + response.getStatus();
                log.warn("[fal.ai] Submit failed: {}", errMsg);
                return VideoSubmitResult.failure(id(), errMsg);
            }
        } catch (Exception e) {
            log.error("[fal.ai] Submit error: {}", e.getMessage(), e);
            return VideoSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        String apiKey = config.getFalApiKey();

        // 从复合 ID 中解析 model 和 requestId
        String[] parts = providerTaskId.split("\\|", 2);
        if (parts.length != 2) {
            return TaskPollResult.failed("无效的任务 ID 格式");
        }
        String model = parts[0];
        String requestId = parts[1];

        try {
            HttpResponse response = HttpRequest.get(
                            STATUS_URL + "/" + model + "/requests/" + requestId + "/status")
                    .header("Authorization", "Key " + apiKey)
                    .timeout(15_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());
            String status = result.path("status").asText();

            return switch (status) {
                case "COMPLETED" -> {
                    // 完成后需要再获取结果
                    HttpResponse resultResponse = HttpRequest.get(
                                    STATUS_URL + "/" + model + "/requests/" + requestId)
                            .header("Authorization", "Key " + apiKey)
                            .timeout(15_000)
                            .execute();
                    JsonNode resultBody = objectMapper.readTree(resultResponse.body());
                    String videoUrl = extractVideoUrl(resultBody);
                    yield TaskPollResult.succeeded(videoUrl, null, resultBody.toString());
                }
                case "FAILED" -> TaskPollResult.failed(
                        result.has("error") ? result.get("error").asText() : "任务失败");
                case "IN_PROGRESS" -> {
                    Integer progress = result.has("progress")
                            ? (int) (result.get("progress").asDouble() * 100) : null;
                    yield TaskPollResult.running(progress);
                }
                default -> TaskPollResult.pending(null);
            };
        } catch (Exception e) {
            log.error("[fal.ai] Poll error for task {}: {}", requestId, e.getMessage());
            return null;
        }
    }

    private String extractVideoUrl(JsonNode resultBody) {
        // fal.ai 通常返回 video.url 或 video[0].url
        JsonNode video = resultBody.path("video");
        if (video.has("url")) {
            return video.get("url").asText();
        }
        if (video.isArray() && !video.isEmpty()) {
            return video.get(0).path("url").asText(null);
        }
        // 有些模型用 output.video
        JsonNode output = resultBody.path("output");
        if (output.has("video")) {
            return output.path("video").path("url").asText(null);
        }
        return null;
    }
}
