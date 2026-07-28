package vip.mate.task.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步任务实体 — 通用长耗时任务持久化（视频生成、图片生成等）
 *
 * @author SnSclaw Team
 */
@Data
@TableName("mate_async_task")
public class AsyncTaskEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对外公开的任务 ID（UUID 格式） */
    private String taskId;

    /** 任务类型：video_generation / image_generation 等 */
    private String taskType;

    /** 任务状态：pending / running / succeeded / failed */
    private String status;

    /** 关联会话 ID（用于完成后回写） */
    private String conversationId;

    /** 关联消息 ID */
    private Long messageId;

    /** 处理该任务的 provider 名称 */
    private String providerName;

    /** provider 返回的外部任务 ID */
    private String providerTaskId;

    /** 序列化的请求参数（JSON），用于重试 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String requestJson;

    /** 序列化的结果（JSON），完成时填充 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String resultJson;

    /** 错误信息 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorMessage;

    /** 进度（0-100） */
    private Integer progress;

    /** 创建人 */
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
