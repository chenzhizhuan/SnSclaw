package vip.mate.skill.installer.model;

import java.util.Map;

/**
 * 解析后的 skill bundle（从外部源获取的完整 skill 包）
 *
 * @author SnSclaw
 */
public record SkillBundle(
        /** skill 名称（从 SKILL.md frontmatter 解析） */
        String name,
        /** SKILL.md 完整内容 */
        String content,
        /** references/ 文件映射（相对路径 → 内容） */
        Map<String, String> references,
        /** scripts/ 文件映射（相对路径 → 内容） */
        Map<String, String> scripts,
        /** 来源类型：github / clawhub / local */
        String sourceType,
        /** 来源 URL */
        String sourceUrl,
        /** 版本 */
        String version,
        /** 描述（从 frontmatter 解析） */
        String description,
        /** 作者 */
        String author,
        /** 图标 */
        String icon
) {}
