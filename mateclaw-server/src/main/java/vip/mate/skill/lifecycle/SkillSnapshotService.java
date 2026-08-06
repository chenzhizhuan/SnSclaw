package vip.mate.skill.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.skill.lifecycle.model.SkillSnapshotEntity;
import vip.mate.skill.lifecycle.repository.SkillSnapshotMapper;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Restore points for the skill library, captured before a mutating curator
 * sweep.
 *
 * <p>Autonomous curation makes broad, unattended changes: consolidation
 * rewrites skill bodies and folds several skills into an umbrella, and the
 * state machine archives skills out of the active set. Both run overnight with
 * nobody watching, so the first time anyone notices a bad pass is well after
 * it finished. A snapshot turns "the curator mangled my library" from an
 * unrecoverable event into one command.
 *
 * <p>Only the fields autonomous curation can actually change are captured.
 * The curator never deletes a skill — it archives, which is a state change —
 * so restoring is always an update over rows that still exist, never a
 * resurrection.
 *
 * <p>Restore is itself snapshotted first, so a rollback applied to the wrong
 * run can be rolled forward again.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillSnapshotService {

    private final SkillMapper skillMapper;
    private final SkillSnapshotMapper snapshotMapper;
    private final SkillLifecycleProperties properties;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Capture the current state of every curatable skill.
     *
     * @param reason why the snapshot was taken, shown in listings
     * @return the persisted snapshot, or {@code null} when snapshots are
     *         disabled or there was nothing to capture
     */
    public SkillSnapshotEntity capture(String reason) {
        if (!properties.isBackupEnabled()) {
            return null;
        }
        List<SkillEntity> skills = skillMapper.selectList(
                new LambdaQueryWrapper<SkillEntity>().eq(SkillEntity::getBuiltin, false));
        if (skills == null || skills.isEmpty()) {
            return null;
        }
        ArrayNode payload = objectMapper.createArrayNode();
        for (SkillEntity skill : skills) {
            payload.add(toNode(skill));
        }
        SkillSnapshotEntity snapshot = new SkillSnapshotEntity();
        snapshot.setReason(reason == null || reason.isBlank() ? "manual" : reason.strip());
        snapshot.setSkillCount(skills.size());
        try {
            snapshot.setPayload(objectMapper.writeValueAsString(payload));
            snapshotMapper.insert(snapshot);
        } catch (Exception e) {
            log.warn("[SkillSnapshot] Capture failed ({}): {}", reason, e.getMessage());
            return null;
        }
        pruneToRetention();
        log.info("[SkillSnapshot] Captured {} skill(s) — reason='{}', id={}",
                skills.size(), snapshot.getReason(), snapshot.getId());
        return snapshot;
    }

    /**
     * Roll the skill library back to a snapshot.
     *
     * <p>Takes a {@code pre-restore} snapshot first, so an unwanted rollback
     * can be undone by restoring that one.
     *
     * @param snapshotId snapshot to restore
     * @return per-skill outcome counts
     * @throws IllegalArgumentException when the snapshot does not exist or its
     *                                  payload cannot be read
     */
    public Map<String, Object> restore(Long snapshotId) {
        SkillSnapshotEntity snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new IllegalArgumentException("Snapshot " + snapshotId + " not found");
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(snapshot.getPayload() == null ? "[]" : snapshot.getPayload());
        } catch (Exception e) {
            throw new IllegalArgumentException("Snapshot " + snapshotId + " payload is unreadable", e);
        }
        if (!payload.isArray()) {
            throw new IllegalArgumentException("Snapshot " + snapshotId + " payload is not an array");
        }

        // Snapshot the current state before overwriting it, so restoring the
        // wrong run is not itself a one-way door.
        capture("pre-restore to snapshot " + snapshotId);

        int restored = 0;
        int missing = 0;
        for (JsonNode node : payload) {
            Long id = node.path("id").isNull() ? null : node.path("id").asLong(0);
            if (id == null || id == 0) {
                continue;
            }
            if (skillMapper.selectById(id) == null) {
                // The curator never deletes, so a row that is gone was removed
                // by something else; re-creating it here would resurrect a
                // deletion the user meant.
                missing++;
                continue;
            }
            try {
                skillMapper.update(null, new LambdaUpdateWrapper<SkillEntity>()
                        .eq(SkillEntity::getId, id)
                        .set(SkillEntity::getSkillContent, textOrNull(node, "skillContent"))
                        .set(SkillEntity::getDescription, textOrNull(node, "description"))
                        .set(SkillEntity::getVersion, textOrNull(node, "version"))
                        .set(SkillEntity::getTags, textOrNull(node, "tags"))
                        .set(SkillEntity::getOrigin, textOrNull(node, "origin"))
                        .set(SkillEntity::getLifecycleState, textOrNull(node, "lifecycleState"))
                        .set(SkillEntity::getEnabled, boolOrNull(node, "enabled"))
                        .set(SkillEntity::getPinned, boolOrNull(node, "pinned")));
                restored++;
            } catch (Exception e) {
                log.warn("[SkillSnapshot] Restore failed for skill id={}: {}", id, e.getMessage());
            }
        }
        log.info("[SkillSnapshot] Restored {} skill(s) from snapshot {} ({} no longer present)",
                restored, snapshotId, missing);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", String.valueOf(snapshotId));
        out.put("restored", restored);
        out.put("missing", missing);
        return out;
    }

    /** Recent snapshots, newest first, without their payloads. */
    public List<Map<String, Object>> list(int limit) {
        List<SkillSnapshotEntity> rows = snapshotMapper.selectList(
                new LambdaQueryWrapper<SkillSnapshotEntity>()
                        .select(SkillSnapshotEntity::getId, SkillSnapshotEntity::getReason,
                                SkillSnapshotEntity::getSkillCount, SkillSnapshotEntity::getCreateTime)
                        .orderByDesc(SkillSnapshotEntity::getCreateTime)
                        .last("LIMIT " + Math.max(1, limit)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillSnapshotEntity row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            // Snowflake id as a string: 19 digits exceed JS Number precision.
            m.put("id", String.valueOf(row.getId()));
            m.put("reason", row.getReason());
            m.put("skillCount", row.getSkillCount());
            m.put("createdAt", row.getCreateTime() == null ? null : row.getCreateTime().format(LABEL_FMT));
            out.add(m);
        }
        return out;
    }

    /** Drop the oldest snapshots beyond the configured retention count. */
    private void pruneToRetention() {
        int keep = Math.max(1, properties.getBackupKeep());
        List<SkillSnapshotEntity> rows = snapshotMapper.selectList(
                new LambdaQueryWrapper<SkillSnapshotEntity>()
                        .select(SkillSnapshotEntity::getId)
                        .orderByDesc(SkillSnapshotEntity::getCreateTime));
        if (rows.size() <= keep) {
            return;
        }
        for (SkillSnapshotEntity stale : rows.subList(keep, rows.size())) {
            try {
                snapshotMapper.deleteById(stale.getId());
            } catch (Exception e) {
                log.debug("[SkillSnapshot] Prune failed for {}: {}", stale.getId(), e.getMessage());
            }
        }
    }

    private ObjectNode toNode(SkillEntity skill) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", skill.getId());
        n.put("name", skill.getName());
        n.put("description", skill.getDescription());
        n.put("version", skill.getVersion());
        n.put("tags", skill.getTags());
        n.put("origin", skill.getOrigin());
        n.put("lifecycleState", skill.getLifecycleState());
        n.put("enabled", skill.getEnabled());
        n.put("pinned", skill.getPinned());
        n.put("skillContent", skill.getSkillContent());
        return n;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Boolean boolOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asBoolean();
    }
}
