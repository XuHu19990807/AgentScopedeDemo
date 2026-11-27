package com.cetc28.identity;

/** AUTH_USER 行。 */
public record UserAccount(String id,
                          String loginName,
                          String passwordHash,
                          String displayName,
                          String email,
                          String phone,
                          String source,
                          String externalId,
                          boolean enabled,
                          long createdAt,
                          long updatedAt,
                          long lastLoginAt) {

    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_SSO = "sso";

    public String display() {
        return displayName == null || displayName.isBlank() ? loginName : displayName;
    }
}
