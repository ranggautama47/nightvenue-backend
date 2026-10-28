package com.nightvenue.api.auth;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

import static com.nightvenue.api.auth.AuthExceptions.RedisUnavailable;

@Repository
public class RefreshTokenStore {
    private static final String CREATE_SESSION = """
            if redis.call('EXISTS', KEYS[1]) == 1 then return 'COLLISION' end
            redis.call('HSET', KEYS[1], 'familyId', ARGV[1], 'userId', ARGV[2], 'status', 'ACTIVE')
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            redis.call('HSET', KEYS[2], 'userId', ARGV[2], 'current', ARGV[4])
            redis.call('EXPIRE', KEYS[2], ARGV[3])
            redis.call('SADD', KEYS[3], ARGV[1])
            redis.call('EXPIRE', KEYS[3], ARGV[3])
            return 'OK'
            """;
    private static final String ROTATE = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 'UNKNOWN' end
            local familyId = redis.call('HGET', KEYS[1], 'familyId')
            local userId = redis.call('HGET', KEYS[1], 'userId')
            local status = redis.call('HGET', KEYS[1], 'status')
            if not familyId or not userId then return 'UNKNOWN' end
            local familyKey = 'rt:fam:' .. familyId
            if redis.call('EXISTS', familyKey) == 0 then return 'REVOKED' end
            local userKey = 'rt:user:' .. userId
            if status == 'USED' then
              local families = redis.call('SMEMBERS', userKey)
              local count = 0
              for _, id in ipairs(families) do
                local key = 'rt:fam:' .. id
                if redis.call('EXISTS', key) == 1 then
                  count = count + 1
                  redis.call('DEL', key)
                end
              end
              if redis.call('EXISTS', familyKey) == 1 then
                redis.call('DEL', familyKey)
                count = count + 1
              end
              if count == 0 then count = 1 end
              redis.call('DEL', userKey)
              return 'REUSED:' .. userId .. ':' .. familyId .. ':' .. count
            end
            if status ~= 'ACTIVE' or redis.call('HGET', familyKey, 'current') ~= ARGV[1] then
              return 'REVOKED'
            end
            redis.call('HSET', KEYS[1], 'status', 'USED')
            redis.call('HSET', KEYS[2], 'familyId', familyId, 'userId', userId, 'status', 'ACTIVE')
            redis.call('EXPIRE', KEYS[2], ARGV[3])
            redis.call('HSET', familyKey, 'current', ARGV[2])
            redis.call('EXPIRE', familyKey, ARGV[3])
            redis.call('SADD', userKey, familyId)
            redis.call('EXPIRE', userKey, ARGV[3])
            return 'OK:' .. userId .. ':' .. familyId
            """;
    private static final String LOGOUT = """
            if redis.call('EXISTS', KEYS[1]) == 0 then return 'OK' end
            local familyId = redis.call('HGET', KEYS[1], 'familyId')
            local userId = redis.call('HGET', KEYS[1], 'userId')
            if not familyId or not userId then return 'OK' end
            local familyKey = 'rt:fam:' .. familyId
            if redis.call('EXISTS', familyKey) == 0 then return 'OK' end
            redis.call('DEL', familyKey)
            local userKey = 'rt:user:' .. userId
            redis.call('SREM', userKey, familyId)
            if redis.call('SCARD', userKey) == 0 then redis.call('DEL', userKey) end
            return 'OK'
            """;
    private static final String REVOKE_ALL = """
            local families = redis.call('SMEMBERS', KEYS[1])
            local count = 0
            for _, id in ipairs(families) do
              local key = 'rt:fam:' .. id
              if redis.call('EXISTS', key) == 1 then
                count = count + 1
                redis.call('DEL', key)
              end
            end
            redis.call('DEL', KEYS[1])
            return count
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<String> createScript = new DefaultRedisScript<>(CREATE_SESSION, String.class);
    private final DefaultRedisScript<String> rotateScript = new DefaultRedisScript<>(ROTATE, String.class);
    private final DefaultRedisScript<String> logoutScript = new DefaultRedisScript<>(LOGOUT, String.class);
    private final DefaultRedisScript<Long> revokeAllScript = new DefaultRedisScript<>(REVOKE_ALL, Long.class);

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void create(long userId, String familyId, String tokenHash, long ttlSeconds) {
        String result = execute(createScript,
                List.of(tokenKey(tokenHash), familyKey(familyId), userKey(userId)),
                familyId, Long.toString(userId), Long.toString(ttlSeconds), tokenHash);
        if (!"OK".equals(result)) {
            throw new IllegalStateException("Unable to create refresh session");
        }
    }

    public String rotate(String oldHash, String newHash, long ttlSeconds) {
        return execute(rotateScript, List.of(tokenKey(oldHash), tokenKey(newHash)),
                oldHash, newHash, Long.toString(ttlSeconds));
    }

    public void logout(String tokenHash) {
        execute(logoutScript, List.of(tokenKey(tokenHash)));
    }

    public int revokeAll(long userId) {
        try {
            Long count = redis.execute(revokeAllScript, List.of(userKey(userId)));
            return count == null ? 0 : Math.toIntExact(count);
        } catch (DataAccessException ex) {
            throw new RedisUnavailable(ex);
        }
    }

    private String execute(DefaultRedisScript<String> script, List<String> keys, String... args) {
        try {
            return Objects.requireNonNullElse(redis.execute(script, keys, (Object[]) args), "");
        } catch (DataAccessException ex) {
            throw new RedisUnavailable(ex);
        }
    }

    private static String tokenKey(String hash) {
        return "rt:tok:" + hash;
    }

    private static String familyKey(String familyId) {
        return "rt:fam:" + familyId;
    }

    private static String userKey(long userId) {
        return "rt:user:" + userId;
    }
}
