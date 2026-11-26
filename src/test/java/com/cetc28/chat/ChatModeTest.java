package com.cetc28.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatModeTest {

    @Test
    void defaultsToChat() {
        assertEquals(ChatMode.CHAT, ChatMode.from(null));
        assertEquals(ChatMode.CHAT, ChatMode.from(""));
        assertEquals(ChatMode.CHAT, ChatMode.from("unknown"));
    }

    @Test
    void parsesKnownModes() {
        assertEquals(ChatMode.CHAT, ChatMode.from("chat"));
        assertEquals(ChatMode.DEEP, ChatMode.from("DEEP"));
        assertEquals(ChatMode.ORCHESTRATE, ChatMode.from(" orchestrate "));
    }
}
