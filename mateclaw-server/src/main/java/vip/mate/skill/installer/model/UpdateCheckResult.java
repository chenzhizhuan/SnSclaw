package vip.mate.skill.installer.model;

import lombok.Builder;
import lombok.Data;

/**
 * Skill 更新检查结果
 *
 * @author SnSclaw Team
 */
@Data
@Builder
public class UpdateCheckResult {

    private String skillName;
    private boolean hasUpdate;
    private String currentVersion;
    private String latestVersion;
    private String sourceType;
    private String sourceUrl;
    private String message;
}
