package vip.mate.tool.video;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 视频生成统一请求
 *
 * @author SnSclaw
 */
@Data
@Builder
public class VideoGenerationRequest {

    /** 视频内容描述 */
    private String prompt;

    /** 生成模式（由 runtime 自动推断） */
    private VideoCapability mode;

    /** 指定模型名称（可选，provider 有默认值） */
    private String model;

    /** 画面比例：16:9 / 9:16 / 1:1 */
    @Builder.Default
    private String aspectRatio = "16:9";

    /** 视频时长（秒） */
    private Integer durationSeconds;

    /** 参考图片 URL（IMAGE_TO_VIDEO 模式） */
    private String imageUrl;

    /** 参考视频 URL（VIDEO_TO_VIDEO 模式） */
    private String videoUrl;

    /** provider 特有的额外参数 */
    private Map<String, Object> extraParams;
}
