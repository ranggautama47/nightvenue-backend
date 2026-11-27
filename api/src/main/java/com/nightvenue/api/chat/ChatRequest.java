package com.nightvenue.api.chat;

public record ChatRequest(Long venueId, String message) {
    @Override
    public String toString() {
        return "ChatRequest[venueId=" + venueId + ", message=[REDACTED]]";
    }
}
