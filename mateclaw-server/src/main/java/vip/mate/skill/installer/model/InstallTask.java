package vip.mate.skill.installer.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 安装任务状态
 *
 * @author SnSclaw Team
 */
@Data
public class InstallTask {

    private String taskId;
    private String bundleUrl;
    private InstallStatus status;
    private String error;
    private InstallResult result;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 取消标志 */
    private volatile boolean cancelRequested;

    public enum InstallStatus {
        PENDING,
        INSTALLING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public static InstallTask create(String taskId, String bundleUrl) {
        InstallTask task = new InstallTask();
        task.setTaskId(taskId);
        task.setBundleUrl(bundleUrl);
        task.setStatus(InstallStatus.PENDING);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    public void markInstalling() {
        this.status = InstallStatus.INSTALLING;
        this.updatedAt = LocalDateTime.now();
    }

    public void markCompleted(InstallResult result) {
        this.status = InstallStatus.COMPLETED;
        this.result = result;
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.status = InstallStatus.FAILED;
        this.error = error;
        this.updatedAt = LocalDateTime.now();
    }

    public void markCancelled() {
        this.status = InstallStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }
}
