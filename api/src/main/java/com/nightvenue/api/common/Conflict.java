package com.nightvenue.api.common;

public class Conflict extends RuntimeException {
    public Conflict(String message) {
        super(message);
    }
}
