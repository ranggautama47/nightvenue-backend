package com.nightvenue.api.auth;

public final class AuthExceptions {
    private AuthExceptions() {
    }

    public static class InvalidCredentials extends RuntimeException {
    }

    public static class InvalidRefreshToken extends RuntimeException {
    }

    public static class InvalidAccessToken extends RuntimeException {
    }

    public static class VenueNotFound extends RuntimeException {
    }

    public static class RateLimitExceeded extends RuntimeException {
        private final long retryAfterSeconds;

        public RateLimitExceeded(long retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    public static class RedisUnavailable extends RuntimeException {
        public RedisUnavailable(Throwable cause) {
            super("Redis is unavailable", cause);
        }
    }
}
