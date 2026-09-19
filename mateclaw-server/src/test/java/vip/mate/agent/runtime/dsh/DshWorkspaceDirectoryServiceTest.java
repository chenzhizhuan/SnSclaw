package vip.mate.agent.runtime.dsh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import vip.mate.agent.model.AgentEntity;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.repository.UserMapper;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the "one user, one directory" behaviour: creating a user materialises
 * {@code {root}/{username}}, and a DSH agent resolves to that directory unless
 * it carries an explicit override.
 */
class DshWorkspaceDirectoryServiceTest {

    private static UserEntity user(long id, String username) {
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setUsername(username);
        return entity;
    }

    private static WorkspaceEntity workspace(long id, String slug) {
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setId(id);
        entity.setSlug(slug);
        return entity;
    }

    private static AgentEntity agent(Long creatorId, Long workspaceId, String override) {
        AgentEntity entity = new AgentEntity();
        entity.setId(7L);
        entity.setCreatorUserId(creatorId);
        entity.setWorkspaceId(workspaceId);
        entity.setWorkspaceBasePath(override);
        return entity;
    }

    private static DshWorkspaceDirectoryService service(Path root, UserEntity user, WorkspaceEntity ws) {
        UserMapper userMapper = mock(UserMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        when(userMapper.selectById(ArgumentMatchers.any())).thenReturn(user);
        when(workspaceMapper.selectById(ArgumentMatchers.any())).thenReturn(ws);
        return new DshWorkspaceDirectoryService(userMapper, workspaceMapper, root.toString());
    }

    @Test
    @DisplayName("creating a user materialises {root}/{username}")
    void createsUserDirectory(@TempDir Path temp) {
        DshWorkspaceDirectoryService service = service(temp, null, null);

        Path created = service.ensureUserDirectoryQuietly("wangfan02");

        assertEquals(temp.resolve("wangfan02").toAbsolutePath().normalize(), created);
        assertTrue(Files.isDirectory(created));
    }

    @Test
    @DisplayName("user directory creation is idempotent")
    void idempotent(@TempDir Path temp) {
        DshWorkspaceDirectoryService service = service(temp, null, null);

        Path first = service.ensureUserDirectoryQuietly("wangfan02");
        Path second = service.ensureUserDirectoryQuietly("wangfan02");

        assertEquals(first, second);
        assertTrue(Files.isDirectory(second));
    }

    @Test
    @DisplayName("a blank username is a no-op rather than creating a nameless directory")
    void blankUsernameNoop(@TempDir Path temp) {
        DshWorkspaceDirectoryService service = service(temp, null, null);

        assertNull(service.ensureUserDirectoryQuietly("   "));
        assertNull(service.ensureUserDirectoryQuietly(null));
    }

    @Test
    @DisplayName("a malicious username cannot escape the workspace root")
    void rejectsUnsafeSegment(@TempDir Path temp) {
        DshWorkspaceDirectoryService service = service(temp, null, null);

        // Quietly: swallowed and reported as null, nothing created outside the root.
        assertNull(service.ensureUserDirectoryQuietly("../escape"));
        assertTrue(Files.isDirectory(temp));
    }

    @Test
    @DisplayName("an explicit agent override wins, preserving existing employees")
    void explicitOverrideWins(@TempDir Path temp) {
        Path explicit = temp.resolve("explicit-dir");
        DshWorkspaceDirectoryService service = service(temp.resolve("root"), user(9L, "yinqiang"), null);

        Path resolved = service.resolveForAgent(agent(9L, 1L, explicit.toString()));

        assertEquals(explicit.toAbsolutePath().normalize(), resolved);
    }

    @Test
    @DisplayName("without an override the agent lands in its creator's directory")
    void resolvesToCreatorDirectory(@TempDir Path temp) {
        Path root = temp.resolve("root");
        DshWorkspaceDirectoryService service = service(root, user(9L, "wangfan02"), workspace(5L, "wangfan"));

        Path resolved = service.resolveForAgent(agent(9L, 5L, null));

        assertEquals(root.resolve("wangfan02").toAbsolutePath().normalize(), resolved);
        assertTrue(Files.isDirectory(resolved), "resolver must materialise the directory");
    }

    @Test
    @DisplayName("a missing creator falls back to the workspace slug, still isolated")
    void fallsBackToWorkspaceSlug(@TempDir Path temp) {
        Path root = temp.resolve("root");
        DshWorkspaceDirectoryService service = service(root, null, workspace(5L, "yongxian"));

        Path resolved = service.resolveForAgent(agent(null, 5L, null));

        assertEquals(root.resolve("yongxian").toAbsolutePath().normalize(), resolved);
    }

    @Test
    @DisplayName("no override, no creator and no workspace fails loudly instead of sharing a root")
    void unresolvableThrows(@TempDir Path temp) {
        DshWorkspaceDirectoryService service = service(temp, null, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveForAgent(agent(null, null, null)));
        assertTrue(error.getMessage().contains("dsh.workspace_unresolved"));
    }
}
