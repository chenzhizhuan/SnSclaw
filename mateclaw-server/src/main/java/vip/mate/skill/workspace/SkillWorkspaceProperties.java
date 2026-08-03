package vip.mate.skill.workspace;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skill 工作区配置
 * <p>
 * 统一管理 skill 工作区根目录、自动初始化策略、删除策略等。
 * 遵循 Maven Local Repository 模式：单一根目录 + 约定子目录结构。
 *
 * @author SnSclaw Team
 */
@Data
@ConfigurationProperties(prefix = "mateclaw.skill.workspace")
public class SkillWorkspaceProperties {

    /**
     * 工作区根目录，默认 ${user.home}/.snsclaw/skills
     * 每个 skill 按名称在此目录下创建子目录：{root}/{skillName}/
     */
    private String root = System.getProperty("user.home") + "/.snsclaw/skills";

    /**
     * 创建 skill 时是否自动初始化目录结构（SKILL.md + references/ + scripts/）
     */
    private boolean autoInit = true;

    /**
     * 删除 skill 时的目录处理策略：
     * - archive: 归档到 {root}/.archived/{name}-{timestamp}/（默认，安全）
     * - ignore: 不处理目录（仅删除数据库记录）
     */
    private String deletePolicy = "archive";

    /**
     * classpath 下预置技能的目录前缀，默认 skills/
     * 启动时自动扫描并同步到 workspace root（仅目标不存在时同步）
     */
    private String bundledSkillsPath = "skills";
}
