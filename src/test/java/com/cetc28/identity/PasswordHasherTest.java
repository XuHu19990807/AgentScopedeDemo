package com.cetc28.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地账号口令哈希：PBKDF2 加盐、常量时间校验、异常输入不崩溃。
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashThenVerifySucceeds() {
        String encoded = hasher.hash("S3cret-pass");
        assertNotNull(encoded);
        assertTrue(encoded.startsWith("pbkdf2$"));
        assertTrue(hasher.verify("S3cret-pass", encoded));
    }

    @Test
    void wrongPasswordFails() {
        String encoded = hasher.hash("S3cret-pass");
        assertFalse(hasher.verify("s3cret-pass", encoded));
        assertFalse(hasher.verify("", encoded));
        assertFalse(hasher.verify(null, encoded));
    }

    @Test
    void samePasswordProducesDifferentHashes() {
        String first = hasher.hash("same-password");
        String second = hasher.hash("same-password");
        assertNotEquals(first, second, "盐必须随机，避免彩虹表");
    }

    @Test
    void malformedHashIsRejectedInsteadOfThrowing() {
        assertFalse(hasher.verify("x", "not-a-hash"));
        assertFalse(hasher.verify("x", "pbkdf2$abc$def$ghi"));
        assertFalse(hasher.verify("x", null));
        assertFalse(hasher.verify("x", ""));
    }
}
