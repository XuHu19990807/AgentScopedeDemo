package com.cetc28.chat;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 对话附件接口：上传、下载、删除。
 */
@RestController
@RequestMapping("/api/chat/files")
public class ChatFileController {

    private final ChatFileService fileService;

    public ChatFileController(ChatFileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      HttpServletRequest request) {
        ChatFile saved = fileService.save(file, UserContext.from(request));
        return fileService.toView(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id, HttpServletRequest request) {
        ChatFileService.Download download = fileService.download(id, UserContext.from(request));
        Resource resource = new FileSystemResource(download.path());
        String disposition = ContentDisposition.attachment()
                .filename(download.name(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpServletRequest request) {
        boolean deleted = fileService.delete(id, UserContext.from(request));
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        return Map.of("success", true);
    }

    @GetMapping("/formats")
    public Map<String, Object> formats() {
        return fileService.status();
    }
}
