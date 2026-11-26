package com.cetc28.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP Server 定义注册表 — 持久化到本地 JSON 文件（默认 ./data/mcp-servers.json）。
 *
 * 语义：
 * - 仅 UI 添加/删除的定义会写回文件（yml 配置与 nacos 发现来源不落盘，重启后仍由各自机制恢复）；
 * - 写入采用 tmp + 原子 rename，进程被杀也不会留下半截文件；
 * - 读取失败（文件损坏）时把坏文件改名为 .bak 后按空列表继续，绝不阻断启动。
 */
@Component
public class McpServerRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistry.class);

    private final Path file;
    private final ObjectMapper mapper;

    public McpServerRegistry(@Value("${mcp.persist-file:./data/mcp-servers.json}") String file) {
        this.file = Paths.get(file).toAbsolutePath().normalize();
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    void init() {
        log.info("MCP 定义持久化文件: {}", file);
    }

    /** 读取全部持久化定义；文件不存在或损坏时返回空列表 */
    public synchronized List<McpServerDefinition> load() {
        if (!Files.exists(file)) return new ArrayList<>();
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return new ArrayList<>();
            List<McpServerDefinition> defs =
                    mapper.readValue(new String(bytes, StandardCharsets.UTF_8),
                            new TypeReference<List<McpServerDefinition>>() { });
            List<McpServerDefinition> valid = new ArrayList<>();
            for (McpServerDefinition def : defs) {
                if (def != null && def.isValid()) valid.add(def);
            }
            return valid;
        } catch (IOException e) {
            Path bak = file.resolveSibling(file.getFileName() + ".bak");
            try {
                Files.move(file, bak, StandardCopyOption.REPLACE_EXISTING);
                log.warn("MCP 持久化文件损坏，已备份到 {} 并按空列表启动: {}", bak, e.getMessage());
            } catch (IOException moveError) {
                log.warn("MCP 持久化文件损坏且备份失败，按空列表启动: {}", e.getMessage());
            }
            return new ArrayList<>();
        }
    }

    /** 新增或替换同名定义（仅 ui 来源写入文件） */
    public synchronized void upsert(McpServerDefinition def) {
        if (def == null || !McpServerDefinition.SOURCE_UI.equals(def.getSource())) return;
        List<McpServerDefinition> defs = load();
        defs.removeIf(d -> d.getName().equals(def.getName()));
        defs.add(def);
        write(defs);
    }

    /** 删除同名定义（仅 ui 来源在文件中） */
    public synchronized void remove(String name) {
        List<McpServerDefinition> defs = load();
        boolean changed = defs.removeIf(d -> name.equals(d.getName())
                && McpServerDefinition.SOURCE_UI.equals(d.getSource()));
        if (changed) write(defs);
    }

    private void write(List<McpServerDefinition> defs) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, mapper.writeValueAsString(defs).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("MCP 定义持久化写入失败（不影响当前运行）: {}", e.getMessage());
        }
    }
}
