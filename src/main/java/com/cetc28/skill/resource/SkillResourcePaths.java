package com.cetc28.skill.resource;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Skill 资源路径与文本解码的公共工具。
 *
 * <p>资源 key 统一使用相对技能根目录、以 {@code /} 分隔的路径；拒绝绝对路径、
 * {@code ..} 和 Windows 盘符，避免目录逃逸。</p>
 */
final class SkillResourcePaths {

    static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    private SkillResourcePaths() {
    }

    /**
     * 归一化资源路径；非法路径返回 {@code null}。
     */
    static String normalize(String raw) {
        if (raw == null) return null;
        String path = raw.trim().replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.isEmpty() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            return null;
        }

        StringBuilder normalized = new StringBuilder(path.length());
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part) || part.indexOf('\0') >= 0) {
                return null;
            }
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(part);
        }
        return normalized.length() == 0 ? null : normalized.toString();
    }

    /** 任一路径段以 . 开头即视为隐藏资源（SKILL.md 本身单独排除）。 */
    static boolean isHidden(String path) {
        for (String part : path.split("/")) {
            if (part.startsWith(".")) return true;
        }
        return false;
    }

    /**
     * 文本资源优先按严格 UTF-8 解码；二进制内容退化为 {@code base64:} + Base64。
     */
    static String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return "base64:" + Base64.getEncoder().encodeToString(bytes);
        }
    }
}
