package vip.mate.skill.runtime;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * SKILL.md frontmatter 解析器
 * 解析 YAML frontmatter（--- 包裹的部分）
 * 支持依赖声明：commands, env, tools, platforms
 */
@Slf4j
@Component
public class SkillFrontmatterParser {

    private static final java.util.regex.Pattern FRONTMATTER_PATTERN = java.util.regex.Pattern.compile(
        "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$",
        java.util.regex.Pattern.DOTALL
    );

    private final Yaml yaml = new Yaml();

    /**
     * 解析 SKILL.md 内容，提取 frontmatter 和正文
     */
    public ParsedSkillMd parse(String content) {
        if (content == null || content.isBlank()) {
            return ParsedSkillMd.builder()
                .name("")
                .description("")
                .body("")
                .dependencies(SkillDependencies.empty())
                .build();
        }

        java.util.regex.Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.matches()) {
            return ParsedSkillMd.builder()
                .name("")
                .description("")
                .body(content)
                .dependencies(SkillDependencies.empty())
                .build();
        }

        String frontmatterYaml = matcher.group(1);
        String body = matcher.group(2);

        try {
            Map<String, Object> frontmatter = yaml.load(frontmatterYaml);
            String name = getString(frontmatter, "name");
            String description = getString(frontmatter, "description");
            SkillDependencies dependencies = parseDependencies(frontmatter);
            List<String> platforms = parseStringList(frontmatter, "platforms");

            return ParsedSkillMd.builder()
                .name(name)
                .description(description)
                .body(body)
                .frontmatter(frontmatter)
                .dependencies(dependencies)
                .platforms(platforms)
                .build();
        } catch (Exception e) {
            log.warn("Failed to parse SKILL.md frontmatter: {}", e.getMessage());
            return ParsedSkillMd.builder()
                .name("")
                .description("")
                .body(body)
                .dependencies(SkillDependencies.empty())
                .build();
        }
    }

    /**
     * 解析依赖声明
     * 支持两种格式：
     * 1. dependencies: { commands: [...], env: [...], tools: [...] }
     * 2. platforms: [...]（顶层）
     */
    @SuppressWarnings("unchecked")
    private SkillDependencies parseDependencies(Map<String, Object> frontmatter) {
        Object depsObj = frontmatter.get("dependencies");
        if (depsObj == null || !(depsObj instanceof Map)) {
            return SkillDependencies.empty();
        }

        Map<String, Object> deps = (Map<String, Object>) depsObj;
        List<String> commands = parseStringList(deps, "commands");
        List<String> env = parseStringList(deps, "env");
        List<String> tools = parseStringList(deps, "tools");

        return SkillDependencies.builder()
            .commands(commands)
            .env(env)
            .tools(tools)
            .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return List.of();
        if (value instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                if (item != null) result.add(item.toString());
            }
            return result;
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    // ==================== 数据模型 ====================

    @Data
    @Builder
    public static class ParsedSkillMd {
        private String name;
        private String description;
        private String body;
        private Map<String, Object> frontmatter;
        private SkillDependencies dependencies;
        @Builder.Default
        private List<String> platforms = List.of();
    }

    @Data
    @Builder
    public static class SkillDependencies {
        /** 系统命令依赖，如 python3, node, tesseract */
        @Builder.Default
        private List<String> commands = List.of();

        /** 环境变量依赖，如 OPENAI_API_KEY */
        @Builder.Default
        private List<String> env = List.of();

        /** SnSclaw 内部工具依赖，如 skillScriptTool */
        @Builder.Default
        private List<String> tools = List.of();

        public boolean isEmpty() {
            return commands.isEmpty() && env.isEmpty() && tools.isEmpty();
        }

        public static SkillDependencies empty() {
            return SkillDependencies.builder().build();
        }
    }
}
