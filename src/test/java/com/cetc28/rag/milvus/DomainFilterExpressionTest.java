package com.cetc28.rag.milvus;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多域过滤表达式：null=不限、空集=必然不命中、非空=in 列表（含转义）。
 */
class DomainFilterExpressionTest {

    @Test
    void nullMeansUnrestricted() {
        assertNull(MilvusBm25Store.domainsFilter(null));
    }

    @Test
    void emptySetNeverMatches() {
        String filter = MilvusBm25Store.domainsFilter(Set.of());
        assertNotNull(filter);
        assertTrue(filter.contains("__no_domain_granted__"));
    }

    @Test
    void multipleDomainsBecomeInList() {
        String filter = MilvusBm25Store.domainsFilter(List.of("default", "装备保障"));
        assertEquals("domain in [\"default\",\"装备保障\"]", filter);
    }

    @Test
    void quotesAndBackslashesAreEscaped() {
        String filter = MilvusBm25Store.domainsFilter(new LinkedHashSet<>(List.of("a\"b", "c\\d")));
        assertNotNull(filter);
        assertTrue(filter.contains("a\\\"b"));
        assertTrue(filter.contains("c\\\\d"));
    }

    @Test
    void blankDomainsAreSkipped() {
        String filter = MilvusBm25Store.domainsFilter(List.of("default", " ", ""));
        assertEquals("domain in [\"default\"]", filter);
    }
}
