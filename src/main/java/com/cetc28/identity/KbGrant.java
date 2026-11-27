package com.cetc28.identity;

/** AUTH_KB_GRANT 行。 */
public record KbGrant(String id, String subjectType, String subjectId,
                      String domain, String accessLevel, long createdAt) {
}
