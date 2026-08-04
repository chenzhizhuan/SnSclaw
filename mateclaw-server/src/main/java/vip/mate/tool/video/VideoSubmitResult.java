package vip.mate.tool.video;

import lombok.Builder;
import lombok.Data;

/**
 * Provider 提交视频生成任务的结果
 *
 * @author SnSclaw
 */
@Data
@Builder
public class VideoSubmitResult {

    /** provider 返回的任务 ID */
    private String providerTaskId;

    /** 提交的 provider 名称 */
    private String providerName;

    /** 是否被接受 */
    private boolean accepted;

    /** 错误信息（仅 accepted=false 时） */
    private String errorMessage;

    public static VideoSubmitResult success(String providerTaskId, String providerName) {
        return VideoSubmitResult.builder()
                .providerTaskId(providerTaskId)
                .providerName(providerName)
                .accepted(true)
                .build();
    }

    public static VideoSubmitResult failure(String providerName, String errorMessage) {
        return VideoSubmitResult.builder()
                .providerName(providerName)
                .accepted(false)
                .errorMessage(errorMessage)
                .build();
    }
}
