package vip.mate.skill.installer.model;

import lombok.Builder;
import lombok.Data;

/**
 * Skill 安装结果
 *
 * @author SnSclaw
 */
@Data
@Builder
public class InstallResult {

    /** 安装后的 skill 名称 */
    private String name;

    /** 是否已启用 */
    private boolean enabled;

    /** 来源 URL */
    private String sourceUrl;

    /** 来源类型 */
    private String sourceType;
}
