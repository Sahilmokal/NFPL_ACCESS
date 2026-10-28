package com.nfpl.access.service;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.exception.ApiException;
import com.nfpl.access.repository.Repo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.nfpl.access.repository.Repo.p;

/**
 * Second-person approval for the dangerous things: cancelling a filed job, reopening a closed one, and granting a
 * sensitive role. Approver must hold APPROVE_SENSITIVE for that scope and cannot be the requester (or the grantee).
 */
@Service
public class ApprovalService {
    static final int APPROVAL_VALID_HOURS = 24;

    record Approval(long id, String kind, Long jobId, String action, String reason, long requestedBy, Long targetUserId,
                    Long roleId, Long clientId, Long branchId, String direction, String mode, LocalDateTime validTo,
                    String status, LocalDateTime decidedAt) {}

    private final Repo repo;
    private final AccessEvaluator eval;

    public ApprovalService(Repo repo, AccessEvaluator eval) {
        this.repo = repo;
        this.eval = eval;
    }

    private Approval load(long id) {
        return repo.jdbc.query("SELECT * FROM approval_request WHERE id = :id", p("id", id),
                (rs, i) -> new Approval(rs.getLong("id"), rs.getString("kind"), Repo.nl(rs, "job_id"),
                        rs.getString("action"), rs.getString("reason"), rs.getLong("requested_by"),
                        Repo.nl(rs, "target_user_id"), Repo.nl(rs, "role_id"), Repo.nl(rs, "client_id"),
                        Repo.nl(rs, "branch_id"), rs.getString("direction"), rs.getString("mode"),
                        Repo.ts(rs, "valid_to"), rs.getString("status"), Repo.ts(rs, "decided_at")))
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "NOT_FOUND", "No such approval #" + id));
    }

    public long requestJobAction(long uid, long jobId, String action, String reason) {
        return repo.insert("INSERT INTO approval_request (kind, job_id, action, reason, requested_by, created_at) "
                        + "VALUES ('JOB_ACTION', :j, :a, :r, :u, :now)",
                p("j", jobId, "a", action, "r", reason, "u", uid, "now", Repo.now()));
    }

    public long requestGrant(long uid, GrantRequest r) {
        return repo.insert("INSERT INTO approval_request (kind, reason, requested_by, target_user_id, role_id, client_id, "
                        + "branch_id, direction, mode, valid_to, created_at) VALUES ('GRANT', :rs, :u, :t, :role, :c, :b, :d, :m, :vt, :now)",
                p("rs", r.reason(), "u", uid, "t", r.userId(), "role", r.roleId(), "c", r.clientId(), "b", r.branchId(),
                        "d", r.direction(), "m", r.mode(), "vt", r.validTo(), "now", Repo.now()));
    }

    /** Used by JobService when the requester comes back with an approved approval. */
    public void consume(long approvalId, long uid, long jobId, String action, LocalDateTime now) {
        Approval a = load(approvalId);
        if (!"JOB_ACTION".equals(a.kind()) || a.requestedBy() != uid || !Objects.equals(a.jobId(), jobId)
                || !action.equals(a.action())) {
            throw new ApiException(403, "INVALID_APPROVAL", "Approval #" + approvalId + " is not for this person, job and action");
        }
        if (!"APPROVED".equals(a.status())) {
            throw new ApiException(409, "APPROVAL_NOT_APPROVED", "Approval #" + approvalId + " is " + a.status());
        }
        if (a.decidedAt() != null && a.decidedAt().isBefore(now.minusHours(APPROVAL_VALID_HOURS))) {
            throw new ApiException(409, "APPROVAL_EXPIRED", "Approval #" + approvalId + " is older than "
                    + APPROVAL_VALID_HOURS + " hours; request it again");
        }
        int n = repo.jdbc.update("UPDATE approval_request SET status = 'CONSUMED', consumed_at = :now "
                + "WHERE id = :id AND status = 'APPROVED'", p("now", now, "id", approvalId));
        if (n == 0) throw new ApiException(409, "APPROVAL_NOT_APPROVED", "Approval #" + approvalId + " was already used");
    }

    @Transactional
    public Map<String, Object> approve(long approverId, long approvalId) {
        Approval a = load(approvalId);
        if (!"PENDING".equals(a.status())) {
            throw new ApiException(409, "NOT_PENDING", "Approval #" + approvalId + " is already " + a.status());
        }
        if (a.requestedBy() == approverId) {
            throw new ApiException(403, "SELF_APPROVAL", "You cannot approve your own request");
        }
        LocalDateTime now = Repo.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalId", approvalId);

        if ("JOB_ACTION".equals(a.kind())) {
            Job job = repo.job(a.jobId()).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Job not found"));
            if (!eval.can(approverId, Permission.APPROVE_SENSITIVE, job)) {
                throw new ApiException(403, "NOT_AN_APPROVER", "You do not hold APPROVE_SENSITIVE for this job's scope");
            }
            repo.jdbc.update("UPDATE approval_request SET status = 'APPROVED', decided_by = :d, decided_at = :now WHERE id = :id",
                    p("d", approverId, "now", now, "id", approvalId));
            out.put("status", "APPROVED");
            out.put("message", repo.userName(a.requestedBy()) + " may now perform " + a.action() + " (valid "
                    + APPROVAL_VALID_HOURS + "h)");
        } else {
            if (Objects.equals(a.targetUserId(), approverId)) {
                throw new ApiException(403, "SELF_APPROVAL", "You cannot approve access for yourself");
            }
            if (!eval.manageContains(approverId, Permission.APPROVE_SENSITIVE, a.clientId(), a.branchId(), a.direction(), a.mode())) {
                throw new ApiException(403, "NOT_AN_APPROVER", "Your APPROVE_SENSITIVE scope does not contain this grant's scope");
            }
            long grantId = repo.insertGrant(a.targetUserId(), a.roleId(), a.clientId(), a.branchId(), a.direction(), a.mode(),
                    now, a.validTo(), a.requestedBy(), approverId, a.reason());
            repo.jdbc.update("UPDATE approval_request SET status = 'CONSUMED', decided_by = :d, decided_at = :now, consumed_at = :now WHERE id = :id",
                    p("d", approverId, "now", now, "id", approvalId));
            out.put("status", "GRANTED");
            out.put("grantId", grantId);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> reject(long approverId, long approvalId, String note) {
        Approval a = load(approvalId);
        if (!"PENDING".equals(a.status())) {
            throw new ApiException(409, "NOT_PENDING", "Approval #" + approvalId + " is already " + a.status());
        }
        if (a.requestedBy() == approverId) {
            throw new ApiException(403, "SELF_APPROVAL", "You cannot decide your own request");
        }
        if (!eval.hasAnywhere(approverId, Permission.APPROVE_SENSITIVE)) {
            throw new ApiException(403, "NOT_AN_APPROVER", "You do not hold APPROVE_SENSITIVE");
        }
        repo.jdbc.update("UPDATE approval_request SET status = 'REJECTED', decided_by = :d, decided_at = :now WHERE id = :id",
                p("d", approverId, "now", Repo.now(), "id", approvalId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalId", approvalId);
        out.put("status", "REJECTED");
        return out;
    }

    public List<Map<String, Object>> pending(long callerId) {
        if (!eval.hasAnywhere(callerId, Permission.APPROVE_SENSITIVE)) {
            throw new ApiException(403, "NOT_AN_APPROVER", "You do not hold APPROVE_SENSITIVE");
        }
        return repo.jdbc.queryForList("SELECT a.id, a.kind, a.job_id, a.action, a.reason, a.requested_by, "
                + "u.full_name AS requested_by_name, a.target_user_id, a.role_id, a.created_at FROM approval_request a "
                + "JOIN app_user u ON u.id = a.requested_by WHERE a.status = 'PENDING' ORDER BY a.id", p());
    }
}
