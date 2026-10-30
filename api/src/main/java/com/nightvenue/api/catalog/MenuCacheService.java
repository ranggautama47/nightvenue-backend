package com.nightvenue.api.catalog;

import org.slf4j.Logger;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

@Service
public class MenuCacheService {
    private static final Logger log = LoggerFactory.getLogger(MenuCacheService.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public MenuCacheService(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${nightvenue.cache.menu.ttl:PT5M}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public List<ProductRepository.Product> readOrLoad(long venueId, Supplier<List<ProductRepository.Product>> fallback) {
        String cacheKey = "menu:" + venueId;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<>() {
                });
            }
        } catch (Exception exception) {
            log.warn("Menu cache read failed for venue {}. Falling back to database", venueId, exception);
        }

        List<ProductRepository.Product> result = fallback.get();
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result), ttl);
        } catch (Exception exception) {
            log.warn("Menu cache write failed for venue {}. Database result will still be returned", venueId, exception);
        }
        return result;
    }
}
