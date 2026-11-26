package com.nightvenue.api.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class ChatRepository {
    private final JdbcTemplate jdbc;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PublicVenueProfile> findActivePublicVenue(long venueId) {
        List<PublicVenueProfile> rows = jdbc.query("""
                SELECT name, address, opening_hours::text AS opening_hours
                FROM venues
                WHERE id = ? AND is_active = true
                """, (rs, row) -> new PublicVenueProfile(
                rs.getString("name"), rs.getString("address"), rs.getString("opening_hours")), venueId);
        return rows.stream().findFirst();
    }

    public Optional<String> findActiveAnswerTemplate(long venueId, String intent) {
        List<String> rows = jdbc.query("""
                SELECT answer_template
                FROM venue_faqs
                WHERE venue_id = ? AND intent = ? AND is_active = true
                """, (rs, row) -> rs.getString("answer_template"), venueId, intent);
        return rows.stream().findFirst();
    }

    public List<PromotionWindow> findActivePromotions(long venueId, LocalDate businessDate) {
        return jdbc.query("""
                SELECT id, title, starts_on, ends_on
                FROM promotions
                WHERE venue_id = ? AND is_active = true AND starts_on <= ? AND ends_on >= ?
                ORDER BY id
                """, (rs, row) -> new PromotionWindow(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getObject("starts_on", LocalDate.class),
                rs.getObject("ends_on", LocalDate.class)),
                venueId, businessDate, businessDate);
    }

    public List<PublicTableInfo> findActivePublicTables(long venueId) {
        return jdbc.query("""
                SELECT area, minimum_spend, status
                FROM venue_tables
                WHERE venue_id = ? AND is_active = true
                ORDER BY id
                """, (rs, row) -> new PublicTableInfo(
                rs.getString("area"), rs.getLong("minimum_spend"), rs.getString("status")), venueId);
    }

    public List<PublicProductInfo> findActiveProductsWithCategory(long venueId) {
        return jdbc.query("""
                SELECT p.name, c.name AS category_name, p.price
                FROM products p
                JOIN product_categories c ON c.id = p.category_id
                WHERE p.venue_id = ? AND p.is_active = true
                ORDER BY p.id
                """, (rs, row) -> new PublicProductInfo(
                rs.getString("name"), rs.getString("category_name"), rs.getLong("price")), venueId);
    }

    public void insertChatRouteLog(ChatRouteLogEntry entry) {
        jdbc.update("""
                INSERT INTO chat_route_logs
                    (venue_id, route, intent, confidence, ml_available, classifier_ms,
                     llm_provider, llm_ms, input_tokens, output_tokens, estimated_cost_usd, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.venueId(),
                entry.route(),
                entry.intent(),
                entry.confidence(),
                entry.mlAvailable(),
                entry.classifierMs(),
                entry.llmProvider(),
                entry.llmMs(),
                entry.inputTokens(),
                entry.outputTokens(),
                entry.estimatedCostUsd(),
                entry.createdAt() == null ? null : Timestamp.from(entry.createdAt()));
    }
}
