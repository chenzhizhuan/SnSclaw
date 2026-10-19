-- V192: make owner-aware recall writes race-safe.
-- Irreversible cleanup: soft-deleted rows are no longer useful to the recall
-- ledger, and duplicate active identities must collapse before uniqueness.
DELETE FROM mate_memory_recall WHERE deleted <> 0;
UPDATE mate_memory_recall SET owner_key = '' WHERE owner_key IS NULL;
DROP TABLE IF EXISTS tmp_memory_recall_merge;
CREATE TEMPORARY TABLE tmp_memory_recall_merge AS
SELECT MAX(id) AS keep_id,
       SUM(COALESCE(recall_count, 0)) AS recall_total,
       SUM(COALESCE(daily_count, 0)) AS daily_total,
       MAX(last_recalled_at) AS last_recalled
FROM mate_memory_recall
GROUP BY agent_id, filename, scope, owner_key
HAVING COUNT(*) > 1;
UPDATE mate_memory_recall AS target
JOIN tmp_memory_recall_merge AS merged ON merged.keep_id = target.id
SET target.recall_count = merged.recall_total,
    target.daily_count = merged.daily_total,
    target.last_recalled_at = merged.last_recalled;
DROP TABLE tmp_memory_recall_merge;
DELETE FROM mate_memory_recall
WHERE id NOT IN (
    SELECT keep_id FROM (
        SELECT MAX(id) AS keep_id
        FROM mate_memory_recall
        GROUP BY agent_id, filename, scope, owner_key
    ) retained
);
ALTER TABLE mate_memory_recall
    MODIFY COLUMN owner_key VARCHAR(128) NOT NULL DEFAULT '';
CREATE UNIQUE INDEX uk_memory_recall_identity
    ON mate_memory_recall(agent_id, filename, scope, owner_key);
