package com.nightvenue.api.chat;

import com.nightvenue.api.common.BusinessRuleViolation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private static final int MAX_MESSAGE_CODE_POINTS = 500;
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request == null || request.venueId() == null || request.venueId() <= 0) {
            throw new BusinessRuleViolation("venueId must be a positive integer");
        }
        if (request.message() == null) {
            throw new BusinessRuleViolation("message must not be null");
        }
        String stripped = request.message().strip();
        if (stripped.isEmpty()) {
            throw new BusinessRuleViolation("message must not be empty");
        }
        if (stripped.codePointCount(0, stripped.length()) > MAX_MESSAGE_CODE_POINTS) {
            throw new BusinessRuleViolation("message must not exceed 500 code points");
        }
        return chatService.handle(request.venueId(), stripped);
    }
}
