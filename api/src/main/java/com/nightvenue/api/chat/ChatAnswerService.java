package com.nightvenue.api.chat;

import com.nightvenue.api.businessday.BusinessDateService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Provides local and fallback chat answers from PostgreSQL data.
 *
 * PENDING PM assumptions:
 * 1. All answers are Indonesian-only for this interim round.
 * 2. Menu answers exclude uncategorized products due to the existing repository INNER JOIN.
 * 3. Table status values are displayed as stored (lowercased), without a hardcoded status vocabulary.
 */
@Service
public class ChatAnswerService {

    private static final String NO_DATA = "Informasi ini belum tersedia untuk venue ini saat ini.";
    private static final int MAX_ITEMS_PER_CATEGORY = 5;
    private static final int MAX_ITEMS_TOTAL = 30;

    private final ChatRepository chatRepository;
    private final ChatVenueTimeRepository venueTimeRepository;
    private final BusinessDateService businessDateService;

    public ChatAnswerService(ChatRepository chatRepository,
                             ChatVenueTimeRepository venueTimeRepository,
                             BusinessDateService businessDateService) {
        this.chatRepository = chatRepository;
        this.venueTimeRepository = venueTimeRepository;
        this.businessDateService = businessDateService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public String answerLocal(long venueId, PublicVenueProfile venue, ChatIntent intent) {
        if (intent == null || !intent.isLocal()) {
            throw new IllegalArgumentException("Intent is not local: " + intent);
        }
        return switch (intent) {
            case OPENING_HOURS       -> buildOpeningHours(venue);
            case VENUE_AREA          -> buildVenueArea(venueId, venue);
            case MINIMUM_SPEND       -> buildMinimumSpend(venueId, venue);
            case MENU_INFORMATION    -> buildMenu(venueId, venue);
            case PROMOTION           -> buildPromotion(venueId, venue);
            case TABLE_AVAILABILITY  -> buildTableAvailability(venueId, venue);
            case RESERVATION         -> buildFaq(venueId, venue, intent);
            case BOTTLE_KEEPING      -> buildFaq(venueId, venue, intent);
            case GENERAL_VENUE_INFORMATION -> buildGeneralVenueInfo(venueId, venue, intent);
            default -> throw new IllegalArgumentException("Intent is not local: " + intent);
        };
    }

    public String answerFallback(long venueId, PublicVenueProfile venue, ChatFallbackCategory category) {
        if (category == null) {
            throw new IllegalArgumentException("Category must not be null");
        }
        return switch (category) {
            case OPENING_HOURS        -> buildOpeningHours(venue);
            case VENUE_ADDRESS        -> buildVenueAddress(venue);
            case MINIMUM_SPEND        -> buildMinimumSpend(venueId, venue);
            case VENUE_AREA           -> buildVenueArea(venueId, venue);
            case BOTTLE_KEEPING_POLICY -> buildFaq(venueId, venue, ChatIntent.BOTTLE_KEEPING);
        };
    }

    public String refusalAnswer() {
        return "Maaf, saya hanya dapat membantu pertanyaan seputar venue NightVenue ini, " +
               "seperti jam buka, menu, area, minimum spend, promo, status meja, dan reservasi.";
    }

    public String advancedAiRequiredAnswer() {
        return "Pertanyaan ini memerlukan asisten AI lanjutan yang belum tersedia saat ini. " +
               "Anda dapat menanyakan jam buka, alamat, menu, area, minimum spend, promo, status meja, atau reservasi.";
    }

    // -------------------------------------------------------------------------
    // Private builders
    // -------------------------------------------------------------------------

    private String buildOpeningHours(PublicVenueProfile venue) {
        return OpeningHoursFormatter.format(venue.openingHours())
            .orElse("Informasi jam operasional belum tersedia untuk venue ini.");
    }

    private String buildVenueAddress(PublicVenueProfile venue) {
        String address = venue.address();
        if (address == null || address.isBlank()) {
            return "Informasi alamat belum tersedia untuk venue ini.";
        }
        return "Alamat " + venueName(venue) + ": " + address + ".";
    }

    private String buildVenueArea(long venueId, PublicVenueProfile venue) {
        List<PublicTableInfo> tables = chatRepository.findActivePublicTables(venueId);
        List<String> areas = new ArrayList<>();
        for (PublicTableInfo t : tables) {
            String area = t.area();
            if (area != null && !area.isBlank() && !areas.contains(area)) {
                areas.add(area);
            }
        }
        if (areas.isEmpty()) return NO_DATA;
        return "Area yang tersedia di " + venueName(venue) + ": " + String.join(", ", areas) + ".";
    }

    private String buildMinimumSpend(long venueId, PublicVenueProfile venue) {
        List<PublicTableInfo> tables = chatRepository.findActivePublicTables(venueId);
        if (tables.isEmpty()) return NO_DATA;

        // group by area, first-seen order
        Map<String, long[]> areaMinMax = new LinkedHashMap<>();
        for (PublicTableInfo t : tables) {
            String area = (t.area() == null || t.area().isBlank()) ? "Umum" : t.area();
            long spend = t.minimumSpend();
            areaMinMax.compute(area, (k, arr) -> {
                if (arr == null) return new long[]{spend, spend};
                arr[0] = Math.min(arr[0], spend);
                arr[1] = Math.max(arr[1], spend);
                return arr;
            });
        }

        StringBuilder sb = new StringBuilder("Minimum spend di ").append(venueName(venue)).append(": ");
        boolean first = true;
        for (Map.Entry<String, long[]> e : areaMinMax.entrySet()) {
            if (!first) sb.append("; ");
            first = false;
            sb.append(e.getKey()).append(" ");
            long min = e.getValue()[0];
            long max = e.getValue()[1];
            if (min == max) {
                sb.append(formatRupiah(min));
            } else {
                sb.append(formatRupiah(min)).append(" - ").append(formatRupiah(max));
            }
        }
        sb.append(".");
        return sb.toString();
    }

    private String buildMenu(long venueId, PublicVenueProfile venue) {
        List<PublicProductInfo> products = chatRepository.findActiveProductsWithCategory(venueId);
        if (products.isEmpty()) return NO_DATA;

        // group by category, first-seen order
        Map<String, List<PublicProductInfo>> byCat = new LinkedHashMap<>();
        for (PublicProductInfo p : products) {
            String cat = (p.category() == null || p.category().isBlank()) ? "Lainnya" : p.category();
            byCat.computeIfAbsent(cat, k -> new ArrayList<>()).add(p);
        }

        StringBuilder sb = new StringBuilder("Menu di ").append(venueName(venue)).append(": ");
        int totalRendered = 0;
        boolean truncated = false;
        boolean firstCat = true;

        for (Map.Entry<String, List<PublicProductInfo>> e : byCat.entrySet()) {
            if (totalRendered >= MAX_ITEMS_TOTAL) { truncated = true; break; }
            if (!firstCat) sb.append("; ");
            firstCat = false;
            sb.append(e.getKey()).append(": ");
            List<PublicProductInfo> items = e.getValue();
            int limit = Math.min(MAX_ITEMS_PER_CATEGORY, items.size());
            if (limit < items.size()) truncated = true;
            for (int i = 0; i < limit; i++) {
                if (totalRendered >= MAX_ITEMS_TOTAL) { truncated = true; break; }
                if (i > 0) sb.append(", ");
                PublicProductInfo p = items.get(i);
                sb.append(p.name()).append(" (").append(formatRupiah(p.price())).append(")");
                totalRendered++;
            }
        }
        if (truncated) sb.append(", dan menu lainnya.");
        else sb.append(".");
        return sb.toString();
    }

    private String buildPromotion(long venueId, PublicVenueProfile venue) {
        Optional<VenueTimeContext> ctxOpt = venueTimeRepository.findActiveVenueTime(venueId);
        if (ctxOpt.isEmpty()) {
            return "Informasi promo belum tersedia untuk venue ini saat ini.";
        }
        VenueTimeContext ctx = ctxOpt.get();
        LocalDate businessDate = businessDateService.currentBusinessDate(ctx.zone(), ctx.cutoffHour());
        List<PromotionWindow> promos = chatRepository.findActivePromotions(venueId, businessDate);
        if (promos.isEmpty()) {
            return "Tidak ada promo yang sedang berlaku di " + venueName(venue) +
                   " pada " + businessDate + ".";
        }
        StringBuilder sb = new StringBuilder("Promo yang sedang berlaku di ")
            .append(venueName(venue)).append(": ");
        for (int i = 0; i < promos.size(); i++) {
            if (i > 0) sb.append("; ");
            PromotionWindow p = promos.get(i);
            sb.append(p.title());
            if (p.endsOn() != null) {
                sb.append(" (hingga ").append(p.endsOn()).append(")");
            }
        }
        sb.append(".");
        return sb.toString();
    }

    private String buildTableAvailability(long venueId, PublicVenueProfile venue) {
        List<PublicTableInfo> tables = chatRepository.findActivePublicTables(venueId);
        if (tables.isEmpty()) return NO_DATA;

        // group by area, then count by status, first-seen order
        Map<String, Map<String, Integer>> areaStatusCount = new LinkedHashMap<>();
        for (PublicTableInfo t : tables) {
            String area = (t.area() == null || t.area().isBlank()) ? "Umum" : t.area();
            String status = (t.status() == null || t.status().isBlank())
                ? "tidak diketahui"
                : t.status().toLowerCase(Locale.ROOT);
            areaStatusCount.computeIfAbsent(area, k -> new LinkedHashMap<>())
                .merge(status, 1, Integer::sum);
        }

        StringBuilder sb = new StringBuilder("Status meja saat ini di ").append(venueName(venue)).append(": ");
        boolean firstArea = true;
        for (Map.Entry<String, Map<String, Integer>> aEntry : areaStatusCount.entrySet()) {
            if (!firstArea) sb.append(". ");
            firstArea = false;
            sb.append(aEntry.getKey()).append(": ");
            boolean firstStatus = true;
            for (Map.Entry<String, Integer> sEntry : aEntry.getValue().entrySet()) {
                if (!firstStatus) sb.append(", ");
                firstStatus = false;
                sb.append(sEntry.getValue()).append(" ").append(sEntry.getKey());
            }
        }
        sb.append(". Ini status saat ini dan bukan jaminan ketersediaan pada waktu lain.");
        return sb.toString();
    }

    private String buildFaq(long venueId, PublicVenueProfile venue, ChatIntent intent) {
        Optional<String> template = chatRepository.findActiveAnswerTemplate(venueId, intent.name());
        if (template.isEmpty() || template.get().isBlank()) return NO_DATA;
        return template.get();
    }

    private String buildGeneralVenueInfo(long venueId, PublicVenueProfile venue, ChatIntent intent) {
        Optional<String> template = chatRepository.findActiveAnswerTemplate(venueId, intent.name());
        if (template.isPresent() && !template.get().isBlank()) return template.get();
        String address = venue.address();
        if (address != null && !address.isBlank()) {
            return venueName(venue) + " berlokasi di " + address + ".";
        }
        return NO_DATA;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private String venueName(PublicVenueProfile venue) {
        String name = venue.name();
        return (name == null || name.isBlank()) ? "venue ini" : name;
    }

    static String formatRupiah(long amount) {
        // locale-independent manual grouping with '.'
        String digits = Long.toString(amount);
        StringBuilder sb = new StringBuilder();
        int len = digits.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) sb.append('.');
            sb.append(digits.charAt(i));
        }
        return "Rp " + sb;
    }
}
