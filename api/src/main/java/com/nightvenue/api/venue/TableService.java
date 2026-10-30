package com.nightvenue.api.venue;

import com.nightvenue.api.auth.VenueAccessService;
import com.nightvenue.api.common.BusinessRuleViolation;
import com.nightvenue.api.common.Conflict;
import com.nightvenue.api.common.ResourceNotFound;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

@Service
@Validated
public class TableService {
    private static final String UNIQUE_CONFLICT = "Table already exists";

    private final TableRepository tables;
    private final VenueAccessService venues;
    private final Clock clock;

    public TableService(TableRepository tables, VenueAccessService venues, Clock clock) {
        this.tables = tables;
        this.venues = venues;
        this.clock = clock;
    }

    public List<TableRepository.VenueTable> list(long venueId) {
        venues.requireAccess(venueId);
        return tables.findByVenue(venueId);
    }

    public TableRepository.VenueTable detail(long id) {
        TableRepository.VenueTable table = tables.findById(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(table.venueId());
        if (!table.active()) {
            throw new ResourceNotFound();
        }
        return table;
    }

    @Transactional
    public TableRepository.VenueTable create(CreateRequest request) {
        venues.requireAccess(request.venueId());
        String code = request.code().trim();
        String area = request.area() == null ? null : request.area().trim();
        validateCreateStatus(request.status());
        if (request.capacity() <= 0) {
            throw new BusinessRuleViolation("Capacity must be greater than 0");
        }
        if (request.minimumSpend() < 0) {
            throw new BusinessRuleViolation("Minimum spend cannot be negative");
        }
        try {
            return tables.create(
                    request.venueId(),
                    code,
                    area,
                    request.capacity(),
                    request.minimumSpend(),
                    "AVAILABLE");
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueViolation(ex)) {
                throw new Conflict(UNIQUE_CONFLICT);
            }
            throw ex;
        }
    }

    @Transactional
    public TableRepository.VenueTable update(long id, UpdateRequest request) {
        TableRepository.VenueTable current = tables.findByIdForUpdate(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(current.venueId());
        String normalizedCode = request.code() == null ? current.code() : request.code().trim();
        String normalizedArea = request.area() == null ? current.area() : request.area().trim();
        int capacity = request.capacity() == null ? current.capacity() : request.capacity();
        long minimumSpend = request.minimumSpend() == null ? current.minimumSpend() : request.minimumSpend();
        if (capacity <= 0) {
            throw new BusinessRuleViolation("Capacity must be greater than 0");
        }
        if (minimumSpend < 0) {
            throw new BusinessRuleViolation("Minimum spend cannot be negative");
        }
        String status = request.status() == null ? current.status() : request.status().toUpperCase();
        validateStatus(status);
        try {
            return tables.update(id, normalizedCode, normalizedArea, capacity, minimumSpend, status)
                    .orElseThrow(ResourceNotFound::new);
        } catch (DataIntegrityViolationException ex) {
            if (isUniqueViolation(ex)) {
                throw new Conflict(UNIQUE_CONFLICT);
            }
            throw ex;
        }
    }

    @Transactional
    public void delete(long id) {
        TableRepository.VenueTable table = tables.findByIdForUpdate(id).orElseThrow(ResourceNotFound::new);
        venues.requireAccess(table.venueId());
        if (!table.active()) {
            return;
        }
        tables.softDelete(id);
    }

    private void validateCreateStatus(String status) {
        if (status == null) {
            return;
        }
        if (!"AVAILABLE".equalsIgnoreCase(status)) {
            throw new BusinessRuleViolation("Table status is controlled by the server");
        }
    }

    private static void validateStatus(String status) {
        if (status == null) {
            return;
        }
        if ("OCCUPIED".equalsIgnoreCase(status)) {
            throw new BusinessRuleViolation("Table status cannot be set to OCCUPIED");
        }
        if (!("AVAILABLE".equalsIgnoreCase(status) || "OUT_OF_SERVICE".equalsIgnoreCase(status))) {
            throw new BusinessRuleViolation("Invalid table status");
        }
    }

    private static boolean isUniqueViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    public record CreateRequest(
            @NotNull @Positive Long venueId,
            @NotBlank @Size(max = 20) String code,
            @Size(max = 50) String area,
            @NotNull @Positive Integer capacity,
            @NotNull @PositiveOrZero Long minimumSpend,
            String status) {
    }

    public record UpdateRequest(
            @Size(max = 20) String code,
            @Size(max = 50) String area,
            @Positive Integer capacity,
            @PositiveOrZero Long minimumSpend,
            String status) {
    }
}
