package com.nightvenue.api.inventory;

import com.nightvenue.api.common.Conflict;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.function.Supplier;

final class InventoryWriteSupport {
    private InventoryWriteSupport() {
    }

    static <T> T persist(Supplier<T> operation, String conflictDetail) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueViolation(exception)) {
                throw new Conflict(conflictDetail);
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
