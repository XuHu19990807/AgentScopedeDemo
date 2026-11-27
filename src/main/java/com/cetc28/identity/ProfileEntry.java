package com.cetc28.identity;

/** USR_PROFILE 行。 */
public record ProfileEntry(String id, String userId, String key, String value,
                          String sourceType, long updatedAt) {

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_MANUAL = "manual";
}
