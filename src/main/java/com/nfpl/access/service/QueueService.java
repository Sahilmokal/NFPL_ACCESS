package com.nfpl.access.service;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.exception.ApiException;
import com.nfpl.access.repository.Repo;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.nfpl.access.service.GrantSql.*;
import static com.nfpl.access.repository.Repo.p;

/**
 * "Jobs waiting for me" and "jobs I may see". Fast at 200,000 jobs because we do not test every job against every grant.
 * Instead we compile the person's few grants into a handful of (stage, scope) entitlements and run one small, ordered,
 * index-backed query per entitlement, then merge. The grant/permission/active-at rules come from the same GrantSql
 * fragments as AccessEvaluator, and a test proves the queue always equals "decide() says yes".
 */
@Service
public class QueueService {
    private static final String COLS = "j.id, j.job_no, j.title, j.stage, j.client_id, j.branch_id, j.direction, j.mode, "
            + "j.owner_id, j.updated_at";
    private static final Set<String> FILTERS = Set.of("all", "mine", "pool");

    private final Repo repo;

    public QueueService(Repo repo) {
        this.repo = repo;
    }

    private record Entitlement(Long clientId, Long branchId, String direction, String mode, String stage, String sodWith) {}

    private static int clamp(int limit) {
        return limit <= 0 ? 50 : Math.min(limit, 200);
    }

    private static String scope(String prefixAnd, Long clientId, Long branchId, String direction, String mode,
                                int i, MapSqlParameterSource ps) {
        StringBuilder w = new StringBuilder(prefixAnd);
        if (clientId != null) { w.append(" AND j.client_id = :c").append(i); ps.addValue("c" + i, clientId); }
        if (branchId != null) { w.append(" AND j.branch_id = :b").append(i); ps.addValue("b" + i, branchId); }
        if (direction != null) { w.append(" AND j.direction = :d").append(i); ps.addValue("d" + i, direction); }
        if (mode != null) { w.append(" AND j.mode = :m").append(i); ps.addValue("m" + i, mode); }
        return w.toString();
    }

    private static final org.springframework.jdbc.core.RowMapper<QueueItem> ITEM = (rs, i) -> new QueueItem(
            rs.getLong("id"), rs.getString("job_no"), rs.getString("title"), rs.getString("stage"),
            rs.getLong("client_id"), rs.getLong("branch_id"), rs.getString("direction"), rs.getString("mode"),
            Repo.nl(rs, "owner_id"), rs.getString("owner_name"), rs.getString("owner_status"),
            rs.getString("next_action"), Repo.ts(rs, "updated_at"));

    private static final String OUTER = "SELECT u.*, o.full_name AS owner_name, o.status AS owner_status, "
            + "(SELECT MIN(t.action) FROM workflow_transition t WHERE t.from_stage = u.stage AND t.is_queue_step = 1) AS next_action "
            + "FROM (%s) u LEFT JOIN app_user o ON o.id = u.owner_id ORDER BY %s LIMIT :lim";

