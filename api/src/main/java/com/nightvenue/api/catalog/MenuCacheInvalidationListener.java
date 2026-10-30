package com.nightvenue.api.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

@Component
public class MenuCacheInvalidationListener {
    private static final Logger log = LoggerFactory.getLogger(MenuCacheInvalidationListener.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public MenuCacheInvalidationListener(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCatalogMutated(CatalogMutatedEvent event) {
        if (event.global()) {
            List<Long> venueIds = jdbc.queryForList(
                    "SELECT id FROM venues WHERE is_active = true ORDER BY id",
                    Long.class);
            for (Long venueId : venueIds) {
                evict(venueId);
            }
            return;
        }
        evict(event.venueId());
    }

    private void evict(Long venueId) {
        if (venueId == null) {
            return;
        }
        try {
            redis.delete("menu:" + venueId);
        } catch (Exception exception) {
            log.warn("Failed to evict menu cache for venue {} after catalog mutation", venueId, exception);
        }
    }
}
