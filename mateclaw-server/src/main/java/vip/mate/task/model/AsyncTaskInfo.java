package vip.mate.task.model;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步任务对外 DTO
 *
 * @author SnSclaw
 */
@Data
@Builder
public class AsyncTaskInfo {

    private String taskId;
    private String taskType;
    private String status;
    private Integer progress;
    private String providerName;
    private String resultVideoUrl;
    private String errorMessage;
    private LocalDateTime createTime;

    public boolean isTerminal() {
        return "succeeded".equals(status) || "failed".equals(status);
    }
}
