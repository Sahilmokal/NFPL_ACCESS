package com.nfpl.access.service;

/**
 * The ONE definition of "this grant gives this person this permission on this job at time :at".
 * Every question (may she do this? who could have? what is waiting for me?) is built from these fragments,
 * so the answers cannot drift apart. Aliases: g = access_grant, r = role, rp = role_permission.
 */
final class GrantSql {
    private GrantSql() {}

    /** The grant itself was in force at :at (not revoked, not expired, already started). */
    static final String ACTIVE_AT =
            "g.valid_from <= :at AND (g.valid_to IS NULL OR g.valid_to > :at) AND (g.revoked_at IS NULL OR g.revoked_at > :at)";

    /** The role may be used by this kind of user (STAFF / CLIENT / SYSTEM). Needs :utype. */
    static final String TYPE_OK = "FIND_IN_SET(:utype, r.allowed_user_types) > 0";

    /** Seeing a job: any job permission implies view. */
    static final String VIEW_PERM = "LEFT(rp.permission, 4) = 'JOB_'";

    /** The grant's scope covers the job. NULL in the grant means "all". */
    static String covers(String client, String branch, String direction, String mode) {
        return "(g.client_id IS NULL OR g.client_id = " + client + ")"
                + " AND (g.branch_id IS NULL OR g.branch_id = " + branch + ")"
                + " AND (g.direction IS NULL OR g.direction = " + direction + ")"
                + " AND (g.mode IS NULL OR g.mode = " + mode + ")";
    }

    /** The grant's role contained the permission at :at (permissions are soft-deleted, so the past can be replayed). */
    static String roleHasPerm(String permissionCondition) {
        return "EXISTS (SELECT 1 FROM role_permission rp WHERE rp.role_id = g.role_id AND " + permissionCondition
                + " AND rp.added_at <= :at AND (rp.removed_at IS NULL OR rp.removed_at > :at))";
    }
}
