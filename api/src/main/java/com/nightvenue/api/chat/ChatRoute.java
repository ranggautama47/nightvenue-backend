package com.nightvenue.api.chat;

public enum ChatRoute {
    LOCAL,
    FALLBACK,
    LLM, // LLM is reserved for Phase 10.4; Phase 10.3 never returns it.
    REFUSED,
    ADVANCED_AI_REQUIRED
}
