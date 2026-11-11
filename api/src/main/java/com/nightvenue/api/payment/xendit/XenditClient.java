package com.nightvenue.api.payment.xendit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Raw HTTP client for Xendit Payment Requests v3 (POST /v3/payment_requests).
 * No SDK. NightVenue error classification policy (NOT a provider guarantee):
 * only the documented validation 400 codes are REJECTED; everything else
 * (rate limit, duplicate, auth, 5xx, timeout, unreadable body, missing QR)
 * is AMBIGUOUS. Never retries Create. Never releases caller state.
 */
@Component
public class XenditClient {
    private static final Logger log = LoggerFactory.getLogger(XenditClient.class);

    /** Documented validation 400 codes: provider confirms no payment request was created. */
    private static final Set<String> REJECTED_400_CODES = Set.of(
            "INVALID_VALUE_ERROR", "API_VALIDATION_ERROR", "INVALID_PAYMENT_DETAILS");

    public enum Outcome { CONFIRMED, REJECTED, AMBIGUOUS }
    public enum VerificationKind { VERIFIED, NOT_FOUND, TRANSIENT, AUTH_FAILURE, MALFORMED }
    public record VerificationResult(VerificationKind kind, String requestId, String referenceId,
                                     String status, Long amount, String currency, String country,
                                     String channelCode, String type, String paymentId) {}

    /**
     * Outcome of one Create attempt. httpStatus is 0 when no HTTP response was
     * produced (timeout/connection drop). paymentRequestId is only set when it
     * is non-blank and at most 39 characters (documented payment_request_id length).
     */
    public record CreateResult(Outcome outcome, String paymentRequestId, String qrString,
                               int httpStatus, String errorCode) {}

    private final RestClient restClient;
    private final XenditProperties properties;
    private final ObjectMapper mapper;

    public XenditClient(RestClient xenditRestClient, XenditProperties properties, ObjectMapper mapper) {
        this.restClient = xenditRestClient;
        this.properties = properties;
        this.mapper = mapper;
    }

