package com.cetc28.identity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 知识域授权语义：
 * 管理员不受限；普通用户 = 直接授权 ∪ 角色授权；无授权时按配置回退默认域或全拒绝。
 */
class KnowledgeScopeServiceTest {

    private final AuthService authService = mock(AuthService.class);
    private final IdentityProperties properties = new IdentityProperties();
    private final KnowledgeScopeService service = new KnowledgeScopeService(authService, properties);

    private void principal(String userId, Set<String> roles, Set<String> domains) {
        when(authService.principalOrAnonymous(userId))
                .thenReturn(IdentityPrincipal.of(userId, userId, userId, roles, domains, "local", "t"));
    }

    @Test
    void adminIsUnrestricted() {
        principal("u1", Set.of("admin"), Set.of());
        assertNull(service.filterDomains("u1"));
        assertTrue(service.canRead("u1", "任意域"));
    }

    @Test
    void userOnlySeesAssignedDomains() {
        principal("u2", Set.of("user"), Set.of("default", "equipment"));
        assertEquals(Set.of("default", "equipment"), service.filterDomains("u2"));
        assertTrue(service.canRead("u2", "equipment"));
        assertFalse(service.canRead("u2", "finance"));
        assertFalse(service.canRead("u2", null));
    }

    @Test
    void unassignedUserFallsBackToDefaultDomain() {
        properties.getKnowledge().setDefaultReadDomains("default, public");
        principal("u3", Set.of("user"), Set.of());
        assertEquals(Set.of("default", "public"), service.filterDomains("u3"));
        assertFalse(service.scopeFor("u3").denyAll());
    }

    @Test
    void unassignedUserCanBeDeniedEntirely() {
        properties.getKnowledge().setDenyAllWhenUnassigned(true);
        principal("u4", Set.of("user"), Set.of());
        KnowledgeScopeService.Scope scope = service.scopeFor("u4");
        assertTrue(scope.denyAll());
        assertTrue(service.filterDomains("u4").isEmpty());
        assertFalse(service.canRead("u4", "default"));
    }

    @Test
    void invalidateDropsCachedScope() {
        principal("u5", Set.of("user"), Set.of("default"));
        assertEquals(Set.of("default"), service.filterDomains("u5"));
        principal("u5", Set.of("user"), Set.of("finance"));
        service.invalidate("u5");
        assertEquals(Set.of("finance"), service.filterDomains("u5"));
    }

    @Test
    void anonymousUsesAnonymousUserId() {
        when(authService.principalOrAnonymous("anonymous"))
                .thenReturn(IdentityPrincipal.anonymous("anonymous"));
        assertEquals(Set.of("default"), service.filterDomains(null));
    }
}
