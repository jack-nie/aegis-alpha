-- P3-14: make workflow_run idempotency index unique (manual migration for existing databases).
-- schema.sql uses CREATE TABLE IF NOT EXISTS, so existing tables keep the old non-unique index
-- until this script is run. NOT executed automatically (spring.sql.init only runs schema.sql).
--
-- Rows with idempotency_key IS NULL never conflict (MySQL UNIQUE allows multiple NULLs).
-- Note: rows with subject IS NULL also never conflict in the index; the application lookup uses
-- coalesce(subject, '') and still deduplicates those on the read path.

-- 1) Inspect duplicates first (must return no rows before step 3):
SELECT workflow_key, subject, idempotency_key, COUNT(*) AS cnt, MIN(started_at) AS first_started
FROM workflow_run
WHERE idempotency_key IS NOT NULL
GROUP BY workflow_key, subject, idempotency_key
HAVING COUNT(*) > 1;

-- 2) Optional cleanup (待确认 before running): keep the earliest run per key, null out the key on later ones
--    so their history is preserved but they no longer collide.
-- UPDATE workflow_run r
-- JOIN (
--   SELECT workflow_key, subject, idempotency_key, MIN(started_at) AS keep_started
--   FROM workflow_run
--   WHERE idempotency_key IS NOT NULL
--   GROUP BY workflow_key, subject, idempotency_key
--   HAVING COUNT(*) > 1
-- ) d ON r.workflow_key = d.workflow_key AND r.subject <=> d.subject AND r.idempotency_key = d.idempotency_key
-- SET r.idempotency_key = NULL
-- WHERE r.started_at > d.keep_started;

-- 3) Swap the index.
ALTER TABLE workflow_run
  DROP INDEX idx_workflow_run_idempotency,
  ADD UNIQUE INDEX idx_workflow_run_idempotency (workflow_key, subject, idempotency_key);
