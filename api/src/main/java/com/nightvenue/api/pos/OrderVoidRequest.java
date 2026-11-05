package com.nightvenue.api.pos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OrderVoidRequest(
        @NotBlank @Size(max = 255) String reason,
        String disposition) {
    public OrderVoidRequest {
        if (disposition != null && !disposition.equals("RESTORED") && !disposition.equals("WASTED")) {
            throw new IllegalArgumentException("disposition must be RESTORED or WASTED");
        }
    }

    public String effectiveDisposition() {
        return disposition == null ? "RESTORED" : disposition;
    }
}
