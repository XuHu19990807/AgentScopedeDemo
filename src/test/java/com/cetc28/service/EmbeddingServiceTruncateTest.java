package com.cetc28.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 针对 EmbeddingService.truncateToMaxTokens 的截断长度单测。
 * 通过反射调用私有方法，不修改生产代码。
 */
class EmbeddingServiceTruncateTest {

    private EmbeddingService service;
    private Method truncate;

    @BeforeEach
    void setUp() throws Exception {
        service = new EmbeddingService();

        // 注入 maxTokens = 512（与 application.yml 默认值一致）
        Field maxTokensField = EmbeddingService.class.getDeclaredField("maxTokens");
        maxTokensField.setAccessible(true);
        maxTokensField.setInt(service, 512);

        truncate = EmbeddingService.class.getDeclaredMethod("truncateToMaxTokens", String.class, int.class);
        truncate.setAccessible(true);
    }

    private String trunc(String text) throws Exception {
        return (String) truncate.invoke(service, text, 512);
    }

    /** 用与实现一致的权重复算估算 token 数，验证不超过预算 */
    private double estTokens(String s) throws Exception {
        Method est = EmbeddingService.class.getDeclaredMethod("estimateTokenWeight", char.class);
        est.setAccessible(true);
        double tokens = 0;
        for (char c : s.toCharArray()) tokens += (double) est.invoke(service, c);
        return tokens;
    }

    @Test
    void cjk_text_truncates_to_budget_chars() throws Exception {
        String input = "测".repeat(1000); // 纯中文，1 字符 ≈ 1 token
        String out = trunc(input);
        // budget = 512 - 60 = 452 tokens → 452 个中文字符
        assertEquals(452, out.length(), "纯中文截断长度应为 452 字符");
        System.out.println("[CJK]   输入 " + input.length() + " → 输出 " + out.length() + " 字符");
    }

    @Test
    void ascii_letters_truncate_conservatively() throws Exception {
        String input = "a".repeat(5000); // 纯 ASCII 字母，0.5 token/字符
        String out = trunc(input);
        // 452 / 0.5 = 904 字符
        assertEquals(904, out.length(), "纯 ASCII 字母截断长度应为 904 字符");
        System.out.println("[ASCII] 输入 " + input.length() + " → 输出 " + out.length() + " 字符");
    }

    @Test
    void digit_symbol_heavy_truncates_at_1_token_per_char() throws Exception {
        // 用户报错场景：英文字母+小数+特殊字符长文本（bge 分词下数字/符号 1 token/个）
        String input = "sn=AB12.3456,val=78.90!@#;ref=3.14159&x%y^z*WX7.6543+$pq/rs?<>".repeat(50);
        String out = trunc(input);
        // 数字/符号按 1 token：估算必须 ≤ budget=452，且不超 512
        double tokens = estTokens(out);
        assertTrue(tokens <= 452, "数字/符号密集文本估算 token 数不应超过 budget=452，实际 " + tokens);
        assertTrue(tokens <= 512, "估算 token 数不应超过 maxTokens=512，实际 " + tokens);
        System.out.println("[SYMBOL] 输入 " + input.length() + " → 输出 " + out.length() + " 字符，估算 " + tokens + " tokens");
    }

    @Test
    void short_text_is_untouched() throws Exception {
        String input = "短文本 no truncation needed";
        String out = trunc(input);
        assertEquals(input, out, "短文本应原样返回");
        System.out.println("[SHORT] 输入 " + input.length() + " → 输出 " + out.length() + " 字符（未截断）");
    }

    @Test
    void null_and_empty_passthrough() throws Exception {
        assertNull(trunc(null), "null 应原样返回");
        assertEquals("", trunc(""), "空串应原样返回");
    }

    @Test
    void mixed_text_stays_within_budget() throws Exception {
        // 中英混合：交替 1 中文 + 2 英文
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append("测").append("ab");
        String out = trunc(sb.toString());
        double tokens = estTokens(out);
        assertTrue(tokens <= 452, "截断后估算 token 数不应超过 budget=452，实际 " + tokens);
        System.out.println("[MIXED] 输入 " + sb.length() + " → 输出 " + out.length() + " 字符，估算 " + tokens + " tokens");
    }

    @Test
    void emoji_surrogate_pairs_estimated() throws Exception {
        // 边界：emoji 由代理对（2 个 char）组成，按非 ASCII 每个 char 算 1 token
        String input = "😀".repeat(2000);
        String out = trunc(input);
        double tokens = estTokens(out);
        assertTrue(tokens <= 452, "emoji 文本估算 token 数不应超过 budget=452，实际 " + tokens);
        System.out.println("[EMOJI] 输入 " + input.length() + " 字符(代理对) → 输出 " + out.length() + " 字符，估算 " + tokens + " tokens");
    }
}
