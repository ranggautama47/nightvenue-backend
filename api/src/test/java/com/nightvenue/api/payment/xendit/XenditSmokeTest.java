package com.nightvenue.api.payment.xendit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M3-B sandbox smoke test. WRITE ONLY by default: it is skipped unless the
 * build is started with -Dxendit.smoke=true AND a non-blank XENDIT_SECRET_KEY
 * environment variable is present. The owner runs it later under a separate
 * approval.
 *
 * <p>Safety: exactly ONE Create attempt (no retries), and it never prints the
 * secret, the Authorization header, the QR string, or the response body.
 */
class XenditSmokeTest {

    @Test
    @EnabledIfSystemProperty(named = "xendit.smoke", matches = "true")
    void singleCreateAgainstProviderSandbox() throws Exception {
        String secret = System.getenv("XENDIT_SECRET_KEY");
        assumeTrue(secret != null && !secret.isBlank(), "XENDIT_SECRET_KEY is not set: smoke test skipped");
        String baseUrl = envOrDefault("XENDIT_BASE_URL", "https://api.xendit.co");
        String apiVersion = envOrDefault("XENDIT_API_VERSION", "2024-11-11");
        String referenceId = "nv_smoke_" + UUID.randomUUID().toString().replace("-", "");

        String payload = """
                {"reference_id":"%s","type":"PAY","country":"ID","currency":"IDR","request_amount":10000,"channel_code":"QRIS"}
                """.formatted(referenceId);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v3/payment_requests"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((secret + ":").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("api-version", apiVersion)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        boolean paymentRequestIdPresent = response.body().contains("\"id\"");
        boolean qrPresent = response.body().contains("QR_STRING");
        // only non-sensitive facts are reported
        System.out.println("xendit smoke: referenceId=" + referenceId
                + " httpStatus=" + response.statusCode()
                + " paymentRequestIdPresent=" + paymentRequestIdPresent
                + " qrPresent=" + qrPresent);
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "smoke create failed with http status " + response.statusCode());
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
