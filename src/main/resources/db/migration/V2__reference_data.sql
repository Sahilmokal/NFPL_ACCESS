-- Reference data: the standard roles and the workflow. (People and jobs live in db/seed.)
-- Role ids are fixed because the sample data refers to them.
INSERT INTO role (id, name, description, allowed_user_types, is_sensitive) VALUES
 (1,'MAKER',        'Creates and prepares jobs',                                   'STAFF',  FALSE),
 (2,'CHECKER',      'Checks prepared jobs (never their own work)',                 'STAFF',  FALSE),
 (3,'FILER',        'Files checked jobs',                                          'STAFF',  FALSE),
 (4,'SUPERVISOR',   'Cancels filed jobs, reopens closed jobs, approves sensitive steps', 'STAFF', TRUE),
 (5,'ADMIN',        'Manages access and roles; can un-stick jobs; does NOT do job work', 'STAFF', TRUE),
 (6,'AUDITOR',      'Read-only, including job history',                            'STAFF',  FALSE),
 (7,'CLIENT_VIEWER','Client-side user who follows their own shipments',            'CLIENT', FALSE),
 (8,'PARSER',       'Overnight automated parser: creates/updates unprocessed jobs','SYSTEM', FALSE);

INSERT INTO role_permission (role_id, permission, added_at) VALUES
 (1,'JOB_VIEW','2024-01-01'),(1,'JOB_CREATE','2024-01-01'),(1,'JOB_EDIT','2024-01-01'),(1,'JOB_EDIT_PREPARED','2024-01-01'),
 (1,'JOB_PREPARE','2024-01-01'),(1,'JOB_CANCEL','2024-01-01'),(1,'HISTORY_VIEW','2024-01-01'),
 (2,'JOB_VIEW','2024-01-01'),(2,'JOB_CHECK','2024-01-01'),(2,'HISTORY_VIEW','2024-01-01'),
 (3,'JOB_VIEW','2024-01-01'),(3,'JOB_FILE','2024-01-01'),(3,'HISTORY_VIEW','2024-01-01'),
 (4,'JOB_VIEW','2024-01-01'),(4,'JOB_CANCEL','2024-01-01'),(4,'JOB_CANCEL_FILED','2024-01-01'),(4,'JOB_REOPEN','2024-01-01'),
 (4,'JOB_REASSIGN','2024-01-01'),(4,'APPROVE_SENSITIVE','2024-01-01'),(4,'HISTORY_VIEW','2024-01-01'),
 (5,'JOB_VIEW','2024-01-01'),(5,'JOB_REASSIGN','2024-01-01'),(5,'JOB_ADMIN_OVERRIDE','2024-01-01'),(5,'HISTORY_VIEW','2024-01-01'),
 (5,'ACCESS_MANAGE','2024-01-01'),(5,'ROLE_MANAGE','2024-01-01'),
 (6,'JOB_VIEW','2024-01-01'),(6,'HISTORY_VIEW','2024-01-01'),
 (7,'JOB_VIEW','2024-01-01'),
 (8,'JOB_CREATE','2024-01-01'),(8,'JOB_EDIT','2024-01-01');

-- Workflow. Stages: CONFIRMED -> PREPARED -> CHECKED -> FILED, plus CANCELLED / REOPENED.
INSERT INTO workflow_transition
 (action, from_stage, to_stage, required_permission, is_queue_step, requires_reason, requires_approval, sod_with) VALUES
 ('UPDATE_DETAILS','CONFIRMED','CONFIRMED','JOB_EDIT',         FALSE,FALSE,FALSE,NULL),
 ('UPDATE_DETAILS','PREPARED', 'PREPARED', 'JOB_EDIT_PREPARED',FALSE,FALSE,FALSE,NULL),
 ('PREPARE','CONFIRMED','PREPARED','JOB_PREPARE',TRUE, FALSE,FALSE,NULL),
 ('PREPARE','REOPENED', 'PREPARED','JOB_PREPARE',TRUE, FALSE,FALSE,NULL),
 ('CHECK',  'PREPARED', 'CHECKED', 'JOB_CHECK',  TRUE, FALSE,FALSE,'PREPARED_BY'),
 ('FILE',   'CHECKED',  'FILED',   'JOB_FILE',   TRUE, FALSE,FALSE,NULL),
 ('CANCEL', 'CONFIRMED','CANCELLED','JOB_CANCEL',FALSE,TRUE, FALSE,NULL),
 ('CANCEL', 'PREPARED', 'CANCELLED','JOB_CANCEL',FALSE,TRUE, FALSE,NULL),
 ('CANCEL', 'CHECKED',  'CANCELLED','JOB_CANCEL',FALSE,TRUE, FALSE,NULL),
 ('CANCEL', 'REOPENED', 'CANCELLED','JOB_CANCEL',FALSE,TRUE, FALSE,NULL),
 ('CANCEL_FILED','FILED','CANCELLED','JOB_CANCEL_FILED',FALSE,TRUE,TRUE,NULL),
 ('REOPEN', 'FILED',    'REOPENED','JOB_REOPEN', FALSE,TRUE, TRUE, NULL),
 ('REOPEN', 'CANCELLED','REOPENED','JOB_REOPEN', FALSE,TRUE, TRUE, NULL),
 ('ADMIN_STEP_BACK','PREPARED','CONFIRMED','JOB_ADMIN_OVERRIDE',FALSE,TRUE,FALSE,NULL),
 ('ADMIN_STEP_BACK','CHECKED', 'PREPARED', 'JOB_ADMIN_OVERRIDE',FALSE,TRUE,FALSE,NULL),
 ('REASSIGN','CONFIRMED','CONFIRMED','JOB_REASSIGN',FALSE,FALSE,FALSE,NULL),
 ('REASSIGN','PREPARED', 'PREPARED', 'JOB_REASSIGN',FALSE,FALSE,FALSE,NULL),
 ('REASSIGN','CHECKED',  'CHECKED',  'JOB_REASSIGN',FALSE,FALSE,FALSE,NULL),
 ('REASSIGN','REOPENED', 'REOPENED', 'JOB_REASSIGN',FALSE,FALSE,FALSE,NULL);
