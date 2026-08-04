package vip.mate.skill.workspace;

import java.nio.file.Path;

/**
 * Skill 工作区生命周期事件
 * <p>
 * 由 SkillWorkspaceManager 发布，SkillRuntimeService 监听以刷新缓存。
 *
 * @author SnSclaw
 */
public record SkillWorkspaceEvent(String skillName, Type type, Path workspacePath) {

    public enum Type {
        /** 工作区目录创建 */
        CREATED,
        /** 工作区目录归档 */
        ARCHIVED,
        /** 数据库 skill 导出到工作区 */
        EXPORTED,
        /** 从外部源安装到工作区 */
        INSTALLED,
        /** 已安装 skill 更新 */
        UPDATED
    }
}
