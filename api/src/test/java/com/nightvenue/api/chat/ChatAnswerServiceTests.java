package com.nightvenue.api.chat;

import com.nightvenue.api.businessday.BusinessDateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatAnswerServiceTests {

    // Fixed clock: 2026-06-01T20:00:00Z
    // Asia/Jakarta = UTC+7, so local time = 2026-06-02T03:00:00+07
    // cutoffHour=6: businessDate = 03:00 - 6h = previous day = 2026-06-01
    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-06-01T20:00:00Z"), ZoneOffset.UTC);

    private ChatRepository chatRepo;
    private ChatVenueTimeRepository timeRepo;
    private ChatAnswerService service;

    private static final long VENUE_ID = 1L;
    private static final PublicVenueProfile VENUE =
        new PublicVenueProfile("Moon Harbor", "North District, Jakarta",
            "{\"mon\":[\"18:00\",\"02:00\"],\"tue\":[\"18:00\",\"02:00\"]," +
            "\"wed\":[\"18:00\",\"02:00\"],\"thu\":[\"18:00\",\"02:00\"]," +
            "\"fri\":[\"18:00\",\"03:00\"],\"sat\":[\"18:00\",\"03:00\"]," +
            "\"sun\":[\"18:00\",\"02:00\"]}");

    @BeforeEach
    void setUp() {
        chatRepo = mock(ChatRepository.class);
        timeRepo = mock(ChatVenueTimeRepository.class);
        BusinessDateService bds = new BusinessDateService(FIXED_CLOCK);
        service = new ChatAnswerService(chatRepo, timeRepo, bds);
    }

    // -------------------------------------------------------------------------
    // OPENING_HOURS
    // -------------------------------------------------------------------------

    @Test
    void openingHoursWithData() {
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.OPENING_HOURS);
        assertNotBlank(result);
        assertTrue(result.contains("Senin"), "Must contain Senin");
        assertTrue(result.contains("hingga keesokan harinya"), "Must mention next day");
        verify(chatRepo, never()).findActiveAnswerTemplate(anyLong(), anyString());
    }

    @Test
    void openingHoursNoData() {
        PublicVenueProfile v = new PublicVenueProfile("V", "A", null);
        String result = service.answerLocal(VENUE_ID, v, ChatIntent.OPENING_HOURS);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    // -------------------------------------------------------------------------
    // VENUE_AREA
    // -------------------------------------------------------------------------

    @Test
    void venueAreaWithData() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(List.of(
            new PublicTableInfo("Main Floor", 0, "AVAILABLE"),
            new PublicTableInfo("VIP", 5_000_000, "AVAILABLE"),
            new PublicTableInfo("Main Floor", 0, "OCCUPIED")
        ));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.VENUE_AREA);
        assertNotBlank(result);
        assertTrue(result.contains("Main Floor"));
        assertTrue(result.contains("VIP"));
        verify(chatRepo, never()).findActiveAnswerTemplate(anyLong(), anyString());
    }

    @Test
    void venueAreaNoData() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(Collections.emptyList());
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.VENUE_AREA);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    // -------------------------------------------------------------------------
    // MINIMUM_SPEND
    // -------------------------------------------------------------------------

    @Test
    void minimumSpendSingleValue() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(List.of(
            new PublicTableInfo("VIP", 1_500_000, "AVAILABLE"),
            new PublicTableInfo("VIP", 1_500_000, "OCCUPIED")
        ));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.MINIMUM_SPEND);
        assertNotBlank(result);
        assertTrue(result.contains("Rp 1.500.000"), "Must render Rp 1.500.000");
    }

    @Test
    void minimumSpendRange() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(List.of(
            new PublicTableInfo("VIP", 1_000_000, "AVAILABLE"),
            new PublicTableInfo("VIP", 2_000_000, "AVAILABLE")
        ));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.MINIMUM_SPEND);
        assertNotBlank(result);
        assertTrue(result.contains("Rp 1.000.000"), "Must contain lower bound");
        assertTrue(result.contains("Rp 2.000.000"), "Must contain upper bound");
    }

    @Test
    void minimumSpendNoData() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(Collections.emptyList());
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.MINIMUM_SPEND);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    // -------------------------------------------------------------------------
    // MENU_INFORMATION
    // -------------------------------------------------------------------------

    @Test
    void menuWithData() {
        when(chatRepo.findActiveProductsWithCategory(VENUE_ID)).thenReturn(List.of(
            new PublicProductInfo("Citrus Highball", "Cocktails", 95_000),
            new PublicProductInfo("Garden Mule", "Cocktails", 105_000)
        ));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.MENU_INFORMATION);
        assertNotBlank(result);
        assertTrue(result.contains("Cocktails"));
        assertTrue(result.contains("Citrus Highball"));
        assertTrue(result.contains("Rp 95.000"));
        verify(chatRepo, never()).findActiveAnswerTemplate(anyLong(), anyString());
    }

    @Test
    void menuNoData() {
        when(chatRepo.findActiveProductsWithCategory(VENUE_ID)).thenReturn(Collections.emptyList());
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.MENU_INFORMATION);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    @Test
    void menuTruncatesAtFivePerCategory() {
        List<PublicProductInfo> items = List.of(
            new PublicProductInfo("P1", "Cat", 100_000),
            new PublicProductInfo("P2", "Cat", 100_000),
            new PublicProductInfo("P3", "Cat", 100_000),
            new PublicProductInfo("P4", "Cat", 100_000),
            new PublicProductInfo("P5", "Cat", 100_000),
            new PublicProductInfo("P6", "Cat", 100_000)  // should be cut
        );
        when(chatRepo.findActiveProductsWithCategory(VENUE_ID)).thenReturn(items);
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.MENU_INFORMATION);
        assertTrue(result.contains("dan menu lainnya."), "Must append truncation notice");
        assertFalse(result.contains("P6"), "P6 must be omitted");
    }

    @Test
    void menuTruncatesAt30Total() {
        // 4 categories × 8 items = 32 items → truncation at 30
        List<PublicProductInfo> items = new java.util.ArrayList<>();
        for (int cat = 1; cat <= 4; cat++) {
            for (int i = 1; i <= 8; i++) {
                items.add(new PublicProductInfo("P" + cat + "_" + i, "Cat" + cat, 50_000));
            }
        }
        when(chatRepo.findActiveProductsWithCategory(VENUE_ID)).thenReturn(items);
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.MENU_INFORMATION);
        assertTrue(result.contains("dan menu lainnya."), "Must append truncation notice");
    }

    // -------------------------------------------------------------------------
    // PROMOTION
    // -------------------------------------------------------------------------

    @Test
    void promotionWithData() {
        VenueTimeContext ctx = new VenueTimeContext(ZoneId.of("Asia/Jakarta"), 6);
        when(timeRepo.findActiveVenueTime(VENUE_ID)).thenReturn(Optional.of(ctx));
        when(chatRepo.findActivePromotions(eq(VENUE_ID), eq(LocalDate.of(2026, 6, 1))))
            .thenReturn(List.of(
                new PromotionWindow(1L, "Welcome Visit Offer", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31))
            ));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.PROMOTION);
        assertNotBlank(result);
        assertTrue(result.contains("Welcome Visit Offer"));
        assertTrue(result.contains("2026-12-31"));
        verify(chatRepo, never()).findActiveAnswerTemplate(anyLong(), anyString());
    }

    @Test
    void promotionBusinessDateIsJune1NotJune2() {
        // At 2026-06-01T20:00Z → Asia/Jakarta 2026-06-02T03:00 → minus 6h = 2026-06-01
        VenueTimeContext ctx = new VenueTimeContext(ZoneId.of("Asia/Jakarta"), 6);
        when(timeRepo.findActiveVenueTime(VENUE_ID)).thenReturn(Optional.of(ctx));
        when(chatRepo.findActivePromotions(anyLong(), any())).thenReturn(Collections.emptyList());
        service.answerLocal(VENUE_ID, VENUE, ChatIntent.PROMOTION);
        verify(chatRepo).findActivePromotions(VENUE_ID, LocalDate.of(2026, 6, 1));
    }

    @Test
    void promotionEmptyTimeContextReturnsNoDataWithoutCallingRepo() {
        when(timeRepo.findActiveVenueTime(VENUE_ID)).thenReturn(Optional.empty());
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.PROMOTION);
        assertNotBlank(result);
        assertNoNullLiteral(result);
        verify(chatRepo, never()).findActivePromotions(anyLong(), any());
    }

    @Test
    void promotionNoActivePromotions() {
        VenueTimeContext ctx = new VenueTimeContext(ZoneId.of("Asia/Jakarta"), 6);
        when(timeRepo.findActiveVenueTime(VENUE_ID)).thenReturn(Optional.of(ctx));
        when(chatRepo.findActivePromotions(anyLong(), any())).thenReturn(Collections.emptyList());
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.PROMOTION);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    // -------------------------------------------------------------------------
    // TABLE_AVAILABILITY
    // -------------------------------------------------------------------------

    @Test
    void tableAvailabilityWithData() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(List.of(
            new PublicTableInfo("Main Floor", 0, "AVAILABLE"),
            new PublicTableInfo("Main Floor", 0, "AVAILABLE"),
            new PublicTableInfo("Main Floor", 0, "OCCUPIED")
        ));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.TABLE_AVAILABILITY);
        assertNotBlank(result);
        assertTrue(result.contains("2 available"), "Must count AVAILABLE lowercased");
        assertTrue(result.contains("1 occupied"), "Must count OCCUPIED lowercased");
        verify(chatRepo, never()).findActiveAnswerTemplate(anyLong(), anyString());
    }

    @Test
    void tableAvailabilityStatusLowercased() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(List.of(
            new PublicTableInfo("Bar", 0, "RESERVED")
        ));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.TABLE_AVAILABILITY);
        assertTrue(result.contains("reserved"), "Status must be lowercased");
        assertFalse(result.contains("RESERVED"), "Status must not be uppercase");
    }

    @Test
    void tableAvailabilityNullStatusBecomesUnknown() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(List.of(
            new PublicTableInfo("Bar", 0, null)
        ));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.TABLE_AVAILABILITY);
        assertNotBlank(result);
        assertFalse(result.contains("null"), "Null status must not appear as 'null'");
        assertTrue(result.contains("tidak diketahui"), "Null status must become 'tidak diketahui'");
    }

    @Test
    void tableAvailabilityNoData() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(Collections.emptyList());
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.TABLE_AVAILABILITY);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    // -------------------------------------------------------------------------
    // RESERVATION
    // -------------------------------------------------------------------------

    @Test
    void reservationWithFaq() {
        when(chatRepo.findActiveAnswerTemplate(VENUE_ID, "RESERVATION"))
            .thenReturn(Optional.of("Reservasi tidak tersedia melalui sistem ini."));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.RESERVATION);
        assertNotBlank(result);
        assertTrue(result.contains("Reservasi tidak tersedia"));
        verify(chatRepo).findActiveAnswerTemplate(VENUE_ID, "RESERVATION");
    }

    @Test
    void reservationNoFaq() {
        when(chatRepo.findActiveAnswerTemplate(VENUE_ID, "RESERVATION")).thenReturn(Optional.empty());
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.RESERVATION);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    // -------------------------------------------------------------------------
    // BOTTLE_KEEPING
    // -------------------------------------------------------------------------

    @Test
    void bottleKeepingWithFaq() {
        when(chatRepo.findActiveAnswerTemplate(VENUE_ID, "BOTTLE_KEEPING"))
            .thenReturn(Optional.of("Bottle keeping tersedia untuk produk tertentu."));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.BOTTLE_KEEPING);
        assertNotBlank(result);
        assertTrue(result.contains("Bottle keeping"));
        verify(chatRepo).findActiveAnswerTemplate(VENUE_ID, "BOTTLE_KEEPING");
    }

    @Test
    void bottleKeepingNoFaq() {
        when(chatRepo.findActiveAnswerTemplate(VENUE_ID, "BOTTLE_KEEPING")).thenReturn(Optional.empty());
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.BOTTLE_KEEPING);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    // -------------------------------------------------------------------------
    // GENERAL_VENUE_INFORMATION
    // -------------------------------------------------------------------------

    @Test
    void generalVenueInfoWithFaq() {
        when(chatRepo.findActiveAnswerTemplate(VENUE_ID, "GENERAL_VENUE_INFORMATION"))
            .thenReturn(Optional.of("Moon Harbor di North District."));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.GENERAL_VENUE_INFORMATION);
        assertNotBlank(result);
        assertTrue(result.contains("Moon Harbor"));
        verify(chatRepo).findActiveAnswerTemplate(VENUE_ID, "GENERAL_VENUE_INFORMATION");
    }

    @Test
    void generalVenueInfoFallsBackToAddress() {
        when(chatRepo.findActiveAnswerTemplate(VENUE_ID, "GENERAL_VENUE_INFORMATION"))
            .thenReturn(Optional.empty());
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.GENERAL_VENUE_INFORMATION);
        assertNotBlank(result);
        assertTrue(result.contains("North District, Jakarta"));
    }

    @Test
    void generalVenueInfoNoData() {
        PublicVenueProfile v = new PublicVenueProfile("V", "", null);
        when(chatRepo.findActiveAnswerTemplate(VENUE_ID, "GENERAL_VENUE_INFORMATION"))
            .thenReturn(Optional.empty());
        String result = service.answerLocal(VENUE_ID, v, ChatIntent.GENERAL_VENUE_INFORMATION);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    // -------------------------------------------------------------------------
    // FAQ guard: non-FAQ intents must NOT call findActiveAnswerTemplate
    // -------------------------------------------------------------------------

    @Test
    void nonFaqIntentsNeverCallAnswerTemplate() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(Collections.emptyList());
        when(chatRepo.findActiveProductsWithCategory(VENUE_ID)).thenReturn(Collections.emptyList());
        when(timeRepo.findActiveVenueTime(VENUE_ID)).thenReturn(Optional.empty());

        for (ChatIntent intent : List.of(
            ChatIntent.OPENING_HOURS,
            ChatIntent.VENUE_AREA,
            ChatIntent.MINIMUM_SPEND,
            ChatIntent.MENU_INFORMATION,
            ChatIntent.PROMOTION,
            ChatIntent.TABLE_AVAILABILITY
        )) {
            service.answerLocal(VENUE_ID, VENUE, intent);
        }
        verify(chatRepo, never()).findActiveAnswerTemplate(anyLong(), anyString());
    }

    @Test
    void faqIntentsCallAnswerTemplateWithIntentName() {
        when(chatRepo.findActiveAnswerTemplate(anyLong(), anyString())).thenReturn(Optional.empty());
        service.answerLocal(VENUE_ID, VENUE, ChatIntent.RESERVATION);
        service.answerLocal(VENUE_ID, VENUE, ChatIntent.BOTTLE_KEEPING);
        service.answerLocal(VENUE_ID, VENUE, ChatIntent.GENERAL_VENUE_INFORMATION);
        verify(chatRepo).findActiveAnswerTemplate(VENUE_ID, "RESERVATION");
        verify(chatRepo).findActiveAnswerTemplate(VENUE_ID, "BOTTLE_KEEPING");
        verify(chatRepo).findActiveAnswerTemplate(VENUE_ID, "GENERAL_VENUE_INFORMATION");
    }

    @Test
    void blankTemplateCountsAsMissing() {
        when(chatRepo.findActiveAnswerTemplate(VENUE_ID, "RESERVATION"))
            .thenReturn(Optional.of("   "));
        String result = service.answerLocal(VENUE_ID, VENUE, ChatIntent.RESERVATION);
        assertNotBlank(result);
        assertFalse(result.isBlank());
    }

    // -------------------------------------------------------------------------
    // Non-local intents throw IllegalArgumentException
    // -------------------------------------------------------------------------

    @Test
    void advancedConversationThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> service.answerLocal(VENUE_ID, VENUE, ChatIntent.ADVANCED_CONVERSATION));
    }

    @Test
    void outOfScopeThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> service.answerLocal(VENUE_ID, VENUE, ChatIntent.OUT_OF_SCOPE));
    }

    // -------------------------------------------------------------------------
    // answerFallback covers all five categories
    // -------------------------------------------------------------------------

    @Test
    void fallbackOpeningHours() {
        String result = service.answerFallback(VENUE_ID, VENUE, ChatFallbackCategory.OPENING_HOURS);
        assertNotBlank(result);
        assertTrue(result.contains("Senin"));
    }

    @Test
    void fallbackVenueAddress() {
        String result = service.answerFallback(VENUE_ID, VENUE, ChatFallbackCategory.VENUE_ADDRESS);
        assertNotBlank(result);
        assertTrue(result.contains("North District, Jakarta"));
    }

    @Test
    void fallbackVenueAddressBlankReturnsNoData() {
        PublicVenueProfile v = new PublicVenueProfile("V", "", null);
        String result = service.answerFallback(VENUE_ID, v, ChatFallbackCategory.VENUE_ADDRESS);
        assertNotBlank(result);
        assertNoNullLiteral(result);
    }

    @Test
    void fallbackMinimumSpend() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(List.of(
            new PublicTableInfo("Bar Side", 0, "AVAILABLE")
        ));
        String result = service.answerFallback(VENUE_ID, VENUE, ChatFallbackCategory.MINIMUM_SPEND);
        assertNotBlank(result);
    }

    @Test
    void fallbackVenueArea() {
        when(chatRepo.findActivePublicTables(VENUE_ID)).thenReturn(List.of(
            new PublicTableInfo("Bar Side", 0, "AVAILABLE")
        ));
        String result = service.answerFallback(VENUE_ID, VENUE, ChatFallbackCategory.VENUE_AREA);
        assertNotBlank(result);
        assertTrue(result.contains("Bar Side"));
    }

    @Test
    void fallbackBottleKeepingPolicy() {
        when(chatRepo.findActiveAnswerTemplate(VENUE_ID, "BOTTLE_KEEPING"))
            .thenReturn(Optional.of("Bottle keeping policy info."));
        String result = service.answerFallback(VENUE_ID, VENUE, ChatFallbackCategory.BOTTLE_KEEPING_POLICY);
        assertNotBlank(result);
        assertTrue(result.contains("Bottle keeping policy info."));
    }

    // -------------------------------------------------------------------------
    // Fixed response methods
    // -------------------------------------------------------------------------

    @Test
    void refusalAnswerIsNonBlankAndDoesNotContainAiAkan() {
        String result = service.refusalAnswer();
        assertNotBlank(result);
        assertFalse(result.contains("AI akan"), "Must not promise AI later");
    }

    @Test
    void advancedAiRequiredAnswerIsNonBlankAndDoesNotContainAiAkan() {
        String result = service.advancedAiRequiredAnswer();
        assertNotBlank(result);
        assertFalse(result.contains("AI akan"), "Must not promise AI later");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void assertNotBlank(String value) {
        assertNotNull(value);
        assertFalse(value.isBlank(), "Answer must not be blank");
    }

    private static void assertNoNullLiteral(String value) {
        assertFalse(value.contains("null"), "Answer must not contain literal 'null'");
    }
}