    public CreateResult createQris(String referenceId, long amountRupiah) {
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri("/v3/payment_requests")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .header("api-version", properties.getApiVersion())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildCreatePayload(referenceId, amountRupiah))
                    .retrieve()
                    .toEntity(String.class);
            CreateResult result = parseSuccess(response.getStatusCode().value(), response.getBody());
            log.info("xendit create referenceId={} httpStatus={} outcome={} paymentRequestId={} errorCode={}",
                    referenceId, result.httpStatus(), result.outcome(), result.paymentRequestId(), result.errorCode());
            return result;
        } catch (RestClientResponseException e) {
            CreateResult result = classifyError(e.getStatusCode().value(), e.getResponseBodyAsString());
            log.info("xendit create referenceId={} httpStatus={} outcome={} paymentRequestId={} errorCode={}",
                    referenceId, result.httpStatus(), result.outcome(), result.paymentRequestId(), result.errorCode());
            return result;
        } catch (RestClientException e) {
            // timeout, connection drop, unreadable response: provider state unknown
            // (ResourceAccessException is a subclass; RestClientResponseException was handled above)
            log.info("xendit create referenceId={} httpStatus=0 outcome=AMBIGUOUS transportFailure={}",
                    referenceId, e.getClass().getSimpleName());
            return new CreateResult(Outcome.AMBIGUOUS, null, null, 0, null);
        }
    }

    public VerificationResult getPaymentRequest(String requestId) {
        if (!properties.isConfigured()) return new VerificationResult(VerificationKind.AUTH_FAILURE, null,null,null,null,null,null,null,null,null);
        try {
            String body = restClient.get().uri("/v3/payment_requests/{id}", requestId)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth()).header("api-version", properties.getApiVersion())
                    .retrieve().body(String.class);
            Map<String,Object> root = mapper.readValue(body, new TypeReference<>() {});
            String id = stringValue(root.get("id"));
            String status = stringValue(root.get("status"));
            String reference = stringValue(root.get("reference_id"));
            Long amount = numberValue(root.get("request_amount"));
            String currency = stringValue(root.get("currency"));
            String country = stringValue(root.get("country"));
            String channel = stringValue(root.get("channel_code"));
            String type = stringValue(root.get("type"));
            if (id == null || !id.equals(requestId) || status == null || reference == null || amount == null
                    || currency == null || country == null || channel == null || type == null)
                return new VerificationResult(VerificationKind.MALFORMED,null,null,null,null,null,null,null,null,null);
            return new VerificationResult(VerificationKind.VERIFIED,id,reference,status,amount,
                    currency,country,channel,type,stringValue(root.get("payment_id")));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return new VerificationResult(VerificationKind.NOT_FOUND,null,null,null,null,null,null,null,null,null);
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) return new VerificationResult(VerificationKind.AUTH_FAILURE,null,null,null,null,null,null,null,null,null);
            return new VerificationResult(VerificationKind.TRANSIENT,null,null,null,null,null,null,null,null,null);
        } catch (tools.jackson.core.JacksonException e) {
            return new VerificationResult(VerificationKind.MALFORMED,null,null,null,null,null,null,null,null,null);
        } catch (RestClientException e) {
            return new VerificationResult(VerificationKind.TRANSIENT,null,null,null,null,null,null,null,null,null);
        } catch (Exception e) {
            return new VerificationResult(VerificationKind.MALFORMED,null,null,null,null,null,null,null,null,null);
        }
    }

    /**
     * Request body builder, kept isolated: flat fields only, no nested
     * payment_method object, no provider idempotency header (NightVenue does
     * not claim provider-side dedup). Payload shape to be confirmed by the
     * sandbox smoke test.
     */
    String buildCreatePayload(String referenceId, long amountRupiah) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reference_id", referenceId);
        payload.put("type", "PAY");
        payload.put("country", "ID");
        payload.put("currency", "IDR");
        payload.put("request_amount", amountRupiah);
        payload.put("channel_code", "QRIS");
        return mapper.writeValueAsString(payload);
    }

    private CreateResult parseSuccess(int httpStatus, String body) {
        String paymentRequestId = null;
        String qrString = null;
        try {
            Map<String, Object> root = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
            paymentRequestId = normalizedRequestId(stringValue(root.get("id")));
            qrString = extractQrString(root);
        } catch (Exception unreadable) {
            // unreadable body: treat exactly like a missing id/QR below
        }
        if (paymentRequestId == null) {
            return new CreateResult(Outcome.AMBIGUOUS, null, null, httpStatus, null);
        }
        if (qrString == null) {
            // 2xx with a usable id but no QR action: do NOT discard the id
            return new CreateResult(Outcome.AMBIGUOUS, paymentRequestId, null, httpStatus, null);
        }
        return new CreateResult(Outcome.CONFIRMED, paymentRequestId, qrString, httpStatus, null);
    }

    private CreateResult classifyError(int httpStatus, String responseBody) {
        String errorCode = extractErrorCode(responseBody);
        Outcome outcome = httpStatus == 400 && errorCode != null && REJECTED_400_CODES.contains(errorCode)
                ? Outcome.REJECTED
                : Outcome.AMBIGUOUS;
        return new CreateResult(outcome, null, null, httpStatus, errorCode);
    }

    /**
     * UNVERIFIED for Indonesian QRIS until the sandbox smoke test: looks for
     * an actions[] entry whose action/descriptor is QR_STRING and reads its
     * value/url/qr_string field defensively. Never throws.
     */
    static String extractQrString(Map<String, Object> root) {
        Object actions = root.get("actions");
        if (!(actions instanceof List<?> entries)) {
            return null;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> action)) {
                continue;
            }
            Object descriptor = stringValue(action.get("action"));
            if (descriptor == null) {
                descriptor = stringValue(action.get("descriptor"));
            }
            if (!"QR_STRING".equals(descriptor)) {
                continue;
            }
            for (String field : new String[]{"value", "url", "qr_string"}) {
                String value = stringValue(action.get(field));
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    /** 4.4: keep the id only when non-blank and <= 39 chars; otherwise treat as absent. */
    static String normalizedRequestId(String id) {
        if (id == null || id.isBlank() || id.length() > 39) {
            return null;
        }
        return id;
    }

    private String extractErrorCode(String responseBody) {
        try {
            Map<String, Object> root = mapper.readValue(responseBody, new TypeReference<Map<String, Object>>() {});
            for (String field : new String[]{"errorCode", "error_code", "code"}) {
                String value = stringValue(root.get(field));
                if (value != null) {
                    return value;
                }
            }
        } catch (Exception unreadable) {
            return null;
        }
        return null;
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder()
                .encodeToString((properties.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8));
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long numberValue(Object value) {
        try { return value == null ? null : new java.math.BigDecimal(String.valueOf(value)).longValueExact(); }
        catch (NumberFormatException | ArithmeticException ignored) { return null; }
    }
}
