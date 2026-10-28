-- Sample data, part 1: reset, branches, clients, 20 users, their grants, one "story" job.
-- Safe to re-run: it wipes people/jobs/history and restores the standard roles.
TRUNCATE TABLE job_event;
TRUNCATE TABLE access_decision_log;
TRUNCATE TABLE approval_request;
TRUNCATE TABLE access_grant;
TRUNCATE TABLE user_status_history;
TRUNCATE TABLE job;
TRUNCATE TABLE app_user;
TRUNCATE TABLE client;
TRUNCATE TABLE branch;
DELETE FROM role_permission WHERE added_by IS NOT NULL OR role_id > 8;
DELETE FROM role WHERE id > 8;
UPDATE role_permission SET removed_at = NULL, removed_by = NULL;

SET @now = UTC_TIMESTAMP(3);
SET @t0  = UTC_TIMESTAMP(3) - INTERVAL 90 DAY;

INSERT INTO branch (id, code, name) VALUES
 (1,'MUM','Mumbai'),(2,'DEL','Delhi'),(3,'CHE','Chennai'),(4,'KOL','Kolkata'),(5,'BLR','Bengaluru');

INSERT INTO client (id, code, name) VALUES
 (1,'C01','Aarav Textiles'),(2,'C02','Bharat Pharma'),(3,'C03','Coastal Spices'),(4,'C04','Delta Auto Parts'),
 (5,'C05','Eastern Tea Co'),(6,'C06','Fabrica Garments'),(7,'C07','Gujarat Chemicals'),(8,'C08','Himal Handicrafts'),
 (9,'C09','Indus Electronics'),(10,'C10','Jaipur Gems');

INSERT INTO app_user (id, username, full_name, user_type, status, client_id, api_key) VALUES
 (1,'asha','Asha Menon','STAFF','ACTIVE',NULL,NULL),
 (2,'ravi','Ravi Kulkarni','STAFF','ACTIVE',NULL,NULL),
 (3,'meera','Meera Shah','STAFF','ABSENT',NULL,NULL),
 (4,'dev','Dev Admin','STAFF','ACTIVE',NULL,NULL),
 (5,'kiran','Kiran Rao','STAFF','ACTIVE',NULL,NULL),
 (6,'lata','Lata Desai','STAFF','ACTIVE',NULL,NULL),
 (7,'neha','Neha Iyer','STAFF','ACTIVE',NULL,NULL),
 (8,'priya','Priya Sharma (client C05)','CLIENT','ACTIVE',5,NULL),
 (9,'raj','Raj Malhotra (client C06)','CLIENT','ACTIVE',6,NULL),
 (10,'overnight-parser','Overnight Parser','SYSTEM','ACTIVE',NULL,'parser-secret-key'),
 (11,'bhavna','Bhavna Joshi','STAFF','ACTIVE',NULL,NULL),
 (12,'sanjay','Sanjay Patil','STAFF','ACTIVE',NULL,NULL),
 (13,'tara','Tara Nair','STAFF','ACTIVE',NULL,NULL),
 (14,'vikram','Vikram Singh','STAFF','ACTIVE',NULL,NULL),
 (15,'anil','Anil Gupta','STAFF','ACTIVE',NULL,NULL),
 (16,'sunita','Sunita Rao','STAFF','ACTIVE',NULL,NULL),
 (17,'farhan','Farhan Khan','STAFF','ACTIVE',NULL,NULL),
 (18,'divya','Divya Pillai','STAFF','ACTIVE',NULL,NULL),
 (19,'imran','Imran Sheikh','STAFF','ACTIVE',NULL,NULL),
 (20,'nikhil','Nikhil Joshi','STAFF','ACTIVE',NULL,NULL);

INSERT INTO user_status_history (user_id, status, changed_by, reason, changed_at)
SELECT id, 'ACTIVE', NULL, 'initial', @t0 FROM app_user;
INSERT INTO user_status_history (user_id, status, changed_by, reason, changed_at)
VALUES (3, 'ABSENT', 4, 'On leave', @now - INTERVAL 3 DAY);

