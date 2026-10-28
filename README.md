# NFPL Access Control Center

A full-stack workspace for managing NFPL shipment access, with a React operations dashboard, an explainable access-control API, MySQL, and an auditable workflow.

**Tech stack:** React, Spring Boot 3, Java 17, MySQL 8, Flyway

Design decisions, trade-offs, and answers to all 14 assignment questions are documented in [DESIGN.md](DESIGN.md).

## Getting Started

The application runs using Docker Compose. You only need Docker Desktop or Docker Engine with Compose installed.

```powershell
docker compose up --build
```

Once the containers are running, open:

- Frontend: http://localhost:3000
- Backend API: http://localhost:8080

Compose builds the frontend and backend, waits for MySQL, applies Flyway migrations, loads the initial sample data, and waits for the API health check before starting the UI.

### Demo Login

| Username | Password |
|---|---|
| `asha` | `nfpl-demo` |
| `dev` | `nfpl-demo` |

All seeded sample and scale users share the configured demo password.

To set a different password before the first startup:

```powershell
$env:DEMO_PASSWORD = "choose-a-local-password"
docker compose up --build
```

Changing the environment variable later does not reset existing account passwords. These credentials are intended for local demonstration only.

### Stopping the Application

Press `Ctrl+C` or run:

```powershell
docker compose down
```

The named MySQL volume is retained.

To remove the database and reset the environment:

```powershell
docker compose down -v
```

This deletes the persisted database data.

### Local Development Commands

| Command | Description |
|---|---|
| `make seed` | Start MySQL and run the API with sample data. Resets the sample data. |
| `make scale` | Run the scale scenario with 500 users and 200,000 jobs. |
| `make test` | Run automated tests against the separate `nfpl_test` database. |
| `make report` | Print every user's effective access in plain language. |
| `make down` | Stop the local MySQL service. |

Sample data is stored as SQL under `src/main/resources/db/seed/`. The scale dataset uses SQL scripts and requires no additional tooling.

## Authentication and API Usage

Staff and client users authenticate through the same session-based login used by the dashboard. The overnight parser uses a separate service-to-service authentication mechanism.

For example, sign in as Neha and compare Ravi's and Farhan's access to job 4:

```powershell
curl.exe -c neha.cookies `
  -H "Content-Type: application/json" `
  -d '{"username":"neha","password":"nfpl-demo"}' `
  http://localhost:8080/api/auth/login

curl.exe -b neha.cookies `
  "http://localhost:8080/api/access/compare?userA=2&userB=17&action=CHECK&jobId=4"
```

The session cookie identifies the authenticated user. Changing the `X-User-Id` header cannot impersonate another person.

| Endpoint | Purpose |
|---|---|
| `POST /api/auth/login` | Authenticate and establish a session. |
| `GET /api/auth/me` | Retrieve the current authenticated user. |
| `POST /api/auth/logout` | End the current session. |

The overnight parser uses `X-User-Id: 10` and `X-Api-Key`. It cannot sign in through the dashboard.

All timestamps use UTC.

## Features

- Operational dashboard and scoped work queues
- Shipment details and history
- Approval workflows
- Effective-access summaries
- Grant and role administration
- Side-by-side access comparison
- Historical authorization replay
- Auditing of successful and denied actions
- Separation of duties and workflow concurrency checks

The backend is the source of truth for all permission decisions.

## Sample Data

The sample dataset contains 20 users, clients C01–C10, branches MUM, DEL, CHE, KOL, and BLR, and approximately 300 jobs.

| ID | User | Access |
|---:|---|---|
| 1 | Asha Menon | MAKER everywhere; CHECKER for C01 and C02. |
| 2 | Ravi Kulkarni | CHECKER for C03, MUM, IMPORT only. |
| 3 | Meera Shah | MAKER for DEL; currently ABSENT. |
| 4 | Dev Admin | ADMIN everywhere; no job-work permissions. |
| 5 | Kiran Rao | SUPERVISOR everywhere. |
| 6 | Lata Desai | SUPERVISOR everywhere; second-person approval. |
| 7 | Neha Iyer | AUDITOR everywhere, including history. |
| 8 | Priya Sharma | CLIENT_VIEWER for C05. |
| 9 | Raj Malhotra | CLIENT_VIEWER for C06. |
| 10 | Overnight Parser | PARSER everywhere; SYSTEM identity. |
| 11 | Bhavna Joshi | ADMIN for DEL only. |
| 12 | Sanjay Patil | CHECKER and FILER everywhere. |
| 13 | Tara Nair | CHECKER everywhere; grant expired 10 days ago. |
| 14 | Vikram Singh | MAKER for CHE and KOL. |
| 15 | Anil Gupta | FILER everywhere. |
| 16 | Sunita Rao | MAKER for BLR. |
| 17 | Farhan Khan | CHECKER and FILER for KOL. |
| 18 | Divya Pillai | Temporary MAKER grant for DEL, valid for seven more days. |
| 19 | Imran Sheikh | FILER for MUM/IMPORT and DEL/IMPORT. |
| 20 | Nikhil Joshi | No grants; new joiner. |

