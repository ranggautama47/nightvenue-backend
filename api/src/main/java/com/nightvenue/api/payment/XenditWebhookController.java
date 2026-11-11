package com.nightvenue.api.payment;

import com.nightvenue.api.payment.xendit.XenditClient;
import com.nightvenue.api.payment.xendit.XenditProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/xendit")
public class XenditWebhookController {
    private final XenditProperties properties;
    private final XenditClient client;
    private final XenditWebhookService service;
    private final ObjectMapper mapper;

    public XenditWebhookController(XenditProperties properties, XenditClient client,
                                   XenditWebhookService service, ObjectMapper mapper) {
        this.properties = properties; this.client = client; this.service = service; this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader(value = "x-callback-token", required = false) String token,
                                        @RequestBody(required = false) String body) {
        if (!constantTimeEquals(properties.getCallbackToken(), token)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Map<String,Object> root;
        try {
            root = mapper.readValue(body, new TypeReference<>() {});
            if (root == null || blank(root.get("event")))
                return ResponseEntity.badRequest().build();
        } catch (Exception malformed) { return ResponseEntity.badRequest().build(); }

        String event = String.valueOf(root.get("event"));
        if (!isSupported(event)) return ResponseEntity.ok().build();
        if (!(root.get("data") instanceof Map<?,?> rawData)) return ResponseEntity.badRequest().build();
        Map<String,Object> data = mapper.convertValue(rawData, new TypeReference<>() {});
        String requestId = string(data.get("payment_request_id"));
        if (blank(requestId)) return ResponseEntity.badRequest().build();
        String reference = string(data.get("reference_id"));
        Payment matched = blank(reference) ? null : service.findByReference(reference).orElse(null);
        if (matched == null && !blank(reference)) matched = service.findByRequestId(requestId).orElse(null);

        XenditClient.VerificationResult verified = client.getPaymentRequest(requestId);
        if (verified.kind() != XenditClient.VerificationKind.VERIFIED) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        if (matched == null && !blank(verified.referenceId())) matched = service.findByReference(verified.referenceId()).orElse(null);
        if (matched == null) matched = service.findByRequestId(requestId).orElse(null);
        if (matched == null) {
            service.unmatched(event, data);
            return ResponseEntity.ok().build();
        }
        service.apply(matched.id(), event, data, verified);
        return ResponseEntity.ok().build();
    }

    private static boolean isSupported(String event) { return event.equals("payment.capture") || event.equals("payment.failure"); }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static boolean blank(Object value) { return value == null || String.valueOf(value).isBlank(); }
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return false;
        return MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
