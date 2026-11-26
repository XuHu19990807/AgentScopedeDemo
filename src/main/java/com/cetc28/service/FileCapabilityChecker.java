package com.cetc28.service;

import com.cetc28.mcp.McpHubService;
import com.cetc28.skill.NacosSkillRepository;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 文件能力预检 — 上传文件时判断是否有 skill 或 MCP 工具可以直接处理该文件类型。
 *
 * 命中结果用于跳过 embedding 建向量（上传耗时的瓶颈）；
 * 未命中则回落原逻辑（逐行 embedding，供语义检索）。
 *
 * 匹配策略（按优先级）：
 * 1. 配置映射表 app.capability.processor-mapping（最可靠，推荐配置）
 *    格式：".pdf=pdf-parse,.xlsx=excel-report"（值为 MCP 工具名或 skill 名）
 * 2. 关键字匹配 MCP 工具名（按扩展名推导候选关键字，如 .xlsx → excel/xlsx/xls）
 * 3. 关键字匹配 skill 名
 *
 * 有意不做描述匹配：Nacos skill 描述需拉取内容（慢），且描述级匹配易误命中。
 */
@Component
public class FileCapabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(FileCapabilityChecker.class);

    /** 扩展名 → 候选匹配关键字（小写） */
    private static final Map<String, List<String>> EXT_KEYWORDS = Map.ofEntries(
            Map.entry(".pdf", List.of("pdf")),
            Map.entry(".docx", List.of("docx", "word", "doc")),
            Map.entry(".doc", List.of("doc", "word", "docx")),
            Map.entry(".xlsx", List.of("xlsx", "xls", "excel")),
            Map.entry(".xls", List.of("xls", "xlsx", "excel")),
            Map.entry(".csv", List.of("csv", "excel")),
            Map.entry(".json", List.of("json")),
            Map.entry(".xml", List.of("xml")),
            Map.entry(".txt", List.of("txt")),
            Map.entry(".md", List.of("md", "markdown"))
    );

    @Value("${app.capability.skip-when-capable:true}")
    private boolean skipWhenCapable;

    @Value("${app.capability.processor-mapping:}")
    private String processorMappingConfig; // ".pdf=pdf-parse,.xlsx=excel-report"

    private volatile Map<String, String> processorMapping = new LinkedHashMap<>();

    @Autowired(required = false)
    private NacosSkillRepository nacosSkillRepository;

    @Autowired(required = false)
    private McpHubService mcpHubService;

    @PostConstruct
    public void init() {
        parseMapping();
    }

    private void parseMapping() {
        Map<String, String> map = new LinkedHashMap<>();
        if (processorMappingConfig != null && !processorMappingConfig.isBlank()) {
            for (String part : processorMappingConfig.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                int idx = part.indexOf('=');
                if (idx <= 0) continue;
                String ext = part.substring(0, idx).trim().toLowerCase(Locale.ROOT);
                String processor = part.substring(idx + 1).trim();
                if (!ext.startsWith(".")) ext = "." + ext;
                if (!ext.isBlank() && !processor.isBlank()) {
                    map.put(ext, processor);
                }
            }
        }
        this.processorMapping = map;
        log.info("FileCapabilityChecker 初始化: skipWhenCapable={}, 映射表={}",
                skipWhenCapable, map.isEmpty() ? "（空，仅靠关键字匹配）" : map);
    }

    /**
     * 判断指定文件是否已有可处理的 skill 或 MCP 工具。
     *
     * @return 命中的处理器描述（如 "mcp:pdf-parse" / "skill:excel-analysis" / 映射表值），未命中返回 empty
     */
    public Optional<String> findCapableProcessor(String filename) {
        if (!skipWhenCapable) return Optional.empty();
        if (filename == null || filename.isBlank()) return Optional.empty();

        String lower = filename.toLowerCase(Locale.ROOT);
        String ext = extension(lower);

        // 1. 配置映射表（最可靠）
        if (ext != null) {
            String mapped = processorMapping.get(ext);
            if (mapped != null && !mapped.isBlank()) {
                log.debug("文件 '{}' 命中映射表: {} → {}", filename, ext, mapped);
                return Optional.of(mapped);
            }
        }

        // 2/3. 关键字匹配已默认关闭：
        // 仅凭"工具名里含 pdf"就跳过向量化，会让文件实际上检索不到内容。
        // 需要跳过 embedding 时，请在 app.capability.processor-mapping 里显式配置。
        return Optional.empty();
    }

    private String findMcpToolByKeywords(List<String> keywords) {
        if (mcpHubService == null) return null;
        try {
            for (List<McpSchema.Tool> tools : mcpHubService.getMcpToolsByServer().values()) {
                for (McpSchema.Tool tool : tools) {
                    String name = tool.name().toLowerCase(Locale.ROOT);
                    for (String kw : keywords) {
                        if (name.contains(kw)) return tool.name();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("MCP 能力预检失败: {}", e.getMessage());
        }
        return null;
    }

    private String findSkillByKeywords(List<String> keywords) {
        if (nacosSkillRepository == null) return null;
        try {
            for (String skillName : nacosSkillRepository.getAllSkillNames()) {
                String name = skillName.toLowerCase(Locale.ROOT);
                for (String kw : keywords) {
                    if (name.contains(kw)) return skillName;
                }
            }
        } catch (Exception e) {
            log.warn("Skill 能力预检失败: {}", e.getMessage());
        }
        return null;
    }

    /** 取小写文件名的扩展名（含点） */
    private String extension(String lowerFilename) {
        int idx = lowerFilename.lastIndexOf('.');
        return idx < 0 ? null : lowerFilename.substring(idx);
    }
}
