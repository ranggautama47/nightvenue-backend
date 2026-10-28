package com.nightvenue.api.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class AuthUserRepository {
    private static final String USER_COLUMNS = "id, email, full_name, role, is_active, password_hash";
    private final JdbcTemplate jdbc;

    public AuthUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByEmail(String email) {
        List<UserAccount> users = jdbc.query("SELECT " + USER_COLUMNS + " FROM users WHERE email = ?",
                (rs, row) -> new UserAccount(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"),
                        rs.getString("role"), rs.getBoolean("is_active"), rs.getString("password_hash")), email);
        return users.stream().findFirst();
    }

    public Optional<UserAccount> findById(long userId) {
        List<UserAccount> users = jdbc.query("SELECT " + USER_COLUMNS + " FROM users WHERE id = ?",
                (rs, row) -> new UserAccount(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"),
                        rs.getString("role"), rs.getBoolean("is_active"), rs.getString("password_hash")), userId);
        return users.stream().findFirst();
    }

    public void updateLastLogin(long userId, Instant now) {
        jdbc.update("UPDATE users SET last_login_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now), userId);
    }

    public List<Long> activeVenueIds(long userId, String role) {
        if ("ADMIN".equals(role)) {
            return jdbc.queryForList("SELECT id FROM venues WHERE is_active = true ORDER BY id", Long.class);
        }
        if (!"MANAGER".equals(role) && !"CASHIER".equals(role)) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT v.id FROM venues v
                JOIN user_venues uv ON uv.venue_id = v.id
                WHERE uv.user_id = ? AND v.is_active = true
                ORDER BY v.id
                """, Long.class, userId);
    }
}
