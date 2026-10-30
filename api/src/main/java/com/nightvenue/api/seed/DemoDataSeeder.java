package com.nightvenue.api.seed;

import com.nightvenue.api.businessday.BusinessDateService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeds initial demo data for NightVenue Phase 2.
 * The 90-day inventory consumption history is synthetic data generated for portfolio demonstration purposes. It is not real production data.
 */
@Component
@Profile({"dev", "test"})
@ConditionalOnProperty(prefix = "nightvenue.seed", name = "enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    // demo only; precomputed BCrypt hash generated outside app
    private static final String DEMO_PASSWORD_HASH = "$2b$10$JNXcS4YOFO0rkcdGXsjCa.cVuTFksIUV.B6.4nUU4HeZTmb/WUdNW";
    private static final List<VenueSeed> VENUES = List.of(
            new VenueSeed("NV-JKT-01", "Moon Harbor", "North District, Jakarta"),
            new VenueSeed("NV-JKT-02", "Lantern Quarter", "South District, Jakarta"));
    private static final List<UserSeed> USERS = List.of(
            new UserSeed("admin@nightvenue.demo", "Demo Administrator", "ADMIN"),
            new UserSeed("manager@nightvenue.demo", "Demo Manager", "MANAGER"),
            new UserSeed("cashier@nightvenue.demo", "Demo Cashier", "CASHIER"),
            new UserSeed("customer@nightvenue.demo", "Demo Customer", "CUSTOMER"));
    private static final List<IngredientSeed> INGREDIENTS = List.of(
            i("vodka", "Neutral spirit", "ML"), i("gin", "Juniper spirit", "ML"),
            i("white-rum", "White cane spirit", "ML"), i("dark-rum", "Aged cane spirit", "ML"),
            i("tequila", "Agave spirit", "ML"), i("whiskey", "Oak spirit", "ML"),
            i("sparkling-wine", "Sparkling wine", "ML"), i("lime-juice", "Lime juice", "ML"),
            i("lemon-juice", "Lemon juice", "ML"), i("simple-syrup", "Simple syrup", "ML"),
            i("soda-water", "Soda water", "ML"), i("tonic-water", "Tonic water", "ML"),
            i("ginger-beer", "Ginger beer", "ML"), i("mint", "Fresh mint", "GRAM"),
            i("orange-juice", "Orange juice", "ML"), i("coffee-liqueur", "Coffee liqueur", "ML"),
            i("ice", "Ice", "GRAM"), i("potato", "Potato", "GRAM"),
            i("cooking-oil", "Cooking oil", "ML"), i("peanuts", "Roasted peanuts", "GRAM"),
            i("cassava-chips", "Cassava chips", "GRAM"), i("olives", "Olives", "PIECE"),
            i("agave-syrup", "Agave syrup", "ML"), i("cola", "Cola", "ML"),
            i("still-water", "Still water", "ML"),
            i("bottle-vodka-700", "Neutral spirit bottle 700 ml", "PIECE"),
            i("bottle-gin-700", "Juniper spirit bottle 700 ml", "PIECE"),
            i("bottle-rum-700", "White cane spirit bottle 700 ml", "PIECE"),
            i("bottle-tequila-700", "Agave spirit bottle 700 ml", "PIECE"),
            i("bottle-whiskey-700", "Oak spirit bottle 700 ml", "PIECE"));
    private static final List<String> CATEGORIES = List.of("Cocktails", "Bottles", "Small Plates");
    private static final List<ProductSeed> PRODUCTS = products();

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final BusinessDateService businessDates;

    public DemoDataSeeder(JdbcTemplate jdbc, Clock clock, BusinessDateService businessDates) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.businessDates = businessDates;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Instant now = clock.instant();
        Map<String, Long> venueIds = seedVenues(now);
        Map<String, Long> userIds = seedUsers(now);
        seedUserVenues(venueIds, userIds);
        Map<String, Long> categoryIds = seedCategories();
        Map<String, Long> ingredientIds = seedIngredients();
        seedCustomers(userIds, now);

        List<InventorySeed> inventory = new ArrayList<>();
        for (VenueSeed venue : VENUES) {
            long venueId = venueIds.get(venue.code());
            seedTables(venueId);
            Map<String, Long> productIds = seedProducts(venueId, categoryIds, now);
            seedRecipes(productIds, ingredientIds, now);
            long warehouseId = seedLocation(venueId, "WAREHOUSE", "WAREHOUSE");
            long barId = seedLocation(venueId, "BAR", "BAR");
            for (IngredientSeed ingredient : INGREDIENTS) {
                inventory.add(seedInventoryItem(venueId, warehouseId, ingredientIds.get(ingredient.sku()), ingredient, "WAREHOUSE", now));
                inventory.add(seedInventoryItem(venueId, barId, ingredientIds.get(ingredient.sku()), ingredient, "BAR", now));
            }
        }
        seedLedger(inventory);
        log.info("Synthetic Phase 2 seed complete: {} venues, {} demo users, {} inventory items",
                VENUES.size(), USERS.size(), inventory.size());
    }

    private Map<String, Long> seedVenues(Instant now) {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (VenueSeed venue : VENUES) {
            jdbc.update("""
                    INSERT INTO venues (code, name, address, timezone, business_day_cutoff_hour, tax_rate_bps,
                        service_charge_bps, void_manager_approval_threshold, opening_hours, is_active, created_at, updated_at)
                    VALUES (?, ?, ?, 'Asia/Jakarta', 6, 0, 0, 500000, ?::jsonb, true, ?, ?)
                    ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name, address=EXCLUDED.address,
                        timezone=EXCLUDED.timezone, business_day_cutoff_hour=EXCLUDED.business_day_cutoff_hour,
                        opening_hours=EXCLUDED.opening_hours, updated_at=EXCLUDED.updated_at
                    """, venue.code(), venue.name(), venue.address(),
                    "{\"mon\":[\"18:00\",\"02:00\"],\"tue\":[\"18:00\",\"02:00\"],\"wed\":[\"18:00\",\"02:00\"],\"thu\":[\"18:00\",\"02:00\"],\"fri\":[\"18:00\",\"03:00\"],\"sat\":[\"18:00\",\"03:00\"],\"sun\":[\"18:00\",\"02:00\"]}", ts(now), ts(now));
            ids.put(venue.code(), id("SELECT id FROM venues WHERE code = ?", venue.code()));
        }
        return ids;
    }

    private Map<String, Long> seedUsers(Instant now) {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (UserSeed user : USERS) {
            jdbc.update("""
                    INSERT INTO users (email, password_hash, full_name, role, is_active, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, true, 0, ?, ?)
                    ON CONFLICT (email) DO NOTHING
                    """, user.email(), DEMO_PASSWORD_HASH, user.name(), user.role(), ts(now), ts(now));
            ids.put(user.email(), id("SELECT id FROM users WHERE email = ?", user.email()));
        }
        return ids;
    }

    private void seedUserVenues(Map<String, Long> venues, Map<String, Long> users) {
        for (String email : List.of("admin@nightvenue.demo", "manager@nightvenue.demo", "cashier@nightvenue.demo")) {
            for (long venueId : venues.values()) {
                jdbc.update("INSERT INTO user_venues (user_id, venue_id) VALUES (?, ?) ON CONFLICT DO NOTHING", users.get(email), venueId);
            }
        }
    }

    private Map<String, Long> seedCategories() {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (int i = 0; i < CATEGORIES.size(); i++) {
            String name = CATEGORIES.get(i);
            jdbc.update("INSERT INTO product_categories (name, sort_order) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET sort_order=EXCLUDED.sort_order", name, i);
            ids.put(name, id("SELECT id FROM product_categories WHERE name = ?", name));
        }
        return ids;
    }

    private Map<String, Long> seedIngredients() {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (IngredientSeed ingredient : INGREDIENTS) {
            jdbc.update("INSERT INTO ingredients (sku, name, unit, is_active) VALUES (?, ?, ?, true) ON CONFLICT (sku) DO UPDATE SET name=EXCLUDED.name, unit=EXCLUDED.unit, is_active=true",
                    ingredient.sku(), ingredient.name(), ingredient.unit());
            ids.put(ingredient.sku(), id("SELECT id FROM ingredients WHERE sku = ?", ingredient.sku()));
        }
        return ids;
    }

    private void seedCustomers(Map<String, Long> users, Instant now) {
        long userId = users.get("customer@nightvenue.demo");
        long verifiedBy = users.get("admin@nightvenue.demo");
        customer(userId, "Demo Customer", "+620000000001", "customer@nightvenue.demo", false, null, null, now);
        customer(null, "Sample Guest One", "+620000000002", "guest1@nightvenue.demo", true, ts(now), verifiedBy, now);
        customer(null, "Sample Guest Two", "+620000000003", "guest2@nightvenue.demo", false, null, null, now);
    }

    private void customer(Long userId, String name, String phone, String email, boolean verified,
                          Timestamp verifiedAt, Long verifiedBy, Instant now) {
        jdbc.update("""
                INSERT INTO customers (user_id, full_name, phone, email, age_verified, age_verified_at, age_verified_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (phone) DO UPDATE SET user_id=EXCLUDED.user_id, full_name=EXCLUDED.full_name,
                    email=EXCLUDED.email, age_verified=EXCLUDED.age_verified, age_verified_at=EXCLUDED.age_verified_at,
                    age_verified_by=EXCLUDED.age_verified_by, updated_at=EXCLUDED.updated_at
                """, userId, name, phone, email, verified, verifiedAt, verifiedBy, ts(now), ts(now));
    }

    private void seedTables(long venueId) {
        for (int n = 1; n <= 12; n++) {
            String area = n <= 4 ? "Main Floor" : n <= 8 ? "VIP" : "Bar Side";
            long minimumSpend = area.equals("VIP") ? 5_000_000L : 0L;
            String code = String.format("T%02d", n);
            jdbc.update("""
                    INSERT INTO venue_tables (venue_id, code, area, capacity, minimum_spend, status, is_active, version)
                    VALUES (?, ?, ?, ?, ?, 'AVAILABLE', true, 0)
                    ON CONFLICT (venue_id, code) DO UPDATE SET area=EXCLUDED.area, capacity=EXCLUDED.capacity,
                        minimum_spend=EXCLUDED.minimum_spend, is_active=true
                    """, venueId, code, area, n <= 8 ? 4 : 2, minimumSpend);
        }
    }

    private Map<String, Long> seedProducts(long venueId, Map<String, Long> categories, Instant now) {
        Map<String, Long> ids = new LinkedHashMap<>();
        for (ProductSeed product : PRODUCTS) {
            long categoryId = categories.get(product.category());
            jdbc.update("""
                    INSERT INTO products (venue_id, category_id, sku, name, price, is_bottle_keepable, bottle_volume_ml, is_active, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, true, 0, ?, ?)
                    ON CONFLICT (venue_id, sku) DO UPDATE SET category_id=EXCLUDED.category_id, name=EXCLUDED.name,
                        price=EXCLUDED.price, is_bottle_keepable=EXCLUDED.is_bottle_keepable,
                        bottle_volume_ml=EXCLUDED.bottle_volume_ml, is_active=true, updated_at=EXCLUDED.updated_at
                    """, venueId, categoryId, product.sku(), product.name(), product.price(), product.bottleVolumeMl() != null,
                    product.bottleVolumeMl(), ts(now), ts(now));
            ids.put(product.sku(), id("SELECT id FROM products WHERE venue_id = ? AND sku = ?", venueId, product.sku()));
        }
        return ids;
    }

    private void seedRecipes(Map<String, Long> products, Map<String, Long> ingredients, Instant now) {
        for (ProductSeed product : PRODUCTS) {
            jdbc.update("INSERT INTO recipes (product_id, version, updated_at) VALUES (?, 1, ?) ON CONFLICT (product_id) DO UPDATE SET version=EXCLUDED.version, updated_at=EXCLUDED.updated_at",
                    products.get(product.sku()), ts(now));
            long recipeId = id("SELECT id FROM recipes WHERE product_id = ?", products.get(product.sku()));
            jdbc.update("DELETE FROM recipe_items WHERE recipe_id = ?", recipeId);
            for (RecipePart part : product.parts()) {
                jdbc.update("INSERT INTO recipe_items (recipe_id, ingredient_id, quantity) VALUES (?, ?, ?)",
                        recipeId, ingredients.get(part.ingredientSku()), part.quantity());
            }
        }
    }

    private long seedLocation(long venueId, String name, String type) {
        jdbc.update("INSERT INTO stock_locations (venue_id, name, type) VALUES (?, ?, ?) ON CONFLICT (venue_id, name) DO UPDATE SET type=EXCLUDED.type",
                venueId, name, type);
        return id("SELECT id FROM stock_locations WHERE venue_id = ? AND name = ?", venueId, name);
    }

    private InventorySeed seedInventoryItem(long venueId, long locationId, long ingredientId, IngredientSeed ingredient,
                                            String location, Instant now) {
        jdbc.update("INSERT INTO inventory_items (location_id, ingredient_id, quantity, reorder_level, version, updated_at) VALUES (?, ?, 0, 0, 0, ?) ON CONFLICT (location_id, ingredient_id) DO NOTHING",
                locationId, ingredientId, ts(now));
        long id = id("SELECT id FROM inventory_items WHERE location_id = ? AND ingredient_id = ?", locationId, ingredientId);
        return new InventorySeed(id, venueId, ingredient.unit(), location);
    }

    private void seedLedger(List<InventorySeed> inventory) {
        Map<Long, VenueConfig> venues = new LinkedHashMap<>();
        for (InventorySeed item : inventory) {
            venues.computeIfAbsent(item.venueId(), vid -> {
                return jdbc.queryForObject("SELECT timezone, business_day_cutoff_hour FROM venues WHERE id = ?",
                        (rs, row) -> new VenueConfig(ZoneId.of(rs.getString("timezone")), rs.getInt("business_day_cutoff_hour")), vid);
            });
        }

        for (InventorySeed item : inventory) {
            if (jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM inventory_transactions WHERE inventory_item_id=? AND reference_type='SEED')", Boolean.class, item.id())) {
                continue;
            }
            VenueConfig venue = venues.get(item.venueId());
            ZoneId zone = venue.zone();
            int cutoff = venue.cutoff();
            LocalDate end = businessDates.currentBusinessDate(zone, cutoff).minusDays(1);
            LocalDate start = end.minusDays(89);

            BigDecimal opening = openingQuantity(item.unit(), item.location());
            BigDecimal balance = BigDecimal.ZERO;
            balance = append(item, "PURCHASE_IN", opening, balance, start, zone, cutoff);
            Random random = new Random(20261025L + item.id());
            for (int offset = 0; offset < 90; offset++) {
                LocalDate date = start.plusDays(offset);
                if (offset > 0 && offset % 14 == 0) {
                    BigDecimal restock = opening.divide(BigDecimal.valueOf(4), 3, java.math.RoundingMode.HALF_UP);
                    balance = append(item, "PURCHASE_IN", restock, balance, date, zone, cutoff);
                }
                if (!item.location().equals("BAR")) continue;
                int dow = date.getDayOfWeek().getValue();
                int multiplier = (dow == 5 || dow == 6) ? 3 : (dow == 7 ? 2 : 1);
                BigDecimal sale = BigDecimal.valueOf(multiplier * (10 + random.nextInt(6)));
                if ("PIECE".equals(item.unit())) {
                    sale = sale.min(BigDecimal.valueOf(8));
                }
                if (balance.compareTo(sale) < 0) {
                    BigDecimal restock = opening.divide(BigDecimal.valueOf(4), 3, java.math.RoundingMode.HALF_UP);
                    if ("PIECE".equals(item.unit())) {
                        restock = opening.divide(BigDecimal.valueOf(2), 3, java.math.RoundingMode.HALF_UP);
                    }
                    balance = append(item, "PURCHASE_IN", restock, balance, date, zone, cutoff);
                }
                if (balance.compareTo(sale) <= 0) {
                    sale = balance.subtract(BigDecimal.ONE).max(BigDecimal.ONE);
                }
                balance = append(item, "SALE_DEDUCT", sale.negate(), balance, date, zone, cutoff);
            }
            jdbc.update("UPDATE inventory_items SET quantity=?, updated_at=? WHERE id=?", balance, ts(clock.instant()), item.id());
        }
    }

    private BigDecimal append(InventorySeed item, String type, BigDecimal delta, BigDecimal current,
                              LocalDate businessDate, ZoneId zone, int cutoff) {
        LocalTime localTime = type.equals("SALE_DEDUCT") ? LocalTime.of(20, 0) : LocalTime.of(6, 30);
        Instant createdAt = businessDate.atStartOfDay(zone).plusHours(localTime.getHour())
                .plusMinutes(localTime.getMinute()).toInstant();
        LocalDate storedBusinessDate = businessDates.businessDate(createdAt, zone, cutoff);
        BigDecimal after = current.add(delta).setScale(3);
        jdbc.update("""
                INSERT INTO inventory_transactions (inventory_item_id, type, delta, balance_after, business_date,
                    reference_type, reference_id, reason, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, 'SEED', NULL, NULL, NULL, ?)
                """, item.id(), type, delta.setScale(3), after, storedBusinessDate, ts(createdAt));
        return after;
    }

    private BigDecimal openingQuantity(String unit, String location) {
        int quantity = switch (unit) {
            case "ML" -> location.equals("BAR") ? 20_000 : 100_000;
            case "GRAM" -> location.equals("BAR") ? 10_000 : 50_000;
            default -> location.equals("BAR") ? 300 : 1_500;
        };
        return BigDecimal.valueOf(quantity).setScale(3);
    }

    private Long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static IngredientSeed i(String sku, String name, String unit) {
        return new IngredientSeed(sku, name, unit);
    }

    private static List<ProductSeed> products() {
        List<ProductSeed> values = new ArrayList<>();
        values.add(p("cocktail-citrus-highball", "Citrus Highball", "Cocktails", 95_000, null, part("vodka", "45"), part("orange-juice", "60"), part("soda-water", "90"), part("ice", "100")));
        values.add(p("cocktail-garden-mule", "Garden Mule", "Cocktails", 105_000, null, part("gin", "45"), part("ginger-beer", "100"), part("lime-juice", "15"), part("mint", "4")));
        values.add(p("cocktail-mint-cane", "Mint Cane", "Cocktails", 98_000, null, part("white-rum", "45"), part("lime-juice", "20"), part("simple-syrup", "15"), part("mint", "5"), part("soda-water", "90")));
        values.add(p("cocktail-sunset-sour", "Sunset Sour", "Cocktails", 110_000, null, part("tequila", "45"), part("lemon-juice", "25"), part("agave-syrup", "15"), part("orange-juice", "20")));
        values.add(p("cocktail-coffee-night", "Coffee Night", "Cocktails", 115_000, null, part("whiskey", "40"), part("coffee-liqueur", "20"), part("simple-syrup", "10")));
        values.add(p("cocktail-sparkling-orchard", "Sparkling Orchard", "Cocktails", 120_000, null, part("sparkling-wine", "100"), part("orange-juice", "30"), part("ice", "80")));
        values.add(p("cocktail-dark-lime", "Dark Lime", "Cocktails", 108_000, null, part("dark-rum", "45"), part("lime-juice", "25"), part("cola", "100")));
        values.add(p("cocktail-tonic-branch", "Tonic Branch", "Cocktails", 102_000, null, part("gin", "45"), part("tonic-water", "120"), part("lime-juice", "10")));
        values.add(p("cocktail-agave-glow", "Agave Glow", "Cocktails", 112_000, null, part("tequila", "45"), part("agave-syrup", "15"), part("lime-juice", "20"), part("soda-water", "50")));
        values.add(p("cocktail-still-morning", "Still Morning", "Cocktails", 92_000, null, part("vodka", "40"), part("lemon-juice", "20"), part("simple-syrup", "15"), part("still-water", "40")));
        values.add(p("bottle-vodka-700", "Neutral Spirit Bottle 700", "Bottles", 1_250_000, 700, part("bottle-vodka-700", "1")));
        values.add(p("bottle-gin-700", "Juniper Spirit Bottle 700", "Bottles", 1_450_000, 700, part("bottle-gin-700", "1")));
        values.add(p("bottle-rum-700", "Cane Spirit Bottle 700", "Bottles", 1_350_000, 700, part("bottle-rum-700", "1")));
        values.add(p("bottle-tequila-700", "Agave Spirit Bottle 700", "Bottles", 1_650_000, 700, part("bottle-tequila-700", "1")));
        values.add(p("bottle-whiskey-700", "Oak Spirit Bottle 700", "Bottles", 1_750_000, 700, part("bottle-whiskey-700", "1")));
        values.add(p("snack-crispy-potato", "Crispy Potato", "Small Plates", 58_000, null, part("potato", "250"), part("cooking-oil", "20")));
        values.add(p("snack-roasted-peanuts", "Roasted Peanuts", "Small Plates", 38_000, null, part("peanuts", "120")));
        values.add(p("snack-cassava-crunch", "Cassava Crunch", "Small Plates", 45_000, null, part("cassava-chips", "150"), part("cooking-oil", "10")));
        values.add(p("snack-olive-picks", "Olive Picks", "Small Plates", 48_000, null, part("olives", "8")));
        values.add(p("snack-water-pair", "Still Water Pair", "Small Plates", 25_000, null, part("still-water", "2")));
        return List.copyOf(values);
    }

    private static ProductSeed p(String sku, String name, String category, long price, Integer bottleMl, RecipePart... parts) {
        return new ProductSeed(sku, name, category, price, bottleMl, List.of(parts));
    }

    private static RecipePart part(String sku, String quantity) {
        return new RecipePart(sku, new BigDecimal(quantity));
    }

    private record VenueSeed(String code, String name, String address) {}
    private record UserSeed(String email, String name, String role) {}
    private record IngredientSeed(String sku, String name, String unit) {}
    private record RecipePart(String ingredientSku, BigDecimal quantity) {}
    private record ProductSeed(String sku, String name, String category, long price, Integer bottleVolumeMl, List<RecipePart> parts) {}
    private record InventorySeed(long id, long venueId, String unit, String location) {}
    private record VenueConfig(ZoneId zone, int cutoff) {}
}
