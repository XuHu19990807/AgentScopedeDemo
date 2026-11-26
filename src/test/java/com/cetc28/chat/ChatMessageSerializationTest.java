package com.cetc28.chat;

import com.cetc28.map.MapCommand;
import com.cetc28.map.MapFeature;
import com.cetc28.map.MapView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldMessageWithoutAttachmentsGetsEmptyList() throws Exception {
        ChatMessage message = mapper.readValue(
                "{\"seq\":1,\"role\":\"user\",\"content\":\"你好\"}", ChatMessage.class);
        assertNotNull(message.getAttachments());
        assertTrue(message.getAttachments().isEmpty());
        assertNotNull(message.getMapCommands());
        assertTrue(message.getMapCommands().isEmpty());
    }

    @Test
    void attachmentsRoundTrip() throws Exception {
        ChatMessage message = ChatMessage.builder()
                .seq(1)
                .role(ChatMessage.ROLE_USER)
                .content("")
                .attachments(List.of(ChatAttachment.builder()
                        .id("f_1").name("a.pdf").size(1024).chars(200).build()))
                .build();
        String json = mapper.writeValueAsString(message);
        ChatMessage parsed = mapper.readValue(json, ChatMessage.class);
        assertEquals(1, parsed.getAttachments().size());
        assertEquals("a.pdf", parsed.getAttachments().get(0).getName());
        assertEquals(200, parsed.getAttachments().get(0).getChars());
    }

    @Test
    void mapCommandsRoundTrip() throws Exception {
        MapCommand command = MapCommand.builder()
                .id("m_1")
                .protocol(MapCommand.PROTOCOL)
                .seq(1)
                .type(MapCommand.TYPE_REPLACE)
                .title("东部城市")
                .view(MapView.builder().fit(true).build())
                .features(List.of(MapFeature.builder()
                        .id("f_1")
                        .kind(MapFeature.KIND_POINT)
                        .name("杭州")
                        .label("杭州")
                        .city("杭州")
                        .province("浙江省")
                        .lon(120.1551)
                        .lat(30.2741)
                        .style("city.primary")
                        .build()))
                .createdAt(1700000000000L)
                .build();
        ChatMessage message = ChatMessage.builder()
                .seq(2)
                .role(ChatMessage.ROLE_ASSISTANT)
                .content("东部新一线城市包括杭州等。")
                .mapCommands(List.of(command))
                .build();

        String json = mapper.writeValueAsString(message);
        ChatMessage parsed = mapper.readValue(json, ChatMessage.class);
        assertEquals(1, parsed.getMapCommands().size());
        MapCommand parsedCommand = parsed.getMapCommands().get(0);
        assertEquals(MapCommand.TYPE_REPLACE, parsedCommand.getType());
        assertEquals("东部城市", parsedCommand.getTitle());
        assertEquals(MapCommand.PROTOCOL, parsedCommand.getProtocol());
        assertEquals("city.primary", parsedCommand.getFeatures().get(0).getStyle());
        assertTrue(parsedCommand.getLegend() == null || parsedCommand.getLegend().isEmpty());
        assertEquals(1, parsedCommand.getFeatures().size());
        assertEquals("杭州", parsedCommand.getFeatures().get(0).getCity());
        assertEquals(120.1551, parsedCommand.getFeatures().get(0).getLon());
        assertEquals(Boolean.TRUE, parsedCommand.getView().getFit());
    }
}
