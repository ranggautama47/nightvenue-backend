package com.nightvenue.api.chat;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;

@Service
public class ChatService {
    private final ChatRepository chatRepository;
    private final ChatClassifierClient classifierClient;
    private final ChatRoutingPolicy routingPolicy;
    private final ChatAnswerService answerService;
    private final ChatFallbackMatcher fallbackMatcher;
    private final Clock clock;

    public ChatService(
            ChatRepository chatRepository,
            ChatClassifierClient classifierClient,
            ChatRoutingPolicy routingPolicy,
            ChatAnswerService answerService,
            ChatFallbackMatcher fallbackMatcher,
            Clock clock) {
        this.chatRepository = chatRepository;
        this.classifierClient = classifierClient;
        this.routingPolicy = routingPolicy;
        this.answerService = answerService;
        this.fallbackMatcher = fallbackMatcher;
        this.clock = clock;
    }

    public ChatResponse handle(long venueId, String strippedMessage) {
        PublicVenueProfile venue = chatRepository.findActivePublicVenue(venueId)
                .orElseThrow(VenueNotFound::new);

        ChatClassifierOutcome outcome = classifierClient.classify(strippedMessage);

        ChatRoute route;
        String answer;
        boolean mlAvailable;
        String intent;
        BigDecimal confidence;
        String modelVersion;

        if (outcome.available()) {
            ChatClassification classification = outcome.classification();
            route = routingPolicy.route(classification);
            answer = switch (route) {
                case LOCAL -> answerService.answerLocal(venueId, venue, classification.intent());
                case REFUSED -> answerService.refusalAnswer();
                case ADVANCED_AI_REQUIRED -> answerService.advancedAiRequiredAnswer();
                default -> throw new IllegalStateException("Unexpected route for classifier outcome");
            };
            mlAvailable = true;
            intent = classification.intent().name();
            confidence = classification.confidence();
            modelVersion = classification.modelVersion();
        } else {
            Optional<ChatFallbackCategory> category = fallbackMatcher.match(strippedMessage);
            route = routingPolicy.routeOnClassifierFailure(category.isPresent());
            answer = switch (route) {
                case FALLBACK -> answerService.answerFallback(venueId, venue, category.orElseThrow());
                case ADVANCED_AI_REQUIRED -> answerService.advancedAiRequiredAnswer();
                default -> throw new IllegalStateException("Unexpected route on classifier failure");
            };
            mlAvailable = false;
            intent = null;
            confidence = null;
            modelVersion = null;
        }

        try {
            chatRepository.insertChatRouteLog(new ChatRouteLogEntry(
                    venueId,
                    route.name(),
                    intent,
                    confidence,
                    mlAvailable,
                    outcome.elapsedMs(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    clock.instant()
            ));
        } catch (DataAccessException ignored) {
            // No cause on purpose: ApiExceptionHandler resolves handlers through the cause chain
            // and would override the chat-specific 503 (and could expose a 500).
            throw new ChatServiceUnavailable();
        }

        return new ChatResponse(answer, route.name(), intent, confidence, modelVersion);
    }
}
