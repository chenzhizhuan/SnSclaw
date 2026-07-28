package vip.mate.tts;

import lombok.Builder;
import lombok.Data;

/**
 * TTS 语音合成结果
 *
 * @author SnSclaw Team
 */
@Data
@Builder
public class TtsResult {

    /** 是否合成成功 */
    private boolean success;

    /** 音频字节数据 */
    private byte[] audioData;

    /** MIME 类型，如 audio/mpeg, audio/ogg */
    private String contentType;

    /** 音频格式：mp3, ogg, wav */
    private String format;

    /** 错误信息（仅 success=false 时） */
    private String errorMessage;

    public static TtsResult success(byte[] audioData, String contentType, String format) {
        return TtsResult.builder()
                .success(true)
                .audioData(audioData)
                .contentType(contentType)
                .format(format)
                .build();
    }

    public static TtsResult failure(String errorMessage) {
        return TtsResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
