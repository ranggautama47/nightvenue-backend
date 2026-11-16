package com.nightvenue.api.bottle;

import com.nightvenue.api.auth.AuthExceptions.InvalidAccessToken;
import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.common.InvalidRequest;
import com.nightvenue.api.common.ResourceNotFound;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class BottleQueryService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "EMPTY", "EXPIRED", "RELEASED");

    private final BottleRepository bottles;
    private final VenueAccessService venues;

    public BottleQueryService(BottleRepository bottles, VenueAccessService venues) {
        this.bottles = bottles;
        this.venues = venues;
    }

    public List<BottleQueryResponse> staffBottles(
            long venueId, String status, Long customerId, int page, int size) {
        venues.requireAccess(venueId);
        validate(status, page, size);
        return bottles.findBottlesForVenue(venueId, status, customerId, size, (long) page * size);
    }

    public List<BottleQueryResponse> customerBottles(String jwtSubject, String status, int page, int size) {
        validate(status, page, size);
        long userId;
        try {
            userId = Long.parseLong(jwtSubject);
        } catch (NumberFormatException ex) {
            throw new InvalidAccessToken();
        }
        long customerId = bottles.findCustomerIdByUserId(userId).orElseThrow(ResourceNotFound::new);
        return bottles.findBottlesForCustomer(customerId, status, size, (long) page * size);
    }

    private static void validate(String status, int page, int size) {
        if (page < 0) {
            throw new InvalidRequest("page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw new InvalidRequest("size must be between 1 and 100");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new InvalidRequest("status must be ACTIVE, EMPTY, EXPIRED, or RELEASED");
        }
    }
}
