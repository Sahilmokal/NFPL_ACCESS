package com.nfpl.access.service;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.exception.ApiException;
import com.nfpl.access.repository.Repo;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.nfpl.access.repository.Repo.p;

/**
 * Answers "may this person do this action on this job?" and explains the answer.
 * Rule: deny unless EVERY check passes. Any unexpected error is also a deny (fail closed).
 */
@Component
public class AccessEvaluator {
    private final Repo repo;

    public AccessEvaluator(Repo repo) {
        this.repo = repo;
    }

    // ------------------------------------------------------------------ decisions

    public Decision decide(long uid, String action, long jobId) {
        Job job = repo.job(jobId).orElse(null);
        if (job == null) return deny(List.of(), "UNKNOWN_SUBJECT", "No such job #" + jobId, null);
        return decide(uid, action, job, Repo.now());
    }

    /** `job` may be a replayed snapshot (see ExplainService.whoCould); `at` is the moment being judged. */
    public Decision decide(long uid, String action, Job job, LocalDateTime at) {
        List<Step> trace = new ArrayList<>();
        try {
            User u = repo.user(uid).orElse(null);
            if (u == null) return deny(trace, "UNKNOWN_SUBJECT", "No such user #" + uid, null);

            String status = statusFor(u, at);
            if (!"ACTIVE".equals(status)) {
                return deny(trace, "USER_NOT_ACTIVE", u.fullName() + " is " + status + " and cannot act", null);
            }
            trace.add(new Step("User is active", true, u.fullName() + " is ACTIVE"));

            Transition t = repo.transition(action, job.stage()).orElse(null);
            if (t == null) {
                boolean known = repo.actionExists(action);
                return deny(trace, known ? "WRONG_STAGE" : "UNKNOWN_ACTION",
                        known ? action + " is not possible while the job is " + job.stage() : "Unknown action " + action,
                        null);
            }
            trace.add(new Step("Stage allows the action", true, action + " is possible from " + job.stage()
                    + " and needs " + t.permission()));
            return finish(u, t.permission(), t, job, at, trace);
        } catch (RuntimeException e) {
            // Fail closed: if we cannot be sure, the answer is no.
            trace.add(new Step("Evaluation error", false, e.getClass().getSimpleName()));
            return new Decision(false, "UNDETERMINED", "Could not evaluate safely, so access is denied", trace,
                    List.of(), null);
        }
    }

    /** Creating a job: there is no job yet, so judge the scope it WOULD have. */
    public Decision decideCreate(long uid, JobSpec spec) {
        List<Step> trace = new ArrayList<>();
        try {
            User u = repo.user(uid).orElse(null);
            if (u == null) return deny(trace, "UNKNOWN_SUBJECT", "No such user #" + uid, null);
            LocalDateTime at = Repo.now();
            String status = statusFor(u, at);
            if (!"ACTIVE".equals(status)) {
                return deny(trace, "USER_NOT_ACTIVE", u.fullName() + " is " + status + " and cannot act", null);
            }
            trace.add(new Step("User is active", true, u.fullName() + " is ACTIVE"));
            Job pseudo = new Job(0, null, spec.title(), spec.clientId(), spec.branchId(), spec.direction(), spec.mode(),
                    "NEW", null, null, null, 0, null, null);
            return finish(u, Permission.JOB_CREATE.name(), null, pseudo, at, trace);
        } catch (RuntimeException e) {
            trace.add(new Step("Evaluation error", false, e.getClass().getSimpleName()));
            return new Decision(false, "UNDETERMINED", "Could not evaluate safely, so access is denied", trace,
                    List.of(), null);
        }
    }

    private Decision finish(User u, String permission, Transition t, Job job, LocalDateTime at, List<Step> trace) {
        List<GrantRow> rows = grantRows(u, permission, job, at);
        List<GrantRow> good = rows.stream().filter(r -> r.covers() && r.hasPermission() && r.typeOk()).toList();
        if (good.isEmpty()) return deny(trace, "NO_GRANT", missMessage(u, permission, job, rows), t);

        List<Long> ids = good.stream().map(GrantRow::grantId).toList();
        GrantRow first = good.get(0);
        trace.add(new Step("An access grant covers this job", true, "Grant #" + first.grantId() + " (" + first.role()
                + " for " + repo.scopeText(first.clientId(), first.branchId(), first.direction(), first.mode())
                + ") includes " + permission + " and covers " + repo.jobScopeText(job)));

        if (t != null && "PREPARED_BY".equals(t.sodWith())) {
            if (Objects.equals(job.preparedBy(), u.id())) {
                return deny(trace, "SEPARATION_OF_DUTIES",
                        u.fullName() + " prepared this job, so someone else must check it", t);
            }
            trace.add(new Step("Maker/checker separation", true, "The preparer of this job is "
                    + repo.userName(job.preparedBy())));
        }
        String msg = u.fullName() + " may " + (t == null ? "CREATE" : t.action()) + " via grant #" + first.grantId();
        return new Decision(true, "ALLOWED", msg, trace, ids, t);
    }

    private String statusFor(User u, LocalDateTime at) {
        // Present-time questions use the live status; past questions replay the status history.
        if (at.isAfter(Repo.now().minusSeconds(2))) return u.status();
        return repo.statusAt(u.id(), at).orElse(u.status());
    }

    private static Decision deny(List<Step> trace, String code, String message, Transition t) {
        List<Step> tr = new ArrayList<>(trace);
        tr.add(new Step(code, false, message));
        return new Decision(false, code, message, tr, List.of(), t);
    }

