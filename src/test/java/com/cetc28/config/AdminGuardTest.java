package com.cetc28.config;

import com.cetc28.identity.IdentityContext;
import com.cetc28.identity.IdentityPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminGuardTest {

    private final AdminGuard guard = new AdminGuard();

    private MockHttpServletRequest request(String remoteAddr, IdentityPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/identity/users");
        request.setRemoteAddr(remoteAddr);
        if (principal != null) request.setAttribute(IdentityContext.ATTR, principal);
        return request;
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    @Test
    void adminIdentityPasses() throws Exception {
        IdentityPrincipal admin = IdentityPrincipal.of("usr_1", "admin", "管理员",
                Set.of("admin"), Set.of(), "local", "tk");
        assertTrue(guard.preHandle(request("10.0.0.9", admin), response(), new Object()));
    }

    @Test
    void authenticatedNormalUserIsDeniedEvenOnLoopback() throws Exception {
        IdentityPrincipal user = IdentityPrincipal.of("usr_2", "tester", "测试",
                Set.of("user"), Set.of("default"), "local", "tk");
        MockHttpServletResponse response = response();
        assertFalse(guard.preHandle(request("127.0.0.1", user), response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void anonymousLoopbackPasses() throws Exception {
        IdentityPrincipal anonymous = IdentityPrincipal.anonymous("u_abc");
        assertTrue(guard.preHandle(request("127.0.0.1", anonymous), response(), new Object()));
    }

    @Test
    void anonymousRemoteWithoutTokenIsDenied() throws Exception {
        IdentityPrincipal anonymous = IdentityPrincipal.anonymous("u_abc");
        MockHttpServletResponse response = response();
        assertFalse(guard.preHandle(request("10.0.0.9", anonymous), response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void adminTokenAllowsRemoteAnonymous() throws Exception {
        AdminGuard withToken = new AdminGuard();
        var field = AdminGuard.class.getDeclaredField("adminToken");
        field.setAccessible(true);
        field.set(withToken, "secret-token");
        MockHttpServletRequest request = request("10.0.0.9", IdentityPrincipal.anonymous("u_abc"));
        request.addHeader("X-Admin-Token", "secret-token");
        assertTrue(withToken.preHandle(request, response(), new Object()));
    }
}
