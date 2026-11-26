package com.cetc28.rag;

/**
 * RediSearch 查询转义工具。
 *
 * <p>坑点：TAG 精确匹配（{@code @filename:{...}}）与全文检索（{@code @content:...}）的保留字符集合
 * 相同，但空格的含义相反 —— TAG 值里的空格必须转义，全文查询里的空格是"词项分隔符"必须保留。
 * 历史上两者共用一个转义函数，且该函数漏掉了 {@code - , + # % * /}，于是文件名含连字符时
 * （如 {@code test-chat-data.txt}）生成 {@code @filename:{test-chat-data\.txt}}，RediSearch 直接
 * 报 {@code Syntax error ... near data\.txt}；异常被上层 catch 吞成空结果后，表现为
 * "文件查不到内容" 和 "删除失败但元数据已删"，最终留下孤儿块。
 */
public final class RedisSearchEscaper {

    /** RediSearch 查询语法保留字符（官方转义列表；空格与反斜杠单独处理） */
    private static final String RESERVED = "\\,.<>{}[]\"':;!@#$%^&*()-+=~|";

    private RedisSearchEscaper() {
    }

    /**
     * 转义 TAG 值（用于 {@code @field:{value}}）。
     * 空格、制表、换行等空白一并转义，保证带空格的文件名能原样匹配。
     */
    public static String tagValue(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isBlank(c) || RESERVED.indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 转义全文查询文本。空格保留为词项分隔符；空白查询退化为 {@code *}（匹配全部），避免语法错误。
     */
    public static String textQuery(String text) {
        if (text == null || text.isBlank()) return "*";
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && (isBlank(c) || RESERVED.indexOf(c) >= 0)) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isBlank(char c) {
        return c <= 0x20 || c == 0x7F;
    }
}
