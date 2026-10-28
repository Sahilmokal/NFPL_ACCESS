package com.nfpl.access.service;

import com.nfpl.access.exception.ApiException;

/** The permission catalogue. Roles are DATA (they bundle these names); adding a NEW permission needs a release. */
public enum Permission {
    JOB_VIEW(false), JOB_CREATE(false), JOB_EDIT(false), JOB_EDIT_PREPARED(false), JOB_PREPARE(false),
    JOB_CHECK(false), JOB_FILE(false), JOB_CANCEL(false),
    JOB_CANCEL_FILED(true), JOB_REOPEN(true), JOB_REASSIGN(false), JOB_ADMIN_OVERRIDE(true),
    HISTORY_VIEW(false), APPROVE_SENSITIVE(true), ACCESS_MANAGE(true), ROLE_MANAGE(true);

    /** Sensitive permissions: roles holding them need a second person to approve every grant, and they cannot be added to roles through the API. */
    public final boolean sensitive;

    Permission(boolean sensitive) { this.sensitive = sensitive; }

    public static Permission parse(String name) {
        try {
            return valueOf(name);
        } catch (RuntimeException e) {
            throw new ApiException(400, "UNKNOWN_PERMISSION", "Unknown permission: " + name);
        }
    }
}
