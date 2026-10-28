package com.nightvenue.api.auth;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

import static com.nightvenue.api.auth.AuthExceptions.RateLimitExceeded;
import static com.nightvenue.api.auth.AuthExceptions.RedisUnavailable;

@Service
public class RateLimitService {
    private static final String TOKEN_BUCKET = """
            local capacity = tonumber(ARGV[1])
            local period = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local stored = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
            local tokens = tonumber(stored[1]) or capacity
            local updated = tonumber(stored[2]) or now
            tokens = math.min(capacity, tokens + math.max(0, now - updated) * capacity / period)
            local allowed = 0
            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry = math.ceil((1 - tokens) * period / capacity / 1000)
              if retry < 1 then retry = 1 end
            end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', now)
            redis.call('PEXPIRE', KEYS[1], period * 2)
            return tostring(allowed) .. ':' .. tostring(retry)
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> script = new DefaultRedisScript<>(TOKEN_BUCKET, String.class);

    public RateLimitService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void check(String scope, String kind, String identity, RateLimitProperties.Limit limit) {
        Duration period = limit.getPeriod();
        if (limit.getCapacity() <= 0 || period == null || period.isZero() || period.isNegative()) {
            throw new IllegalStateException("Rate limit capacity and period must be positive");
        }
        long periodMs = period.toMillis();
        if (periodMs <= 0) {
            throw new IllegalStateException("Rate limit period must be at least one millisecond");
        }

        String result;
        try {
            result = redis.execute(script, List.of("rl:" + scope + ":" + kind + ":" + identity),
                    Integer.toString(limit.getCapacity()), Long.toString(periodMs));
        } catch (DataAccessException ex) {
            throw new RedisUnavailable(ex);
        }
        if (result == null) {
            throw new RedisUnavailable(new IllegalStateException("Redis returned no rate limit result"));
        }
        String[] values = result.split(":", 2);
        if (values.length != 2) {
            throw new RedisUnavailable(new IllegalStateException("Redis returned an invalid rate limit result"));
        }
        if ("0".equals(values[0])) {
            throw new RateLimitExceeded(Long.parseLong(values[1]));
        }
    }
}
