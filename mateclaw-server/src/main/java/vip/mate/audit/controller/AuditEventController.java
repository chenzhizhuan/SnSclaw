package vip.mate.audit.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import vip.mate.audit.model.AuditEventEntity;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.LocalDateTime;

/**
 * 审计事件查询接口
 *
 * @author SnSclaw Team
 */
@Tag(name = "审计事件")
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditEventController {

    private final AuditEventService auditEventService;

    @Operation(summary = "分页查询审计事件")
    @GetMapping("/events")
    @RequireWorkspaceRole("admin")
    public R<IPage<AuditEventEntity>> listEvents(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(auditEventService.listEvents(workspaceId, action, resourceType, startTime, endTime, page, size));
    }
}
