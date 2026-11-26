package com.cetc28.chat;

import com.cetc28.agentchat.base.AgentRegistry;
import com.cetc28.dto.StreamEvent;
import com.cetc28.export.ExportFormat;
import com.cetc28.memory.UserMemoryService;
import com.cetc28.agentchat.orchestration.OrchestrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 只验证收尾阶段“生成文件收集 + 兜底导出”这段纯逻辑，不启动完整对话链路。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceOutputTest {

    @Mock
    private AgentRegistry agentRegistry;
    @Mock
    private ConversationStore store;
    @Mock
    private RunRegistry runRegistry;
    @Mock
    private SsePublisher publisher;
    @Mock
    private OrchestrationService coordinationService;
    @Mock
    private UserMemoryService memoryService;
    @Mock
    private ChatFileService fileService;
    @Mock
    private FileContextBuilder fileContextBuilder;
    @Mock
    private GeneratedFileService generatedFileService;
    @Mock
    private ChatOutputRegistry outputRegistry;
    @Mock
    private com.cetc28.rag.KnowledgeRetriever knowledgeRetriever;
    @Mock
    private com.cetc28.rag.RagContextBuilder ragContextBuilder;
    @Mock
    private com.cetc28.modelconfig.ModelRuntime modelRuntime;
    @Mock
    private ScheduledExecutorService scheduler;

    private ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(agentRegistry, store, runRegistry, publisher, coordinationService,
                memoryService, fileService, fileContextBuilder, generatedFileService, outputRegistry,
                knowledgeRetriever, ragContextBuilder, new com.cetc28.rag.RagProperties(), modelRuntime, scheduler);
        ReflectionTestUtils.setField(service, "exportFallbackEnabled", true);
    }

    private RunContext run(String userText) {
        RunContext run = new RunContext("c1", "u1", ChatMode.CHAT, null);
        run.setUserText(userText);
        return run;
    }

    @SuppressWarnings("unchecked")
    private List<ChatAttachment> collect(RunContext run, String content) {
        return (List<ChatAttachment>) ReflectionTestUtils.invokeMethod(service, "collectOutputs", run, content);
    }

    @Test
    void returnsFilesCreatedByToolsWithoutFallback() {
        ChatAttachment attachment = ChatAttachment.builder().id("f_1").name("a.xlsx").build();
        when(outputRegistry.drain("c1")).thenReturn(List.of(attachment));

        List<ChatAttachment> outputs = collect(run("保存成 Excel"), "正文");

        assertEquals(1, outputs.size());
        assertEquals("f_1", outputs.get(0).getId());
        verifyNoInteractions(generatedFileService);
    }

    @Test
    void fallsBackToExportWhenModelDidNotSave() throws Exception {
        when(outputRegistry.drain("c1")).thenReturn(List.of());
        when(generatedFileService.exportAnswer(eq("c1"), eq("正文内容"), eq(ExportFormat.DOCX)))
                .thenReturn(ChatFile.builder().id("f_9").name("对话导出-1.docx").build());

        List<ChatAttachment> outputs = collect(run("请把上面的内容保存成 Word 文档"), "正文内容");

        assertEquals(1, outputs.size());
        assertEquals("f_9", outputs.get(0).getId());
    }

    @Test
    void skipsFallbackWithoutExportIntent() throws Exception {
        when(outputRegistry.drain("c1")).thenReturn(List.of());

        List<ChatAttachment> outputs = collect(run("介绍一下这个项目"), "正文内容");

        assertTrue(outputs.isEmpty());
        verify(generatedFileService, never()).exportAnswer(anyString(), anyString(), any());
    }

    @Test
    void ignoresFallbackFailure() throws Exception {
        when(outputRegistry.drain("c1")).thenReturn(List.of());
        when(generatedFileService.exportAnswer(eq("c1"), anyString(), eq(ExportFormat.XLSX)))
                .thenThrow(new IllegalArgumentException("正文中没有可转换为 Excel 的表格"));

        List<ChatAttachment> outputs = collect(run("导出成 Excel"), "只有文字");

        assertTrue(outputs.isEmpty());
    }

    @Test
    void agentEndEndsRunWithoutWaitingForStreamCompletion() {
        RunContext run = new RunContext("c1", "u1", ChatMode.CHAT, mock(SseEmitter.class));
        run.setUserText("hello");

        // 只丢一个 agent_end，不 complete 上游流：模拟“正文已结束，但记忆 flush 还挂着”。
        ReflectionTestUtils.invokeMethod(service, "onEvent", run,
                new StreamEvent("agent_end", java.util.Map.of("replyId", "r1")));

        assertTrue(run.isFinished());
        verify(publisher).send(any(), eq("done"), any());
        verify(publisher).complete(any());
    }
}

