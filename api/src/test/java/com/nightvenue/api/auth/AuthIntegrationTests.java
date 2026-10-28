package com.nightvenue.api.auth;

import com.nightvenue.api.TestcontainersConfig;
import org.testcontainers.DockerClientFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nightvenue.seed.enabled=true",
                "spring.data.redis.timeout=PT1S",
                "server.tomcat.remoteip.internal-proxies=127[.]0[.]0[.]1"
        })
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class AuthIntegrationTests {
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String REFRESH = "/api/v1/auth/refresh";
    private static final String LOGOUT = "/api/v1/auth/logout";
    private static final String ME = "/api/v1/auth/me";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper objectMapper;
    @Autowired RateLimitProperties rateLimitProperties;
    @Autowired @Qualifier("redisContainer") GenericContainer<?> redisContainer;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AuthProperties authProperties;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired JwtEncoder jwtEncoder;
    @LocalServerPort int port;

    @BeforeEach
    void resetAuthState() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        jdbc.update("UPDATE users SET is_active = true");
        jdbc.update("""
                UPDATE users SET role = CASE email
                  WHEN 'admin@nightvenue.demo' THEN 'ADMIN'
                  WHEN 'manager@nightvenue.demo' THEN 'MANAGER'
                  WHEN 'cashier@nightvenue.demo' THEN 'CASHIER'
                  WHEN 'customer@nightvenue.demo' THEN 'CUSTOMER'
                  ELSE role END
                """);
        jdbc.update("DELETE FROM venues WHERE code='TEST-AUTH-EXTRA'");
        addDemoVenueAssignments();
        rateLimitProperties.getLogin().setCapacity(1000);
        rateLimitProperties.getRefresh().setCapacity(1000);
        rateLimitProperties.getLogout().setCapacity(1000);
        rateLimitProperties.getMe().setCapacity(1000);
    }

    @AfterEach
    void restoreRateLimits() {
        rateLimitProperties.getLogin().setCapacity(1000);
        rateLimitProperties.getRefresh().setCapacity(1000);
        rateLimitProperties.getLogout().setCapacity(1000);
        rateLimitProperties.getMe().setCapacity(1000);
    }

    @Test
    void seededDemoCredentialsAuthenticateWithHttpOnlyCookie() throws Exception {
        String body = """
                {"email":" ADMIN@NIGHTVENUE.DEMO ","password":"password"}
                """;
        var result = mvc.perform(post(LOGIN).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.user.venueIds.length()").value(2))
                .andExpect(header().string("Set-Cookie",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("SameSite=Lax"),
                                org.hamcrest.Matchers.containsString("Path=/api/v1/auth"),
                                org.hamcrest.Matchers.containsString("Max-Age=604800"),
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Secure")))))
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        assertTrue(token.split("\\.").length == 3);
        var claims = jwtDecoder.decode(token);
        assertEquals("nightvenue", claims.getClaimAsString("iss"));
        assertEquals("ADMIN", claims.getClaimAsString("role"));
        assertEquals("900", Long.toString(claims.getExpiresAt().getEpochSecond() - claims.getIssuedAt().getEpochSecond()));
        assertTrue(redis.keys("rt:tok:*").stream().allMatch(key -> key.length() == "rt:tok:".length() + 64));
        assertFalse(result.getResponse().getContentAsString().contains("nv_refresh"));
        assertTrue(jdbc.queryForObject("SELECT last_login_at IS NOT NULL FROM users WHERE email=?",
                Boolean.class, "admin@nightvenue.demo"));
        assertTrue(passwordEncoder.matches("password", jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email='admin@nightvenue.demo'", String.class)));
        assertTrue(findRepoReadme()
                .contains("password `password`"));
    }

    private static String findRepoReadme() throws Exception {
        java.nio.file.Path current = java.nio.file.Path.of("").toAbsolutePath();
        while (current != null) {
            java.nio.file.Path candidate = current.resolve("README.md");
            if (java.nio.file.Files.exists(candidate)) {
                String text = java.nio.file.Files.readString(candidate);
                if (text.contains("password `password`")) {
                    return text;
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository README.md not found from working directory: " + java.nio.file.Path.of("").toAbsolutePath());
    }

    @Test
    void invalidLoginBodiesAreIdenticalAndInactiveUsersStillFail() throws Exception {
        jdbc.update("UPDATE users SET is_active = false WHERE email='cashier@nightvenue.demo'");
        String unknown = loginFailure("{\"email\":\"missing@nightvenue.demo\",\"password\":\"password\"}");
        String wrong = loginFailure("{\"email\":\"admin@nightvenue.demo\",\"password\":\"wrong\"}");
        String inactive = loginFailure("{\"email\":\"cashier@nightvenue.demo\",\"password\":\"password\"}");
        assertEquals(unknown, wrong);
        assertEquals(wrong, inactive);
        assertTrue(unknown.contains("Invalid credentials"));
    }

    @Test
    void unknownEmailReturns401() throws Exception {
        loginFailure("{\"email\":\"missing@nightvenue.demo\",\"password\":\"password\"}");
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        loginFailure("{\"email\":\"admin@nightvenue.demo\",\"password\":\"wrong\"}");
    }

    @Test
    void inactiveUserReturns401() throws Exception {
        jdbc.update("UPDATE users SET is_active = false WHERE email='cashier@nightvenue.demo'");
        loginFailure("{\"email\":\"cashier@nightvenue.demo\",\"password\":\"password\"}");
    }

    @Test
    void malformedOrOversizedLoginIsBadRequest() throws Exception {
        mvc.perform(post(LOGIN).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(LOGIN).contentType("application/json").content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(LOGIN).contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new AuthController.LoginRequest("admin@nightvenue.demo", "é".repeat(37)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginMeAndBadBearerTokensFollowContract() throws Exception {
        LoginSession session = login("admin@nightvenue.demo");
        mvc.perform(get(ME).header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@nightvenue.demo"));
        mvc.perform(get(ME)).andExpect(status().isUnauthorized());
        mvc.perform(get(ME).header("Authorization", "Bearer " + session.refreshToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ME).header("Authorization", "Bearer " + session.accessToken().substring(0,
                        session.accessToken().length() - 2) + "xx"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ME).header("Authorization", "Bearer "
                        + unsignedNoneToken()))
                .andExpect(status().isUnauthorized());
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().issuer("nightvenue").subject("1").claim("role", "ADMIN")
                        .issuedAt(java.time.Instant.now().minusSeconds(180))
                        .expiresAt(java.time.Instant.now().minusSeconds(120)).build())).getTokenValue();
        mvc.perform(get(ME).header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReadsCurrentRoleAndRejectsDisabledUsers() throws Exception {
        LoginSession session = login("admin@nightvenue.demo");
        jdbc.update("UPDATE users SET role='CUSTOMER' WHERE email='admin@nightvenue.demo'");
        mvc.perform(get(ME).header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.venueIds").isEmpty());
        jdbc.update("UPDATE users SET is_active=false WHERE email='admin@nightvenue.demo'");
        mvc.perform(get(ME).header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesAndReuseRevokesAllFamiliesAndAudits() throws Exception {
        LoginSession first = login("admin@nightvenue.demo");
        LoginSession second = login("admin@nightvenue.demo");
        var loginBody = objectMapper.readTree(mvc.perform(post(LOGIN).contentType("application/json")
                        .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var rotatedResult = refresh(first.refreshToken()).andExpect(status().isOk()).andReturn();
        String rotated = rotatedResult.getResponse().getCookie("nv_refresh").getValue();
        var refreshedBody = objectMapper.readTree(rotatedResult.getResponse().getContentAsString());
        assertEquals(loginBody.has("user"), refreshedBody.has("user"));
        assertEquals(loginBody.get("expiresIn"), refreshedBody.get("expiresIn"));
        assertEquals(loginBody.get("user"), refreshedBody.get("user"));
        assertTrue(refreshedBody.get("accessToken").isTextual());

        mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", first.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", rotated)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", second.refreshToken())))
                .andExpect(status().isUnauthorized());

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='AUTH_REFRESH_REUSE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='AUTH_SESSION_REVOKED'", Integer.class));
        String details = jdbc.queryForObject("""
                SELECT details::text FROM audit_logs WHERE action='AUTH_REFRESH_REUSE'
                """, String.class);
        assertTrue(details.contains("familyId"));
        List<String> auditDetails = jdbc.queryForList("""
                SELECT details::text FROM audit_logs
                WHERE action IN ('AUTH_REFRESH_REUSE', 'AUTH_SESSION_REVOKED')
                """, String.class);
        for (String auditDetail : auditDetails) {
            assertFalse(auditDetail.contains(first.refreshToken()));
            assertFalse(auditDetail.contains(TokenDigests.sha256(first.refreshToken())));
            assertFalse(auditDetail.contains(first.accessToken()));
            assertFalse(auditDetail.contains("nv_refresh"));
        }
    }

    @Test
    void parallelRefreshHasExactlyOneSuccessAndLeavesNoSession() throws Exception {
        LoginSession first = login("manager@nightvenue.demo");
        int requestCount = 6;
        var pool = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<RefreshAttempt>> calls = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                calls.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent refresh start timed out");
                    }
                    var response = mvc.perform(post(REFRESH)
                                    .cookie(new Cookie("nv_refresh", first.refreshToken())))
                            .andReturn().getResponse();
                    Cookie rotated = response.getCookie("nv_refresh");
                    return new RefreshAttempt(response.getStatus(), rotated == null ? null : rotated.getValue());
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Refresh workers did not reach the start gate");
            start.countDown();
            List<RefreshAttempt> attempts = calls.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }).toList();
            assertEquals(1, attempts.stream().filter(attempt -> attempt.status() == 200).count());
            assertEquals(requestCount - 1, attempts.stream().filter(attempt -> attempt.status() == 401).count());
            for (RefreshAttempt attempt : attempts) {
                if (attempt.refreshToken() != null) {
                    mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", attempt.refreshToken())))
                            .andExpect(status().isUnauthorized());
                }
            }
            assertTrue(redis.keys("rt:fam:*").isEmpty(), "Reuse detection must revoke every session family");
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void invalidAndMissingRefreshCookiesClearCookieAndReturn401() throws Exception {
        mvc.perform(post(REFRESH)).andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", "not-a-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", "broken.refresh.token")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void expiredRefreshTokenReturns401() throws Exception {
        LoginSession session = login("admin@nightvenue.demo");
        redis.expire("rt:tok:" + TokenDigests.sha256(session.refreshToken()), Duration.ZERO);
        refresh(session.refreshToken()).andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void inactiveUserAfterLoginRevokesAllRefreshFamilies() throws Exception {
        LoginSession first = login("cashier@nightvenue.demo");
        LoginSession second = login("cashier@nightvenue.demo");
        jdbc.update("UPDATE users SET is_active=false WHERE email='cashier@nightvenue.demo'");
        mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", first.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", second.refreshToken())))
                .andExpect(status().isUnauthorized());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs WHERE action='AUTH_SESSION_REVOKED'
                  AND details->>'reason'='USER_INACTIVE'
                """, Integer.class));
    }

    @Test
    void logoutRevokesOnlyItsFamilyAndIsIdempotent() throws Exception {
        LoginSession first = login("cashier@nightvenue.demo");
        LoginSession second = login("cashier@nightvenue.demo");
        mvc.perform(post(LOGOUT).cookie(new Cookie("nv_refresh", first.refreshToken())))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"),
                        org.hamcrest.Matchers.containsString("Path=/api/v1/auth"),
                        org.hamcrest.Matchers.containsString("Max-Age=0"))));
        mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", first.refreshToken())))
                .andExpect(status().isUnauthorized());
        refresh(second.refreshToken()).andExpect(status().isOk());
        mvc.perform(post(LOGOUT)).andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
    }

    @Test
    void roleChecksAreExplicitAndVenueAssignmentsAreLoadedFromDatabase() throws Exception {
        LoginSession manager = login("manager@nightvenue.demo");
        mvc.perform(get("/test/roles/manager-only").header("Authorization", "Bearer " + manager.accessToken()))
                .andExpect(status().isOk());
        mvc.perform(get(ME).header("Authorization", "Bearer " + manager.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.venueIds.length()").value(2));
        mvc.perform(get("/test/roles/admin-only").header("Authorization", "Bearer " + manager.accessToken()))
                .andExpect(status().isForbidden());

        long firstVenue = jdbc.queryForObject("SELECT id FROM venues ORDER BY id LIMIT 1", Long.class);
        long secondVenue = jdbc.queryForObject("SELECT id FROM venues ORDER BY id OFFSET 1 LIMIT 1", Long.class);
        mvc.perform(get("/test/venues/" + firstVenue + "/access")
                        .header("Authorization", "Bearer " + manager.accessToken()))
                .andExpect(status().isOk());
        LoginSession cashier = login("cashier@nightvenue.demo");
        mvc.perform(get("/test/venues/" + firstVenue + "/access")
                        .header("Authorization", "Bearer " + cashier.accessToken()))
                .andExpect(status().isOk());
        jdbc.update("""
                DELETE FROM user_venues WHERE user_id=(SELECT id FROM users WHERE email='manager@nightvenue.demo')
                  AND venue_id=?
                """, secondVenue);
        mvc.perform(get("/test/venues/" + secondVenue + "/access")
                        .header("Authorization", "Bearer " + manager.accessToken()))
                .andExpect(status().isNotFound());
        jdbc.update("""
                DELETE FROM user_venues WHERE user_id=(SELECT id FROM users WHERE email='cashier@nightvenue.demo')
                  AND venue_id=?
                """, secondVenue);
        mvc.perform(get("/test/venues/" + secondVenue + "/access")
                        .header("Authorization", "Bearer " + cashier.accessToken()))
                .andExpect(status().isNotFound());
        jdbc.update("""
                INSERT INTO user_venues(user_id, venue_id)
                VALUES ((SELECT id FROM users WHERE email='manager@nightvenue.demo'), ?)
                """, secondVenue);
        mvc.perform(get("/test/venues/" + secondVenue + "/access")
                        .header("Authorization", "Bearer " + manager.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void rolesHaveOnlyTheirExplicitAuthorityAndCustomerHasNoVenues() throws Exception {
        LoginSession cashier = login("cashier@nightvenue.demo");
        mvc.perform(get("/test/roles/cashier-only").header("Authorization", "Bearer " + cashier.accessToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/test/roles/manager-only").header("Authorization", "Bearer " + cashier.accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get(ME).header("Authorization", "Bearer " + cashier.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.venueIds.length()").value(2));
        LoginSession admin = login("admin@nightvenue.demo");
        mvc.perform(get("/test/roles/manager-only").header("Authorization", "Bearer " + admin.accessToken()))
                .andExpect(status().isForbidden());
        LoginSession customer = login("customer@nightvenue.demo");
        mvc.perform(get("/test/roles/cashier-only").header("Authorization", "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/test/roles/customer-only").header("Authorization", "Bearer " + customer.accessToken()))
                .andExpect(status().isOk());
        mvc.perform(get(ME).header("Authorization", "Bearer " + customer.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.venueIds").isEmpty());

        jdbc.update("""
                INSERT INTO venues (code, name, timezone, business_day_cutoff_hour, tax_rate_bps,
                    service_charge_bps, void_manager_approval_threshold, is_active, created_at, updated_at)
                VALUES ('TEST-AUTH-EXTRA', 'Auth Test Venue', 'Asia/Jakarta', 6, 0, 0, 0, true, now(), now())
                """);
        LoginSession crossVenueAdmin = login("admin@nightvenue.demo");
        long extraVenue = jdbc.queryForObject("SELECT id FROM venues WHERE code='TEST-AUTH-EXTRA'", Long.class);
        mvc.perform(get(ME).header("Authorization", "Bearer " + crossVenueAdmin.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.venueIds.length()").value(3));
        mvc.perform(get("/test/venues/" + extraVenue + "/access")
                        .header("Authorization", "Bearer " + crossVenueAdmin.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void rateLimitReturnsProblemAndRetryAfter() throws Exception {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        rateLimitProperties.getLogin().setCapacity(1);
        mvc.perform(post(LOGIN).contentType("application/json")
                        .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"password\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(LOGIN).contentType("application/json")
                        .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"password\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Rate limit exceeded"));
    }

    @Test
    void rateLimitsAreIndependentForLoginRefreshLogoutAndMe() throws Exception {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        // 1. Build all required sessions first under normal capacity
        LoginSession refreshSession = login("manager@nightvenue.demo");
        LoginSession logoutSession = login("cashier@nightvenue.demo");
        LoginSession meSession = login("customer@nightvenue.demo");

        // 2. Set login capacity to 1
        rateLimitProperties.getLogin().setCapacity(1);

        // 3. First login succeeds, second login is blocked (429)
        mvc.perform(post(LOGIN).contentType("application/json")
                        .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"password\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(LOGIN).contentType("application/json")
                        .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"password\"}"))
                .andExpect(status().isTooManyRequests());

        // 4. Assert refresh, logout, and me still pass WITHOUT any flushDb in between
        refresh(refreshSession.refreshToken()).andExpect(status().isOk());
        mvc.perform(post(LOGOUT).cookie(new Cookie("nv_refresh", logoutSession.refreshToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get(ME).header("Authorization", "Bearer " + meSession.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void authEndpointsReturn503WhenRedisUnavailable() throws Exception {
        LoginSession session = login("admin@nightvenue.demo");
        var docker = DockerClientFactory.instance().client();
        String containerId = redisContainer.getContainerId();
        docker.pauseContainerCmd(containerId).exec();
        try {
            assertRedisUnavailable(mvc.perform(post(LOGIN).contentType("application/json")
                    .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"password\"}"))
                    .andReturn().getResponse());
            assertRedisUnavailable(mvc.perform(post(REFRESH)
                    .cookie(new Cookie("nv_refresh", session.refreshToken())))
                    .andReturn().getResponse());
            assertRedisUnavailable(mvc.perform(get(ME)
                    .header("Authorization", "Bearer " + session.accessToken()))
                    .andReturn().getResponse());
        } finally {
            docker.unpauseContainerCmd(containerId).exec();
            boolean redisReady = false;
            for (int attempt = 0; attempt < 30 && !redisReady; attempt++) {
                try {
                    redis.getConnectionFactory().getConnection().ping();
                    redisReady = true;
                } catch (RuntimeException ex) {
                    Thread.sleep(100);
                }
            }
            assertTrue(redisReady, "Redis did not recover after restart");
        }
    }

    @Test
    void forwardedForIsNotUsedDirectlyAndSecureCookieFollowsConfiguration() throws Exception {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        rateLimitProperties.getLogin().setCapacity(1);
        mvc.perform(post(LOGIN).header("X-Forwarded-For", "203.0.113.10")
                        .contentType("application/json")
                        .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"invalid\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(LOGIN).header("X-Forwarded-For", "198.51.100.23")
                        .contentType("application/json")
                        .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"invalid\"}"))
                .andExpect(status().isTooManyRequests());

        authProperties.setCookieSecure(true);
        try {
            mvc.perform(post(LOGIN).contentType("application/json")
                            .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"password\"}"))
                    .andExpect(status().isTooManyRequests());
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
            mvc.perform(post(LOGIN).contentType("application/json")
                            .content("{\"email\":\"admin@nightvenue.demo\",\"password\":\"password\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Set-Cookie",
                            org.hamcrest.Matchers.containsString("; Secure")));
        } finally {
            authProperties.setCookieSecure(false);
        }
    }

    @Test
    void trustedProxyForwardedAddressSeparatesRealHttpRateLimitBuckets() throws Exception {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        rateLimitProperties.getLogin().setCapacity(1);
        HttpClient client = HttpClient.newHttpClient();
        assertEquals(401, realLogin(client, "203.0.113.10").statusCode());
        assertEquals(429, realLogin(client, "203.0.113.10").statusCode());
        assertEquals(401, realLogin(client, "198.51.100.23").statusCode());
    }

    @Test
    void seededDemoAccountPassesRealHttpLoginSmoke() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + LOGIN))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"admin@nightvenue.demo\",\"password\":\"password\"}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        String accessToken = json.get("accessToken").asText();
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        String refreshToken = setCookie.substring("nv_refresh=".length(), setCookie.indexOf(';'));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertTrue(setCookie.contains("Path=/api/v1/auth"));
        assertTrue(setCookie.contains("Max-Age=604800"));
        assertFalse(setCookie.contains("; Secure"));
        assertFalse(response.body().contains("nv_refresh"));

        HttpRequest meRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ME))
                .header("Authorization", "Bearer " + accessToken)
                .GET().build();
        assertEquals(200, client.send(meRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
        System.out.println("HTTP " + response.statusCode()
                + "\nSet-Cookie: " + setCookie.replace(refreshToken, "[REDACTED]")
                + "\nBody: " + response.body().replace(accessToken, "[REDACTED]"));
    }

      @Test
    void sensitiveAuthenticationValuesNeverAppearInCapturedLogs() throws Exception {
        String passwordSecret = "NV_PHASE3_LOG_SECRET_7pJ4kQ9x";
        Logger appLogger = (Logger) LoggerFactory.getLogger("com.nightvenue.api");
        ch.qos.logback.classic.Level originalLevel = appLogger.getLevel();
        appLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level originalRootLevel = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.DEBUG);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            LoginSession session = login("admin@nightvenue.demo");
            mvc.perform(get(ME).header("Authorization", "Bearer " + session.accessToken()))
                    .andExpect(status().isOk());
            var refreshed = refresh(session.refreshToken()).andExpect(status().isOk()).andReturn();
            String rotatedToken = refreshed.getResponse().getCookie("nv_refresh").getValue();
            mvc.perform(post(LOGIN).contentType("application/json")
                            .content("{\"email\":\"missing@nightvenue.demo\",\"password\":\"" + passwordSecret + "\"}"))
                    .andExpect(status().isUnauthorized());

            // Force a failure path that records an exception/throwable with a stack trace.
            // Sent with real Authorization and Cookie headers to prove neither leaks into logs.
            mvc.perform(get("/test/logging/exception")
                            .header("Authorization", "Bearer " + session.accessToken())
                            .cookie(new Cookie("nv_refresh", rotatedToken)))
                    .andExpect(status().isOk());

            StringBuilder logBuilder = new StringBuilder();
            int throwableEvents = 0;
            for (ILoggingEvent event : appender.list) {
                logBuilder.append(event.getFormattedMessage()).append("\n");
                if (event.getThrowableProxy() != null) {
                    throwableEvents++;
                    var tp = event.getThrowableProxy();
                    logBuilder.append(tp.getClassName()).append(": ").append(tp.getMessage()).append("\n");
                    if (tp.getStackTraceElementProxyArray() != null) {
                        for (var step : tp.getStackTraceElementProxyArray()) {
                            logBuilder.append(step.getSTEAsString()).append("\n");
                        }
                    }
                }
            }
            String logs = logBuilder.toString();

            assertTrue(throwableEvents > 0, "Expected at least one logged throwable to inspect its stack trace");
            assertFalse(logs.contains(passwordSecret), "Password must never appear in logs or stack traces");
            assertFalse(logs.contains(session.accessToken()), "Access token must never appear in logs or stack traces");
            assertFalse(logs.contains(session.refreshToken()), "Refresh token must never appear in logs or stack traces");
            assertFalse(logs.contains(rotatedToken), "Rotated refresh token must never appear in logs or stack traces");
            assertFalse(logs.contains("nv_refresh=" + session.refreshToken()), "Cookie must never appear in logs or stack traces");
            assertFalse(logs.contains("Authorization: Bearer " + session.accessToken()), "Auth header value must never appear in logs or stack traces");
        } finally {
            root.detachAppender(appender);
            appender.stop();
            root.setLevel(originalRootLevel);
            appLogger.setLevel(originalLevel);
        }
    }

    @Test
    void healthRemainsPublicAndOpenApiIsPrivateByDefault() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").exists()); // in test profile show-details is always
        mvc.perform(get("/openapi.yaml")).andExpect(status().isUnauthorized());
    }

    /**
     * Trade-off test: Stateless JWT claims (role) are trusted until access token expiry (15m window).
     * When user role is downgraded in the database from MANAGER to CASHIER:
     * - The existing access token STILL returns 200 on manager-only endpoints until it expires.
     * - Upon rotating via /refresh, the new access token reflects the updated role from DB and returns 403.
     */
    @Test
    void statelessJwtRoleTradeOffAllowsOldRoleUntilRefresh() throws Exception {
        // Reset role to MANAGER first
        jdbc.update("UPDATE users SET role = 'MANAGER' WHERE email = 'manager@nightvenue.demo'");

        LoginSession managerSession = login("manager@nightvenue.demo");
        // Verify manager can access manager-only endpoint
        mvc.perform(get("/test/roles/manager-only")
                        .header("Authorization", "Bearer " + managerSession.accessToken()))
                .andExpect(status().isOk());

        // Downgrade role in the database directly
        jdbc.update("UPDATE users SET role = 'CASHIER' WHERE email = 'manager@nightvenue.demo'");
        try {
            // Documented stateless JWT trade-off: old access token remains valid (200) until expiry
            mvc.perform(get("/test/roles/manager-only")
                            .header("Authorization", "Bearer " + managerSession.accessToken()))
                    .andExpect(status().isOk());

            // After refresh, database role is re-read and new access token is issued with CASHIER role
            var refreshed = refresh(managerSession.refreshToken())
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode body = objectMapper.readTree(refreshed.getResponse().getContentAsString());
            String newAccessToken = body.get("accessToken").asText();

            // New access token is now forbidden (403) on manager-only endpoint
            mvc.perform(get("/test/roles/manager-only")
                            .header("Authorization", "Bearer " + newAccessToken))
                    .andExpect(status().isForbidden());
        } finally {
            // Restore original role
            jdbc.update("UPDATE users SET role = 'MANAGER' WHERE email = 'manager@nightvenue.demo'");
        }
    }

    private String loginFailure(String body) throws Exception {
        return mvc.perform(post(LOGIN).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    private LoginSession login(String email) throws Exception {
        var result = mvc.perform(post(LOGIN).contentType("application/json")
                        .content(objectMapper.writeValueAsString(new AuthController.LoginRequest(email, "password"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String cookie = result.getResponse().getCookie("nv_refresh").getValue();
        return new LoginSession(json.get("accessToken").asText(), cookie);
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String token) throws Exception {
        return mvc.perform(post(REFRESH).cookie(new Cookie("nv_refresh", token)));
    }

    private HttpResponse<String> realLogin(HttpClient client, String forwardedFor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + LOGIN))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"missing@nightvenue.demo\",\"password\":\"wrong\"}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertRedisUnavailable(
            org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        assertTrue(response.getContentAsString().contains("\"status\":503"));
        assertTrue(response.getContentAsString().contains("Authentication service unavailable"));
    }

    private String unsignedNoneToken() {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                "{\"sub\":\"1\",\"iss\":\"nightvenue\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    private void addDemoVenueAssignments() {
        jdbc.update("""
                INSERT INTO user_venues(user_id, venue_id)
                SELECT u.id, v.id FROM users u CROSS JOIN venues v
                WHERE u.email IN ('admin@nightvenue.demo', 'manager@nightvenue.demo', 'cashier@nightvenue.demo')
                ON CONFLICT DO NOTHING
                """);
    }

    private record LoginSession(String accessToken, String refreshToken) {
    }

    private record RefreshAttempt(int status, String refreshToken) {
    }
}
