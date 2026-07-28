package vip.mate.tool.video;

import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.task.AsyncTaskService.TaskPollResult;

import java.util.Set;

/**
 * 视频生成提供商接口 — 所有视频 provider 统一实现此接口
 * <p>
 * 设计参考 {@link vip.mate.tool.search.SearchProvider}。
 *
 * @author SnSclaw Team
 */
public interface VideoGenerationProvider {

    /** 提供商唯一 ID，如 "dashscope"、"zhipu-cogvideo"、"fal"、"kling" */
    String id();

    /** 显示名称 */
    String label();

    /** 是否需要 API Key / Credential */
    boolean requiresCredential();

    /**
     * 自动探测排序优先级（升序）。
     * 有 credential 的 provider 优先于不需要 credential 的。
     */
    int autoDetectOrder();

    /** 该 provider 支持的能力集 */
    Set<VideoCapability> capabilities();

    /** 细粒度能力声明（支持的 aspectRatio、duration、模型列表等） */
    VideoProviderCapabilities detailedCapabilities();

    /**
     * 判断该 provider 在当前配置下是否可用
     * （API Key 已配置等）
     */
    boolean isAvailable(SystemSettingsDTO config);

    /**
     * 提交视频生成任务（异步，非阻塞）
     *
     * @param request 统一请求参数
     * @param config  系统配置
     * @return 提交结果（含 provider 任务 ID）
     */
    VideoSubmitResult submit(VideoGenerationRequest request, SystemSettingsDTO config);

    /**
     * 轮询任务状态
     *
     * @param providerTaskId provider 返回的任务 ID
     * @param config         系统配置
     * @return 轮询结果
     */
    TaskPollResult checkStatus(String providerTaskId, SystemSettingsDTO config);
}
