package vip.mate.tts;

import lombok.Builder;
import lombok.Data;

/**
 * TTS 语音合成统一请求
 *
 * @author SnSclaw
 */
@Data
@Builder
public class TtsRequest {

    /** 待合成的文本 */
    private String text;

    /** 语音 ID（可选，使用 Provider 默认） */
    private String voice;

    /** 模型名称（可选） */
    private String model;

    /** 语速 0.5-2.0，默认 1.0 */
    @Builder.Default
    private Double speed = 1.0;

    /** 输出格式：mp3 / ogg / wav，默认 mp3 */
    @Builder.Default
    private String format = "mp3";
}
