package com.nightvenue.api.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;

@Service
public class AuthAuditService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuthAuditService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshReuse(long userId, String familyId, String ipAddress) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_user_id, action, entity_type, entity_id, details, ip_address, created_at)
                VALUES (?, 'AUTH_REFRESH_REUSE', 'USER', ?, jsonb_build_object('familyId', ?), ?, ?)
                """, userId, userId, familyId, Objects.requireNonNull(ipAddress), Timestamp.from(clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sessionsRevoked(long userId, boolean userExists, String reason, int familyCount) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_user_id, action, entity_type, entity_id, details, created_at)
                VALUES (?, 'AUTH_SESSION_REVOKED', 'USER', ?, jsonb_build_object('reason', ?, 'families', ?), ?)
                """, userExists ? userId : null, userId, reason, familyCount, Timestamp.from(clock.instant()));
    }
}