A blank scope means all values for that dimension. Every specified field within a grant must match. Separate grants are additive.

### Named Demo Jobs

| ID | Job | Scenario |
|---:|---|---|
| 1 | `NFPL-STORY-001` | Filed C01/MUM/IMPORT job prepared by Asha and checked by Tara. Demonstrates historical authorization replay. |
| 2 | `NFPL-ABSENT-001` | Owned by absent user Meera; Divya or a supervisor can take over. |
| 3 | `NFPL-SOD-001` | Asha prepared the job but cannot check her own work. |
| 4 | `NFPL-NARROW-001` | Ravi can check this C03/MUM/IMPORT job. |
| 5 | `NFPL-NARROW-002` | Ravi cannot check this C03/MUM/EXPORT job because his grant is IMPORT-only. Farhan cannot check it either. |

The remaining jobs, named `NFPL-000001` and onward, cover different workflow stages, directions, and modes. Their histories are illustrative; the five named scenarios are exact.

## Architecture

```text
React + Vite
     |
     v
Spring Boot REST API
     |
     +-- Controllers
     +-- AccessEvaluator
     +-- Workflow and queue services
     +-- Security and authentication
     +-- JDBC repositories
     |
     v
MySQL 8 <--- Flyway migrations
```

### Project Structure

```text
frontend/                         React + Vite dashboard, served by nginx

src/main/java/com/nfpl/access/
  AccessApplication.java           Spring Boot entry point
  controller/                      REST endpoints
  service/                         Access rules, workflow and queues
  repository/                      JDBC queries and persistence
  model/                           Domain and API records
  security/                        Session and parser authentication
  config/                          Password encoder and seed loader
  exception/                       API errors and error responses

src/main/resources/db/migration/   Flyway migrations
src/main/resources/db/seed/        Sample and scale SQL data
src/test/java/com/nfpl/access/     Automated tests
```

`model/Models.java` contains Java records, not JPA entities. Persistence uses SQL migrations, `NamedParameterJdbcTemplate`, and repository helpers.

The main application class is located in `com.nfpl.access`, allowing Spring Boot to discover the application's subpackages.

### Request Lifecycle

1. Staff and client users authenticate with BCrypt-verified passwords and server-side sessions. The parser uses its SYSTEM identity and API key.
2. Controllers validate requests and delegate to services. They do not make authorization decisions.
3. Services load the user, job, grants, role permissions, and workflow transition through `repository/Repo`.
4. `service/AccessEvaluator` checks user status, action, workflow stage, grant scope, role permissions, and separation of duties. Uncertain decisions are denied with an explanation trace.
5. Successful changes are persisted with an event and version check. Denied attempts are also recorded.
6. The React dashboard retrieves data and displays results from the API.

Queue calculations, authorization explanations, and historical replay share SQL rules in `service/GrantSql`.

## API Reference

All endpoints are under `/api`.

| Endpoint | Purpose |
|---|---|
| `GET /access/check?userId&action&jobId` | Evaluate an action and explain the decision. |
| `GET /access/compare?userA&userB&action&jobId` | Compare authorization decisions for two users. |
| `GET /users/{id}/queue?filter=all\|mine\|pool` | Return actionable jobs with counts and pagination. |
| `GET /jobs` | List jobs the user may see. |
| `GET /jobs/{id}` | Retrieve job details. |
| `POST /jobs/{id}/actions/{ACTION}` | Execute PREPARE, CHECK, FILE, CANCEL, CANCEL_FILED, REOPEN, REASSIGN, ADMIN_STEP_BACK, or UPDATE_DETAILS. |
| `PUT /jobs/{jobNo}` | Create or update an unprocessed job; used by the parser. |
| `GET /jobs/{id}/history` | Retrieve events, actors, explanations, and denied attempts. |
| `GET /jobs/{id}/who-could?action&at` | Replay authorization at a specific instant. |
| `POST /grants` | Create an access grant. |
| `POST /grants/{id}/revoke` | Revoke a grant. |
| `GET /grants?userId` | View grants within the caller's administration scope. |
| `GET /users/directory` | View users within the caller's administration scope. |
| `POST /roles` | Create a role. |
| `PUT /roles/{id}/permissions` | Update role permissions. |
| `GET /roles` | List roles. |
| `PUT /users/{id}/status` | Set status to ACTIVE, ABSENT, or DISABLED. |
| `GET /approvals` | List approvals. |
| `POST /approvals/{id}/approve` | Approve a request. |
| `POST /approvals/{id}/reject` | Reject a request. |
| `GET /users/{id}/access` | Return effective access in plain language. |
| `GET /access/report` | Generate an access report. |

