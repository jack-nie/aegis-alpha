package com.marketmind.alpha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class CacheService {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public <T> T get(String key, Class<T> type) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return value == null ? null : objectMapper.readValue(value, type);
        } catch (Exception ex) {
            return null;
        }
    }

    public void put(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception ignored) {
        }
    }

    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
        }
    }
}
