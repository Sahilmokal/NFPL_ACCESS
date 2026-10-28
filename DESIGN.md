NFPL Access Control Center — Design Notes

This document explains the access-control model, key design decisions, operational scenarios, assumptions, and limitations of the NFPL Access Control Center.

1. Overview

The service controls what each user can see and which actions they can perform on a job. Access depends on the user's role and the scope of the grants assigned to them.

Grants, roles, and workflow

A grant can be limited by client, branch, direction, and mode. Leaving a scope field blank means the grant applies to all values for that field. Every specified field must match the job. When a user has several grants, their permissions are combined. Explicit deny rules are not supported.

Roles are stored as data in `role` and `role_permission`. The initial roles are:

- `MAKER`
- `CHECKER`
- `FILER`
- `SUPERVISOR`
- `ADMIN`
- `AUDITOR`
- `CLIENT_VIEWER`
- `PARSER`

Each role is restricted to the appropriate user type: staff, client, or system.

The workflow is configurable through `workflow_transition`. This table defines which actions are available at each stage, which permission is required, whether the action appears in a user's work queue, and whether it requires a reason, a second approver, or maker/checker separation. A new workflow step can usually be added as a row rather than through a code change.

Audit trail

Important changes remain traceable instead of being overwritten:

- Grants are revoked rather than edited.
- Removed role permissions receive a `removed_at` timestamp.
- User status changes are preserved in a history table.
- Job changes are recorded as events.
- Both allowed and denied action attempts are logged, including the reason for the decision.

How an access decision is made

An action is allowed only when all of the following conditions are met:

1. The user is `ACTIVE`.
2. The action is valid for the job's current stage.
3. An active grant covers the job's scope, and the role attached to that grant includes the required permission for the user's type.
4. Any separation-of-duties rule is satisfied. For example, the person who prepared the job in the current cycle cannot check it.
5. The request can be evaluated safely. If the system cannot establish that access is allowed, it denies the action or returns `UNDETERMINED`.

Some actions also require a reason or approval from another person. The job's `owner_id` routes work; it does not grant permission to perform an action.

The grant-and-permission check is defined in `GrantSql`. The normal access check, its explanation, the “who could have acted?” endpoint, and the work queue reuse the same SQL fragments. This reduces the risk of different parts of the service applying different rules.

2. Main Design Choices

Decision — Reason — Trade-off
Use scoped grants with a small set of roles — A role can be reused across many clients. For example, checking three clients requires three grants, not three separate roles. — People with many different scopes may need many grants. This is manageable for the expected 120 staff users.
Do not support deny rules or “all except” grants — The rules are easier to understand and explain. — Exceptions must be represented with narrower grants.
Use a small custom evaluator instead of a policy engine — Decisions need to be explainable, and the same rules must be usable in SQL for queue queries. — The team must maintain and test the evaluator.
Do not cache access decisions — Grant revocations, role changes, and user disabling should affect the next request. — Each decision requires a few database queries, acceptable for the expected 500 users.
Treat ownership as routing, not permission — A missing or absent owner should not prevent other qualified staff from handling a job. — Another qualified person may act on a colleague's job. The service flags this and supports `mine` and `pool` filters. A stricter reservation rule could be added later.
Build queues from indexed entitlement queries — Queue results must agree with the access evaluator. At 200,000 jobs, measured query time fell from 300–400 ms to 15–90 ms. — Queue filtering is another expression of the access rules. Shared SQL fragments and consistency tests help keep them aligned.
Do not use foreign keys — Bulk-loading 200,000 rows and truncating test data are simpler. — The service must enforce data integrity itself.
Keep job scope immutable — Client, branch, direction, and mode must remain stable so historical decisions can be reconstructed. — If scope was entered incorrectly, the job must be cancelled and recreated.
Return `404` for jobs the user cannot see — This avoids revealing that another client's shipment exists. — Users cannot ask why they were denied access to a hidden job. Users with `HISTORY_VIEW` can inspect relevant history where permitted.

3. Example Scenarios

1. Should everyone be able to see every job?

