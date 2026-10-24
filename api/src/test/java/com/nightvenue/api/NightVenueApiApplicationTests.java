package com.nightvenue.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the Spring application context loads successfully
 * with real PostgreSQL and Redis containers via Testcontainers.
 *
 * <p>If Docker is unavailable locally, this test will fail with a
 * container startup error. CI always runs Docker; mark NOT VERIFIED locally.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class NightVenueApiApplicationTests {

    @Test
    void contextLoads() {
        // If Spring context loads without exception, the test passes.
    }
}

