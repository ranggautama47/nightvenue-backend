package com.nightvenue.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

/**
 * NightVenue API Application entry point.
 *
 * <p>Package root: com.nightvenue.api — no business packages created in Phase 1.
 * JPA, Flyway, and Security are excluded intentionally (added in Phase 2/3).
 */
@SpringBootApplication
public class NightVenueApiApplication {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(NightVenueApiApplication.class, args);
    }
}
