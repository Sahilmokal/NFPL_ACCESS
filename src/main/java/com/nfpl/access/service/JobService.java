package com.nfpl.access.service;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.exception.ApiException;
import com.nfpl.access.repository.Repo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

import static com.nfpl.access.repository.Repo.p;

/** Performs actions on jobs. Authority is re-checked at the moment of action; nothing is cached. */
@Service
public class JobService {
    private static final Set<String> DIRECTIONS = Set.of("IMPORT", "EXPORT");
    private static final Set<String> MODES = Set.of("AIR", "FCL", "LCL");

    private final Repo repo;
    private final AccessEvaluator eval;
    private final DecisionLog decisionLog;
    private final ApprovalService approvals;

    public JobService(Repo repo, AccessEvaluator eval, DecisionLog decisionLog, ApprovalService approvals) {
        this.repo = repo;
        this.eval = eval;
        this.decisionLog = decisionLog;
        this.approvals = approvals;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Read one job: only if the person may see it; client-side users get it without internal staff details. */
    public Job get(long uid, long jobId) {
        Job job = repo.job(jobId).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Job not found"));
        if (!eval.canView(uid, job)) throw new ApiException(404, "NOT_FOUND", "Job not found");
        boolean client = repo.user(uid).map(u -> "CLIENT".equals(u.userType())).orElse(false);
        return client ? job.redactedForClient() : job;
    }

    /** Do an action (PREPARE, CHECK, FILE, CANCEL, CANCEL_FILED, REOPEN, UPDATE_DETAILS, REASSIGN, ADMIN_STEP_BACK ...). */
    @Transactional
    public ActionOutcome perform(long uid, String action, long jobId, ActionRequest req) {
        LocalDateTime now = Repo.now();
        Job job = repo.job(jobId).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "Job not found"));

        Decision d = eval.decide(uid, action, job, now);
        long decisionId = decisionLog.record(uid, action, jobId, d);   // survives even if we throw below
        if (!d.allowed()) {
            // People who cannot even see the job are told it does not exist (no leaking of other clients' shipments).
            if (!"USER_NOT_ACTIVE".equals(d.code()) && !eval.canView(uid, job)) {
                throw new ApiException(404, "NOT_FOUND", "Job not found");
            }
            throw new ApiException(403, d.code(), d.message(), d);
        }

        ActionRequest r = req == null ? new ActionRequest(null, null, null, null) : req;
        Transition t = d.transition();
        if (t.requiresReason() && blank(r.reason())) {
            throw new ApiException(400, "REASON_REQUIRED", action + " needs a reason");
        }
        if (t.requiresApproval()) {
            if (r.approvalId() == null) {
                long id = approvals.requestJobAction(uid, jobId, action, r.reason());
                return new ActionOutcome("PENDING_APPROVAL", job, id, "Approval #" + id + " is needed from a second person "
                        + "who holds APPROVE_SENSITIVE. After they approve, repeat this call with approvalId=" + id);
            }
            approvals.consume(r.approvalId(), uid, jobId, action, now);
        }

        Long owner = job.ownerId();
        Long prepared = job.preparedBy();
        Long checked = job.checkedBy();
        String title = job.title();
        String details = null;
        switch (action) {
            case "PREPARE" -> { prepared = uid; checked = null; }
            case "CHECK" -> checked = uid;
            case "REOPEN" -> checked = null;
            case "ADMIN_STEP_BACK" -> { if ("PREPARED".equals(job.stage())) prepared = null; else checked = null; }
            case "UPDATE_DETAILS" -> {
                if (blank(r.title())) throw new ApiException(400, "TITLE_REQUIRED", "UPDATE_DETAILS needs a title");
                title = r.title();
                details = "{\"title\":" + quote(title) + "}";
            }
            case "REASSIGN" -> {
                owner = r.newOwnerId();
                if (owner != null) {
                    Job fresh = job;
                    boolean ok = repo.user(owner).map(u -> "ACTIVE".equals(u.status())).orElse(false) && eval.canView(owner, fresh);
                    if (!ok) throw new ApiException(400, "INVALID_OWNER", "The new owner must be an active person who can see this job");
                }
                details = "{\"from\":" + job.ownerId() + ",\"to\":" + owner + "}";
            }
            default -> { }
        }

        int n = repo.jdbc.update("UPDATE job SET stage = :to, owner_id = :owner, prepared_by = :prep, checked_by = :chk, "
                        + "title = :title, version = version + 1, updated_at = :now "
                        + "WHERE id = :id AND stage = :from AND version = :ver",
                p("to", t.toStage(), "owner", owner, "prep", prepared, "chk", checked, "title", title, "now", now,
                        "id", jobId, "from", job.stage(), "ver", job.version()));
        if (n == 0) throw new ApiException(409, "CONFLICT", "The job changed while you were acting; reload and retry");

        repo.appendEvent(jobId, action, uid, job.stage(), t.toStage(), r.reason(), details, decisionId, now);
        return new ActionOutcome("DONE", repo.job(jobId).orElseThrow(), null, "OK");
    }

    /** Create a job, or update the details of an unprocessed one. Idempotent by job number (safe for the overnight parser). */
    @Transactional
    public ActionOutcome upsert(long uid, String jobNo, JobSpec spec) {
        if (blank(jobNo)) throw new ApiException(400, "JOB_NO_REQUIRED", "A job number is required");
        if (spec == null) throw new ApiException(400, "BODY_REQUIRED", "A job body is required");

        Job existing = repo.jobByNo(jobNo).orElse(null);
        if (existing != null) {
            boolean sameScope = (spec.clientId() == null || spec.clientId() == existing.clientId())
                    && (spec.branchId() == null || spec.branchId() == existing.branchId())
                    && (spec.direction() == null || spec.direction().equals(existing.direction()))
                    && (spec.mode() == null || spec.mode().equals(existing.mode()));
            if (!sameScope) {
                throw new ApiException(409, "SCOPE_IMMUTABLE", "Client, branch, direction and mode of a job cannot be changed");
            }
            return perform(uid, "UPDATE_DETAILS", existing.id(), new ActionRequest(null, null, spec.title(), null));
        }

        if (spec.clientId() == null || spec.branchId() == null || !DIRECTIONS.contains(spec.direction())
                || !MODES.contains(spec.mode())) {
            throw new ApiException(400, "INVALID_JOB", "clientId, branchId, direction (IMPORT/EXPORT) and mode (AIR/FCL/LCL) are required");
        }
        if (!repo.clientExists(spec.clientId()) || !repo.branchExists(spec.branchId())) {
            throw new ApiException(400, "INVALID_JOB", "Unknown client or branch");
        }
        Decision d = eval.decideCreate(uid, spec);
        long decisionId = decisionLog.record(uid, "CREATE", null, d);
        if (!d.allowed()) throw new ApiException(403, d.code(), d.message(), d);

        LocalDateTime now = Repo.now();
        long id = repo.insert("INSERT INTO job (job_no, title, client_id, branch_id, direction, mode, stage, version, created_at, "
                        + "updated_at) VALUES (:n, :t, :c, :b, :d, :m, 'CONFIRMED', 0, :now, :now)",
                p("n", jobNo, "t", spec.title(), "c", spec.clientId(), "b", spec.branchId(), "d", spec.direction(),
                        "m", spec.mode(), "now", now));
        repo.appendEvent(id, "CREATE", uid, null, "CONFIRMED", null, null, decisionId, now);
        return new ActionOutcome("DONE", repo.job(id).orElseThrow(), null, "Created");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
