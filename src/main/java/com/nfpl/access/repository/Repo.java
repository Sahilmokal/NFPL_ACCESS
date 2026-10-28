package com.nfpl.access.repository;

import com.nfpl.access.model.Models.*;
import com.nfpl.access.exception.ApiException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Small data-access helper shared by the services. */
@Component
public class Repo {
    public final NamedParameterJdbcTemplate jdbc;

    public Repo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- helpers ----------
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    /** Named parameters from key,value pairs. Null values are allowed. */
    public static MapSqlParameterSource p(Object... kv) {
        MapSqlParameterSource s = new MapSqlParameterSource();
        for (int i = 0; i < kv.length; i += 2) {
            s.addValue((String) kv[i], kv[i + 1]);
        }
        return s;
    }

    public static Long nl(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDateTime.class);
    }

    public long insert(String sql, MapSqlParameterSource ps) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(sql, ps, kh, new String[]{"id"});
        return kh.getKey().longValue();
    }

    // ---------- row mappers ----------
    public static final RowMapper<User> USER = (rs, i) -> new User(rs.getLong("id"), rs.getString("username"),
            rs.getString("full_name"), rs.getString("user_type"), rs.getString("status"), nl(rs, "client_id"),
            rs.getString("api_key"));

    public static final RowMapper<Role> ROLE = (rs, i) -> new Role(rs.getLong("id"), rs.getString("name"),
            rs.getString("allowed_user_types"), rs.getBoolean("is_sensitive"));

    public static final RowMapper<Job> JOB = (rs, i) -> new Job(rs.getLong("id"), rs.getString("job_no"), rs.getString("title"),
            rs.getLong("client_id"), rs.getLong("branch_id"), rs.getString("direction"), rs.getString("mode"),
            rs.getString("stage"), nl(rs, "owner_id"), nl(rs, "prepared_by"), nl(rs, "checked_by"),
            rs.getInt("version"), ts(rs, "created_at"), ts(rs, "updated_at"));

    public static final RowMapper<Transition> TRANSITION = (rs, i) -> new Transition(rs.getString("action"),
            rs.getString("from_stage"), rs.getString("to_stage"), rs.getString("required_permission"),
            rs.getBoolean("is_queue_step"), rs.getBoolean("requires_reason"), rs.getBoolean("requires_approval"),
            rs.getString("sod_with"));

    // ---------- lookups ----------
    public Optional<User> user(long id) {
        return jdbc.query("SELECT * FROM app_user WHERE id = :id", p("id", id), USER).stream().findFirst();
    }

    public Optional<User> userByUsername(String username) {
        return jdbc.query("SELECT * FROM app_user WHERE username = :username", p("username", username), USER)
                .stream().findFirst();
    }

    public Optional<String> passwordHash(long userId) {
        return jdbc.queryForList("SELECT password_hash FROM app_user WHERE id = :id", p("id", userId), String.class)
                .stream().findFirst();
    }

    public Optional<Job> job(long id) {
        return jdbc.query("SELECT * FROM job WHERE id = :id", p("id", id), JOB).stream().findFirst();
    }

    public Optional<Job> jobByNo(String jobNo) {
        return jdbc.query("SELECT * FROM job WHERE job_no = :n", p("n", jobNo), JOB).stream().findFirst();
    }

    public Optional<Role> role(long id) {
        return jdbc.query("SELECT * FROM role WHERE id = :id", p("id", id), ROLE).stream().findFirst();
    }

    public Optional<Role> roleByName(String name) {
        return jdbc.query("SELECT * FROM role WHERE name = :n", p("n", name), ROLE).stream().findFirst();
    }

    public Optional<Transition> transition(String action, String stage) {
        return jdbc.query("SELECT * FROM workflow_transition WHERE action = :a AND from_stage = :s",
                p("a", action, "s", stage), TRANSITION).stream().findFirst();
    }

    public boolean actionExists(String action) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_transition WHERE action = :a", p("a", action),
                Integer.class);
        return n != null && n > 0;
    }

    public Optional<String> statusAt(long userId, LocalDateTime at) {
        return jdbc.queryForList("SELECT status FROM user_status_history WHERE user_id = :u AND changed_at <= :at "
                + "ORDER BY changed_at DESC, id DESC LIMIT 1", p("u", userId, "at", at), String.class).stream().findFirst();
    }

    public List<String> permissionsOfRole(long roleId, LocalDateTime at) {
        return jdbc.queryForList("SELECT permission FROM role_permission WHERE role_id = :r AND added_at <= :at "
                + "AND (removed_at IS NULL OR removed_at > :at) ORDER BY id", p("r", roleId, "at", at), String.class);
    }

    public boolean clientExists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM client WHERE id = :id", p("id", id), Integer.class);
        return n != null && n > 0;
    }

    public boolean branchExists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM branch WHERE id = :id", p("id", id), Integer.class);
        return n != null && n > 0;
    }

    // ---------- names, for explanations ----------
    public String userName(Long id) {
        if (id == null) return "nobody";
        return jdbc.queryForList("SELECT full_name FROM app_user WHERE id = :id", p("id", id), String.class)
                .stream().findFirst().orElse("user #" + id);
    }

    public String clientCode(Long id) {
        return jdbc.queryForList("SELECT code FROM client WHERE id = :id", p("id", id), String.class)
                .stream().findFirst().orElse("#" + id);
    }

    public String branchCode(Long id) {
        return jdbc.queryForList("SELECT code FROM branch WHERE id = :id", p("id", id), String.class)
                .stream().findFirst().orElse("#" + id);
    }

    /** "client C01, all branches, IMPORT only, all modes" (a NULL scope field means "all"). */
    public String scopeText(Long clientId, Long branchId, String direction, String mode) {
        return (clientId == null ? "all clients" : "client " + clientCode(clientId)) + ", "
                + (branchId == null ? "all branches" : "branch " + branchCode(branchId)) + ", "
                + (direction == null ? "all directions" : direction + " only") + ", "
                + (mode == null ? "all modes" : mode + " only");
    }

    public String jobScopeText(Job j) {
        return "client " + clientCode(j.clientId()) + ", branch " + branchCode(j.branchId()) + ", " + j.direction()
                + ", " + j.mode();
    }

    // ---------- writes used by several services ----------
    public long insertGrant(long userId, long roleId, Long clientId, Long branchId, String direction, String mode,
                            LocalDateTime validFrom, LocalDateTime validTo, Long grantedBy, Long approvedBy, String reason) {
        return insert("INSERT INTO access_grant (user_id, role_id, client_id, branch_id, direction, mode, valid_from, "
                        + "valid_to, granted_by, approved_by, reason) VALUES (:u, :r, :c, :b, :d, :m, :vf, :vt, :gb, :ab, :rs)",
                p("u", userId, "r", roleId, "c", clientId, "b", branchId, "d", direction, "m", mode, "vf", validFrom,
                        "vt", validTo, "gb", grantedBy, "ab", approvedBy, "rs", reason));
    }

    /** Append-only job history. Call inside the transaction that changes the job. */
    public void appendEvent(long jobId, String type, Long actorId, String fromStage, String toStage, String reason,
                            String detailsJson, Long decisionId, LocalDateTime at) {
        Integer seq = jdbc.queryForObject("SELECT COALESCE(MAX(seq), 0) + 1 FROM job_event WHERE job_id = :j",
                p("j", jobId), Integer.class);
        String actorType = actorId == null ? "SYSTEM" : user(actorId).map(User::userType).orElse("STAFF");
        jdbc.update("INSERT INTO job_event (job_id, seq, event_type, actor_id, actor_type, from_stage, to_stage, reason, "
                        + "details, decision_id, created_at) VALUES (:j, :s, :t, :a, :at, :f, :to, :r, CAST(:d AS JSON), :dec, :now)",
                p("j", jobId, "s", seq, "t", type, "a", actorId, "at", actorType, "f", fromStage, "to", toStage,
                        "r", reason, "d", detailsJson, "dec", decisionId, "now", at));
    }
}
