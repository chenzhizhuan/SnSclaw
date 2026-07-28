package vip.mate.skill.installer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.skill.installer.model.SkillBundle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bundle URL 源策略解析器
 * <p>
 * 根据 URL 自动识别来源类型（GitHub / ClawHub），并委托对应 fetcher 获取 bundle。
 * 预留扩展点，可通过增加 pattern 支持更多源。
 *
 * @author SnSclaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BundleResolver {

    private final GitSkillFetcher gitSkillFetcher;
    private final SkillHubClient skillHubClient;

    // GitHub URL 模式: https://github.com/owner/repo[/tree/ref/sub/path]
    private static final Pattern GITHUB_PATTERN = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/tree/([^/]+)(?:/(.+))?)?/?$"
    );

    // ClawHub URL 模式: https://clawhub.ai/skills/slug[@version]
    private static final Pattern CLAWHUB_PATTERN = Pattern.compile(
            "^https?://clawhub\\.ai/skills/([^/@]+)(?:@(.+))?/?$"
    );

    /**
     * 根据 URL 自动识别源类型并获取 bundle
     *
     * @param bundleUrl skill 来源 URL
     * @param version   版本覆盖（可选，优先级高于 URL 中的版本）
     * @return 解析后的 SkillBundle，失败返回 null
     */
    public SkillBundle resolve(String bundleUrl, String version) {
        if (bundleUrl == null || bundleUrl.isBlank()) {
            log.error("Bundle URL is empty");
            return null;
        }

        // 1. 尝试 GitHub
        Matcher githubMatcher = GITHUB_PATTERN.matcher(bundleUrl.trim());
        if (githubMatcher.matches()) {
            String owner = githubMatcher.group(1);
            String repo = githubMatcher.group(2);
            String ref = version != null ? version : githubMatcher.group(3);
            String subPath = githubMatcher.group(4);

            String repoUrl = "https://github.com/" + owner + "/" + repo + ".git";
            log.info("Resolving GitHub skill: {}/{} ref={} subPath={}", owner, repo, ref, subPath);
            return gitSkillFetcher.fetch(repoUrl, ref, subPath);
        }

        // 2. 尝试 ClawHub
        Matcher clawHubMatcher = CLAWHUB_PATTERN.matcher(bundleUrl.trim());
        if (clawHubMatcher.matches()) {
            String slug = clawHubMatcher.group(1);
            String urlVersion = clawHubMatcher.group(2);
            String effectiveVersion = version != null ? version : urlVersion;

            log.info("Resolving ClawHub skill: {} version={}", slug, effectiveVersion);
            return skillHubClient.fetchBundle(slug, effectiveVersion);
        }

        // 3. 尝试当作普通 GitHub URL（不含 /tree/ 的情况）
        if (bundleUrl.contains("github.com/")) {
            Pattern simpleGithub = Pattern.compile("^https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");
            Matcher simple = simpleGithub.matcher(bundleUrl.trim());
            if (simple.matches()) {
                String repoUrl = "https://github.com/" + simple.group(1) + "/" + simple.group(2) + ".git";
                log.info("Resolving simple GitHub skill: {}", repoUrl);
                return gitSkillFetcher.fetch(repoUrl, version, null);
            }
        }

        log.error("Unsupported bundle URL format: {}", bundleUrl);
        return null;
    }

    /**
     * 检测 URL 对应的源类型
     */
    public String detectSourceType(String bundleUrl) {
        if (bundleUrl == null) return "unknown";
        if (bundleUrl.contains("github.com")) return "github";
        if (bundleUrl.contains("clawhub.ai")) return "clawhub";
        return "unknown";
    }
}
