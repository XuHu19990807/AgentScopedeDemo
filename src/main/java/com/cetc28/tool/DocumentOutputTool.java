package com.cetc28.tool;

import com.cetc28.chat.ChatFile;
import com.cetc28.chat.GeneratedFileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把对话内容保存为可下载文件（TXT/Markdown/CSV/JSON/Word/Excel）。
 *
 * 会话上下文通过 {@link RuntimeContext} 注入（无 {@code @ToolParam} 的参数不会进入
 * 给模型的 schema）：sessionId 即 conversationId，服务层据此反查用户归属。
 * 任何异常都转成 {"error": "..."} 文本返回，避免中断整轮生成。
 */
@Component
public class DocumentOutputTool {

    private static final Logger log = LoggerFactory.getLogger(DocumentOutputTool.class);

    private final GeneratedFileService generatedFileService;
    private final ObjectMapper mapper;

    public DocumentOutputTool(GeneratedFileService generatedFileService, ObjectMapper mapper) {
        this.generatedFileService = generatedFileService;
        this.mapper = mapper;
    }

    @Tool(name = "save_text_file",
            description = "把文本内容保存为可下载文件（.txt/.md/.csv/.json）。"
                    + "用户要求“保存成文本/导出 md/存个 json”时使用；content 必须是要写入文件的完整内容。")
    public String saveTextFile(
            @ToolParam(name = "fileName", required = true,
                    description = "文件名，需带扩展名，例如 report.md、data.csv、notes.txt") String fileName,
            @ToolParam(name = "content", required = true,
                    description = "要写入文件的完整文本内容（不要省略）") String content,
            RuntimeContext context) {
        try {
            ChatFile file = generatedFileService.saveText(conversationId(context), fileName, content);
            return ok(file);
        } catch (Exception e) {
            return error(e);
        }
    }

    @Tool(name = "save_word_document",
            description = "把 Markdown/文本内容导出为 Word 文档（.docx）。"
                    + "支持标题、列表、表格、代码块；用户要求“保存成 Word/生成文档”时使用。")
    public String saveWordDocument(
            @ToolParam(name = "fileName", required = true,
                    description = "文件名，例如 项目方案.docx（可省略扩展名）") String fileName,
            @ToolParam(name = "markdown", required = true,
                    description = "Markdown 格式的正文内容，表格请用 | a | b | 形式") String markdown,
            RuntimeContext context) {
        try {
            ChatFile file = generatedFileService.saveDocx(conversationId(context), fileName, markdown);
            return ok(file);
        } catch (Exception e) {
            return error(e);
        }
    }

    @Tool(name = "save_excel_workbook",
            description = "把结构化数据导出为 Excel（.xlsx）。sheetsJson 是 JSON："
                    + "[{\"name\":\"Sheet1\",\"headers\":[\"项目\",\"金额\"],\"rows\":[[\"A\",100],[\"B\",200]]}]；"
                    + "多个 sheet 用数组元素表示。用户要求“保存成 Excel/导出表格”时使用。")
    public String saveExcelWorkbook(
            @ToolParam(name = "fileName", required = true,
                    description = "文件名，例如 预算表.xlsx（可省略扩展名）") String fileName,
            @ToolParam(name = "sheetsJson", required = true,
                    description = "表格数据 JSON，含 name/headers/rows；rows 为二维数组") String sheetsJson,
            RuntimeContext context) {
        try {
            ChatFile file = generatedFileService.saveXlsx(conversationId(context), fileName, sheetsJson);
            return ok(file);
        } catch (Exception e) {
            return error(e);
        }
    }

    private String conversationId(RuntimeContext context) {
        return context == null ? null : context.getSessionId();
    }

    private String ok(ChatFile file) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fileId", file.getId());
        payload.put("fileName", file.getName());
        payload.put("size", file.getSize());
        payload.put("downloadUrl", "/api/chat/files/" + file.getId());
        return write(payload);
    }

    private String error(Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
        log.warn("生成文件失败: {}", message);
        return write(Map.of("error", message));
    }

    private String write(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }
}
