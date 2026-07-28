package vip.mate.audit.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计事件实体
 *
 * @author SnSclaw Team
 */
@Data
@TableName("mate_audit_event")
public class AuditEventEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private Long userId;

    private String username;

    /** 操作类型：CREATE / UPDATE / DELETE / LOGIN / LOGOUT / ENABLE / DISABLE */
    private String action;

    /** 资源类型：AGENT / CHANNEL / SKILL / WIKI / NODE / MEMBER / WORKSPACE */
    private String resourceType;

    private String resourceId;

    private String resourceName;

    /** 变更详情 JSON */
    private String detailJson;

    private String ipAddress;

    private String userAgent;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
