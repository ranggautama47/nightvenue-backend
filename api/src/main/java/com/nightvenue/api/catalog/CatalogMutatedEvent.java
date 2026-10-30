package com.nightvenue.api.catalog;

public record CatalogMutatedEvent(Long venueId, boolean global) {
    public static CatalogMutatedEvent forVenue(long venueId) {
        return new CatalogMutatedEvent(venueId, false);
    }

    public static CatalogMutatedEvent forAllVenues() {
        return new CatalogMutatedEvent(null, true);
    }
}
