package com.cetc28.identity;

/** AUTH_ROLE 行。 */
public record UserRole(String id,
                       String code,
                       String name,
                       String description,
                       boolean builtIn,
                       long createdAt,
                       long updatedAt) {

    public static final String ADMIN_CODE = "admin";
    public static final String USER_CODE = "user";
}
