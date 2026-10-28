package com.nfpl.access.model;

import java.time.LocalDateTime;
import java.util.List;

/** Plain data carriers. All times are UTC. */
public final class Models {
    private Models() {}

    public record User(long id, String username, String fullName, String userType, String status, Long clientId, String apiKey) {}

    public record LoginRequest(String username, String password) {}

    public record AuthUser(long id, String username, String fullName, String userType, String status, Long clientId) {}

    public record Role(long id, String name, String allowedUserTypes, boolean sensitive) {}

    public record Job(long id, String jobNo, String title, long clientId, long branchId, String direction, String mode,
                      String stage, Long ownerId, Long preparedBy, Long checkedBy, int version,
                      LocalDateTime createdAt, LocalDateTime updatedAt) {
        /** The same job as it looked at another time (used to replay history). */
        /** Client-side users must not see which internal staff handled their job. */
        public Job redactedForClient() {
            return new Job(id, jobNo, title, clientId, branchId, direction, mode, stage, null, null, null, version,
                    createdAt, updatedAt);
        }

        public Job at(String stage, Long preparedBy) {
            return new Job(id, jobNo, title, clientId, branchId, direction, mode, stage, ownerId, preparedBy, checkedBy,
                    version, createdAt, updatedAt);
        }
    }

    public record Transition(String action, String fromStage, String toStage, String permission, boolean queueStep,
                             boolean requiresReason, boolean requiresApproval, String sodWith) {}

    /** One line of the "why" trail. */
    public record Step(String check, boolean passed, String detail) {}

    /** One of the person's grants, judged against one job and one permission. */
    public record GrantRow(long grantId, String role, Long clientId, Long branchId, String direction, String mode,
                           boolean covers, boolean hasPermission, boolean typeOk) {}

    public record Decision(boolean allowed, String code, String message, List<Step> trace, List<Long> grantIds,
                           Transition transition) {}

    public record ActionRequest(String reason, Long approvalId, String title, Long newOwnerId) {}

    public record ActionOutcome(String status, Job job, Long approvalId, String message) {}

    public record JobSpec(String title, Long clientId, Long branchId, String direction, String mode) {}

    public record GrantRequest(Long userId, Long roleId, Long clientId, Long branchId, String direction, String mode,
                               LocalDateTime validTo, String reason) {}

    public record GrantOutcome(String status, Long grantId, Long approvalId, String message) {}

    public record RoleRequest(String name, String description, List<String> allowedUserTypes, List<String> permissions) {}

    public record QueueItem(long id, String jobNo, String title, String stage, long clientId, long branchId,
                            String direction, String mode, Long ownerId, String ownerName, String ownerStatus,
                            String nextAction, LocalDateTime updatedAt) {}

    public record QueuePage(long count, List<QueueItem> items) {}
}
