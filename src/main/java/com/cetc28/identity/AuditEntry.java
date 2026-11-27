package com.cetc28.identity;

/** AUTH_AUDIT 行。 */
public record AuditEntry(String id, String userId, String action, String targetId,
                         String detail, String clientIp, long createdAt) {
}
