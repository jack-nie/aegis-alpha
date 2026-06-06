package com.aegis.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheServiceTest {
    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private CacheService cacheService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        objectMapper = new ObjectMapper();
        cacheService = new CacheService(redisTemplate, objectMapper);
    }

    @SuppressWarnings("unchecked")
    @Test
    void getReturnsDeserializedValue() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("test:key")).thenReturn("{\"name\":\"test\"}");

        TestDto result = cacheService.get("test:key", TestDto.class);

        assertThat(result).isNotNull();
        assertThat(result.name).isEqualTo("test");
    }

    @Test
    void getReturnsNullWhenKeyNotFound() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("missing")).thenReturn(null);

        assertThat(cacheService.get("missing", TestDto.class)).isNull();
    }

    @Test
    void getReturnsNullOnParseException() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("bad")).thenReturn("not json");

        assertThat(cacheService.get("bad", TestDto.class)).isNull();
    }

    @Test
    void getReturnsNullOnRedisException() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("error")).thenThrow(new RuntimeException("connection refused"));

        assertThat(cacheService.get("error", TestDto.class)).isNull();
    }

    @Test
    void putSerializesAndStores() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        cacheService.put("test:key", new TestDto("hello"), Duration.ofSeconds(60));

        verify(ops).set(eq("test:key"), any(String.class), eq(Duration.ofSeconds(60)));
    }

    @Test
    void putSwallowsExceptions() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        doThrow(new RuntimeException("write failed")).when(ops).set(any(), any(), any(Duration.class));

        cacheService.put("key", "value", Duration.ofSeconds(10));
    }

    @Test
    void evictDeletesKey() {
        when(redisTemplate.delete("key")).thenReturn(true);

        cacheService.evict("key");

        verify(redisTemplate).delete("key");
    }

    @Test
    void evictSwallowsExceptions() {
        when(redisTemplate.delete("key")).thenThrow(new RuntimeException("connection lost"));

        cacheService.evict("key");
    }

    static class TestDto {
        public String name;
        public TestDto() {}
        public TestDto(String name) { this.name = name; }
    }
}