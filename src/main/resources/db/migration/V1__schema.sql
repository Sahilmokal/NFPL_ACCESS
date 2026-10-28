-- NFPL Access Control Service: schema
-- Design notes:
--  * Authority = a GRANT (user + role + scope). Scope columns NULL mean "all".
--  * Roles are DATA (role + role_permission). Workflow is DATA (workflow_transition).
--  * Everything that matters is append-only or soft-deleted, so the past can be replayed.
--  * No foreign keys on purpose: keeps 200k-row loads and truncates simple. Integrity is enforced in the service.

CREATE TABLE branch (
  id   BIGINT PRIMARY KEY,
  code VARCHAR(10) NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE client (
  id   BIGINT PRIMARY KEY,
  code VARCHAR(10) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE app_user (
  id        BIGINT PRIMARY KEY AUTO_INCREMENT,
  username  VARCHAR(60) NOT NULL UNIQUE,
  full_name VARCHAR(120) NOT NULL,
  user_type ENUM('STAFF','CLIENT','SYSTEM') NOT NULL,
  status    ENUM('ACTIVE','ABSENT','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  client_id BIGINT NULL,            -- set for CLIENT users: the one client they belong to
  api_key   VARCHAR(80) NULL        -- set for SYSTEM users (the overnight parser)
) ENGINE=InnoDB;

CREATE TABLE user_status_history (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT NOT NULL,
  status     ENUM('ACTIVE','ABSENT','DISABLED') NOT NULL,
  changed_by BIGINT NULL,
  reason     VARCHAR(255) NULL,
  changed_at DATETIME(3) NOT NULL,
  KEY idx_ush (user_id, changed_at)
) ENGINE=InnoDB;

CREATE TABLE role (
  id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  name               VARCHAR(60) NOT NULL UNIQUE,
  description        VARCHAR(255) NULL,
  allowed_user_types VARCHAR(40) NOT NULL,       -- e.g. 'STAFF' or 'CLIENT' or 'SYSTEM'
  is_sensitive       BOOLEAN NOT NULL DEFAULT FALSE  -- granting it needs a second person
) ENGINE=InnoDB;

CREATE TABLE role_permission (          -- soft-deleted (removed_at) so past decisions can be replayed
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  role_id    BIGINT NOT NULL,
  permission VARCHAR(40) NOT NULL,
  added_at   DATETIME(3) NOT NULL,
  added_by   BIGINT NULL,
  removed_at DATETIME(3) NULL,
  removed_by BIGINT NULL,
  KEY idx_rp (role_id, permission)
) ENGINE=InnoDB;

CREATE TABLE access_grant (             -- never edited, only revoked
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT NOT NULL,
  role_id       BIGINT NOT NULL,
  client_id     BIGINT NULL,
  branch_id     BIGINT NULL,
  direction     ENUM('IMPORT','EXPORT') NULL,
  mode          ENUM('AIR','FCL','LCL') NULL,
  valid_from    DATETIME(3) NOT NULL,
  valid_to      DATETIME(3) NULL,
  granted_by    BIGINT NULL,
  approved_by   BIGINT NULL,
  reason        VARCHAR(255) NULL,
  revoked_at    DATETIME(3) NULL,
  revoked_by    BIGINT NULL,
  revoke_reason VARCHAR(255) NULL,
  KEY idx_grant_user (user_id, revoked_at, valid_from),
  KEY idx_grant_role (role_id)
) ENGINE=InnoDB;

CREATE TABLE workflow_transition (      -- the workflow is data: add a row, not code
  action              VARCHAR(30) NOT NULL,
  from_stage          VARCHAR(20) NOT NULL,
  to_stage            VARCHAR(20) NOT NULL,
  required_permission VARCHAR(40) NOT NULL,
  is_queue_step       BOOLEAN NOT NULL DEFAULT FALSE,  -- shows up in "jobs waiting for me"
  requires_reason     BOOLEAN NOT NULL DEFAULT FALSE,
  requires_approval   BOOLEAN NOT NULL DEFAULT FALSE,
  sod_with            ENUM('PREPARED_BY') NULL,         -- separation of duties
  PRIMARY KEY (action, from_stage)
) ENGINE=InnoDB;

CREATE TABLE job (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_no      VARCHAR(30) NOT NULL UNIQUE,
  title       VARCHAR(255) NULL,
  client_id   BIGINT NOT NULL,
  branch_id   BIGINT NOT NULL,
  direction   ENUM('IMPORT','EXPORT') NOT NULL,
  mode        ENUM('AIR','FCL','LCL') NOT NULL,
  stage       ENUM('CONFIRMED','PREPARED','CHECKED','FILED','CANCELLED','REOPENED') NOT NULL,
  owner_id    BIGINT NULL,      -- responsible person: a routing hint, NOT authority
  prepared_by BIGINT NULL,      -- maker of the current cycle (for maker/checker)
  checked_by  BIGINT NULL,
  version     INT NOT NULL DEFAULT 0,
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  KEY idx_job_stage (stage, client_id, branch_id),
  KEY idx_job_owner (owner_id, stage),
  KEY idx_job_updated (stage, updated_at, id),
  KEY idx_job_recent (updated_at, id)
) ENGINE=InnoDB;

CREATE TABLE access_decision_log (      -- every attempted action: allowed or denied, with the reasoning
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT NOT NULL,
  action     VARCHAR(30) NOT NULL,
  job_id     BIGINT NULL,
  allowed    BOOLEAN NOT NULL,
  code       VARCHAR(40) NOT NULL,
  message    VARCHAR(1000) NULL,
  grant_ids  VARCHAR(200) NULL,
  trace      JSON NULL,
  created_at DATETIME(3) NOT NULL,
  KEY idx_dl_job (job_id, created_at),
  KEY idx_dl_user (user_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE job_event (                -- append-only job history
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_id      BIGINT NOT NULL,
  seq         INT NOT NULL,
  event_type  VARCHAR(30) NOT NULL,
  actor_id    BIGINT NULL,
  actor_type  VARCHAR(10) NOT NULL,
  from_stage  VARCHAR(20) NULL,
  to_stage    VARCHAR(20) NULL,
  reason      VARCHAR(500) NULL,
  details     JSON NULL,
  decision_id BIGINT NULL,
  created_at  DATETIME(3) NOT NULL,
  UNIQUE KEY uq_event (job_id, seq),
  KEY idx_event_time (job_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE approval_request (         -- second-person approval for sensitive actions / roles
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  kind           ENUM('JOB_ACTION','GRANT') NOT NULL,
  job_id         BIGINT NULL,
  action         VARCHAR(30) NULL,
  reason         VARCHAR(500) NULL,
  requested_by   BIGINT NOT NULL,
  target_user_id BIGINT NULL,
  role_id        BIGINT NULL,
  client_id      BIGINT NULL,
  branch_id      BIGINT NULL,
  direction      ENUM('IMPORT','EXPORT') NULL,
  mode           ENUM('AIR','FCL','LCL') NULL,
  valid_to       DATETIME(3) NULL,
  status         ENUM('PENDING','APPROVED','REJECTED','CONSUMED') NOT NULL DEFAULT 'PENDING',
  decided_by     BIGINT NULL,
  decided_at     DATETIME(3) NULL,
  consumed_at    DATETIME(3) NULL,
  created_at     DATETIME(3) NOT NULL,
  KEY idx_ar_status (status, created_at)
) ENGINE=InnoDB;
