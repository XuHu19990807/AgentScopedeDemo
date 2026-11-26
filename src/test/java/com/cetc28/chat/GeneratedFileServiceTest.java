package com.cetc28.chat;

import com.cetc28.export.ExportFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratedFileServiceTest {

    @Mock
    private ChatFileService fileService;
    @Mock
    private ConversationStore store;
    @Mock
    private ChatOutputRegistry registry;

    private final ObjectMapper mapper = new ObjectMapper();
    private GeneratedFileService service;

    @BeforeEach
    void setUp() {
        service = new GeneratedFileService(fileService, store, registry, mapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxChars", 200_000);
    }

    private ChatFile stubSaved(String name) {
        ChatFile file = ChatFile.builder()
                .id("f_1234567890abcdef")
                .userId("u1")
                .conversationId("c1")
                .name(name)
                .size(42)
                .source("generated")
                .build();
        when(fileService.saveGenerated(eq("u1"), eq("c1"), eq(name), any(byte[].class), anyString()))
                .thenReturn(file);
        when(store.get("c1", null)).thenReturn(Optional.of(
                Conversation.builder().id("c1").userId("u1").build()));
        return file;
    }

    @Test
    void saveTextDefaultsToTxtAndRegistersOutput() throws Exception {
        stubSaved("a.txt");
        ChatFile file = service.saveText("c1", "a.txt", "hello");
        assertEquals("a.txt", file.getName());

        ArgumentCaptor<ChatAttachment> captor = ArgumentCaptor.forClass(ChatAttachment.class);
        verify(registry).add(eq("c1"), captor.capture());
        assertEquals("f_1234567890abcdef", captor.getValue().getId());
    }

    @Test
    void saveTextRejectsWordExtension() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveText("c1", "a.docx", "x"));
        assertTrue(error.getMessage().contains("save_word_document"));
    }

    @Test
    void saveXlsxParsesSheetsJson() throws Exception {
        stubSaved("预算.xlsx");
        String json = """
                [{"name":"预算","headers":["项目","金额"],"rows":[["A",100]]}]
                """;
        ChatFile file = service.saveXlsx("c1", "预算", json);
        assertEquals("预算.xlsx", file.getName());
    }

    @Test
    void saveXlsxRejectsInvalidJson() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.saveXlsx("c1", "a.xlsx", "not-json"));
        assertTrue(error.getMessage().contains("JSON"));
    }

    @Test
    void exportAnswerRequiresTableForXlsx() {
        assertThrows(IllegalArgumentException.class,
                () -> service.exportAnswer("c1", "纯文字，没有表格", ExportFormat.XLSX));
    }

    @Test
    void persistFailsWithoutConversation() {
        when(store.get("missing", null)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> service.saveText("missing", "a.txt", "x"));
    }

    @Test
    void rejectsContentOverLimit() {
        ReflectionTestUtils.setField(service, "maxChars", 5);
        assertThrows(IllegalArgumentException.class,
                () -> service.saveText("c1", "a.txt", "0123456789"));
    }
}
