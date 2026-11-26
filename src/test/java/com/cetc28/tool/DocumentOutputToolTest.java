package com.cetc28.tool;

import com.cetc28.chat.ChatFile;
import com.cetc28.chat.GeneratedFileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentOutputToolTest {

    @Mock
    private GeneratedFileService generatedFileService;

    private final ObjectMapper mapper = new ObjectMapper();
    private DocumentOutputTool tool;
    private RuntimeContext context;

    @BeforeEach
    void setUp() {
        tool = new DocumentOutputTool(generatedFileService, mapper);
        context = mock(RuntimeContext.class);
        when(context.getSessionId()).thenReturn("c1");
    }

    @Test
    void returnsDownloadPayloadOnSuccess() throws Exception {
        when(generatedFileService.saveXlsx(eq("c1"), eq("预算"), anyString()))
                .thenReturn(ChatFile.builder().id("f_1").name("预算.xlsx").size(1024).build());
        String json = tool.saveExcelWorkbook("预算", "[]", context);
        JsonNode node = mapper.readTree(json);
        assertEquals("f_1", node.path("fileId").asText());
        assertEquals("预算.xlsx", node.path("fileName").asText());
        assertEquals("/api/chat/files/f_1", node.path("downloadUrl").asText());
    }

    @Test
    void returnsErrorJsonInsteadOfThrowing() throws Exception {
        when(generatedFileService.saveDocx(eq("c1"), anyString(), anyString()))
                .thenThrow(new IllegalStateException("会话不存在，无法保存文件"));
        String json = tool.saveWordDocument("方案.docx", "# 内容", context);
        JsonNode node = mapper.readTree(json);
        assertTrue(node.path("error").asText().contains("会话不存在"));
    }

    @Test
    void textToolUsesSessionAsConversation() throws Exception {
        when(generatedFileService.saveText(eq("c1"), eq("a.txt"), anyString()))
                .thenReturn(ChatFile.builder().id("f_2").name("a.txt").size(3).build());
        String json = tool.saveTextFile("a.txt", "abc", context);
        assertEquals("f_2", mapper.readTree(json).path("fileId").asText());
    }
}
