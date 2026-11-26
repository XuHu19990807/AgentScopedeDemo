package com.cetc28.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatOutputRegistryTest {

    private static ChatAttachment attachment(String id, String name) {
        return ChatAttachment.builder().id(id).name(name).size(10).build();
    }

    @Test
    void drainsOncePerConversation() {
        ChatOutputRegistry registry = new ChatOutputRegistry();
        registry.add("c1", attachment("f_1", "a.txt"));
        registry.add("c1", attachment("f_2", "b.docx"));
        registry.add("c2", attachment("f_3", "c.xlsx"));

        List<ChatAttachment> drained = registry.drain("c1");
        assertEquals(2, drained.size());
        assertTrue(registry.drain("c1").isEmpty());
        assertEquals(1, registry.drain("c2").size());
    }

    @Test
    void ignoresDuplicateFileId() {
        ChatOutputRegistry registry = new ChatOutputRegistry();
        registry.add("c1", attachment("f_1", "a.txt"));
        registry.add("c1", attachment("f_1", "a.txt"));
        assertEquals(1, registry.drain("c1").size());
    }

    @Test
    void clearDropsPendingFiles() {
        ChatOutputRegistry registry = new ChatOutputRegistry();
        registry.add("c1", attachment("f_1", "a.txt"));
        registry.clear("c1");
        assertTrue(registry.drain("c1").isEmpty());
    }

    @Test
    void ignoresBlankConversation() {
        ChatOutputRegistry registry = new ChatOutputRegistry();
        registry.add("", attachment("f_1", "a.txt"));
        registry.add(null, attachment("f_2", "b.txt"));
        assertTrue(registry.drain("").isEmpty());
        assertTrue(registry.drain(null).isEmpty());
    }
}