    /** Why the person has no usable grant, in plain words (lists each grant they hold and what is wrong with it). */
    private String missMessage(User u, String permission, Job job, List<GrantRow> rows) {
        String head = u.fullName() + " cannot do this: no active access covers " + permission + " for "
                + repo.jobScopeText(job);
        if (rows.isEmpty()) return head + ". They hold no active grants.";
        List<String> why = new ArrayList<>();
        for (GrantRow r : rows) {
            String g = "grant #" + r.grantId() + " (" + r.role() + ", "
                    + repo.scopeText(r.clientId(), r.branchId(), r.direction(), r.mode()) + ")";
            if (!r.typeOk()) why.add(g + " cannot be used by a " + u.userType() + " user");
            else if (!r.hasPermission()) why.add(g + " does not include " + permission);
            else if (!r.covers()) why.add(g + " does not cover this job");
        }
        return head + ". " + String.join("; ", why);
    }

    // ------------------------------------------------------------------ grant breakdown

    /** Each of the user's grants in force at `at`, judged against one permission and one job's scope. */
    List<GrantRow> grantRows(User u, String permission, Job job, LocalDateTime at) {
        String sql = "SELECT g.id AS grant_id, r.name AS role_name, g.client_id, g.branch_id, g.direction, g.mode, "
                + "(" + GrantSql.covers(":c", ":b", ":d", ":m") + ") AS covers, "
                + "(" + GrantSql.roleHasPerm("rp.permission = :perm") + ") AS has_perm, "
                + "(" + GrantSql.TYPE_OK + ") AS type_ok "
                + "FROM access_grant g JOIN role r ON r.id = g.role_id "
                + "WHERE g.user_id = :uid AND " + GrantSql.ACTIVE_AT + " ORDER BY g.id";
        return repo.jdbc.query(sql, p("uid", u.id(), "utype", u.userType(), "perm", permission, "c", job.clientId(),
                "b", job.branchId(), "d", job.direction(), "m", job.mode(), "at", at),
                (rs, i) -> new GrantRow(rs.getLong("grant_id"), rs.getString("role_name"), Repo.nl(rs, "client_id"),
                        Repo.nl(rs, "branch_id"), rs.getString("direction"), rs.getString("mode"),
                        rs.getInt("covers") == 1, rs.getInt("has_perm") == 1, rs.getInt("type_ok") == 1));
    }

    // ------------------------------------------------------------------ quick yes/no checks (present time)

    private boolean active(long uid) {
        return repo.user(uid).map(u -> "ACTIVE".equals(u.status())).orElse(false);
    }

    private boolean exists(long uid, String permCondition, Job job) {
        User u = repo.user(uid).orElse(null);
        if (u == null || !"ACTIVE".equals(u.status())) return false;
        String sql = "SELECT EXISTS (SELECT 1 FROM access_grant g JOIN role r ON r.id = g.role_id WHERE g.user_id = :uid AND "
                + GrantSql.TYPE_OK + " AND " + GrantSql.ACTIVE_AT + " AND "
                + GrantSql.covers(":c", ":b", ":d", ":m") + " AND " + GrantSql.roleHasPerm(permCondition) + ")";
        Integer v = repo.jdbc.queryForObject(sql, p("uid", uid, "utype", u.userType(), "c", job.clientId(),
                "b", job.branchId(), "d", job.direction(), "m", job.mode(), "at", Repo.now(), "perm", null), Integer.class);
        return v != null && v == 1;
    }

    /** Does the person hold this permission for this job right now? */
    public boolean can(long uid, Permission perm, Job job) {
        return exists(uid, "rp.permission = '" + perm.name() + "'", job);
    }

    /** May the person see this job? Any job permission implies view; client users only ever see their own client. */
    public boolean canView(long uid, Job job) {
        return exists(uid, GrantSql.VIEW_PERM, job);
    }

    /**
     * Does one of the person's ACCESS_MANAGE-style grants CONTAIN the given scope?
     * (A narrower admin can never hand out, revoke or approve anything wider than their own scope.)
     */
    public boolean manageContains(long uid, Permission perm, Long clientId, Long branchId, String direction, String mode) {
        User u = repo.user(uid).orElse(null);
        if (u == null || !active(uid)) return false;
        String sql = "SELECT EXISTS (SELECT 1 FROM access_grant g JOIN role r ON r.id = g.role_id WHERE g.user_id = :uid AND "
                + GrantSql.TYPE_OK + " AND " + GrantSql.ACTIVE_AT + " AND "
                + GrantSql.roleHasPerm("rp.permission = :perm")
                + " AND (g.client_id IS NULL OR g.client_id <=> :c) AND (g.branch_id IS NULL OR g.branch_id <=> :b)"
                + " AND (g.direction IS NULL OR g.direction <=> :d) AND (g.mode IS NULL OR g.mode <=> :m))";
        Integer v = repo.jdbc.queryForObject(sql, p("uid", uid, "utype", u.userType(), "perm", perm.name(), "c", clientId,
                "b", branchId, "d", direction, "m", mode, "at", Repo.now()), Integer.class);
        return v != null && v == 1;
    }

    /** Does the person hold this permission anywhere at all (any scope)? */
    public boolean hasAnywhere(long uid, Permission perm) {
        User u = repo.user(uid).orElse(null);
        if (u == null || !active(uid)) return false;
        String sql = "SELECT EXISTS (SELECT 1 FROM access_grant g JOIN role r ON r.id = g.role_id WHERE g.user_id = :uid AND "
                + GrantSql.TYPE_OK + " AND " + GrantSql.ACTIVE_AT + " AND " + GrantSql.roleHasPerm("rp.permission = :perm") + ")";
        Integer v = repo.jdbc.queryForObject(sql, p("uid", uid, "utype", u.userType(), "perm", perm.name(),
                "at", Repo.now()), Integer.class);
        return v != null && v == 1;
    }
}
