package com.cetc28.chat;

import com.cetc28.export.ExcelWorkbookWriter;
import com.cetc28.export.ExportFormat;
import com.cetc28.export.MarkdownDocWriter;
import com.cetc28.export.TextFileWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 生成文件的门面：把文本/表格/Markdown 渲染成 docx/xlsx/txt 等并登记为对话附件。
 *
 * 所有入口都要求 conversationId：工具从 RuntimeContext 拿会话 ID，
 * 这里再反查会话归属的 userId，落盘与下载校验都走 {@link ChatFileService}。
 */
@Service
public class GeneratedFileService {

    private static final Logger log = LoggerFactory.getLogger(GeneratedFileService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_SHEET_ROWS = 5000;
    private static final java.util.Set<String> TEXT_EXTENSIONS = java.util.Set.of(
            ".txt", ".md", ".markdown", ".csv", ".json", ".log", ".xml", ".yaml", ".yml");

    private final ChatFileService fileService;
    private final ConversationStore store;
    private final ChatOutputRegistry registry;
    private final ObjectMapper mapper;

    @Value("${chat.files.export-enabled:true}")
    private boolean enabled = true;

    @Value("${chat.files.export-max-chars:200000}")
    private int maxChars = 200_000;

    public GeneratedFileService(ChatFileService fileService,
                                ConversationStore store,
                                ChatOutputRegistry registry,
                                ObjectMapper mapper) {
        this.fileService = fileService;
        this.store = store;
        this.registry = registry;
        this.mapper = mapper;
    }

    /** 文本类文件：按文件名后缀决定 txt/md/csv/json。 */
    public ChatFile saveText(String conversationId, String fileName, String content) throws Exception {
        String ext = extensionOf(fileName);
        if (!ext.isEmpty() && !TEXT_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("save_text_file 不支持 " + ext
                    + "；Word 请用 save_word_document，Excel 请用 save_excel_workbook");
        }
        ExportFormat format = ext.isEmpty() ? ExportFormat.TXT : ExportFormat.fromFileName(fileName);
        if (format == null) format = ExportFormat.TXT;
        checkLength(content);
        String name = ExportFormat.ensureExtension(fileName, format);
        return persist(conversationId, name, TextFileWriter.write(content, format), content);
    }

    /** Markdown/纯文本 → Word。 */
    public ChatFile saveDocx(String conversationId, String fileName, String markdown) throws Exception {
        checkLength(markdown);
        String name = ExportFormat.ensureExtension(fileName, ExportFormat.DOCX);
        return persist(conversationId, name, MarkdownDocWriter.toDocx(markdown), markdown);
    }

    /** 多 sheet 数据 → Excel；sheetsJson 格式见工具说明。 */
    public ChatFile saveXlsx(String conversationId, String fileName, String sheetsJson) throws Exception {
        List<ExcelWorkbookWriter.SheetSpec> sheets = parseSheets(sheetsJson);
        String name = ExportFormat.ensureExtension(fileName, ExportFormat.XLSX);
        String preview = sheets.stream()
                .map(sheet -> sheet.name() + "（" + sheet.rows().size() + " 行）")
                .collect(Collectors.joining("，"));
        return persist(conversationId, name, ExcelWorkbookWriter.toXlsx(sheets), preview);
    }

    /**
     * 兜底导出：模型没调工具、但用户明确要求"保存/导出为文件"时，把最终答复转成文件。
     * 仅支持 txt/md/docx/csv/xlsx（csv/xlsx 要求正文里有 Markdown 表格）。
     */
    public ChatFile exportAnswer(String conversationId, String content, ExportFormat format) throws Exception {
        checkLength(content);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("没有可导出的正文内容");
        }
        String base = "对话导出-" + LocalDateTime.now().format(STAMP);
        return switch (format) {
            case DOCX -> persist(conversationId, base + ".docx",
                    MarkdownDocWriter.toDocx(content), content);
            case MD -> persist(conversationId, base + ".md",
                    TextFileWriter.write(content, ExportFormat.MD), content);
            case TXT -> persist(conversationId, base + ".txt",
                    TextFileWriter.write(content, ExportFormat.TXT), content);
            case CSV -> {
                String csv = TextFileWriter.markdownTableToCsv(content);
                if (csv.isBlank()) {
                    throw new IllegalArgumentException("正文中没有可转换为 CSV 的表格");
                }
                yield persist(conversationId, base + ".csv",
                        TextFileWriter.write(csv, ExportFormat.CSV), csv);
            }
            case XLSX -> {
                ExcelWorkbookWriter.SheetSpec spec = ExcelWorkbookWriter.fromMarkdownTable(content, "Sheet1");
                if (spec == null) {
                    throw new IllegalArgumentException("正文中没有可转换为 Excel 的表格");
                }
                yield persist(conversationId, base + ".xlsx",
                        ExcelWorkbookWriter.toXlsx(List.of(spec)), content);
            }
            default -> throw new IllegalArgumentException("不支持导出为 " + format.extension());
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ========== 内部 ==========

    private ChatFile persist(String conversationId, String fileName, byte[] bytes, String preview) {
        if (!enabled) {
            throw new IllegalStateException("文件导出未启用");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalStateException("缺少会话上下文，无法保存文件");
        }
        Conversation conversation = store.get(conversationId, null)
                .orElseThrow(() -> new IllegalStateException("会话不存在，无法保存文件"));
        ChatFile file = fileService.saveGenerated(conversation.getUserId(), conversationId,
                fileName, bytes, preview);
        registry.add(conversationId, ChatAttachment.of(file));
        log.info("生成文件完成: cid={}, file={}, {} bytes", conversationId, file.getName(), file.getSize());
        return file;
    }

    private void checkLength(String content) {
        if (content != null && content.length() > maxChars) {
            throw new IllegalArgumentException("内容过长（" + content.length() + " 字符，上限 " + maxChars
                    + "），请精简或分段保存");
        }
    }

    private List<ExcelWorkbookWriter.SheetSpec> parseSheets(String sheetsJson) throws Exception {
        if (sheetsJson == null || sheetsJson.isBlank()) {
            throw new IllegalArgumentException("sheetsJson 不能为空");
        }
        JsonNode root;
        try {
            root = mapper.readTree(sheetsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("sheetsJson 不是合法 JSON：" + e.getMessage());
        }
        JsonNode array = root.isArray() ? root : mapper.createArrayNode().add(root);
        List<ExcelWorkbookWriter.SheetSpec> sheets = new ArrayList<>();
        int totalRows = 0;
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            if (!node.isObject()) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 个 sheet 不是对象");
            }
            String name = node.path("name").asText("Sheet" + (i + 1));
            List<String> headers = textList(node.path("headers"));
            List<List<String>> rows = new ArrayList<>();
            JsonNode rowNodes = node.path("rows");
            if (rowNodes.isArray()) {
                for (JsonNode rowNode : rowNodes) {
                    if (!rowNode.isArray()) {
                        throw new IllegalArgumentException("sheet '" + name + "' 的 rows 必须是二维数组");
                    }
                    List<String> values = new ArrayList<>();
                    for (JsonNode cell : rowNode) {
                        values.add(cellText(cell));
                    }
                    rows.add(values);
                    totalRows++;
                }
            }
            if (headers.isEmpty() && rows.isEmpty()) {
                throw new IllegalArgumentException("sheet '" + name + "' 既没有 headers 也没有 rows");
            }
            sheets.add(new ExcelWorkbookWriter.SheetSpec(name, headers, rows));
        }
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("sheetsJson 至少需要一个 sheet");
        }
        if (totalRows > MAX_SHEET_ROWS) {
            throw new IllegalArgumentException("表格行数过多（" + totalRows + " 行，上限 " + MAX_SHEET_ROWS + "）");
        }
        return sheets;
    }

    private List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                values.add(cellText(item));
            }
        }
        return values;
    }

    private String cellText(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
        if (node.isBoolean()) return String.valueOf(node.asBoolean());
        return node.toString();
    }

    private String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot).toLowerCase();
    }
}
