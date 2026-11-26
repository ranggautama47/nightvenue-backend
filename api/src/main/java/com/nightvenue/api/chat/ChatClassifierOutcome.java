package com.nightvenue.api.chat;

public record ChatClassifierOutcome(ChatClassification classification, int elapsedMs) {
    public boolean available() {
        return classification != null;
    }
}
