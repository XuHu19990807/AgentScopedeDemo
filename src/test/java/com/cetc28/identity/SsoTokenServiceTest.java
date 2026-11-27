package com.cetc28.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 大系统 SSO 令牌：签名、过期、篡改都必须被拒绝。
 */
class SsoTokenServiceTest {

    private IdentityProperties properties;
    private SsoTokenService service;

    @BeforeEach
    void setUp() {
        properties = new IdentityProperties();
        properties.getAuth().getSso().setSharedSecret("unit-test-secret");
        properties.getAuth().getSso().setToleranceSeconds(0);
        service = new SsoTokenService(properties);
    }

    @Test
    void createThenVerifyKeepsClaims() {
        String token = service.create("u1001", "张三", List.of("admin", "analyst"),
                List.of("default", "equipment"), "erp", 120);
        SsoTokenService.SsoClaims claims = service.verify(token);
        assertEquals("u1001", claims.userId());
        assertEquals("张三", claims.displayName());
        assertEquals(List.of("admin", "analyst"), claims.roles());
        assertEquals(List.of("default", "equipment"), claims.domains());
        assertEquals("erp", claims.system());
        assertTrue(claims.expiresAt() > System.currentTimeMillis() / 1000);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = service.create("u1001", "张三", List.of(), List.of(), "erp", 120);
        String tampered = token.substring(0, token.length() - 2) + "xx";
        IdentityException error = assertThrows(IdentityException.class, () -> service.verify(tampered));
        assertEquals(401, error.status().value());
    }

    @Test
    void missingSecretDisablesSso() {
        IdentityProperties empty = new IdentityProperties();
        SsoTokenService disabled = new SsoTokenService(empty);
        assertFalse(disabled.enabled());
        IdentityException error = assertThrows(IdentityException.class,
                () -> disabled.create("u1", null, null, null, null, 60));
        assertEquals(503, error.status().value());
    }

    @Test
    void blankUserIdIsRejected() {
        assertThrows(IdentityException.class,
                () -> service.create(" ", null, null, null, null, 60));
    }
}
