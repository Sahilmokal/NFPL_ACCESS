-- Scale case, part 2: 480 more staff (ids 21..500) with 1-2 grants each, scopes spread across the business.
SET SESSION cte_max_recursion_depth = 1000000;
SET @now = UTC_TIMESTAMP(3);
SET @t0  = UTC_TIMESTAMP(3) - INTERVAL 90 DAY;

INSERT INTO app_user (id, username, full_name, user_type, status, client_id, api_key)
WITH RECURSIVE seq(n) AS (SELECT 21 UNION ALL SELECT n + 1 FROM seq WHERE n < 500)
SELECT n, CONCAT('staff', n), CONCAT('Staff Member ', n), 'STAFF', IF(n % 25 = 0, 'ABSENT', 'ACTIVE'), NULL, NULL FROM seq;

INSERT INTO user_status_history (user_id, status, changed_by, reason, changed_at)
SELECT id, 'ACTIVE', NULL, 'scale seed', @t0 FROM app_user WHERE id > 20;

-- grant #1 for everyone, grant #2 for ~60% of them
INSERT INTO access_grant (user_id, role_id, client_id, branch_id, direction, mode, valid_from, granted_by, reason)
WITH RECURSIVE seq(n) AS (SELECT 21 UNION ALL SELECT n + 1 FROM seq WHERE n < 500)
SELECT n, ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('r1', n)),1,8),16,10) AS UNSIGNED) % 3, 1, 2, 3),
       IF(CAST(CONV(SUBSTRING(MD5(CONCAT('c1', n)),1,8),16,10) AS UNSIGNED) % 3 = 0, NULL, 1 + CAST(CONV(SUBSTRING(MD5(CONCAT('cc1', n)),1,8),16,10) AS UNSIGNED) % 10),
       IF(CAST(CONV(SUBSTRING(MD5(CONCAT('b1', n)),1,8),16,10) AS UNSIGNED) % 4 = 0, NULL, 1 + CAST(CONV(SUBSTRING(MD5(CONCAT('bb1', n)),1,8),16,10) AS UNSIGNED) % 5),
       IF(CAST(CONV(SUBSTRING(MD5(CONCAT('d1', n)),1,8),16,10) AS UNSIGNED) % 5 = 0, ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('dd1', n)),1,8),16,10) AS UNSIGNED) % 2, 'IMPORT', 'EXPORT'), NULL),
       IF(CAST(CONV(SUBSTRING(MD5(CONCAT('m1', n)),1,8),16,10) AS UNSIGNED) % 6 = 0, ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('mm1', n)),1,8),16,10) AS UNSIGNED) % 3, 'AIR', 'FCL', 'LCL'), NULL),
       @t0, 4, 'scale seed'
FROM seq;

INSERT INTO access_grant (user_id, role_id, client_id, branch_id, direction, mode, valid_from, granted_by, reason)
WITH RECURSIVE seq(n) AS (SELECT 21 UNION ALL SELECT n + 1 FROM seq WHERE n < 500)
SELECT n, ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('r2', n)),1,8),16,10) AS UNSIGNED) % 3, 2, 3, 1),
       IF(CAST(CONV(SUBSTRING(MD5(CONCAT('c2', n)),1,8),16,10) AS UNSIGNED) % 3 = 0, NULL, 1 + CAST(CONV(SUBSTRING(MD5(CONCAT('cc2', n)),1,8),16,10) AS UNSIGNED) % 10),
       IF(CAST(CONV(SUBSTRING(MD5(CONCAT('b2', n)),1,8),16,10) AS UNSIGNED) % 4 = 0, NULL, 1 + CAST(CONV(SUBSTRING(MD5(CONCAT('bb2', n)),1,8),16,10) AS UNSIGNED) % 5),
       IF(CAST(CONV(SUBSTRING(MD5(CONCAT('d2', n)),1,8),16,10) AS UNSIGNED) % 5 = 0, ELT(1 + CAST(CONV(SUBSTRING(MD5(CONCAT('dd2', n)),1,8),16,10) AS UNSIGNED) % 2, 'IMPORT', 'EXPORT'), NULL),
       NULL, @t0, 4, 'scale seed'
FROM seq WHERE CAST(CONV(SUBSTRING(MD5(CONCAT('has2', n)),1,8),16,10) AS UNSIGNED) % 10 < 6;
