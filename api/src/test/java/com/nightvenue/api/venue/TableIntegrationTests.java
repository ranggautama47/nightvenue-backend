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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class TableIntegrationTests {
    private static final String TABLES = "/api/v1/tables";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired ObjectMapper objectMapper;

    private String fixture;
    private long venueAId;
    private long venueBId;
    private long adminId;
    private long managerId;
    private long cashierId;

    @BeforeEach
    void createFixtures() {
        fixture = "T" + UUID.randomUUID().toString().substring(0, 8);
        venueAId = createVenue(fixture + "-venue-a");
        venueBId = createVenue(fixture + "-venue-b");
        adminId = createUser("ADMIN");
        managerId = createUser("MANAGER");
        cashierId = createUser("CASHIER");
        for (long userId : new long[] {adminId, managerId, cashierId}) {
            jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?)", userId, venueAId);
        }
    }

    @AfterEach
    void removeFixtures() {
        if (venueAId > 0) jdbc.update("DELETE FROM venue_tables WHERE venue_id = ?", venueAId);
        if (venueBId > 0) jdbc.update("DELETE FROM venue_tables WHERE venue_id = ?", venueBId);
        if (fixture != null) {
            jdbc.update("DELETE FROM users WHERE email LIKE ?", fixture.toLowerCase() + "-%@test.local");
            jdbc.update("DELETE FROM venues WHERE code IN (?, ?)",
                    fixture + "-venue-a", fixture + "-venue-b");
        }
    }

    @Test
    void adminCanCompleteTableCrud() throws Exception {
        String auth = bearer(adminId, "ADMIN");
        long id = createTable(venueAId, fixture + "-admin", auth);
        mvc.perform(get(TABLES).param("venueId", Long.toString(venueAId)).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + id + ")].code").value(fixture + "-admin"));
        mvc.perform(get(TABLES + "/" + id).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(put(TABLES + "/" + id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"area\":\"Admin Area\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.area").value("Admin Area"));
        mvc.perform(delete(TABLES + "/" + id).header("Authorization", auth)).andExpect(status().isNoContent());
    }

    @Test
    void managerCanCompleteTableCrudInAssignedVenue() throws Exception {
        String auth = bearer(managerId, "MANAGER");
        long id = createTable(venueAId, fixture + "-manager", auth);
        mvc.perform(get(TABLES).param("venueId", Long.toString(venueAId)).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + id + ")]").isNotEmpty());
        mvc.perform(get(TABLES + "/" + id).header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(put(TABLES + "/" + id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"area\":\"Manager Area\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.area").value("Manager Area"));
        mvc.perform(delete(TABLES + "/" + id).header("Authorization", auth)).andExpect(status().isNoContent());
    }

    @Test
    void cashierCanReadCollectionAndDetail() throws Exception {
        String auth = bearer(cashierId, "CASHIER");
        long id = insertTable(venueAId, fixture + "-cashier");
        mvc.perform(get(TABLES).param("venueId", Long.toString(venueAId)).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + id + ")]").isNotEmpty());
        mvc.perform(get(TABLES + "/" + id).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void cashierCannotCreateTable() throws Exception {
        mvc.perform(post(TABLES).header("Authorization", bearer(cashierId, "CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(venueAId, fixture + "-post")))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotUpdateTable() throws Exception {
        long id = insertTable(venueAId, fixture + "-put");
        mvc.perform(put(TABLES + "/" + id).header("Authorization", bearer(cashierId, "CASHIER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"area\":\"Nope\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cashierCannotDeleteTable() throws Exception {
        long id = insertTable(venueAId, fixture + "-delete");
        mvc.perform(delete(TABLES + "/" + id).header("Authorization", bearer(cashierId, "CASHIER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void userWithoutVenueAccessCannotListReadUpdateOrDeleteAnotherVenuesTable() throws Exception {
        String auth = bearer(managerId, "MANAGER");
        long id = insertTable(venueBId, fixture + "-other");
        mvc.perform(get(TABLES).param("venueId", Long.toString(venueBId)).header("Authorization", auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Venue not found"));
        mvc.perform(get(TABLES + "/" + id).header("Authorization", auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Venue not found"));
        mvc.perform(put(TABLES + "/" + id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"area\":\"Nope\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Venue not found"));
        mvc.perform(delete(TABLES + "/" + id).header("Authorization", auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Venue not found"));
    }

    @Test
    void duplicateCodeInVenueReturnsSanitizedConflict() throws Exception {
        String auth = bearer(managerId, "MANAGER");
        String body = createJson(venueAId, fixture + "-duplicate");
        mvc.perform(post(TABLES).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        var response = mvc.perform(post(TABLES).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Table already exists"))
                .andReturn();
        String responseBody = response.getResponse().getContentAsString();
        assertFalse(responseBody.contains("uq_venue_tables_venue_code"));
        assertFalse(responseBody.contains("SQLState"));
    }

    @Test
    void nonPositiveCapacityIsRejectedAsValidationErrorNotServerError() throws Exception {
        mvc.perform(post(TABLES).header("Authorization", bearer(managerId, "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":" + venueAId + ",\"code\":\"" + fixture + "-cap\",\"capacity\":0,\"minimumSpend\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
    }

    @Test
    void negativeMinimumSpendIsRejectedAsValidationErrorNotServerError() throws Exception {
        mvc.perform(post(TABLES).header("Authorization", bearer(managerId, "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":" + venueAId + ",\"code\":\"" + fixture + "-bad-spend\",\"capacity\":2,\"minimumSpend\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request validation failed"));
    }

    @Test
    void createDefaultsToAvailableAndRejectsRequestedNonAvailableStatus() throws Exception {
        String auth = bearer(managerId, "MANAGER");
        mvc.perform(post(TABLES).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(venueAId, fixture + "-ds")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AVAILABLE"));
        mvc.perform(post(TABLES).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(venueAId, fixture + "-req", "OUT_OF_SERVICE")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Table status is controlled by the server"));
    }

    @Test
    void manualOccupiedTransitionIsRejectedAsBusinessRule() throws Exception {
        long id = insertTable(venueAId, fixture + "-occupied");
        mvc.perform(put(TABLES + "/" + id).header("Authorization", bearer(managerId, "MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OCCUPIED\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Table status cannot be set to OCCUPIED"));
    }

    @Test
    void availableAndOutOfServiceStatusesAreAcceptedOnManualUpdate() throws Exception {
        long id = insertTable(venueAId, fixture + "-status");
        String auth = bearer(managerId, "MANAGER");
        mvc.perform(put(TABLES + "/" + id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OUT_OF_SERVICE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
        mvc.perform(put(TABLES + "/" + id).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AVAILABLE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void deleteMarksTableInactiveInsteadOfRemovingRow() throws Exception {
        long id = insertTable(venueAId, fixture + "-sd");
        mvc.perform(delete(TABLES + "/" + id).header("Authorization", bearer(managerId, "MANAGER")))
                .andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM venue_tables WHERE id = ?", Integer.class, id));
        assertEquals(false, jdbc.queryForObject("SELECT is_active FROM venue_tables WHERE id = ?", Boolean.class, id));
    }

    @Test
    void softDeletedTableIsExcludedFromCollectionAndDetail() throws Exception {
        long id = insertTable(venueAId, fixture + "-hidden");
        String auth = bearer(managerId, "MANAGER");
        mvc.perform(delete(TABLES + "/" + id).header("Authorization", auth)).andExpect(status().isNoContent());
        mvc.perform(get(TABLES).param("venueId", Long.toString(venueAId)).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
        mvc.perform(get(TABLES + "/" + id).header("Authorization", auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Resource not found"));
    }

    private long createTable(long venueId, String code, String auth) throws Exception {
        String response = mvc.perform(post(TABLES).header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(venueId, code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("id").asLong();
    }

    private long insertTable(long venueId, String code) {
        return jdbc.queryForObject("""
                INSERT INTO venue_tables (venue_id, code, capacity, minimum_spend, status, is_active, version)
                VALUES (?, ?, 4, 0, 'AVAILABLE', true, 0) RETURNING id
                """, Long.class, venueId, code);
    }

    private String createJson(long venueId, String code) {
        return createJson(venueId, code, null);
    }

    private String createJson(long venueId, String code, String status) {
        String json = "{\"venueId\":" + venueId + ",\"code\":\"" + code
                + "\",\"area\":\"Main\",\"capacity\":4,\"minimumSpend\":0";
        return json + (status == null ? "}" : ",\"status\":\"" + status + "\"}");
    }

    private long createVenue(String code) {
        return jdbc.queryForObject("""
                INSERT INTO venues (code, name, created_at, updated_at)
                VALUES (?, ?, now(), now()) RETURNING id
                """, Long.class, code, code);
    }

    private long createUser(String role) {
        String email = fixture.toLowerCase() + "-" + role.toLowerCase() + "@test.local";
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, is_active, version, created_at, updated_at)
                VALUES (?, 'test-hash', ?, ?, true, 0, now(), now()) RETURNING id
                """, Long.class, email, email, role);
    }

    private String bearer(long userId, String role) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("nightvenue").subject(Long.toString(userId))
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
