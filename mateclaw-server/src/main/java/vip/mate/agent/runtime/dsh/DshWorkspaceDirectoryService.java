package vip.mate.agent.runtime.dsh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.mate.agent.model.AgentEntity;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * DSH 工作目录：按用户名建档。
 *
 * <p>职责有两个：</p>
 * <ol>
 *   <li>{@link #ensureUserDirectory(String)} —— 智屿建用户时调用，
 *       在 DSH 工作根下创建以**用户名**命名的目录（如 {@code /app/data/workspace/wangfan02}）；</li>
 *   <li>{@link #resolveForAgent(AgentEntity)} —— DSH 员工启动前解析其工作目录。</li>
 * </ol>
 *
 * <p>工作根由 {@code mateclaw.agent.runtime.dsh.workspace-root} 配置，缺省
 * {@code /app/data/workspace}（容器内即命名卷 {@code snsclaw_server_data} 的
 * {@code workspace/} 子目录）。</p>
 *
 * <p><b>为什么不是 DSH 自己建</b>：平台以该路径作为子进程的 cwd 启动 DSH
 * （{@code ProcessBuilder.directory}），而 POSIX 要求 cwd 在 exec 之前就存在，
 * 否则子进程根本起不来。因此必须由父进程先建。DSH 自己负责的是它的会话目录
 * （{@code $DSH_HOME/sessions/--<cwd编码>--/<sessionId>/}），那部分平台不碰。</p>
 *
 * @author SnSclaw
 */
@Slf4j
@Service
public class DshWorkspaceDirectoryService {

    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final String workspaceRoot;

    public DshWorkspaceDirectoryService(
            UserMapper userMapper,
            WorkspaceMapper workspaceMapper,
            @Value("${mateclaw.agent.runtime.dsh.workspace-root:/app/data/workspace}") String workspaceRoot) {
        this.userMapper = userMapper;
        this.workspaceMapper = workspaceMapper;
        this.workspaceRoot = workspaceRoot;
        log.info("[DSH] user workspace directory root: {}", workspaceRoot);
    }

    /** 配置的 DSH 工作根。 */
    public String workspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 建用户时调用：确保 {@code {root}/{username}} 存在。
     *
     * <p>幂等，失败只告警不抛出——建用户不应因目录创建失败而失败。</p>
     *
     * @param username 新用户名
     * @return 目录路径；用户名为空时返回 {@code null}
     */
    public Path ensureUserDirectoryQuietly(String username) {
        if (username == null || username.isBlank()) return null;
        try {
            Path directory = root().resolve(safeSegment(username));
            createIfMissing(directory);
            log.info("[DSH] ensured user workspace directory: {}", directory);
            return directory;
        } catch (Exception error) {
            log.warn("[DSH] cannot create workspace directory for user {}: {}", username, error.getMessage());
            return null;
        }
    }

    /**
     * 解析 DSH 员工的工作目录。
     *
     * <p>优先级：</p>
     * <ol>
     *   <li>{@code agent.workspace_base_path} —— 员工级显式覆盖（既有员工全部走这条，行为不变）；</li>
     *   <li>{@code {root}/{创建者用户名}} —— 新员工默认落到其创建者的用户目录；</li>
     *   <li>{@code {root}/{所属工作区slug}} —— 创建者缺失时的隔离兜底（仍按工作区隔离，不回共享根）。</li>
     * </ol>
     */
    public Path resolveForAgent(AgentEntity agent) {
        if (agent == null) {
            throw new IllegalStateException("dsh.workspace_unresolved: agent is null");
        }

        // ① 员工级显式覆盖
        String override = agent.getWorkspaceBasePath();
        if (override != null && !override.isBlank()) {
            return createIfMissing(Path.of(override.trim()).toAbsolutePath().normalize());
        }

        // ② 创建者的用户目录
        String username = usernameOf(agent.getCreatorUserId());
        if (username != null) {
            return createIfMissing(root().resolve(safeSegment(username)));
        }

        // ③ 所属工作区 slug 兜底
        String slug = slugOf(agent.getWorkspaceId());
        if (slug != null) {
            return createIfMissing(root().resolve(safeSegment(slug)));
        }

        throw new IllegalStateException("dsh.workspace_unresolved: agent " + agent.getId()
                + " has no workspace_base_path, no creator, and no resolvable workspace");
    }

    /** 按用户 ID 取用户名；查不到返回 {@code null}。 */
    private String usernameOf(Long userId) {
        if (userId == null) return null;
        try {
            UserEntity user = userMapper.selectById(userId);
            if (user == null) return null;
            String username = user.getUsername();
            return username == null || username.isBlank() ? null : username.trim();
        } catch (Exception error) {
            log.warn("[DSH] user lookup failed for id={}: {}", userId, error.getMessage());
            return null;
        }
    }

    /** 按工作区 ID 取 slug；查不到返回 {@code null}。 */
    private String slugOf(Long workspaceId) {
        if (workspaceId == null) return null;
        try {
            WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
            if (workspace == null) return null;
            String slug = workspace.getSlug();
            return slug == null || slug.isBlank() ? null : slug.trim();
        } catch (Exception error) {
            log.warn("[DSH] workspace lookup failed for id={}: {}", workspaceId, error.getMessage());
            return null;
        }
    }

    /** 工作根（绝对化）。 */
    private Path root() {
        return Path.of(workspaceRoot).toAbsolutePath().normalize();
    }

    /**
     * 把用户名/工作区标识收敛为一个安全的路径段：
     * 拒绝路径分隔符与 {@code ..}，避免越出工作根。
     */
    static String safeSegment(String raw) {
        String segment = raw.trim();
        if (segment.isEmpty() || segment.contains("/") || segment.contains("\\") || segment.contains("..")) {
            throw new IllegalArgumentException("unsafe workspace directory segment: " + raw);
        }
        return segment;
    }

    /** 确保目录存在；失败即抛出（不静默降级）。 */
    private Path createIfMissing(Path directory) {
        if (Files.isDirectory(directory)) return directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            if (Files.isDirectory(directory)) return directory;
            throw new IllegalStateException("dsh.workspace_not_creatable: " + directory + " (" + error.getMessage() + ")");
        }
        return directory;
    }
}
