package com.cetc28.export;

import java.util.Locale;

/**
 * 支持的导出格式。扩展名是唯一事实来源，文件名/意图识别都从这里派生。
 */
public enum ExportFormat {

    DOCX(".docx"),
    XLSX(".xlsx"),
    TXT(".txt"),
    MD(".md"),
    CSV(".csv"),
    JSON(".json");

    private final String extension;

    ExportFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    /** 依据文件名后缀识别格式；识别不出返回 null。 */
    public static ExportFormat fromFileName(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return null;
        String ext = lower.substring(dot);
        if (".markdown".equals(ext)) return MD;
        for (ExportFormat format : values()) {
            if (format.extension.equals(ext)) return format;
        }
        return null;
    }

    /**
     * 从用户原话中识别"保存/导出为某格式"的意图。
     * 没有导出动作或没有指明格式时返回 null（宁可不导出，也不要生成错格式的文件）。
     */
    public static ExportFormat fromUserIntent(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean intent = lower.matches(".*(保存|导出|生成|另存|存成|写成|输出|整理成|转成|转为).*")
                || lower.matches(".*\\b(save|export|write|convert)\\b.*");
        if (!intent) return null;
        if (lower.matches(".*(excel|xlsx|电子表格|工作簿|表格文件).*")) return XLSX;
        if (lower.matches(".*(word|docx|doc文件).*")) return DOCX;
        if (lower.matches(".*(markdown|\\.md\\b|md文件|md 文件).*")) return MD;
        if (lower.matches(".*\\bcsv\\b.*")) return CSV;
        if (lower.matches(".*\\bjson\\b.*")) return JSON;
        if (lower.matches(".*(txt|文本文件|纯文本|记事本).*")) return TXT;
        return null;
    }

    /** 缺省扩展名的文件名补全（已是目标扩展名则原样返回）。 */
    public static String ensureExtension(String fileName, ExportFormat format) {
        String name = fileName == null ? "" : fileName.trim();
        if (name.isEmpty()) {
            name = "导出文件";
        }
        if (fromFileName(name) == format) return name;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name + format.extension();
    }
}
