package com.cetc28.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapPromptTest {

    @Test
    void appendsAddendumOnce() {
        String prompt = "你是助手。";
        String once = MapPrompt.ensure(prompt);
        String twice = MapPrompt.ensure(once);

        assertTrue(once.contains(MapPrompt.MARKER));
        assertTrue(once.contains("map_render"));
        assertEquals(once, twice);
    }

    @Test
    void keepsPromptThatAlreadyHasMapSection() {
        String prompt = "你是助手。\n" + MapPrompt.MARKER + "\n已有地图规则";
        assertEquals(prompt, MapPrompt.ensure(prompt));
    }

    @Test
    void handlesBlankPrompt() {
        assertEquals(MapPrompt.ADDENDUM, MapPrompt.ensure(""));
        assertEquals(MapPrompt.ADDENDUM, MapPrompt.ensure(null));
        assertFalse(MapPrompt.ADDENDUM.isBlank());
    }
}