Errors use a consistent JSON structure:

```json
{
  "error": "<CODE>",
  "message": "...",
  "trace": []
}
```

Authorization denials include the reasoning behind the decision.

## Testing

`make test` runs the database-backed access-control and HTTP authentication tests against a freshly loaded `nfpl_test` database. `AuthUnitTest` runs independently without MySQL.

| Scenario | Test |
|---|---|
| Maker/checker separation | `makerCannotCheckOwnWork` |
| Scoped checker permissions | `checkerLimitedToOneClientBranchAndDirection` |
| Absent owner and cover | `absentOwnerJobsFallIntoThePoolAndCanBeCoveredOrReassigned` |
| Grant revocation | `revokingAGrantReleasesTheJobsTheyCanNoLongerSee` |
| Filed-job cancellation | `cancellingAFiledJobNeedsSupervisorReasonAndASecondPerson` |
| Closed-job reopening | `reopeningAClosedJobNeedsTheSameCareThenReturnsItToTheMakers` |
| Admin self-escalation | `adminCannotMakeThemselvesMorePowerful` |
| Admin versus job-work permissions | `adminCanUnstickAJobButDoesNotDoJobWork` |
| Parser restrictions | `parserCanCreateAndUpdateUnprocessedJobsButNothingElse` |
| Client-side restrictions | `clientUserSeesOnlyTheirOwnShipmentsAndCannotAct` |
| Queue and authorization consistency | `jobsWaitingForMeIsExactlyWhatDecideAllows` |
| Access comparison | `compareExplainsWhyOnePersonCanAndAnotherCannot` |
| Historical replay | `whoCouldReplaysThePastNotThePresent` |
| Dynamic role changes | `aNewRoleTakesEffectImmediatelyAndNarrowingItAlsoDoes` |
| Fail-closed behavior | `whenUnsureTheAnswerIsNo` |
| Concurrent actions | `twoPeopleActingAtOnceOnlyOneWins` |
| User status changes | `statusChangesTakeEffectAtOnce` |

The queue consistency test checks every user against every job.

## Performance

Measurements were taken on a laptop-class machine with MySQL 8.0, 500 users, and 200,000 jobs.

| Operation | Time |
|---|---:|
| Load the complete scale dataset | Approximately 12 seconds |
| Queue page of 50 jobs plus exact count, including broad users with around 60,000 matching jobs | 15–90 ms |
| Visible-jobs page of 50 rows | 2–30 ms |

The initial implementation evaluated every job against every grant and took 300–400 ms for broad users.

The optimized `QueueService` compiles each user's grants into a small set of `(stage, scope)` entitlements, then executes ordered, index-backed queries per entitlement.

Counts were compared with an independent per-job calculation for eight users across the 200,000-job dataset and matched exactly.

## Assumptions and Limitations

The full design rationale and all 14 assignment answers are in [DESIGN.md](DESIGN.md).

The implementation assumes:

- Demo credentials are for local use; production should use corporate SSO.
- All timestamps are UTC.
- A job's client, branch, direction, and mode do not change.
- Ownership is a routing hint, not an authorization rule.
- Grant scopes are additive, with blank fields representing unrestricted dimensions.

The following features are outside the current scope:

- Corporate SSO, MFA, and production credential lifecycle.
- Historical authorization queries over time windows. The system evaluates an exact instant and lists events that occurred that day.
- Explicit deny rules and multi-valued scopes. Access to client A or B is represented by two grants.
- Notifications for pending approvals.
- Bulk grant import.
- Rate limiting.

## Verification

The following checks passed during implementation:

| Command | Result |
|---|---|
| `npm --prefix frontend run build` | Frontend production build passed. |
| `mvn -q -DskipTests package` | Backend and test sources compiled successfully. |
| `mvn -q -Dtest=AuthUnitTest test` | Isolated password/authentication tests passed. |
| `docker compose config` | Compose configuration parsed successfully. |

The full Docker Compose and MySQL-backed integration suite was not run in the implementation environment because Docker Engine was unavailable and the local MySQL service could not be started.

To verify the complete application locally:

```powershell
docker compose up --build
```

To run the database-backed tests when the required MySQL test database is available:

```powershell
make test
```

Compilation and configuration checks do not establish that every integration scenario passes.

---

For the complete design rationale, trade-offs, and answers to the 14 assignment questions, see [DESIGN.md](DESIGN.md).
