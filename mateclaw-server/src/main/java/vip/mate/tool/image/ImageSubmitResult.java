package vip.mate.tool.image;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Provider 提交图片生成任务的结果
 * <p>
 * 与视频不同，图片 Provider 分同步和异步两种：
 * - 同步（async=false）：submit 时已完成生成，imageUrls 直接包含结果
 * - 异步（async=true）：返回 providerTaskId，需后续轮询
 *
 * @author SnSclaw Team
 */
@Data
@Builder
public class ImageSubmitResult {

    /** 是否被接受 */
    private boolean accepted;

    /** true=异步需轮询, false=同步已完成 */
    private boolean async;

    /** 提交的 provider 名称 */
    private String providerName;

    /** 异步模式：provider 返回的任务 ID */
    private String providerTaskId;

    /** 同步模式：直接返回的图片 URL 列表 */
    private List<String> imageUrls;

    /** 错误信息（仅 accepted=false 时） */
    private String errorMessage;

    public static ImageSubmitResult syncSuccess(String providerName, List<String> imageUrls) {
        return ImageSubmitResult.builder()
                .accepted(true)
                .async(false)
                .providerName(providerName)
                .imageUrls(imageUrls)
                .build();
    }

    public static ImageSubmitResult asyncSuccess(String providerTaskId, String providerName) {
        return ImageSubmitResult.builder()
                .accepted(true)
                .async(true)
                .providerName(providerName)
                .providerTaskId(providerTaskId)
                .build();
    }

    public static ImageSubmitResult failure(String providerName, String errorMessage) {
        return ImageSubmitResult.builder()
                .providerName(providerName)
                .accepted(false)
                .errorMessage(errorMessage)
                .build();
    }
}
