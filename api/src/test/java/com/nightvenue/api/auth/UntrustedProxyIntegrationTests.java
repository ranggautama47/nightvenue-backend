package com.nightvenue.api.auth;

import com.nightvenue.api.TestcontainersConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UntrustedProxyIntegrationTests {
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String LOOPBACK_ONLY = "127[.][0-9]+[.][0-9]+[.][0-9]+|::1|0:0:0:0:0:0:0:1";

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "nightvenue.seed.enabled=true",
                    "nightvenue.ratelimit.login.capacity=1"
            })
    @Testcontainers
    @ActiveProfiles("test")
    @Import(TestcontainersConfig.class)
    class DefaultLoopbackConfiguration {
        @Autowired StringRedisTemplate redis;
        @Value("${server.tomcat.remoteip.internal-proxies}") String trustedProxies;
        @LocalServerPort int port;

        @BeforeEach
        void clearRateLimitState() {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        }

        @Test
        void defaultConfigurationTrustsOnlyLoopback() {
            // application.yml resolves ${APP_TRUSTED_PROXIES:...} to the loopback regex when unset.
            assertEquals(LOOPBACK_ONLY, trustedProxies);
        }

        @Test
        void xForwardedForFromUntrustedPeerIsIgnoredUnderDefaultConfiguration() throws Exception {
            // A loopback peer would itself be trusted, so this test deliberately connects through the
            // host's non-loopback address to mirror the production/Docker case where the direct peer
            // (e.g. 10.x or 172.x bridge) is not in the loopback-only trusted set.
            HttpClient client = HttpClient.newHttpClient();
            String baseUrl = reachableNonLoopbackBaseUrl(client, port);
            Assumptions.assumeTrue(baseUrl != null,
                    "No reachable non-loopback IPv4 address available to simulate an untrusted peer");

            // XFF claims two different clients, but the untrusted peer means the bucket stays the peer's.
            assertEquals(401, sendLogin(baseUrl, client, "10.0.0.1").statusCode());
            assertEquals(429, sendLogin(baseUrl, client, "172.16.0.5").statusCode());
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "nightvenue.seed.enabled=true",
                    "nightvenue.ratelimit.login.capacity=1",
                    // Exercises the real binding used in compose/production:
                    // server.tomcat.remoteip.internal-proxies resolves ${APP_TRUSTED_PROXIES:...}
                    "APP_TRUSTED_PROXIES=127[.][0-9]+[.][0-9]+[.][0-9]+|10[.]0[.]0[.]1"
            })
    @Testcontainers
    @ActiveProfiles("test")
    @Import(TestcontainersConfig.class)
    class ConfiguredTrustedProxyConfiguration {
        @Autowired StringRedisTemplate redis;
        @LocalServerPort int port;

        @BeforeEach
        void clearRateLimitState() {
            redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        }

        @Test
        void xForwardedForIsHonoredWhenProxyMatchesConfiguredRange() throws Exception {
            HttpClient client = HttpClient.newHttpClient();
            // The loopback peer matches the configured trusted range, so XFF yields distinct client buckets.
            assertEquals(401, sendLogin(baseUrl(), client, "203.0.113.10").statusCode());
            assertEquals(401, sendLogin(baseUrl(), client, "198.51.100.23").statusCode());
            // Same client IP hits rate limit
            assertEquals(429, sendLogin(baseUrl(), client, "203.0.113.10").statusCode());
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + port;
        }
    }

    private static HttpResponse<String> sendLogin(String baseUrl, HttpClient client, String forwardedFor)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + LOGIN))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"missing@nightvenue.demo\",\"password\":\"wrong\"}"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Finds a non-loopback base URL the test JVM can reach itself on. Probing uses the
     * non-rate-limited health endpoint so it cannot consume a login bucket token.
     */
    private static String reachableNonLoopbackBaseUrl(HttpClient client, int port) {
        for (String host : nonLoopbackIpv4Candidates()) {
            try {
                HttpRequest probe = HttpRequest.newBuilder(
                                URI.create("http://" + host + ":" + port + "/actuator/health"))
                        .timeout(java.time.Duration.ofSeconds(3))
                        .GET()
                        .build();
                if (client.send(probe, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return "http://" + host + ":" + port;
                }
            } catch (Exception ignored) {
                // try the next candidate address
            }
        }
        return null;
    }

    private static java.util.List<String> nonLoopbackIpv4Candidates() {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()
                            && !address.isMulticastAddress()) {
                        candidates.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
            // return whatever was collected
        }
        return candidates;
    }
}
