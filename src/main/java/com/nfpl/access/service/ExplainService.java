package com.nfpl.access.service;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.exception.ApiException;
import com.nfpl.access.repository.Repo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

import static com.nfpl.access.service.GrantSql.*;
import static com.nfpl.access.repository.Repo.p;

/** Reading the story: who has what, why one person can and another cannot, who could have, what happened. */
@Service
public class ExplainService {
    private final Repo repo;
    private final AccessEvaluator eval;

    public ExplainService(Repo repo, AccessEvaluator eval) {
        this.repo = repo;
        this.eval = eval;
    }

    private Job visibleJob(long callerId, long jobId, Permission needed) {
        Job job = repo.job(jobId).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Job not found"));
        if (!eval.can(callerId, needed, job)) throw new ApiException(404, "NOT_FOUND", "Job not found");
        return job;
    }

    // ------------------------------------------------------------------ who has what, in plain words

    private record GrantLine(long roleId, String role, Long clientId, Long branchId, String direction, String mode,
                             LocalDateTime from, LocalDateTime to, LocalDateTime revoked) {}

    public Map<String, Object> describeAccess(long callerId, long userId, boolean includeEnded) {
        if (callerId != userId && !eval.hasAnywhere(callerId, Permission.ACCESS_MANAGE)) {
            throw new ApiException(403, "NOT_ALLOWED", "You can see your own access, or anyone's if you hold ACCESS_MANAGE");
        }
        User u = repo.user(userId).orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "No such user"));
        LocalDateTime now = Repo.now();
        List<GrantLine> grants = repo.jdbc.query("SELECT g.role_id, r.name AS role_name, g.client_id, g.branch_id, g.direction, g.mode, "
                        + "g.valid_from, g.valid_to, g.revoked_at FROM access_grant g JOIN role r ON r.id = g.role_id "
                        + "WHERE g.user_id = :u ORDER BY g.id", p("u", userId),
                (rs, i) -> new GrantLine(rs.getLong("role_id"), rs.getString("role_name"), Repo.nl(rs, "client_id"),
                        Repo.nl(rs, "branch_id"), rs.getString("direction"), rs.getString("mode"), Repo.ts(rs, "valid_from"),
                        Repo.ts(rs, "valid_to"), Repo.ts(rs, "revoked_at")));
        List<String> lines = new ArrayList<>();
        for (GrantLine g : grants) {
            boolean ended = g.revoked() != null || (g.to() != null && !g.to().isAfter(now));
            boolean notYet = g.from().isAfter(now);
            if ((ended || notYet) && !includeEnded) continue;
            String line = u.fullName() + " has role " + g.role() + " for "
                    + repo.scopeText(g.clientId(), g.branchId(), g.direction(), g.mode()) + ". Permissions: "
                    + String.join(", ", repo.permissionsOfRole(g.roleId(), now)) + ".";
            if (g.to() != null && !ended) line += " Valid until " + g.to() + " UTC.";
            if (g.revoked() != null) line += " ENDED: revoked " + g.revoked() + " UTC.";
            else if (ended) line += " ENDED: expired " + g.to() + " UTC.";
            if (notYet) line += " Not active until " + g.from() + " UTC.";
            lines.add(line);
        }
        if (lines.isEmpty()) lines.add(u.fullName() + " has no access grants.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", u.fullName());
        out.put("userType", u.userType());
        out.put("status", u.status());
        out.put("lines", lines);
        return out;
    }

    public List<String> report(long callerId) {
        if (!eval.hasAnywhere(callerId, Permission.ACCESS_MANAGE)) {
            throw new ApiException(403, "NOT_ALLOWED", "The full access report needs ACCESS_MANAGE");
        }
        List<String> all = new ArrayList<>();
        for (Long id : repo.jdbc.queryForList("SELECT id FROM app_user ORDER BY id", p(), Long.class)) {
            Map<String, Object> d = describeAccess(callerId, id, true);
            all.add(d.get("user") + " [" + d.get("userType") + ", " + d.get("status") + "]");
            @SuppressWarnings("unchecked") List<String> lines = (List<String>) d.get("lines");
            for (String l : lines) all.add("    " + l);
        }
        return all;
    }

    // ------------------------------------------------------------------ "why can she and he can't?"

    private Map<String, Object> decisionMap(long uid, Decision d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", repo.userName(uid));
        m.put("allowed", d.allowed());
        m.put("code", d.code());
        m.put("message", d.message());
        m.put("trace", d.trace());
        return m;
    }

    /** One person asking about themselves, or anyone with HISTORY_VIEW on the job asking about another person. */
    public Map<String, Object> check(long callerId, long userId, String action, long jobId) {
        Job job = repo.job(jobId).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Job not found"));
        if (callerId != userId && !eval.can(callerId, Permission.HISTORY_VIEW, job)) {
            throw new ApiException(404, "NOT_FOUND", "Job not found");
        }
        if (callerId == userId && !eval.canView(callerId, job)) throw new ApiException(404, "NOT_FOUND", "Job not found");
        return decisionMap(userId, eval.decide(userId, action, job, Repo.now()));
    }

    public Map<String, Object> compare(long callerId, long userA, long userB, String action, long jobId) {
        Job job = visibleJob(callerId, jobId, Permission.HISTORY_VIEW);
        LocalDateTime now = Repo.now();
        Decision a = eval.decide(userA, action, job, now);
        Decision b = eval.decide(userB, action, job, now);
        String summary;
        if (a.allowed() == b.allowed()) {
            summary = "Both get the same answer: " + (a.allowed() ? "allowed." : "denied. " + a.message() + " / " + b.message());
        } else {
            long yes = a.allowed() ? userA : userB;
            long no = a.allowed() ? userB : userA;
            Decision yesD = a.allowed() ? a : b;
            Decision noD = a.allowed() ? b : a;
            String because = yesD.trace().stream().filter(s -> s.check().equals("An access grant covers this job"))
                    .map(Step::detail).findFirst().orElse(yesD.message());
            summary = repo.userName(yes) + " can " + action + " " + job.jobNo() + ". " + because + ". " + noD.message();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job.jobNo() + " (" + repo.jobScopeText(job) + ", stage " + job.stage() + ")");
        out.put("action", action);
        out.put("summary", summary);
        out.put("a", decisionMap(userA, a));
        out.put("b", decisionMap(userB, b));
        return out;
    }

    // ------------------------------------------------------------------ "who could have changed this job on the 3rd?"

    /**
     * Replays the job's state and everyone's access as they were at `at`: the stage and maker come from the event log,
     * grants and role permissions from their start/end times, and user status from the status history.
     */
    public Map<String, Object> whoCould(long callerId, long jobId, String action, LocalDateTime at) {
        Job job = visibleJob(callerId, jobId, Permission.HISTORY_VIEW);
        LocalDateTime when = at == null ? Repo.now() : at;

        String stage = null;
        Long preparedBy = null;
        for (Map<String, Object> e : repo.jdbc.queryForList("SELECT event_type, actor_id, to_stage FROM job_event "
                + "WHERE job_id = :j AND created_at <= :at ORDER BY seq", p("j", jobId, "at", when))) {
            String type = (String) e.get("event_type");
            String to = (String) e.get("to_stage");
            if (to != null) stage = to;
            if ("PREPARE".equals(type) && e.get("actor_id") != null) preparedBy = ((Number) e.get("actor_id")).longValue();
            if ("ADMIN_STEP_BACK".equals(type) && "CONFIRMED".equals(to)) preparedBy = null;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job.jobNo() + " (" + repo.jobScopeText(job) + ")");
        out.put("at", when + " UTC");
        out.put("action", action);
        if (stage == null) {
            out.put("stageAtThatTime", null);
            out.put("message", "The job did not exist yet at that time");
            out.put("couldAct", List.of());
            return out;
        }
        out.put("stageAtThatTime", stage);
        out.put("preparedByAtThatTime", preparedBy == null ? null : repo.userName(preparedBy));

        Transition t = repo.transition(action, stage).orElse(null);
        if (t == null) {
            out.put("message", action + " was not possible while the job was " + stage + ", so nobody could do it");
            out.put("couldAct", List.of());
            out.put("excluded", List.of());
        } else {
            String sql = "SELECT u.id AS user_id, u.full_name, g.id AS grant_id, r.name AS role_name, g.client_id, g.branch_id, "
                    + "g.direction, g.mode FROM access_grant g JOIN role r ON r.id = g.role_id JOIN app_user u ON u.id = g.user_id "
                    + "WHERE FIND_IN_SET(u.user_type, r.allowed_user_types) > 0 AND " + ACTIVE_AT + " AND "
                    + covers(":c", ":b", ":d", ":m") + " AND " + roleHasPerm("rp.permission = :perm") + " ORDER BY u.id, g.id";
            List<Map<String, Object>> rows = repo.jdbc.queryForList(sql, p("at", when, "c", job.clientId(), "b", job.branchId(),
                    "d", job.direction(), "m", job.mode(), "perm", t.permission()));
            Map<Long, Map<String, Object>> could = new LinkedHashMap<>();
            Map<Long, Map<String, Object>> excluded = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                long uid = ((Number) row.get("user_id")).longValue();
                String status = repo.statusAt(uid, when).orElse("ACTIVE");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("userId", uid);
                entry.put("user", row.get("full_name"));
                entry.put("viaGrant", "#" + row.get("grant_id") + " " + row.get("role_name") + " ("
                        + repo.scopeText(row.get("client_id") == null ? null : ((Number) row.get("client_id")).longValue(),
                        row.get("branch_id") == null ? null : ((Number) row.get("branch_id")).longValue(),
                        (String) row.get("direction"), (String) row.get("mode")) + ")");
                if (!"ACTIVE".equals(status)) {
                    entry.put("excludedBecause", "was " + status + " at that time");
                    excluded.put(uid, entry);
                } else if ("PREPARED_BY".equals(t.sodWith()) && Objects.equals(preparedBy, uid)) {
                    entry.put("excludedBecause", "prepared the job, so maker/checker separation applies");
                    excluded.put(uid, entry);
                } else if (!could.containsKey(uid)) {
                    could.put(uid, entry);
                }
            }
            out.put("requiredPermission", t.permission());
            out.put("couldAct", new ArrayList<>(could.values()));
            out.put("excluded", new ArrayList<>(excluded.values()));
        }

        LocalDateTime dayStart = when.toLocalDate().atStartOfDay();
        out.put("whatActuallyHappenedThatDay", repo.jdbc.queryForList("SELECT e.seq, e.event_type, u.full_name AS actor, e.from_stage, "
                + "e.to_stage, e.created_at FROM job_event e LEFT JOIN app_user u ON u.id = e.actor_id WHERE e.job_id = :j "
                + "AND e.created_at >= :ds AND e.created_at < :de ORDER BY e.seq", p("j", jobId, "ds", dayStart, "de", dayStart.plusDays(1))));
        return out;
    }

    // ------------------------------------------------------------------ job history

    public Map<String, Object> history(long callerId, long jobId) {
        Job job = visibleJob(callerId, jobId, Permission.HISTORY_VIEW);
        List<Map<String, Object>> events = repo.jdbc.queryForList("SELECT e.seq, e.event_type, e.actor_id, u.full_name AS actor_name, "
                + "e.actor_type, e.from_stage, e.to_stage, e.reason, e.details, e.created_at, l.grant_ids, l.message AS authorised_because "
                + "FROM job_event e LEFT JOIN app_user u ON u.id = e.actor_id LEFT JOIN access_decision_log l ON l.id = e.decision_id "
                + "WHERE e.job_id = :j ORDER BY e.seq", p("j", jobId));
        List<Map<String, Object>> denied = repo.jdbc.queryForList("SELECT l.id, l.user_id, u.full_name AS user_name, l.action, l.code, "
                + "l.message, l.created_at FROM access_decision_log l JOIN app_user u ON u.id = l.user_id "
                + "WHERE l.job_id = :j AND l.allowed = 0 ORDER BY l.id", p("j", jobId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job);
        out.put("events", events);
        out.put("deniedAttempts", denied);
        return out;
    }
}
