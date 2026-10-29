package com.nightvenue.api.catalog;

import com.nightvenue.api.common.Conflict;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.function.Supplier;

final class CatalogWriteSupport {
    private static final String UNIQUE_CONFLICT_DETAIL =
            "A catalog entry with that identifier already exists";

    private CatalogWriteSupport() {
    }

    static <T> T persist(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueViolation(exception)) {
                throw new Conflict(UNIQUE_CONFLICT_DETAIL);
            }
            throw exception;
        }
    }

    private static boolean isUniqueViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
