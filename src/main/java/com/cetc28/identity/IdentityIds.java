package com.cetc28.identity;

import java.util.UUID;

/** 身份域 ID 生成：与现有会话 c_/运行 r_ 风格保持一致，全部由应用侧生成。 */
public final class IdentityIds {

    private IdentityIds() {
    }

    public static String user() {
        return of("usr");
    }

    public static String role() {
        return of("rol");
    }

    public static String grant() {
        return of("grt");
    }

    public static String profile() {
        return of("prf");
    }

    public static String audit() {
        return of("log");
    }

    public static String of(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
