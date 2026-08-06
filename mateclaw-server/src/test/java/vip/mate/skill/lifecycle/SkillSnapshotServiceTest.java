package vip.mate.skill.lifecycle;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.skill.lifecycle.model.SkillSnapshotEntity;
import vip.mate.skill.lifecycle.repository.SkillSnapshotMapper;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillOrigin;
import vip.mate.skill.repository.SkillMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the curator's restore points — the only thing standing between an
 * unattended overnight sweep and an unrecoverable skill library.
 */
class SkillSnapshotServiceTest {

    private SkillMapper skillMapper;
    private SkillSnapshotMapper snapshotMapper;
    private SkillLifecycleProperties properties;
    private SkillSnapshotService service;

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper resolves column names through MyBatis Plus's
        // per-entity cache, which only Spring normally populates.
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, SkillEntity.class);
        TableInfoHelper.initTableInfo(assistant, SkillSnapshotEntity.class);
    }

    @BeforeEach
    void setUp() {
        skillMapper = mock(SkillMapper.class);
        snapshotMapper = mock(SkillSnapshotMapper.class);
        properties = new SkillLifecycleProperties();
        service = new SkillSnapshotService(skillMapper, snapshotMapper, properties, new ObjectMapper());
    }

    private SkillEntity skill(Long id, String name, String content, String state) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName(name);
        s.setSkillContent(content);
        s.setLifecycleState(state);
        s.setOrigin(SkillOrigin.AGENT.code());
        s.setEnabled(true);
        s.setPinned(false);
        return s;
    }

    @Test
    @DisplayName("capture serializes every curatable skill")
    void captureSerializesSkills() {
        when(skillMapper.selectList(any())).thenReturn(List.of(
                skill(1L, "a", "# A", "active"),
                skill(2L, "b", "# B", "stale")));
        when(snapshotMapper.selectList(any())).thenReturn(List.of());

        SkillSnapshotEntity snapshot = service.capture("pre-sweep");

        assertNotNull(snapshot);
        assertEquals(2, snapshot.getSkillCount());
        assertEquals("pre-sweep", snapshot.getReason());
        assertTrue(snapshot.getPayload().contains("\"name\":\"a\""), snapshot.getPayload());
        assertTrue(snapshot.getPayload().contains("# B"), snapshot.getPayload());
        verify(snapshotMapper, times(1)).insert(any(SkillSnapshotEntity.class));
    }

    @Test
    @DisplayName("capture is skipped when backups are disabled")
    void captureRespectsDisabledFlag() {
        properties.setBackupEnabled(false);

        assertNull(service.capture("pre-sweep"));

        verify(skillMapper, never()).selectList(any());
        verify(snapshotMapper, never()).insert(any(SkillSnapshotEntity.class));
    }

    @Test
    @DisplayName("capture with no skills writes nothing")
    void captureWithNoSkills() {
        when(skillMapper.selectList(any())).thenReturn(List.of());

        assertNull(service.capture("pre-sweep"));

        verify(snapshotMapper, never()).insert(any(SkillSnapshotEntity.class));
    }

    @Test
    @DisplayName("restore writes the captured content back over the current rows")
    void restoreRewritesSkills() {
        SkillSnapshotEntity snapshot = new SkillSnapshotEntity();
        snapshot.setId(77L);
        snapshot.setPayload("[{\"id\":1,\"name\":\"a\",\"skillContent\":\"# original\","
                + "\"lifecycleState\":\"active\",\"origin\":\"agent\",\"enabled\":true,\"pinned\":false}]");
        when(snapshotMapper.selectById(77L)).thenReturn(snapshot);
        when(skillMapper.selectById(1L)).thenReturn(skill(1L, "a", "# consolidated away", "archived"));
        when(skillMapper.selectList(any())).thenReturn(List.of(skill(1L, "a", "# consolidated away", "archived")));
        when(snapshotMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.restore(77L);

        assertEquals(1, result.get("restored"));
        assertEquals(0, result.get("missing"));
        verify(skillMapper, times(1)).update(eq(null), any());
    }

    @Test
    @DisplayName("restore snapshots the current state first, so a rollback is reversible")
    void restoreCapturesPreRestorePoint() {
        SkillSnapshotEntity snapshot = new SkillSnapshotEntity();
        snapshot.setId(77L);
        snapshot.setPayload("[]");
        when(snapshotMapper.selectById(77L)).thenReturn(snapshot);
        when(skillMapper.selectList(any())).thenReturn(List.of(skill(1L, "a", "# now", "active")));
        when(snapshotMapper.selectList(any())).thenReturn(List.of());

        service.restore(77L);

        ArgumentCaptor<SkillSnapshotEntity> captured = ArgumentCaptor.forClass(SkillSnapshotEntity.class);
        verify(snapshotMapper, atLeastOnce()).insert(captured.capture());
        assertTrue(captured.getValue().getReason().startsWith("pre-restore"),
                "rolling back must itself be undoable: " + captured.getValue().getReason());
    }

    @Test
    @DisplayName("restore does not resurrect a skill that no longer exists")
    void restoreSkipsMissingSkills() {
        SkillSnapshotEntity snapshot = new SkillSnapshotEntity();
        snapshot.setId(77L);
        snapshot.setPayload("[{\"id\":9,\"name\":\"gone\",\"skillContent\":\"# x\"}]");
        when(snapshotMapper.selectById(77L)).thenReturn(snapshot);
        when(skillMapper.selectById(9L)).thenReturn(null);
        when(skillMapper.selectList(any())).thenReturn(List.of());
        when(snapshotMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> result = service.restore(77L);

        assertEquals(0, result.get("restored"));
        assertEquals(1, result.get("missing"));
        verify(skillMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("restoring an unknown snapshot is rejected")
    void restoreUnknownSnapshot() {
        when(snapshotMapper.selectById(404L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.restore(404L));
    }

    @Test
    @DisplayName("capture prunes snapshots beyond the retention count")
    void capturePrunesOldSnapshots() {
        properties.setBackupKeep(2);
        when(skillMapper.selectList(any())).thenReturn(List.of(skill(1L, "a", "# A", "active")));
        SkillSnapshotEntity s1 = new SkillSnapshotEntity();
        s1.setId(1L);
        SkillSnapshotEntity s2 = new SkillSnapshotEntity();
        s2.setId(2L);
        SkillSnapshotEntity s3 = new SkillSnapshotEntity();
        s3.setId(3L);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(s1, s2, s3));

        service.capture("pre-sweep");

        // Newest two kept; the third is pruned.
        verify(snapshotMapper, times(1)).deleteById(3L);
        verify(snapshotMapper, never()).deleteById(1L);
        verify(snapshotMapper, never()).deleteById(2L);
    }

    @Test
    @DisplayName("listings expose snowflake ids as strings")
    void listReturnsStringIds() {
        SkillSnapshotEntity row = new SkillSnapshotEntity();
        row.setId(2055137662148763649L);
        row.setReason("pre-sweep");
        row.setSkillCount(3);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(row));

        List<Map<String, Object>> out = service.list(20);

        assertEquals("2055137662148763649", out.get(0).get("id"),
                "a 19-digit id must not round-trip through a JS Number");
    }
}
