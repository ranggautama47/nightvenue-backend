package com.nightvenue.api.venue;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "nightvenue.seed.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class VenueTableIntegrationTests {

    private static final String TABLES = "/api/v1/tables";
    private static final String FIXTURE_PREFIX = "VT-";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;

    private String fixture;
    private long venueId;
    private long tableId;

    @BeforeEach
    void createFixtures() {
        cleanupFixtures();
        fixture = FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 8);

        venueId = jdbc.queryForObject("SELECT id FROM venues ORDER BY id LIMIT 1", Long.class);
        tableId = jdbc.queryForObject("""
                INSERT INTO venue_tables (venue_id, code, area, capacity, minimum_spend, status, is_active, version)
                VALUES (?, ?, 'Test Area', 4, 0, 'AVAILABLE', true, 0)
                RETURNING id
                """, Long.class, venueId, fixture + "-T1");
    }

    @AfterEach
    void cleanup() {
        cleanupFixtures();
    }

    @Test
    void updateTableWithLowercaseStatusNormalizesToUppercase() throws Exception {
        String adminToken = bearer("ADMIN");

        // Send PUT with lowercase "available" - should normalize to "AVAILABLE"
        mvc.perform(put(TABLES + "/" + tableId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"available\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        // Verify database also has uppercase value
        String dbStatus = jdbc.queryForObject("SELECT status FROM venue_tables WHERE id = ?", String.class, tableId);
        assertEquals("AVAILABLE", dbStatus, "Database status must be normalized to uppercase");
    }

    private void cleanupFixtures() {
        jdbc.update("DELETE FROM venue_tables WHERE code LIKE ?", fixture + "-%");
    }

    private String bearer(String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("nightvenue")
                .subject("1")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("role", role)
                .build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}