No. Viewing a job and acting on it are separate checks.

A user can see a job when one of their grants covers its scope and the associated role has a job-related permission. Acting on it additionally requires the correct permission for the current stage and compliance with workflow rules.

For example, a checker granted access to client `C03`, branch `MUM`, and `IMPORT` jobs can see that slice of work. Auditors, supervisors, and admins can see jobs within their permitted scope. A client user can see only their own client's shipments, and the response must not expose the names of internal staff working on them.

Job history has its own `HISTORY_VIEW` permission. Staff roles include it; client users do not.

2. A user is a Maker for all clients but a Checker for only a few

Asha has one `MAKER` grant with blank scope fields, plus separate `CHECKER` grants for clients `C01` and `C02`.

Ravi has a `CHECKER` grant limited to client `C03`, branch `MUM`, and direction `IMPORT`. The access report and denial messages explain which grant was checked. For example:

> Grant #4 (`CHECKER`, client `C03`, `MUM`, `IMPORT`) does not cover this job.

3. The person who prepared a job is also a qualified Checker

The four-eyes rule prevents a person from checking a job they prepared during the current cycle, even if they have the `CHECKER` role. The rule is configured with `sod_with = PREPARED_BY` and cannot be overridden, including by admins.

The decision explanation records that the user had the relevant permission but was blocked by separation of duties. The job should not appear in that user's checking queue.

If a job is reopened and prepared again, the new preparer becomes the person excluded from checking it. Smaller branches can use cross-branch Checker grants to provide enough coverage without weakening this rule.

4. The job owner is absent

A user with status `ABSENT` cannot perform actions and does not receive a work queue. Their jobs are not blocked: other qualified users can find them in the pool view, where the jobs are flagged as having an absent owner.

A supervisor can use `REASSIGN`, provided the new owner is allowed to see the job. Temporary cover can be handled with a time-limited grant; for example, Divya can cover Meera for seven days.

5. A user's responsibilities change while jobs are in progress

Access is checked whenever an action is attempted, so grant, role, and user-status changes take effect on the next request.

If a grant is revoked and the owner can no longer see an open job, the job returns to the pool and an `OWNER_RELEASED` event is recorded. Disabling a user releases all of their jobs. Completed work remains attributed to the original user in the history.

If a role is narrowed but the user can still see a job, they keep ownership, although the job may no longer appear in their actionable queue.

6. Cancelling a filed job or reopening a closed one

`JOB_CANCEL_FILED` and `JOB_REOPEN` are separate sensitive permissions, assigned only to `SUPERVISOR`.

Both actions require:

- A written reason.
- Approval from a second user with `APPROVE_SENSITIVE` for the job's scope.
- A different requester and approver.

Approval can be used once and expires after 24 hours. Admins cannot approve job-work actions. Each step is recorded in the audit history.

7. A client employee wants to follow their shipments

A user with type `CLIENT` can be assigned only the `CLIENT_VIEWER` role. Staff roles cannot be granted to client users, and a client's grant must refer to that client's own account. The service checks these restrictions both when a grant is created and when access is evaluated.

Client users can view their own shipments, but cannot see internal staff details, read job history, access a work queue, or perform job actions. Attempts to act are denied and logged.

8. An admin needs to fix a stuck job or increase their own access

`ADMIN` manages users, grants, and roles but does not have ordinary job-work permissions.

To move a stuck job back one stage, an admin can use `ADMIN_STEP_BACK`. The action requires a reason, is logged, cannot be used on filed jobs, and does not perform a check or bypass maker/checker separation.

Several rules limit privilege escalation:

- Users cannot change their own access.
- An admin can grant, revoke, or approve only within their own scope. For example, a Delhi admin cannot manage access in Kolkata.
- Sensitive roles require approval from someone other than the requester and grantee.
- Sensitive permissions cannot be added to roles through the API.
- A user cannot edit a role they currently hold.
- User status changes require global scope.

9. The overnight parser

The parser runs as a `SYSTEM` user with the `PARSER` role, which allows creation and updates only. It authenticates with an API key and can be used only by system users.

