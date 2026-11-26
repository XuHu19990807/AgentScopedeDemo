package com.cetc28.chat;

import com.cetc28.service.DocumentTextExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatFileServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private HashOperations<String, Object, Object> hash;
    @Mock
    private ListOperations<String, String> list;
    @Mock
    private DocumentTextExtractor extractor;

    private final ObjectMapper mapper = new ObjectMapper();
    private ChatFileService service;

    @BeforeEach
    void setUp() {
        service = new ChatFileService(redis, mapper, extractor, Runnable::run);
        lenient().when(redis.opsForHash()).thenReturn(hash);
        lenient().when(redis.opsForList()).thenReturn(list);
    }

    @Test
    void rejectsMoreThanFiveFiles() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.resolve(List.of("a", "b", "c", "d", "e", "f"), "u"));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "a.exe", "application/octet-stream", new byte[]{1});
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.save(file, "u"));
        assertEquals(415, error.getStatusCode().value());
    }

    @Test
    void rejectsOversizedFile() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("a.txt");
        when(file.getSize()).thenReturn(21L * 1024 * 1024);
        when(extractor.supports(".txt")).thenReturn(true);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.save(file, "u"));
        assertEquals(413, error.getStatusCode().value());
    }

    @Test
    void reportsExpiredWhenMetaMissing() {
        when(hash.get("chat:file:missing", "meta")).thenReturn(null);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.resolve(List.of("missing"), "u"));
        assertEquals(410, error.getStatusCode().value());
    }

    @Test
    void rejectsOtherUsersFile() throws Exception {
        ChatFile meta = ChatFile.builder()
                .id("f_other").userId("other").name("a.txt").storedName("f_other_a.txt")
                .ext(".txt").size(10).chars(10).chunks(1).build();
        when(hash.get("chat:file:f_other", "meta")).thenReturn(mapper.writeValueAsString(meta));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.resolve(List.of("f_other"), "u"));
        assertEquals(404, error.getStatusCode().value());
    }

    @Test
    void reportsExpiredWhenChunksMissing() throws Exception {
        ChatFile meta = ChatFile.builder()
                .id("f_empty").userId("u").name("a.txt").storedName("f_empty_a.txt")
                .ext(".txt").size(10).chars(10).chunks(1).build();
        when(hash.get("chat:file:f_empty", "meta")).thenReturn(mapper.writeValueAsString(meta));
        when(list.range("chat:file:f_empty:chunks", 0, -1)).thenReturn(List.of());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.resolve(List.of("f_empty"), "u"));
        assertEquals(410, error.getStatusCode().value());
    }
}
