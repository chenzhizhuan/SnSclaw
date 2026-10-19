package vip.mate.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.skill.model.SkillFileEntity;
import vip.mate.skill.repository.SkillFileMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistence layer for skill bundle files.
 * <p>
 * Treated as the canonical store: every install writes the full set of
 * scripts/references rows here, and {@code SkillFileSyncer} mirrors them
 * to the local workspace cache on every node so script execution works
 * across a multi-instance deployment that shares one database.
 *
 * @author SnSclaw
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillFileService {

    private final SkillFileMapper mapper;

    /** All file rows owned by a skill. */
    public List<SkillFileEntity> listBySkillId(Long skillId) {
        if (skillId == null) return List.of();
        QueryWrapper<SkillFileEntity> q = new QueryWrapper<>();
        q.eq("skill_id", skillId);
        return mapper.selectList(q);
    }

    /** Compute SHA-256 hex of a UTF-8 string (used for idempotent diffs). */
    public static String sha256Hex(String content) {
        if (content == null) content = "";
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    /**
     * Replace the skill's full file set with {@code newFiles}, using
     * write-then-prune semantics to mirror the on-disk applyBundleFiles.
     *
     * <p>Empty-bundle guard: if {@code newFiles} contains zero entries
     * for a bucket (scripts/ or references/) and there are existing rows
     * for that bucket, the rows are preserved unless {@code force=true}.
     * This blocks the same data-loss scenario that tripped up the FS path.
     *
     * @param skillId  owning skill id
     * @param newFiles new full file set, keyed by path under workspace root
     *                 (e.g. {@code "scripts/run.py"})
     * @param force    bypass empty-bundle guard
     */
    @Transactional
    public ApplyResult applyBundleFiles(Long skillId, Map<String, String> newFiles, boolean force) {
        Map<String, byte[]> bytes = new HashMap<>();
        if (newFiles != null) {
            newFiles.forEach((path, content) -> bytes.put(path,
                    (content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
        }
        return applyBundleBytes(skillId, bytes, force);
    }

    /** Apply a full DB-eligible bundle without decoding binary attachments as text. */
    @Transactional
    public ApplyResult applyBundleBytes(Long skillId, Map<String, byte[]> newFiles, boolean force) {
        if (skillId == null) {
            return new ApplyResult(0, 0, false, false);
        }

        Map<String, byte[]> incoming = newFiles == null ? Map.of() : newFiles;
        boolean newHasScripts = bucketHasEntries(incoming, "scripts/");
        boolean newHasRefs = bucketHasEntries(incoming, "references/");
        boolean newHasTemplates = bucketHasEntries(incoming, "templates/");

        List<SkillFileEntity> existing = listBySkillId(skillId);
        boolean existingHasScripts = existing.stream().anyMatch(e -> e.getFilePath() != null && e.getFilePath().startsWith("scripts/"));
        boolean existingHasRefs = existing.stream().anyMatch(e -> e.getFilePath() != null && e.getFilePath().startsWith("references/"));
        boolean existingHasTemplates = existing.stream().anyMatch(e -> e.getFilePath() != null && e.getFilePath().startsWith("templates/"));

        boolean preserveScripts = !newHasScripts && existingHasScripts && !force;
        boolean preserveRefs = !newHasRefs && existingHasRefs && !force;
        boolean preserveTemplates = !newHasTemplates && existingHasTemplates && !force;

        Map<String, SkillFileEntity> existingByPath = new HashMap<>();
        for (SkillFileEntity e : existing) existingByPath.put(e.getFilePath(), e);

        Set<String> keepPaths = new HashSet<>();
        if (preserveScripts) {
            for (SkillFileEntity e : existing) {
                if (e.getFilePath() != null && e.getFilePath().startsWith("scripts/")) {
                    keepPaths.add(e.getFilePath());
                }
            }
        }
        if (preserveRefs) {
            for (SkillFileEntity e : existing) {
                if (e.getFilePath() != null && e.getFilePath().startsWith("references/")) {
                    keepPaths.add(e.getFilePath());
                }
            }
        }
        if (preserveTemplates) {
            for (SkillFileEntity e : existing) {
                if (e.getFilePath() != null && e.getFilePath().startsWith("templates/")) {
                    keepPaths.add(e.getFilePath());
                }
            }
        }
        keepPaths.addAll(incoming.keySet());

        int written = 0;
        LocalDateTime now = LocalDateTime.now();
        for (var entry : incoming.entrySet()) {
            String path = entry.getKey();
            byte[] bytes = entry.getValue() == null ? new byte[0] : entry.getValue();
            EncodedContent encoded = encode(bytes);
            String content = encoded.content();
            String hash = sha256Hex(bytes);
            int size = bytes.length;

            SkillFileEntity prior = existingByPath.get(path);
            if (prior == null) {
                SkillFileEntity row = new SkillFileEntity();
                row.setSkillId(skillId);
                row.setFilePath(path);
                row.setContent(content);
                row.setContentEncoding(encoded.encoding());
                row.setContentSize(size);
                row.setSha256(hash);
                row.setCreateTime(now);
                row.setUpdateTime(now);
                mapper.insert(row);
                written++;
            } else if (!hash.equals(prior.getSha256())
                    || !encoded.encoding().equals(prior.isBinary() ? "base64" : "utf8")) {
                prior.setContent(content);
                prior.setContentEncoding(encoded.encoding());
                prior.setContentSize(size);
                prior.setSha256(hash);
                prior.setUpdateTime(now);
                mapper.updateById(prior);
                written++;
            }
        }

        int pruned = 0;
        for (SkillFileEntity e : existing) {
            if (!keepPaths.contains(e.getFilePath())) {
                mapper.deleteById(e.getId());
                pruned++;
            }
        }

        if (preserveScripts) {
            log.warn("Refused to prune scripts/ for skill_id={} — new bundle is empty. Pass force=true to override.", skillId);
        }
        if (preserveRefs) {
            log.warn("Refused to prune references/ for skill_id={} — new bundle is empty. Pass force=true to override.", skillId);
        }
        if (preserveTemplates) {
            log.warn("Refused to prune templates/ for skill_id={} — new bundle is empty. Pass force=true to override.", skillId);
        }

        return new ApplyResult(written, pruned, preserveScripts, preserveRefs);
    }

    /** Drop every file row for a skill (used on hard-delete). */
    @Transactional
    public int deleteAllForSkill(Long skillId) {
        if (skillId == null) return 0;
        return mapper.deleteBySkillId(skillId);
    }

    /** One file row by exact path, or {@code null}. */
    public SkillFileEntity getFile(Long skillId, String filePath) {
        if (skillId == null || filePath == null || filePath.isBlank()) return null;
        QueryWrapper<SkillFileEntity> q = new QueryWrapper<>();
        q.eq("skill_id", skillId).eq("file_path", filePath);
        return mapper.selectOne(q);
    }

    /**
     * Create or update a single file row. Content hash and size are
     * recomputed; a same-hash write is a no-op so idempotent saves don't
     * churn {@code update_time}.
     *
     * @return the persisted row (existing row instance on no-op)
     */
    @Transactional
    public SkillFileEntity upsertFile(Long skillId, String filePath, String content) {
        return upsertBytes(skillId, filePath,
                (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    /** Preserve arbitrary bytes; only strictly valid UTF-8 without control bytes is editable text. */
    @Transactional
    public SkillFileEntity upsertBytes(Long skillId, String filePath, byte[] bytes) {
        EncodedContent encoded = encode(bytes);
        String content = encoded.content();
        String encoding = encoded.encoding();
        String hash = sha256Hex(bytes);
        SkillFileEntity row = getFile(skillId, filePath);
        boolean insert = row == null;
        if (!insert && hash.equals(row.getSha256())) return row;
        LocalDateTime now = LocalDateTime.now();
        if (insert) {
            row = new SkillFileEntity();
            row.setSkillId(skillId);
            row.setFilePath(filePath);
            row.setCreateTime(now);
        }
        row.setContent(content);
        row.setContentEncoding(encoding);
        row.setContentSize(bytes.length);
        row.setSha256(hash);
        row.setUpdateTime(now);
        if (insert) mapper.insert(row);
        else mapper.updateById(row);
        return row;
    }

    /** Delete a single file row. Returns {@code true} when a row was removed. */
    @Transactional
    public boolean deleteFile(Long skillId, String filePath) {
        SkillFileEntity existing = getFile(skillId, filePath);
        if (existing == null) return false;
        mapper.deleteById(existing.getId());
        return true;
    }

    private static EncodedContent encode(byte[] bytes) {
        String text = null;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.codePoints().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')) text = null;
        } catch (CharacterCodingException binary) {
            // Keep the original bytes below.
        }
        String encoding = text == null ? "base64" : "utf8";
        String content = text == null ? Base64.getEncoder().encodeToString(bytes) : text;
        return new EncodedContent(content, encoding);
    }

    private record EncodedContent(String content, String encoding) {}

    private boolean bucketHasEntries(Map<String, ?> files, String prefix) {
        for (String key : files.keySet()) {
            if (key != null && key.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Outcome of {@link #applyBundleFiles}. */
    public record ApplyResult(int rowsWritten,
                              int rowsPruned,
                              boolean scriptsPreservedDueToEmptyBundle,
                              boolean referencesPreservedDueToEmptyBundle) {}
}
