-- Generates @job_count jobs (deterministic: an MD5 hash of the row number), each with a plausible event history.
-- Needs @job_count and @job_prefix (see 05_set_sample.sql / 05_set_scale.sql) and the people from 00_*.sql.
SET SESSION cte_max_recursion_depth = 1000000;
SET @now = UTC_TIMESTAMP(3);
SET @first_id = (SELECT COALESCE(MAX(id), 0) FROM job);

INSERT INTO job (job_no, title, client_id, branch_id, direction, mode, stage, owner_id, prepared_by, checked_by, version, created_at, updated_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < @job_count),
base AS (
  SELECT n,
    1 + CAST(CONV(SUBSTRING(MD5(CONCAT('client', n)),1,8),16,10) AS UNSIGNED) % 10 AS client_id,
    1 + CAST(CONV(SUBSTRING(MD5(CONCAT('branch', n)),1,8),16,10) AS UNSIGNED) % 5  AS branch_id,
    IF(CAST(CONV(SUBSTRING(MD5(CONCAT('dir', n)),1,8),16,10) AS UNSIGNED) % 2 = 0, 'IMPORT', 'EXPORT') AS direction,
    ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('mode', n)),1,8),16,10) AS UNSIGNED) % 3, 'AIR', 'FCL', 'LCL') AS mode,
    ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('stage', n)),1,8),16,10) AS UNSIGNED) % 10, 'CONFIRMED','CONFIRMED','PREPARED','PREPARED','CHECKED',
                                            'FILED','FILED','FILED','CANCELLED','REOPENED') AS stage
  FROM seq),
calc AS (
  SELECT b.*,
    ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('maker', n)),1,8),16,10) AS UNSIGNED) % 3, 1, 14, 16)   AS maker_id,
    ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('checker', n)),1,8),16,10) AS UNSIGNED) % 3, 12, 2, 17) AS checker_id,
    CASE stage WHEN 'CONFIRMED' THEN 1 WHEN 'PREPARED' THEN 2 WHEN 'CHECKED' THEN 3
               WHEN 'FILED' THEN 4 WHEN 'REOPENED' THEN 5 ELSE 6 END AS last_step,
    @now - INTERVAL (5 + CAST(CONV(SUBSTRING(MD5(CONCAT('age', n)),1,8),16,10) AS UNSIGNED) % 40) DAY - INTERVAL (CAST(CONV(SUBSTRING(MD5(CONCAT('hr', n)),1,8),16,10) AS UNSIGNED) % 24) HOUR AS created
  FROM base b)
SELECT CONCAT(@job_prefix, LPAD(n, 6, '0')), CONCAT('Shipment ', n), client_id, branch_id, direction, mode, stage,
       IF(stage IN ('CONFIRMED','PREPARED','CHECKED','REOPENED'),
          ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('own', n)),1,8),16,10) AS UNSIGNED) % 6, 1, 3, 14, 16, NULL, 12), NULL),
       IF(stage IN ('PREPARED','CHECKED','FILED','REOPENED'), maker_id, NULL),
       IF(stage IN ('CHECKED','FILED'), checker_id, NULL),
       last_step, created, created + INTERVAL (last_step - 1) * 3 HOUR
FROM calc;

INSERT INTO job_event (job_id, seq, event_type, actor_id, actor_type, from_stage, to_stage, reason, details, decision_id, created_at)
SELECT job_id, ROW_NUMBER() OVER (PARTITION BY job_id ORDER BY step), event_type, actor_id, actor_type,
       from_stage, to_stage, reason, NULL, NULL, created_at
FROM (
  SELECT j.id AS job_id, s.step,
    ELT(s.step, 'CREATE','PREPARE','CHECK','FILE','REOPEN','CANCEL') AS event_type,
    ELT(s.step, IF(j.id % 3 = 0, 10, 1), j.prepared_by, j.checked_by, 15, 5, 14) AS actor_id,
    IF(s.step = 1 AND j.id % 3 = 0, 'SYSTEM', 'STAFF') AS actor_type,
    ELT(s.step, NULL, 'CONFIRMED', 'PREPARED', 'CHECKED', 'FILED', 'CONFIRMED') AS from_stage,
    ELT(s.step, 'CONFIRMED', 'PREPARED', 'CHECKED', 'FILED', 'REOPENED', 'CANCELLED') AS to_stage,
    ELT(s.step, NULL, NULL, NULL, NULL, 'Client asked for corrections', 'Client cancelled the order') AS reason,
    j.created_at + INTERVAL (s.step - 1) * 3 HOUR AS created_at
  FROM job j
  JOIN (SELECT 1 AS step UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) s
    ON (s.step = 1
        OR (s.step = 2 AND j.stage IN ('PREPARED','CHECKED','FILED','REOPENED'))
        OR (s.step = 3 AND j.stage IN ('CHECKED','FILED','REOPENED'))
        OR (s.step = 4 AND j.stage IN ('FILED','REOPENED'))
        OR (s.step = 5 AND j.stage = 'REOPENED')
        OR (s.step = 6 AND j.stage = 'CANCELLED'))
  WHERE j.id > @first_id
) ev;
