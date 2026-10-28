package com.nfpl.access.service;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.exception.ApiException;
import com.nfpl.access.repository.Repo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.nfpl.access.service.GrantSql.*;
import static com.nfpl.access.repository.Repo.p;

/**
 * Managing who has what: grants, roles and user status. Guard rails:
 *  - nobody changes their own access;
 *  - an admin can only hand out / revoke access INSIDE their own scope (scope ceiling);
 *  - sensitive roles need a second person; sensitive permissions cannot be added to roles through the API;
 *  - nobody edits a role they currently hold.
 */
@Service
public class GrantService {
    private static final Set<String> DIRECTIONS = Set.of("IMPORT", "EXPORT");
    private static final Set<String> MODES = Set.of("AIR", "FCL", "LCL");
    private static final Set<String> USER_TYPES = Set.of("STAFF", "CLIENT", "SYSTEM");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "ABSENT", "DISABLED");

    private final Repo repo;
    private final AccessEvaluator eval;
    private final ApprovalService approvals;

    public GrantService(Repo repo, AccessEvaluator eval, ApprovalService approvals) {
        this.repo = repo;
        this.eval = eval;
        this.approvals = approvals;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private User activeActor(long actorId) {
        User a = repo.user(actorId).orElseThrow(() -> new ApiException(401, "UNKNOWN_CALLER", "No such user"));
        if (!"ACTIVE".equals(a.status())) throw new ApiException(403, "USER_NOT_ACTIVE", a.fullName() + " is " + a.status());
        return a;
    }

    // ------------------------------------------------------------------ grants

    @Transactional
    public GrantOutcome grant(long actorId, GrantRequest r) {
        activeActor(actorId);
        if (r == null || r.userId() == null || r.roleId() == null) {
            throw new ApiException(400, "INVALID_GRANT", "userId and roleId are required");
        }
        if (actorId == r.userId()) {
            throw new ApiException(403, "SELF_GRANT", "You cannot change your own access; ask another administrator");
        }
        User target = repo.user(r.userId()).orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "No such user"));
        if ("DISABLED".equals(target.status())) throw new ApiException(409, "USER_DISABLED", target.fullName() + " is disabled");
        Role role = repo.role(r.roleId()).orElseThrow(() -> new ApiException(404, "ROLE_NOT_FOUND", "No such role"));

        if (!Arrays.asList(role.allowedUserTypes().split(",")).contains(target.userType())) {
            throw new ApiException(403, "ROLE_NOT_ALLOWED_FOR_USER_TYPE", "Role " + role.name() + " cannot be given to a "
                    + target.userType() + " user");
        }
        if ("CLIENT".equals(target.userType()) && (r.clientId() == null || !r.clientId().equals(target.clientId()))) {
            throw new ApiException(403, "CLIENT_USER_MUST_BE_SCOPED_TO_OWN_CLIENT",
                    "A client-side user can only be given access to their own client");
        }
        if ((r.direction() != null && !DIRECTIONS.contains(r.direction())) || (r.mode() != null && !MODES.contains(r.mode()))) {
            throw new ApiException(400, "INVALID_GRANT", "direction must be IMPORT/EXPORT and mode AIR/FCL/LCL (or omitted)");
        }
        if ((r.clientId() != null && !repo.clientExists(r.clientId())) || (r.branchId() != null && !repo.branchExists(r.branchId()))) {
            throw new ApiException(400, "INVALID_GRANT", "Unknown client or branch");
        }
        LocalDateTime now = Repo.now();
        if (r.validTo() != null && !r.validTo().isAfter(now)) {
            throw new ApiException(400, "INVALID_GRANT", "validTo must be in the future");
        }
        if (!eval.manageContains(actorId, Permission.ACCESS_MANAGE, r.clientId(), r.branchId(), r.direction(), r.mode())) {
            throw new ApiException(403, "OUTSIDE_MANAGE_SCOPE",
                    "You can only give access inside the scope you manage; this grant is wider or elsewhere");
        }
        if (role.sensitive()) {
            long id = approvals.requestGrant(actorId, r);
            return new GrantOutcome("PENDING_APPROVAL", null, id, "Role " + role.name() + " is sensitive. Approval #" + id
                    + " is needed from a second person who holds APPROVE_SENSITIVE.");
        }
        long gid = repo.insertGrant(r.userId(), r.roleId(), r.clientId(), r.branchId(), r.direction(), r.mode(), now,
                r.validTo(), actorId, null, r.reason());
        return new GrantOutcome("GRANTED", gid, null, "Effective immediately");
    }

    @Transactional
    public Map<String, Object> revoke(long actorId, long grantId, String reason) {
        activeActor(actorId);
        List<Map<String, Object>> rows = repo.jdbc.queryForList("SELECT user_id, client_id, branch_id, direction, mode, "
                + "revoked_at FROM access_grant WHERE id = :id", p("id", grantId));
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "No such grant #" + grantId);
        Map<String, Object> g = rows.get(0);
        if (g.get("revoked_at") != null) throw new ApiException(409, "ALREADY_REVOKED", "Grant #" + grantId + " is already revoked");
        Long c = g.get("client_id") == null ? null : ((Number) g.get("client_id")).longValue();
        Long b = g.get("branch_id") == null ? null : ((Number) g.get("branch_id")).longValue();
        if (!eval.manageContains(actorId, Permission.ACCESS_MANAGE, c, b, (String) g.get("direction"), (String) g.get("mode"))) {
            throw new ApiException(403, "OUTSIDE_MANAGE_SCOPE", "You can only revoke access inside the scope you manage");
        }
        LocalDateTime now = Repo.now();
        repo.jdbc.update("UPDATE access_grant SET revoked_at = :now, revoked_by = :a, revoke_reason = :r "
                + "WHERE id = :id AND revoked_at IS NULL", p("now", now, "a", actorId, "r", reason, "id", grantId));
        long target = ((Number) g.get("user_id")).longValue();
        int released = releaseOwned(target, actorId, "Grant #" + grantId + " revoked", true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revokedGrantId", grantId);
        out.put("jobsReleasedToPool", released);
        return out;
    }

    public List<Map<String, Object>> listUsers(long actorId) {
        requireAccessManager(actorId);
        return repo.jdbc.queryForList("SELECT id, username, full_name, user_type, status, client_id "
                + "FROM app_user ORDER BY full_name", p());
    }

    public List<Map<String, Object>> listGrants(long actorId, long userId) {
        requireAccessManager(actorId);
        if (repo.user(userId).isEmpty()) throw new ApiException(404, "USER_NOT_FOUND", "No such user");
        List<Map<String, Object>> grants = repo.jdbc.queryForList("SELECT g.id, g.role_id, r.name AS role_name, "
                + "g.client_id, g.branch_id, g.direction, g.mode, g.valid_from, g.valid_to, g.revoked_at, g.reason "
                + "FROM access_grant g JOIN role r ON r.id = g.role_id WHERE g.user_id = :userId ORDER BY g.id DESC",
                p("userId", userId));
        return grants.stream().filter(grant -> eval.manageContains(actorId, Permission.ACCESS_MANAGE,
                nullableLong(grant.get("client_id")), nullableLong(grant.get("branch_id")),
                (String) grant.get("direction"), (String) grant.get("mode"))).toList();
    }

    private void requireAccessManager(long actorId) {
        activeActor(actorId);
        if (!eval.hasAnywhere(actorId, Permission.ACCESS_MANAGE)) {
            throw new ApiException(403, "NOT_ALLOWED", "This operation needs ACCESS_MANAGE");
        }
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /**
     * Responsibilities changed: jobs the person owns but can no longer even see go back to the pool (event OWNER_RELEASED).
     * With onlyLosingAccess=false (user disabled) every open job they own is released.
     */
    int releaseOwned(long uid, long actorId, String reason, boolean onlyLosingAccess) {
        User u = repo.user(uid).orElseThrow();
        LocalDateTime now = Repo.now();
        String lacking = onlyLosingAccess
                ? " AND NOT EXISTS (SELECT 1 FROM access_grant g JOIN role r ON r.id = g.role_id WHERE g.user_id = :uid AND "
                + TYPE_OK + " AND " + ACTIVE_AT + " AND " + covers("j.client_id", "j.branch_id", "j.direction", "j.mode")
                + " AND " + roleHasPerm(VIEW_PERM) + ")"
                : "";
        List<Long> ids = repo.jdbc.queryForList("SELECT j.id FROM job j WHERE j.owner_id = :uid AND j.stage IN "
                + "('CONFIRMED','PREPARED','CHECKED','REOPENED')" + lacking, p("uid", uid, "utype", u.userType(), "at", now), Long.class);
        for (Long id : ids) {
            Job j = repo.job(id).orElseThrow();
            repo.jdbc.update("UPDATE job SET owner_id = NULL, version = version + 1, updated_at = :now "
                    + "WHERE id = :id AND owner_id = :uid", p("now", now, "id", id, "uid", uid));
            repo.appendEvent(id, "OWNER_RELEASED", actorId, j.stage(), j.stage(), reason,
                    "{\"previousOwner\":" + uid + "}", null, now);
        }
        return ids.size();
    }

    // ------------------------------------------------------------------ user status

    @Transactional
    public Map<String, Object> changeStatus(long actorId, long targetId, String status, String reason) {
        activeActor(actorId);
        if (!STATUSES.contains(status)) throw new ApiException(400, "INVALID_STATUS", "status must be ACTIVE, ABSENT or DISABLED");
        if (actorId == targetId) throw new ApiException(403, "SELF_CHANGE", "You cannot change your own status");
        if (!eval.manageContains(actorId, Permission.ACCESS_MANAGE, null, null, null, null)) {
            throw new ApiException(403, "OUTSIDE_MANAGE_SCOPE", "Changing a person's status needs ACCESS_MANAGE for all scopes");
        }
        repo.user(targetId).orElseThrow(() -> new ApiException(404, "USER_NOT_FOUND", "No such user"));
        LocalDateTime now = Repo.now();
        repo.jdbc.update("UPDATE app_user SET status = :s WHERE id = :id", p("s", status, "id", targetId));
        repo.jdbc.update("INSERT INTO user_status_history (user_id, status, changed_by, reason, changed_at) "
                + "VALUES (:u, :s, :by, :r, :now)", p("u", targetId, "s", status, "by", actorId, "r", reason, "now", now));
        int released = "DISABLED".equals(status) ? releaseOwned(targetId, actorId, "User disabled", false) : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", targetId);
        out.put("status", status);
        out.put("jobsReleasedToPool", released);
        return out;
    }

    // ------------------------------------------------------------------ roles

    private void requireRoleManager(long actorId) {
        activeActor(actorId);
        if (!eval.manageContains(actorId, Permission.ROLE_MANAGE, null, null, null, null)) {
            throw new ApiException(403, "NOT_ALLOWED", "Managing roles needs ROLE_MANAGE for all scopes");
        }
    }

    private List<String> checkedPermissions(List<String> names) {
        if (names == null || names.isEmpty()) throw new ApiException(400, "INVALID_ROLE", "A role needs at least one permission");
        List<String> out = new ArrayList<>();
        for (String n : new LinkedHashSet<>(names)) {
            Permission perm = Permission.parse(n);
            if (perm.sensitive) {
                throw new ApiException(403, "SENSITIVE_PERMISSION_NOT_ALLOWED_VIA_API", perm.name()
                        + " is a sensitive permission. It only exists in the standard sensitive roles and is changed by a reviewed migration");
            }
            out.add(perm.name());
        }
        return out;
    }

    private void addPermissions(long roleId, List<String> perms, long actorId, LocalDateTime now) {
        for (String perm : perms) {
            repo.jdbc.update("INSERT INTO role_permission (role_id, permission, added_at, added_by) VALUES (:r, :p, :now, :a)",
                    p("r", roleId, "p", perm, "now", now, "a", actorId));
        }
    }

    @Transactional
    public Map<String, Object> createRole(long actorId, RoleRequest r) {
        requireRoleManager(actorId);
        if (r == null || blank(r.name())) throw new ApiException(400, "INVALID_ROLE", "A role needs a name");
        if (repo.roleByName(r.name()).isPresent()) throw new ApiException(409, "ROLE_EXISTS", "Role " + r.name() + " already exists");
        List<String> perms = checkedPermissions(r.permissions());
        List<String> types = (r.allowedUserTypes() == null || r.allowedUserTypes().isEmpty()) ? List.of("STAFF") : r.allowedUserTypes();
        if (!USER_TYPES.containsAll(types)) throw new ApiException(400, "INVALID_ROLE", "allowedUserTypes must be STAFF, CLIENT or SYSTEM");
        LocalDateTime now = Repo.now();
        long id = repo.insert("INSERT INTO role (name, description, allowed_user_types, is_sensitive) VALUES (:n, :d, :t, FALSE)",
                p("n", r.name(), "d", r.description(), "t", String.join(",", types)));
        addPermissions(id, perms, actorId, now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roleId", id);
        out.put("name", r.name());
        out.put("permissions", perms);
        out.put("message", "Effective immediately for anyone who is granted this role");
        return out;
    }

    @Transactional
    public Map<String, Object> setRolePermissions(long actorId, long roleId, List<String> permissions) {
        requireRoleManager(actorId);
        Role role = repo.role(roleId).orElseThrow(() -> new ApiException(404, "ROLE_NOT_FOUND", "No such role"));
        if (role.sensitive()) {
            throw new ApiException(403, "SENSITIVE_ROLE_FIXED", "Standard sensitive roles are changed by a reviewed migration, not the API");
        }
        LocalDateTime now = Repo.now();
        Integer holds = repo.jdbc.queryForObject("SELECT COUNT(*) FROM access_grant g WHERE g.user_id = :u AND g.role_id = :r AND "
                + ACTIVE_AT, p("u", actorId, "r", roleId, "at", now), Integer.class);
        if (holds != null && holds > 0) {
            throw new ApiException(403, "CANNOT_EDIT_OWN_ROLE", "You currently hold role " + role.name() + " and cannot change what it can do");
        }
        List<String> wanted = checkedPermissions(permissions);
        List<String> current = repo.permissionsOfRole(roleId, now);
        List<String> removed = new ArrayList<>(current);
        removed.removeAll(wanted);
        List<String> added = new ArrayList<>(wanted);
        added.removeAll(current);
        for (String perm : removed) {
            repo.jdbc.update("UPDATE role_permission SET removed_at = :now, removed_by = :a WHERE role_id = :r AND permission = :p "
                    + "AND removed_at IS NULL", p("now", now, "a", actorId, "r", roleId, "p", perm));
        }
        addPermissions(roleId, added, actorId, now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roleId", roleId);
        out.put("added", added);
        out.put("removed", removed);
        return out;
    }

    public List<Map<String, Object>> listRoles(long callerId) {
        if (!eval.hasAnywhere(callerId, Permission.ACCESS_MANAGE) && !eval.hasAnywhere(callerId, Permission.ROLE_MANAGE)) {
            throw new ApiException(403, "NOT_ALLOWED", "Listing roles needs ACCESS_MANAGE or ROLE_MANAGE");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime now = Repo.now();
        for (Map<String, Object> row : repo.jdbc.queryForList("SELECT id, name, description, allowed_user_types, is_sensitive FROM role ORDER BY id", p())) {
            Map<String, Object> m = new LinkedHashMap<>(row);
            m.put("permissions", repo.permissionsOfRole(((Number) row.get("id")).longValue(), now));
            out.add(m);
        }
        return out;
    }
}
