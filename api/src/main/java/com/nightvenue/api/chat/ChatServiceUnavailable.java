package com.nightvenue.api.chat;

public class ChatServiceUnavailable extends RuntimeException {
    private static final String DEFAULT_MESSAGE = "Chat service temporarily unavailable";

    public ChatServiceUnavailable() {
        super(DEFAULT_MESSAGE);
    }

    public ChatServiceUnavailable(Throwable cause) {
        super(DEFAULT_MESSAGE, cause);
    }
}
