package vip.mate.tts;

import vip.mate.system.model.SystemSettingsDTO;

import java.util.List;

/**
 * TTS 语音合成提供商接口
 *
 * @author SnSclaw Team
 */
public interface TtsProvider {

    /** 提供商唯一 ID，如 "edge-tts"、"openai"、"dashscope" */
    String id();

    /** 显示名称 */
    String label();

    /** 是否需要 API Key */
    boolean requiresCredential();

    /** 自动探测排序优先级（升序），免费 Provider 优先 */
    int autoDetectOrder();

    /** 判断该 provider 在当前配置下是否可用 */
    boolean isAvailable(SystemSettingsDTO config);

    /** 可用语音列表 */
    List<String> availableVoices();

    /** 默认语音 */
    String defaultVoice();

    /**
     * 合成语音
     *
     * @param request 请求参数
     * @param config  系统配置
     * @return 合成结果（含音频字节）
     */
    TtsResult synthesize(TtsRequest request, SystemSettingsDTO config);
}
