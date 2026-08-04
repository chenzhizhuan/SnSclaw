package vip.mate.tool.image;

import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.task.AsyncTaskService.TaskPollResult;

import java.util.Set;

/**
 * 图片生成提供商接口 — 所有图片 provider 统一实现此接口
 * <p>
 * 设计参考 {@link vip.mate.tool.video.VideoGenerationProvider}。
 * 与视频不同，图片 Provider 分同步和异步两种模式。
 *
 * @author SnSclaw
 */
public interface ImageGenerationProvider {

    /** 提供商唯一 ID，如 "dashscope"、"openai"、"fal"、"zhipu-cogview" */
    String id();

    /** 显示名称 */
    String label();

    /** 是否需要 API Key / Credential */
    boolean requiresCredential();

    /**
     * 自动探测排序优先级（升序）。
     */
    int autoDetectOrder();

    /** 该 provider 支持的能力集 */
    Set<ImageCapability> capabilities();

    /** 细粒度能力声明（支持的 size、aspectRatio、模型列表等） */
    ImageProviderCapabilities detailedCapabilities();

    /**
     * 判断该 provider 在当前配置下是否可用
     */
    boolean isAvailable(SystemSettingsDTO config);

    /**
     * 提交图片生成任务
     * <p>
     * 同步 Provider 在此方法内完成生成并返回 imageUrls（async=false）。
     * 异步 Provider 返回 providerTaskId（async=true），需后续轮询。
     *
     * @param request 统一请求参数
     * @param config  系统配置
     * @return 提交结果
     */
    ImageSubmitResult submit(ImageGenerationRequest request, SystemSettingsDTO config);

    /**
     * 轮询任务状态（仅异步 Provider 需要实现）
     *
     * @param providerTaskId provider 返回的任务 ID
     * @param config         系统配置
     * @return 轮询结果，同步 Provider 可返回 null
     */
    default TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config) {
        return null;
    }
}
