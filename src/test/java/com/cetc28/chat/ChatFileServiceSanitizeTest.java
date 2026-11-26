package com.cetc28.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileServiceSanitizeTest {

    @Test
    void stripsPathSegmentsFromFilename() {
        assertEquals("evil.txt", ChatFileService.sanitizeFilename("..\\..\\evil.txt"));
        assertEquals("passwd", ChatFileService.sanitizeFilename("../../etc/passwd"));
        assertFalse(ChatFileService.sanitizeFilename("a/b/c.txt").contains("/"));
    }

    @Test
    void replacesIllegalCharacters() {
        String sanitized = ChatFileService.sanitizeFilename("a<b>c:d.txt");
        assertFalse(sanitized.contains("<"));
        assertFalse(sanitized.contains(":"));
    }

    @Test
    void userIdCannotEscapeDirectory() {
        String sanitized = ChatFileService.sanitizeUserId("../../x");
        assertTrue(sanitized.matches("[A-Za-z0-9_-]+"), "userId 只应包含安全字符: " + sanitized);
        assertEquals("default", ChatFileService.sanitizeUserId(null));
        assertEquals("default", ChatFileService.sanitizeUserId("   "));
    }
}
