package vip.mate.agent.runtime.dsh.management;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireGlobalAdmin;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

@Tag(name = "DeepSeek Harness Runtime Management")
@RestController
@RequestMapping("/api/v1/admin/dsh")
@RequiredArgsConstructor
public class DshManagementController {
    private final DshManagementService managementService;
    private final DshUpgradeService upgrades;

    public record UpgradeRequest(String targetVersion, String expectedRevision, String idempotencyKey) {}
    public record RollbackRequest(String expectedRevision, String idempotencyKey) {}

    @Operation(summary = "Get managed DSH runtime status")
    @GetMapping("/status")
    @RequireGlobalAdmin
    public R<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>(managementService.status());
        status.putAll(upgrades.status());
        return R.ok(status);
    }

    @Operation(summary = "Save managed DSH runtime configuration")
    @PutMapping("/config")
    @RequireGlobalAdmin
    public R<Map<String, Object>> saveConfig(@RequestBody Map<String, String> values) {
        return R.ok(managementService.saveConfig(values));
    }

    @Operation(summary = "Install the server-selected DSH artifact")
    @PostMapping("/install")
    @RequireGlobalAdmin
    public R<Map<String, String>> install() {
        return R.ok(upgrades.upgrade("0.2.0-rc.1", (String) upgrades.status().get("configRevision"), UUID.randomUUID().toString()));
    }

    @PostMapping("/test-task")
    @RequireGlobalAdmin
    public R<Map<String, Object>> testTask() { return R.ok(managementService.testTask()); }

    @PostMapping("/upgrades")
    @RequireGlobalAdmin
    public R<Map<String, String>> upgrade(@RequestBody UpgradeRequest request) {
        return R.ok(upgrades.upgrade(request.targetVersion(), request.expectedRevision(), request.idempotencyKey()));
    }

    @GetMapping("/upgrades/{id}")
    @RequireGlobalAdmin
    public R<Map<String, String>> operation(@PathVariable String id) { return R.ok(upgrades.operation(id)); }

    @PostMapping("/upgrades/{id}/rollback")
    @RequireGlobalAdmin
    public R<Map<String, String>> rollback(@PathVariable String id, @RequestBody RollbackRequest request) {
        return R.ok(upgrades.rollback(id, request.expectedRevision(), request.idempotencyKey()));
    }

    @Operation(summary = "Verify DSH runtime configuration")
    @PostMapping("/verify")
    @RequireGlobalAdmin
    public R<Map<String, Object>> verify() { return R.ok(managementService.verify()); }

    @Operation(summary = "Test starting the DSH process")
    @PostMapping("/test-connection")
    @RequireGlobalAdmin
    public R<Map<String, Object>> testConnection() { return R.ok(managementService.testConnection()); }

    @Operation(summary = "Enable managed DSH runtime")
    @PostMapping("/enable")
    @RequireGlobalAdmin
    public R<Map<String, Object>> enable() { return R.ok(managementService.enable()); }

    @Operation(summary = "Disable managed DSH runtime")
    @PostMapping("/disable")
    @RequireGlobalAdmin
    public R<Map<String, Object>> disable() { return R.ok(managementService.disable()); }
}
