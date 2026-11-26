package com.cetc28.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 日志管理控制器 — 在线查看和下载日志
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    @Value("${logging.file.path:${java.io.tmpdir}/logs/agentscope-demo}")
    private String logPath;

    @GetMapping("/files")
    public List<Map<String, Object>> listLogFiles() {
        File logDir = new File(logPath);
        if (!logDir.exists() || !logDir.isDirectory()) return List.of();

        File[] files = logDir.listFiles((dir, name) -> name.endsWith(".log") || name.endsWith(".log.gz"));
        if (files == null) return List.of();

        return Arrays.stream(files)
                .sorted((a, b) -> Long.compare(b.lastModified(), a.lastModified()))
                .map(f -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", f.getName());
                    info.put("size", f.length());
                    info.put("sizeHuman", formatSize(f.length()));
                    info.put("lastModified", new Date(f.lastModified()));
                    info.put("isGz", f.getName().endsWith(".gz"));
                    return info;
                })
                .collect(Collectors.toList());
    }

    @GetMapping("/view")
    public ResponseEntity<?> viewLog(@RequestParam String file,
                                      @RequestParam(defaultValue = "0") int offset,
                                      @RequestParam(defaultValue = "200") int limit) {
        Path logFile = Paths.get(logPath, file).normalize();
        if (!logFile.startsWith(Paths.get(logPath).normalize())) {
            return ResponseEntity.badRequest().body(Map.of("error", "非法路径"));
        }
        if (!logFile.toFile().exists() || file.endsWith(".gz")) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不存在或已压缩"));
        }

        try {
            List<String> allLines = readLogLines(logFile);
            int totalLines = allLines.size();
            int from = Math.max(0, offset);
            int to = Math.min(totalLines, offset + limit);
            return ResponseEntity.ok(Map.of(
                    "file", file, "totalLines", totalLines,
                    "offset", from, "limit", limit,
                    "lines", allLines.subList(from, to)));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 容错读取日志文件 — 用 REPLACE 策略处理非法 UTF-8 字节，避免 MalformedInputException
     */
    private List<String> readLogLines(Path path) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), decoder))) {
            return reader.lines().collect(Collectors.toList());
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