-- role ids: 1 MAKER, 2 CHECKER, 3 FILER, 4 SUPERVISOR, 5 ADMIN, 6 AUDITOR, 7 CLIENT_VIEWER, 8 PARSER
INSERT INTO access_grant (user_id, role_id, client_id, branch_id, direction, mode, valid_from, valid_to, granted_by, approved_by, reason, revoked_at, revoked_by, revoke_reason) VALUES
 (1, 1, NULL, NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Senior maker, all clients',                 NULL, NULL, NULL),
 (1, 2, 1,    NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Also checks client C01',                    NULL, NULL, NULL),
 (1, 2, 2,    NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Also checks client C02',                    NULL, NULL, NULL),
 (2, 2, 3,    1,    'IMPORT', NULL, @t0, NULL, 4,    NULL, 'Checker for C03 imports at Mumbai only',    NULL, NULL, NULL),
 (3, 1, NULL, 2,    NULL,     NULL, @t0, NULL, 4,    NULL, 'Maker at Delhi (currently absent)',         NULL, NULL, NULL),
 (4, 5, NULL, NULL, NULL,     NULL, @t0, NULL, NULL, NULL, 'Bootstrap admin',                           NULL, NULL, NULL),
 (5, 4, NULL, NULL, NULL,     NULL, @t0, NULL, NULL, NULL, 'Bootstrap supervisor',                      NULL, NULL, NULL),
 (6, 4, NULL, NULL, NULL,     NULL, @t0, NULL, NULL, NULL, 'Bootstrap supervisor (second person)',      NULL, NULL, NULL),
 (7, 6, NULL, NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Auditor, read only',                        NULL, NULL, NULL),
 (8, 7, 5,    NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Client user follows C05 shipments',         NULL, NULL, NULL),
 (9, 7, 6,    NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Client user follows C06 shipments',         NULL, NULL, NULL),
 (10,8, NULL, NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Overnight parser',                          NULL, NULL, NULL),
 (11,5, NULL, 2,    NULL,     NULL, @t0, NULL, NULL, NULL, 'Branch admin for Delhi only',               NULL, NULL, NULL),
 (12,2, NULL, NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Checker for everything',                    NULL, NULL, NULL),
 (12,3, NULL, NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Filer for everything',                      NULL, NULL, NULL),
 (13,2, NULL, NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Checker (moved teams)',                     @now - INTERVAL 10 DAY, 4, 'Moved to another team'),
 (14,1, NULL, 3,    NULL,     NULL, @t0, NULL, 4,    NULL, 'Maker at Chennai',                          NULL, NULL, NULL),
 (14,1, NULL, 4,    NULL,     NULL, @t0, NULL, 4,    NULL, 'Maker at Kolkata',                          NULL, NULL, NULL),
 (15,3, NULL, NULL, NULL,     NULL, @t0, NULL, 4,    NULL, 'Filer for everything',                      NULL, NULL, NULL),
 (16,1, NULL, 5,    NULL,     NULL, @t0, NULL, 4,    NULL, 'Maker at Bengaluru',                        NULL, NULL, NULL),
 (17,2, NULL, 4,    NULL,     NULL, @t0, NULL, 4,    NULL, 'Checker at Kolkata',                        NULL, NULL, NULL),
 (17,3, NULL, 4,    NULL,     NULL, @t0, NULL, 4,    NULL, 'Filer at Kolkata',                          NULL, NULL, NULL),
 (18,1, NULL, 2,    NULL,     NULL, @now - INTERVAL 3 DAY, @now + INTERVAL 7 DAY, 4, NULL, 'Covering for Meera at Delhi', NULL, NULL, NULL),
 (19,3, NULL, 1,    'IMPORT', NULL, @t0, NULL, 4,    NULL, 'Import filer at Mumbai',                    NULL, NULL, NULL),
 (19,3, NULL, 2,    'IMPORT', NULL, @t0, NULL, 4,    NULL, 'Import filer at Delhi',                     NULL, NULL, NULL);
 -- user 20 (Nikhil) deliberately has no grants: a new joiner

-- The story job: a filed import shipment. Asha prepared it, Tara checked it (her grant has since been revoked).
INSERT INTO job (job_no, title, client_id, branch_id, direction, mode, stage, owner_id, prepared_by, checked_by, version, created_at, updated_at)
VALUES ('NFPL-STORY-001', 'Story job: a filed import shipment used in the demo', 1, 1, 'IMPORT', 'AIR', 'FILED', NULL, 1, 13, 4,
        @now - INTERVAL 25 DAY, @now - INTERVAL 21 DAY);
SET @story = LAST_INSERT_ID();
INSERT INTO job_event (job_id, seq, event_type, actor_id, actor_type, from_stage, to_stage, reason, details, decision_id, created_at) VALUES
 (@story, 1, 'CREATE',  10, 'SYSTEM', NULL,        'CONFIRMED', NULL, NULL, NULL, @now - INTERVAL 25 DAY),
 (@story, 2, 'PREPARE',  1, 'STAFF',  'CONFIRMED', 'PREPARED',  NULL, NULL, NULL, @now - INTERVAL 24 DAY),
 (@story, 3, 'CHECK',   13, 'STAFF',  'PREPARED',  'CHECKED',   NULL, NULL, NULL, @now - INTERVAL 22 DAY),
 (@story, 4, 'FILE',    15, 'STAFF',  'CHECKED',   'FILED',     NULL, NULL, NULL, @now - INTERVAL 21 DAY);

-- Deliberate demo jobs (named, so the README and the demo can refer to them).
--  NFPL-ABSENT-001 : owned by Meera (absent), Delhi, still to be prepared -> Divya (cover) and others can pick it up
--  NFPL-SOD-001    : Asha prepared it; Asha also holds CHECKER for C01 -> she still cannot check it
--  NFPL-NARROW-001 : C03 / Mumbai / IMPORT  -> Ravi (checker for exactly that slice) CAN check it
--  NFPL-NARROW-002 : C03 / Mumbai / EXPORT  -> Ravi cannot (wrong direction)
INSERT INTO job (job_no, title, client_id, branch_id, direction, mode, stage, owner_id, prepared_by, checked_by, version, created_at, updated_at) VALUES
 ('NFPL-ABSENT-001',  'Demo: owner is absent',                    2, 2, 'EXPORT', 'FCL', 'CONFIRMED', 3,    NULL, NULL, 0, @now - INTERVAL 2 DAY, @now - INTERVAL 2 DAY),
 ('NFPL-SOD-001',     'Demo: prepared by Asha, who may also check C01', 1, 1, 'EXPORT', 'FCL', 'PREPARED',  NULL, 1,    NULL, 1, @now - INTERVAL 2 DAY, @now - INTERVAL 1 DAY),
 ('NFPL-NARROW-001',  'Demo: Ravi may check this one',            3, 1, 'IMPORT', 'LCL', 'PREPARED',  NULL, 1,    NULL, 1, @now - INTERVAL 2 DAY, @now - INTERVAL 1 DAY),
 ('NFPL-NARROW-002',  'Demo: Ravi may not check this one',        3, 1, 'EXPORT', 'LCL', 'PREPARED',  NULL, 1,    NULL, 1, @now - INTERVAL 2 DAY, @now - INTERVAL 1 DAY);
INSERT INTO job_event (job_id, seq, event_type, actor_id, actor_type, from_stage, to_stage, reason, details, decision_id, created_at)
SELECT j.id, 1, 'CREATE', 10, 'SYSTEM', NULL, 'CONFIRMED', NULL, NULL, NULL, j.created_at
FROM job j WHERE j.job_no IN ('NFPL-ABSENT-001','NFPL-SOD-001','NFPL-NARROW-001','NFPL-NARROW-002');
INSERT INTO job_event (job_id, seq, event_type, actor_id, actor_type, from_stage, to_stage, reason, details, decision_id, created_at)
SELECT j.id, 2, 'PREPARE', 1, 'STAFF', 'CONFIRMED', 'PREPARED', NULL, NULL, NULL, j.created_at + INTERVAL 1 DAY
FROM job j WHERE j.job_no IN ('NFPL-SOD-001','NFPL-NARROW-001','NFPL-NARROW-002');
