package com.cetc28.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TikaReader;
import org.apache.tika.sax.BodyContentHandler;

/**
 * 统一文档文本提取器。
 *
 * <p>RAG 的召回上限由解析质量决定，因此这里不只是"能读出字"，而是尽量保留结构：
 * Word 标题转 Markdown 标题（切块时据此生成 headingPath）、表格转 Markdown 表格（行内
 * 语义完整，单行即可回答表格事实题）、Excel 每个 Sheet 转 Markdown、PPT 按页取标题与正文。
 */
@Component
public class DocumentTextExtractor {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentTextExtractor.class);

    /** 纯文本类（含代码/配置/Markdown/CSV/JSON 等），按文本读取。 */
    private static final Set<String> TEXT_EXTENSIONS = new LinkedHashSet<>(List.of(
            ".txt", ".md", ".markdown", ".csv", ".tsv", ".json", ".jsonl", ".log",
            ".yaml", ".yml", ".xml", ".html", ".htm", ".sql", ".properties", ".ini",
            ".conf", ".sh", ".bat", ".ps1", ".java", ".py", ".js", ".ts", ".css"));

    /** Office/PDF 类，需要专用解析器。 */
    private static final Set<String> OFFICE_EXTENSIONS = new LinkedHashSet<>(List.of(
            ".pdf", ".docx", ".doc", ".xlsx", ".xls", ".pptx", ".ppt"));

    /**
     * 复用 AgentScope TikaReader 兜底的格式（暂未单独实现解析器的办公/邮件格式）。
     *
     * <p>Tika 3.3.0 随 rag-simple 传递进 classpath，这里只取纯文本，不保留结构；
     * docx/xlsx/pptx/pdf 等仍走本地调优解析器（保留标题与表格结构）。
     */
    private static final Set<String> TIKA_EXTENSIONS = new LinkedHashSet<>(List.of(
            ".rtf", ".odt", ".ods", ".odp", ".epub", ".eml", ".msg", ".wps", ".et", ".dps"));

    private static final Set<String> ALL_EXTENSIONS;

    static {
        LinkedHashSet<String> all = new LinkedHashSet<>(TEXT_EXTENSIONS);
        all.addAll(OFFICE_EXTENSIONS);
        all.addAll(TIKA_EXTENSIONS);
        ALL_EXTENSIONS = Set.copyOf(all);
    }

    @Value("${chat.files.max-extract-chars:2000000}")
    private int maxExtractChars = 2_000_000;

    @Value("${chat.files.max-pdf-pages:300}")
    private int maxPdfPages = 300;

    @Value("${chat.files.max-excel-rows:5000}")
    private int maxExcelRows = 5000;

    /** 单个单元格（Excel/Word 表格）最长字符数，防止一格撑爆 Markdown 行 */
    @Value("${chat.files.max-excel-cell-chars:1000}")
    private int maxCellChars = 1000;

    /** 现有解析器失败时是否回退 TikaReader 取纯文本 */
    @Value("${chat.files.tika-fallback:true}")
    private boolean tikaFallback = true;

    /** 取小写扩展名（含点）；无扩展名返回空串。 */
    public static String extensionOf(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) return "";
        return filename.substring(idx).toLowerCase();
    }

    public boolean supports(String ext) {
        return ext != null && ALL_EXTENSIONS.contains(ext.toLowerCase());
    }

    public Set<String> supportedExtensions() {
        return ALL_EXTENSIONS;
    }

    public String unsupportedMessage() {
        return "暂不支持该文件格式。支持：.pdf .docx .doc .xlsx .xls .pptx .ppt .txt .md .markdown "
                + ".csv .tsv .json .jsonl .log .yaml .yml .xml .html .htm .sql .properties .ini .conf "
                + ".sh .bat .ps1 .java .py .js .ts .css .rtf .odt .ods .odp .epub .eml .msg .wps .et .dps。";
    }

    /**
     * 从磁盘文件提取文本。
     *
     * <p>已知格式走专用解析器；解析失败且开启 tika-fallback 时，用 AgentScope 的
     * {@link TikaReader} 取纯文本兜底（至少保证可检索）。
     */
    public String extract(Path path, String ext) throws Exception {
        String lower = ext == null ? "" : ext.toLowerCase();
        if (!supports(lower)) {
            throw new UnsupportedOperationException(unsupportedMessage());
        }
        if (TIKA_EXTENSIONS.contains(lower)) {
            return tikaOrThrow(path, lower);
        }
        try {
            return extractLocal(path, lower);
        } catch (Exception e) {
            return fallbackTika(path, lower, e);
        }
    }

    private String extractLocal(Path path, String lower) throws Exception {
        if (".pdf".equals(lower)) {
            try (PDDocument document = Loader.loadPDF(path.toFile())) {
                return extractPdf(document);
            }
        }
        return extractBytes(Files.readAllBytes(path), lower);
    }

    /**
     * 从上传文件提取文本（知识库上传使用）。
     */
    public String extract(MultipartFile file, String ext) throws Exception {
        String lower = ext == null ? "" : ext.toLowerCase();
        if (!supports(lower)) {
            throw new UnsupportedOperationException(unsupportedMessage());
        }
        byte[] bytes = file.getBytes();
        if (TIKA_EXTENSIONS.contains(lower)) {
            return tikaWithTempFile(bytes, lower);
        }
        try {
            if (".pdf".equals(lower)) {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    return extractPdf(document);
                }
            }
            return extractBytes(bytes, lower);
        } catch (Exception e) {
            return fallbackTikaWithBytes(bytes, lower, e);
        }
    }

    // ========== Tika 兜底（复用 AgentScope TikaReader） ==========

    private String tikaOrThrow(Path path, String ext) throws Exception {
        String text = tikaText(path);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Tika 无法解析该 " + ext + " 文件");
        }
        return text;
    }

    private String fallbackTika(Path path, String ext, Exception cause) throws Exception {
        if (!tikaFallback) throw cause;
        log.warn("专用解析器解析 {} 失败，回退 TikaReader：{}", ext, cause.getMessage());
        String text = tikaText(path);
        if (text == null || text.isBlank()) throw cause;
        return text;
    }

    private String fallbackTikaWithBytes(byte[] bytes, String ext, Exception cause) throws Exception {
        if (!tikaFallback) throw cause;
        log.warn("专用解析器解析 {} 失败，回退 TikaReader：{}", ext, cause.getMessage());
        String text = tikaWithTempFile(bytes, ext);
        if (text == null || text.isBlank()) throw cause;
        return text;
    }

    private String tikaWithTempFile(byte[] bytes, String ext) throws Exception {
        Path temp = Files.createTempFile("extract-", ext.isEmpty() ? ".bin" : ext);
        try {
            Files.write(temp, bytes);
            return tikaText(temp);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // 临时文件清理失败不影响解析结果
            }
        }
    }

    /**
     * 用框架的 TikaReader 取纯文本。
     *
     * <p>每次新建 reader + 每次新建 {@link BodyContentHandler}（perReadHandler），
     * 避免框架提示的"复用 content handler 会串文本"问题；chunkSize 取上限以免 Tika 端再切分。
     */
    private String tikaText(Path path) {
        try {
            TikaReader reader = TikaReader.perReadHandler(1_000_000, SplitStrategy.PARAGRAPH, 0,
                    () -> new BodyContentHandler(-1));
            List<Document> documents = reader.read(ReaderInput.fromPath(path))
                    .block(Duration.ofSeconds(60));
            if (documents == null || documents.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (Document document : documents) {
                if (document == null || document.getMetadata() == null) continue;
                String text = document.getMetadata().getContentText();
                if (text == null || text.isBlank()) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(text.trim());
            }
            return cap(sb.toString());
        } catch (Exception e) {
            log.warn("Tika 兜底解析失败：{}", e.getMessage());
            return "";
        }
    }

    private String extractBytes(byte[] bytes, String ext) throws Exception {
        return switch (ext) {
            case ".pdf" -> {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    yield extractPdf(document);
                }
            }
            case ".docx" -> extractDocx(bytes);
            case ".doc" -> extractDoc(bytes);
            case ".xlsx" -> {
                try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    yield extractWorkbook(workbook);
                }
            }
            case ".xls" -> {
                try (HSSFWorkbook workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes))) {
                    yield extractWorkbook(workbook);
                }
            }
            case ".pptx" -> extractPptx(bytes);
            case ".ppt" -> extractPpt(bytes);
            case ".xml" -> stripMarkup(decode(bytes));
            case ".html", ".htm" -> stripMarkup(decode(bytes));
            default -> cap(decode(bytes));
        };
    }

    // ========== 文本编码 ==========

    /**
     * 解码文本：BOM 优先；否则严格 UTF-8，失败回退 GB18030（兼容 GBK/GB2312）。
     */
    String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    // ========== PDF ==========

    private String extractPdf(PDDocument document) throws Exception {
        int total = document.getNumberOfPages();
        int pages = Math.min(total, Math.max(1, maxPdfPages));
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setLineSeparator("\n");
        stripper.setParagraphStart("");
        StringBuilder sb = new StringBuilder();
        String previousFooter = null;
        for (int page = 1; page <= pages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = cleanPdfPage(stripper.getText(document), previousFooter);
            sb.append("=== 第 ").append(page).append(" 页 ===\n")
                    .append(pageText == null ? "" : pageText.trim()).append("\n\n");
            previousFooter = lastLine(pageText);
            if (sb.length() >= maxExtractChars) break;
        }
        if (total > pages) {
            sb.append("…（仅解析前 ").append(pages).append(" 页，共 ").append(total).append(" 页）…\n");
        }
        return cap(sb.toString());
    }

    /**
     * PDF 去噪：合并被排版拆断的英文连字符、去掉连续空行；
     * 若上一页末行在本页首行重复（页眉页脚常见），把本页首行删掉。
     */
    private String cleanPdfPage(String raw, String previousFooter) {
        if (raw == null) return "";
        String text = raw.replaceAll("(?m)^\\s*$\\n?", "\n")
                .replace("-\n", "-")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        if (previousFooter != null && !previousFooter.isBlank()) {
            int idx = text.indexOf('\n');
            String first = idx < 0 ? text : text.substring(0, idx);
            if (first.trim().equals(previousFooter.trim())) {
                text = idx < 0 ? "" : text.substring(idx + 1);
            }
        }
        return text;
    }

    private static String lastLine(String text) {
        if (text == null) return null;
        String[] lines = text.trim().split("\n");
        return lines.length == 0 ? null : lines[lines.length - 1].trim();
    }

    // ========== Word ==========

    /**
     * DOCX：段落按样式转 Markdown 标题，表格转 Markdown 表格，保持正文顺序。
     */
    private String extractDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new ByteArrayInputStream(bytes);
             XWPFDocument document = new XWPFDocument(in)) {
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendDocxParagraph(sb, paragraph);
                } else if (element instanceof XWPFTable table) {
                    appendMarkdownTable(sb, docxTableRows(table));
                }
                if (sb.length() >= maxExtractChars) break;
            }
        }
        return cap(sb.toString());
    }

    private void appendDocxParagraph(StringBuilder sb, XWPFParagraph paragraph) {
        String text = paragraph.getText();
        if (text == null || text.isBlank()) return;
        int level = headingLevel(paragraph.getStyle());
        sb.append("#".repeat(Math.max(0, Math.min(6, level))))
                .append(level > 0 ? " " : "")
                .append(text.trim())
                // 段落之间空一行：切块时一段即一个语义块，段落不会被拼成一大块后被切开
                .append("\n\n");
    }

    /** 样式名形如 Heading1 / 标题 1 / 标题1 → 级别；正文返回 0。 */
    static int headingLevel(String style) {
        if (style == null || style.isBlank()) return 0;
        String normalized = style.toLowerCase().replace(" ", "");
        if (normalized.startsWith("heading")) {
            String digits = normalized.replaceAll("\\D+", "");
            try {
                return digits.isEmpty() ? 1 : Math.min(6, Integer.parseInt(digits));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        if (normalized.startsWith("标题") || normalized.startsWith("titre")) return 1;
        return 0;
    }

    private List<List<String>> docxTableRows(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText() == null ? "" : cell.getText().replace("\n", " ").trim());
            }
            rows.add(cells);
        }
        return rows;
    }

    /** 老版 .doc：HWPF 只能取纯文本，结构信息弱，但至少可检索。 */
    private String extractDoc(byte[] bytes) throws Exception {
        try (InputStream in = new ByteArrayInputStream(bytes);
             HWPFDocument document = new HWPFDocument(in);
             WordExtractor extractor = new WordExtractor(document)) {
            return cap(extractor.getText());
        }
    }

    // ========== PowerPoint ==========

    private String extractPptx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new ByteArrayInputStream(bytes);
             XMLSlideShow slideShow = new XMLSlideShow(in)) {
            for (XSLFSlide slide : slideShow.getSlides()) {
                sb.append("## 第 ").append(slide.getSlideNumber()).append(" 页\n\n");
                String title = slide.getTitle() == null ? "" : slide.getTitle();
                if (title != null && !title.isBlank()) sb.append("### ").append(title.trim()).append("\n\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text == null || text.isBlank() || text.equals(title)) continue;
                        sb.append(text.trim()).append("\n\n");
                    }
                }
                if (sb.length() >= maxExtractChars) break;
            }
        }
        return cap(sb.toString());
    }

    /** 老版 .ppt：POI 的 SlideShowExtractor 泛型跨版本不稳定，直接遍历文本框更可控。 */
    private String extractPpt(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new ByteArrayInputStream(bytes);
             HSLFSlideShow slideShow = new HSLFSlideShow(in)) {
            for (HSLFSlide slide : slideShow.getSlides()) {
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.isBlank()) sb.append(text.trim()).append('\n');
                    }
                }
                sb.append('\n');
                if (sb.length() >= maxExtractChars) break;
            }
            return cap(sb.toString());
        } catch (Exception e) {
            return cap("");
        }
    }

    // ========== Excel ==========

    /**
     * Excel：每个 Sheet 转 Markdown 表格（首行当表头），行内信息完整，便于表格事实题。
     */
    private String extractWorkbook(Workbook workbook) {
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            sb.append("## Sheet: ").append(sheet.getSheetName()).append("\n\n");
            List<List<String>> rows = new ArrayList<>();
            int rowCount = 0;
            for (Row row : sheet) {
                if (rowCount >= maxExcelRows) {
                    sb.append("…（本 Sheet 仅显示前 ").append(maxExcelRows).append(" 行）…\n");
                    break;
                }
                List<String> cells = new ArrayList<>();
                for (Cell cell : row) {
                    cells.add(cellValue(formatter, evaluator, cell));
                }
                if (cells.stream().allMatch(String::isBlank)) continue;
                rows.add(cells);
                rowCount++;
                if (sb.length() + rowCount * 40 >= maxExtractChars) break;
            }
            appendMarkdownTable(sb, rows);
            sb.append('\n');
            if (sb.length() >= maxExtractChars) return cap(sb.toString());
        }
        return cap(sb.toString());
    }

    /** 前两行作为表头，其余为数据行；列数按最宽行对齐。 */
    private void appendMarkdownTable(StringBuilder sb, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return;
        int columns = 0;
        for (List<String> row : rows) columns = Math.max(columns, row.size());
        if (columns == 0) return;
        List<String> header = rows.get(0);
        sb.append("| ");
        for (int c = 0; c < columns; c++) {
            sb.append(cell(header, c)).append(" | ");
        }
        sb.append('\n').append("|");
        for (int c = 0; c < columns; c++) sb.append(" --- |");
        sb.append('\n');
        for (int r = 1; r < rows.size(); r++) {
            sb.append("| ");
            for (int c = 0; c < columns; c++) {
                sb.append(cell(rows.get(r), c)).append(" | ");
            }
            sb.append('\n');
        }
    }

    private String cell(List<String> row, int index) {
        if (index >= row.size()) return "";
        String value = row.get(index);
        if (value == null) return "";
        String escaped = value.replace("|", "\\|").replace("\n", " ").trim();
        // 单格过长会把整张表撑成超长行，切块时被反复切开；这里显式截断并标注
        if (maxCellChars > 0 && escaped.length() > maxCellChars) {
            escaped = escaped.substring(0, maxCellChars) + "…";
        }
        return escaped;
    }

    private String cellValue(DataFormatter formatter, FormulaEvaluator evaluator, Cell cell) {
        try {
            if (cell.getCellType() == CellType.FORMULA) {
                return formatter.formatCellValue(cell, evaluator);
            }
        } catch (Exception ignored) {
            // 公式无法计算时回退原文本
        }
        return formatter.formatCellValue(cell);
    }

    // ========== 标记语言 ==========

    private String stripMarkup(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = raw
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("<[^>]+>", " ");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return cap(text);
    }

    // ========== 上限 ==========

    private String cap(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() <= maxExtractChars) return text;
        return text.substring(0, maxExtractChars)
                + "\n…（内容过长，已截断，原文共 " + text.length() + " 字符）…";
    }
}
