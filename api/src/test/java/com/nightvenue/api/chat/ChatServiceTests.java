package com.nightvenue.api.chat;

import com.nightvenue.api.auth.AuthExceptions.VenueNotFound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class ChatServiceTests {

    private static final BigDecimal THETA = new BigDecimal("0.2663504950761663");
    private static final Instant FIXED_NOW = Instant.parse("2026-06-01T20:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final long VENUE_ID = 42L;
    private static final PublicVenueProfile VENUE =
            new PublicVenueProfile("Moon Harbor", "North District", "{\"mon\":[\"18:00\",\"02:00\"]}");

    private ChatRepository chatRepository;
    private ChatClassifierClient classifierClient;
    private ChatAnswerService answerService;
    private ChatFallbackMatcher fallbackMatcher;
    private ChatService service;

    @BeforeEach
    void setUp() {
        chatRepository = mock(ChatRepository.class);
        classifierClient = mock(ChatClassifierClient.class);
        answerService = mock(ChatAnswerService.class);
        fallbackMatcher = mock(ChatFallbackMatcher.class);

        ChatProperties props = new ChatProperties();
        props.setConfidenceThreshold(THETA);
        ChatRoutingPolicy routingPolicy = new ChatRoutingPolicy(props);

        service = new ChatService(
                chatRepository,
                classifierClient,
                routingPolicy,
                answerService,
                fallbackMatcher,
                FIXED_CLOCK
        );

        when(chatRepository.findActivePublicVenue(VENUE_ID)).thenReturn(Optional.of(VENUE));
        when(answerService.refusalAnswer()).thenReturn("Refusal");
        when(answerService.advancedAiRequiredAnswer()).thenReturn("Advanced AI required");
    }

    @Test
    void localIntentAtExactThetaRoutesLocalAndLogsAllFields() {
        when(classifierClient.classify("jam buka"))
                .thenReturn(new ChatClassifierOutcome(
                        new ChatClassification(ChatIntent.OPENING_HOURS, THETA, "v1"), 12));
        when(answerService.answerLocal(VENUE_ID, VENUE, ChatIntent.OPENING_HOURS))
                .thenReturn("Jam operasional: Senin 18:00-02:00");

        ChatResponse response = service.handle(VENUE_ID, "jam buka");

        assertEquals("LOCAL", response.route());
        assertEquals("OPENING_HOURS", response.intent());
        assertEquals(0, THETA.compareTo(response.confidence()));
        assertEquals("v1", response.modelVersion());
        verify(answerService).answerLocal(VENUE_ID, VENUE, ChatIntent.OPENING_HOURS);

        ArgumentCaptor<ChatRouteLogEntry> captor = ArgumentCaptor.forClass(ChatRouteLogEntry.class);
        verify(chatRepository, times(1)).insertChatRouteLog(captor.capture());
        ChatRouteLogEntry log = captor.getValue();
        assertEquals(VENUE_ID, log.venueId());
        assertEquals("LOCAL", log.route());
        assertEquals("OPENING_HOURS", log.intent());
        assertEquals(0, THETA.compareTo(log.confidence()));
        assertTrue(log.mlAvailable());
        assertEquals(12, log.classifierMs());
        assertNull(log.llmProvider());
        assertNull(log.llmMs());
        assertNull(log.inputTokens());
        assertNull(log.outputTokens());
        assertNull(log.estimatedCostUsd());
        assertEquals(FIXED_NOW, log.createdAt());
    }

    @Test
    void localIntentJustBelowThetaRoutesAdvancedAiRequired() {
        BigDecimal below = new BigDecimal("0.2663504950761662");
        when(classifierClient.classify("jam buka"))
                .thenReturn(new ChatClassifierOutcome(
                        new ChatClassification(ChatIntent.OPENING_HOURS, below, "v1"), 10));

        ChatResponse response = service.handle(VENUE_ID, "jam buka");

        assertEquals("ADVANCED_AI_REQUIRED", response.route());
        verify(answerService, never()).answerLocal(anyLong(), any(), any());
        verify(answerService).advancedAiRequiredAnswer();
        verify(chatRepository, times(1)).insertChatRouteLog(any());
    }

    @Test
    void advancedConversationAtConfidenceOneRoutesAdvancedAiRequired() {
        when(classifierClient.classify("rekomendasi"))
                .thenReturn(new ChatClassifierOutcome(
                        new ChatClassification(ChatIntent.ADVANCED_CONVERSATION, BigDecimal.ONE, "v1"), 15));

        ChatResponse response = service.handle(VENUE_ID, "rekomendasi");

        assertEquals("ADVANCED_AI_REQUIRED", response.route());
        verify(answerService, never()).answerLocal(anyLong(), any(), any());
        verify(answerService).advancedAiRequiredAnswer();
    }

    @Test
    void outOfScopeAtOrAboveThetaRoutesRefused() {
        when(classifierClient.classify("siapa presiden"))
                .thenReturn(new ChatClassifierOutcome(
                        new ChatClassification(ChatIntent.OUT_OF_SCOPE, THETA, "v1"), 8));

        ChatResponse response = service.handle(VENUE_ID, "siapa presiden");

        assertEquals("REFUSED", response.route());
        verify(answerService, never()).answerLocal(anyLong(), any(), any());
        verify(answerService).refusalAnswer();
    }

    @Test
    void outOfScopeBelowThetaRoutesAdvancedAiRequired() {
        BigDecimal below = new BigDecimal("0.2663504950761662");
        when(classifierClient.classify("apa kabar"))
                .thenReturn(new ChatClassifierOutcome(
                        new ChatClassification(ChatIntent.OUT_OF_SCOPE, below, "v1"), 8));

        ChatResponse response = service.handle(VENUE_ID, "apa kabar");

        assertEquals("ADVANCED_AI_REQUIRED", response.route());
        verify(answerService, never()).answerLocal(anyLong(), any(), any());
        verify(answerService).advancedAiRequiredAnswer();
    }

    @Test
    void classifierUnavailableWithFallbackMatchRoutesFallback() {
        when(classifierClient.classify("jam buka"))
                .thenReturn(new ChatClassifierOutcome(null, 2001));
        when(fallbackMatcher.match("jam buka"))
                .thenReturn(Optional.of(ChatFallbackCategory.OPENING_HOURS));
        when(answerService.answerFallback(VENUE_ID, VENUE, ChatFallbackCategory.OPENING_HOURS))
                .thenReturn("Jam operasional fallback");

        ChatResponse response = service.handle(VENUE_ID, "jam buka");

        assertEquals("FALLBACK", response.route());
        assertNull(response.intent());
        assertNull(response.confidence());
        assertNull(response.modelVersion());
        verify(answerService).answerFallback(VENUE_ID, VENUE, ChatFallbackCategory.OPENING_HOURS);

        ArgumentCaptor<ChatRouteLogEntry> captor = ArgumentCaptor.forClass(ChatRouteLogEntry.class);
        verify(chatRepository, times(1)).insertChatRouteLog(captor.capture());
        ChatRouteLogEntry log = captor.getValue();
        assertEquals("FALLBACK", log.route());
        assertNull(log.intent());
        assertNull(log.confidence());
        assertFalse(log.mlAvailable());
        assertEquals(2001, log.classifierMs());
    }

    @Test
    void classifierUnavailableWithoutFallbackMatchRoutesAdvancedAiRequired() {
        when(classifierClient.classify("pertanyaan acak"))
                .thenReturn(new ChatClassifierOutcome(null, 50));
        when(fallbackMatcher.match("pertanyaan acak")).thenReturn(Optional.empty());

        ChatResponse response = service.handle(VENUE_ID, "pertanyaan acak");

        assertEquals("ADVANCED_AI_REQUIRED", response.route());
        assertNull(response.intent());
        assertNull(response.confidence());
        assertNull(response.modelVersion());

        ArgumentCaptor<ChatRouteLogEntry> captor = ArgumentCaptor.forClass(ChatRouteLogEntry.class);
        verify(chatRepository, times(1)).insertChatRouteLog(captor.capture());
        ChatRouteLogEntry log = captor.getValue();
        assertEquals("ADVANCED_AI_REQUIRED", log.route());
        assertNull(log.intent());
        assertNull(log.confidence());
        assertFalse(log.mlAvailable());
        assertEquals(50, log.classifierMs());
    }

    @Test
    void venueNotFoundThrowsWithoutCallingClassifierOrInsert() {
        when(chatRepository.findActivePublicVenue(999L)).thenReturn(Optional.empty());

        assertThrows(VenueNotFound.class, () -> service.handle(999L, "jam buka"));
        verifyNoInteractions(classifierClient);
        verify(chatRepository, never()).insertChatRouteLog(any());
    }

    @Test
    void routeLogInsertFailureThrowsChatServiceUnavailable() {
        when(classifierClient.classify("jam buka"))
                .thenReturn(new ChatClassifierOutcome(
                        new ChatClassification(ChatIntent.OPENING_HOURS, THETA, "v1"), 5));
        when(answerService.answerLocal(VENUE_ID, VENUE, ChatIntent.OPENING_HOURS))
                .thenReturn("Jam operasional");
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(chatRepository).insertChatRouteLog(any());

        assertThrows(ChatServiceUnavailable.class, () -> service.handle(VENUE_ID, "jam buka"));
    }

    @Test
    void chatRequestToStringRedactsMessage() {
        ChatRequest req = new ChatRequest(1L, "SUPER_SECRET_MESSAGE_123");
        String str = req.toString();
        assertFalse(str.contains("SUPER_SECRET_MESSAGE_123"));
        assertTrue(str.contains("1"));
    }
}
