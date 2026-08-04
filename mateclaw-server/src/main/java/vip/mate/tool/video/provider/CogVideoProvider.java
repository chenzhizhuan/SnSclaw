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
 * 智谱 CogVideoX Provider — 支持 CogVideoX-Flash (免费) 和 CogVideoX (高质量)
 * <p>
 * API 文档: https://bigmodel.cn/dev/api/video-generation/cogvideox
 *
 * @author SnSclaw
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CogVideoProvider implements VideoGenerationProvider {

    private final ObjectMapper objectMapper;

    private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn";
    private static final String DEFAULT_MODEL = "cogvideox-flash";

    @Override
    public String id() {
        return "zhipu-cogvideo";
    }

    @Override
    public String label() {
        return "智谱 CogVideoX";
    }

    @Override
    public boolean requiresCredential() {
        return true;
    }

    @Override
    public int autoDetectOrder() {
        return 200;
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
                .supportedDurations(List.of(6))
                .maxDurationSeconds(6)
                .defaultModel(DEFAULT_MODEL)
                .models(List.of("cogvideox-flash", "cogvideox"))
                .build();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return StringUtils.hasText(config.getZhipuApiKey());
    }

    @Override
    public VideoSubmitResult submit(VideoGenerationRequest request, SystemSettingsDTO config) {
        String apiKey = config.getZhipuApiKey();
        String baseUrl = StringUtils.hasText(config.getZhipuBaseUrl())
                ? config.getZhipuBaseUrl() : DEFAULT_BASE_URL;

        try {
            String model = request.getModel() != null ? request.getModel() : DEFAULT_MODEL;
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", request.getPrompt());

            if (request.getMode() == VideoCapability.IMAGE_TO_VIDEO && request.getImageUrl() != null) {
                body.put("image_url", request.getImageUrl());
            }

            // 智谱视频 API 使用 size 参数
            if (request.getAspectRatio() != null) {
                String size = aspectRatioToSize(request.getAspectRatio());
                if (size != null) {
                    body.put("size", size);
                }
            }
            if (request.getDurationSeconds() != null) {
                body.put("duration", request.getDurationSeconds());
            }

            HttpResponse response = HttpRequest.post(baseUrl + "/api/paas/v4/videos/generations")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(30_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());

            if (response.getStatus() == 200 && result.has("id")) {
                String taskId = result.get("id").asText();
                log.info("[CogVideo] Submitted task: {} (model={})", taskId, model);
                return VideoSubmitResult.success(taskId, id());
            } else {
                String errMsg = result.has("error")
                        ? result.get("error").path("message").asText()
                        : "HTTP " + response.getStatus();
                log.warn("[CogVideo] Submit failed: {}", errMsg);
                return VideoSubmitResult.failure(id(), errMsg);
            }
        } catch (Exception e) {
            log.error("[CogVideo] Submit error: {}", e.getMessage(), e);
            return VideoSubmitResult.failure(id(), e.getMessage());
        }
    }

    @Override
    public TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        String apiKey = config.getZhipuApiKey();
        String baseUrl = StringUtils.hasText(config.getZhipuBaseUrl())
                ? config.getZhipuBaseUrl() : DEFAULT_BASE_URL;

        try {
            HttpResponse response = HttpRequest.get(
                            baseUrl + "/api/paas/v4/videos/" + providerTaskId)
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(15_000)
                    .execute();

            JsonNode result = objectMapper.readTree(response.body());
            String taskStatus = result.path("task_status").asText();

            return switch (taskStatus) {
                case "SUCCESS" -> {
                    // 智谱返回 video_result_list 数组
                    JsonNode videoResults = result.path("video_result_list");
                    String videoUrl = null;
                    if (videoResults.isArray() && !videoResults.isEmpty()) {
                        videoUrl = videoResults.get(0).path("url").asText(null);
                    }
                    // 兼容 video_result 字段
                    if (videoUrl == null) {
                        videoUrl = result.path("video_result").path("url").asText(null);
                    }
                    yield TaskPollResult.succeeded(videoUrl, null, result.toString());
                }
                case "FAIL" -> TaskPollResult.failed(
                        result.has("error") ? result.get("error").path("message").asText() : "任务失败");
                case "PROCESSING" -> TaskPollResult.running(null);
                default -> TaskPollResult.pending(null);
            };
        } catch (Exception e) {
            log.error("[CogVideo] Poll error for task {}: {}", providerTaskId, e.getMessage());
            return null;
        }
    }

    private String aspectRatioToSize(String aspectRatio) {
        return switch (aspectRatio) {
            case "16:9" -> "1920x1080";
            case "9:16" -> "1080x1920";
            case "1:1" -> "1080x1080";
            default -> null;
        };
    }
}
