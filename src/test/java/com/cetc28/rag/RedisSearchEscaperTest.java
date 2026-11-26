package com.cetc28.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归保护：TAG 值与全文查询必须用不同的转义规则。
 *
 * <p>故障背景：{@code getFileChunks/searchInFile/deleteFile} 曾把文件名按"文本查询"规则转义后塞进
 * {@code @filename:{...}}，转义集合里没有 {@code -}，于是 {@code test-chat-data.txt} 生成
 * {@code @filename:{test-chat-data\.txt}}，RediSearch 报 {@code Syntax error at offset 30 near data\.txt}，
 * 上层 catch 成空列表 —— 表现为"知识库里明明有这个文件，工具却说没有内容"。
 */
class RedisSearchEscaperTest {

    @Test
    void tagValueEscapesHyphenAndDotInFilename() {
        assertEquals("test\\-chat\\-data\\.txt", RedisSearchEscaper.tagValue("test-chat-data.txt"));
    }

    @Test
    void tagValueEscapesSpacesCommasAndWildcards() {
        assertEquals("a\\ b\\.txt", RedisSearchEscaper.tagValue("a b.txt"));
        assertEquals("x\\,y\\.txt", RedisSearchEscaper.tagValue("x,y.txt"));
        assertEquals("\\*star\\.txt", RedisSearchEscaper.tagValue("*star.txt"));
        // ? / : 等字符不属于 RediSearch 保留字符，不加反斜杠
        assertEquals("q?mark\\.txt", RedisSearchEscaper.tagValue("q?mark.txt"));
    }

    @Test
    void tagValueKeepsCjkAndAsciiLettersUntouched() {
        assertEquals("测试文件\\.txt", RedisSearchEscaper.tagValue("测试文件.txt"));
        assertEquals("report2024", RedisSearchEscaper.tagValue("report2024"));
    }

    @Test
    void tagValueHandlesNullAndEmpty() {
        assertEquals("", RedisSearchEscaper.tagValue(null));
        assertEquals("", RedisSearchEscaper.tagValue(""));
    }

    @Test
    void textQueryKeepsSpacesAsTermSeparators() {
        // 空格是词项分隔符，不能转义；否则 "路由器 预算" 会被当成一个词
        assertEquals("路由器 预算", RedisSearchEscaper.textQuery("路由器 预算"));
    }

    @Test
    void textQueryEscapesReservedChars() {
        assertEquals("NE40E\\-X8", RedisSearchEscaper.textQuery("NE40E-X8"));
        assertEquals("3\\.5\\%", RedisSearchEscaper.textQuery("3.5%"));
    }

    @Test
    void textQueryFallsBackToMatchAllWhenBlank() {
        assertEquals("*", RedisSearchEscaper.textQuery(null));
        assertEquals("*", RedisSearchEscaper.textQuery("   "));
    }

    @Test
    void tagQueryRemainsBalancedForPathologicalFilenames() {
        String[] names = {"test-chat-data.txt", "2024年度 报告(终版).docx", "a,b;c#d%e*f/g.txt"};
        for (String name : names) {
            String query = "@filename:{" + RedisSearchEscaper.tagValue(name) + "}";
            assertTrue(query.startsWith("@filename:{") && query.endsWith("}"), query);
            // 去掉转义符后应还原为原文件名
            assertFalse(query.contains("\\}"), query);
            assertEquals(name, query.substring("@filename:{".length(), query.length() - 1)
                    .replace("\\.", ".").replace("\\-", "-").replace("\\ ", " ")
                    .replace("\\,", ",").replace("\\;", ";").replace("\\#", "#")
                    .replace("\\%", "%").replace("\\*", "*").replace("\\/", "/")
                    .replace("\\(", "(").replace("\\)", ")"));
        }
    }
}