    /**
     * Jobs this person can act on RIGHT NOW (their next step is one they may perform).
     * filter: all | mine (I am the owner) | pool (no owner, or the owner is absent/disabled).
     */
    public QueuePage queue(long uid, String filter, int limit, LocalDateTime afterUpdatedAt, Long afterId) {
        String f = filter == null ? "all" : filter;
        if (!FILTERS.contains(f)) throw new ApiException(400, "INVALID_FILTER", "filter must be all, mine or pool");
        User u = repo.user(uid).orElse(null);
        if (u == null || !"ACTIVE".equals(u.status())) return new QueuePage(0, List.of());   // absent people have no queue
        int lim = clamp(limit);
        LocalDateTime now = Repo.now();

        List<Entitlement> ents = repo.jdbc.query("SELECT DISTINCT g.client_id, g.branch_id, g.direction, g.mode, t.from_stage, t.sod_with "
                        + "FROM access_grant g JOIN role r ON r.id = g.role_id "
                        + "JOIN role_permission rp ON rp.role_id = g.role_id AND rp.added_at <= :at AND (rp.removed_at IS NULL OR rp.removed_at > :at) "
                        + "JOIN workflow_transition t ON t.required_permission = rp.permission AND t.is_queue_step = 1 "
                        + "WHERE g.user_id = :uid AND " + TYPE_OK + " AND " + ACTIVE_AT,
                p("uid", uid, "utype", u.userType(), "at", now),
                (rs, i) -> new Entitlement(Repo.nl(rs, "client_id"), Repo.nl(rs, "branch_id"), rs.getString("direction"),
                        rs.getString("mode"), rs.getString("from_stage"), rs.getString("sod_with")));
        if (ents.isEmpty()) return new QueuePage(0, List.of());

        MapSqlParameterSource ps = p("uid", uid, "lim", lim);
        List<String> preds = new ArrayList<>();
        for (int i = 0; i < ents.size(); i++) {
            Entitlement e = ents.get(i);
            ps.addValue("s" + i, e.stage());
            String pred = scope("j.stage = :s" + i, e.clientId(), e.branchId(), e.direction(), e.mode(), i, ps);
            if (e.sodWith() != null) pred += " AND NOT (j.prepared_by <=> :uid)";   // maker/checker: never your own work
            preds.add(pred);
        }
        String extra = switch (f) {
            case "mine" -> " AND j.owner_id = :uid";
            case "pool" -> " AND (j.owner_id IS NULL OR j.owner_id IN (SELECT id FROM app_user WHERE status <> 'ACTIVE'))";
            default -> "";
        };

        Long count = repo.jdbc.queryForObject("SELECT COUNT(*) FROM job j WHERE ((" + String.join(") OR (", preds) + "))" + extra,
                ps, Long.class);

        String keyset = "";
        if (afterUpdatedAt != null && afterId != null) {
            keyset = " AND (j.updated_at, j.id) > (:au, :aid)";
            ps.addValue("au", afterUpdatedAt);
            ps.addValue("aid", afterId);
        }
        List<String> branches = new ArrayList<>();
        for (String pred : preds) {
            branches.add("(SELECT " + COLS + " FROM job j WHERE " + pred + extra + keyset
                    + " ORDER BY j.updated_at, j.id LIMIT :lim)");
        }
        List<QueueItem> items = repo.jdbc.query(String.format(OUTER, String.join(" UNION ", branches), "u.updated_at, u.id"), ps, ITEM);
        return new QueuePage(count == null ? 0 : count, items);
    }

    /** Jobs this person may SEE (any job permission implies view), newest first. Optional stage filter. */
    public List<QueueItem> visible(long uid, String stage, int limit) {
        User u = repo.user(uid).orElse(null);
        if (u == null || !"ACTIVE".equals(u.status())) return List.of();
        int lim = clamp(limit);
        List<Entitlement> ents = repo.jdbc.query("SELECT DISTINCT g.client_id, g.branch_id, g.direction, g.mode "
                        + "FROM access_grant g JOIN role r ON r.id = g.role_id WHERE g.user_id = :uid AND " + TYPE_OK + " AND "
                        + ACTIVE_AT + " AND " + roleHasPerm(VIEW_PERM),
                p("uid", uid, "utype", u.userType(), "at", Repo.now()),
                (rs, i) -> new Entitlement(Repo.nl(rs, "client_id"), Repo.nl(rs, "branch_id"), rs.getString("direction"),
                        rs.getString("mode"), null, null));
        if (ents.isEmpty()) return List.of();

        MapSqlParameterSource ps = p("lim", lim);
        String stageCond = "1=1";
        if (stage != null && !stage.isBlank()) {
            stageCond = "j.stage = :stage";
            ps.addValue("stage", stage);
        }
        List<String> branches = new ArrayList<>();
        for (int i = 0; i < ents.size(); i++) {
            Entitlement e = ents.get(i);
            String pred = scope(stageCond, e.clientId(), e.branchId(), e.direction(), e.mode(), i, ps);
            branches.add("(SELECT " + COLS + " FROM job j WHERE " + pred + " ORDER BY j.updated_at DESC, j.id DESC LIMIT :lim)");
        }
        List<QueueItem> items = repo.jdbc.query(String.format(OUTER, String.join(" UNION ", branches), "u.updated_at DESC, u.id DESC"), ps, ITEM);
        if ("CLIENT".equals(u.userType())) {
            // a client sees their own shipments, not who inside NFPL handles them
            items = items.stream().map(i -> new QueueItem(i.id(), i.jobNo(), i.title(), i.stage(), i.clientId(), i.branchId(),
                    i.direction(), i.mode(), null, null, null, null, i.updatedAt())).toList();
        }
        return items;
    }
}