It processes jobs in the `CONFIRMED` stage, uses the job number to avoid duplicate processing, and cannot change a job's scope. It cannot act as a Maker or Checker and never receives a work queue. Its actions are marked `SYSTEM` in the history.

10. Why can one person perform an action while another cannot?

`GET /access/compare` evaluates both users against the same job using the normal access evaluator. The response explains which grant covered or failed to cover the job for each person and identifies the first rule that blocked the denied action.

11. Who could have changed a job on a particular date?

`GET /jobs/{id}/who-could?action&at` reconstructs the access decision for a specific point in time. It uses job events to determine the stage and preparer, grant validity periods, role-permission history, and user-status history.

The response lists who could have performed the action, who could not and why, along with the actions that actually occurred. In the example scenario, Tara could check the job 23 days ago but cannot check it now. Asha was excluded at the time because she had prepared it.

12. Adding a role or workflow step

`POST /roles` can create a role from existing non-sensitive permissions. `PUT /roles/{id}/permissions` can update its permissions, with changes taking effect immediately. A new workflow step can be configured through `workflow_transition`.

A code change is still needed when the system requires a genuinely new permission or a change to sensitive behaviour. This restriction is intentional.

13. Making sure the work queue matches actual permissions

The queue count and job list use a query built from the same SQL fragments as the access check. Tests compare the queue against the jobs for which the evaluator allows the user's next action.

At 200,000 jobs, queue counts also matched a separate per-job calculation. This matters because a queue that shows jobs a user cannot act on is misleading, while a queue that hides actionable jobs can delay work.

14. What happens when the system cannot make a reliable decision?

The service fails closed. An unknown user, job, or action, invalid input, or unexpected error must not result in access being granted. The response is `UNDETERMINED` or a specific denial code, and the attempt is logged.

Jobs the user cannot see are omitted from lists and return “not found” when requested directly.

4. Additional Cases Covered

- Who can read history and access explanations? Users need `HISTORY_VIEW` for the relevant job. Staff roles can have this permission; client users cannot. Users can always ask for information about their own access decision.
- What if two users act on the same job at once? The update checks the job's current stage and version. One request succeeds; the other receives `CONFLICT`. This behaviour is covered by tests.
- Are denied attempts recorded? Yes. The denial is written in a separate transaction so the record remains even if the main operation rolls back. The history includes the reason for denial.
- Can a regional admin act outside their region? No. The same scope limit applies when granting, revoking, and approving access.
- What if the client or branch was entered incorrectly? Job scope cannot be edited after creation. Cancel the job and create a replacement so the history remains consistent.
- When do access changes take effect? Grant revocations, role edits, and status changes apply immediately. Future-dated or expiring grants follow their specified times. Sensitive approvals expire after 24 hours.

5. Assumptions

- Staff and client users sign in through the demo username/password flow. Authentication uses a server-side session and an `HttpOnly` cookie, and passwords are stored as BCrypt hashes. Human callers cannot authenticate by supplying an `X-User-Id` header. The overnight `SYSTEM` user uses an API key. Before production use, replace demo login with corporate SSO and MFA, enforce HTTPS, and configure secure session-cookie settings.
- All timestamps are stored and interpreted in UTC. Current access checks use live user status; historical checks use status history.
- The normal stages are `Confirmed → Prepared → Checked → Filed`, with `Cancelled` and `Reopened` also supported. Reopening sends a job back to Prepare and requires the same controls as cancelling a filed job.
- Filed jobs cannot be edited. A supervised, approved reopen is the only route back into the workflow.
- Each grant contains one value per scope field. Access to client A and client B requires two grants rather than one grant with multiple client values.

6. Out of Scope

The current version does not include a production identity provider, SSO integration, MFA, or full credential lifecycle management. Historical “who could have acted?” queries cover one point in time rather than a date range. Deny rules, notifications, bulk grant imports, and rate limiting are also out of scope.

The demo UI and HTTP authentication checks are included. See the [README](README.md) for setup and usage details.
