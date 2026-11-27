package com.nightvenue.api.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

final class OpeningHoursFormatter {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String[][] DAYS = {
        {"mon", "Senin"},
        {"tue", "Selasa"},
        {"wed", "Rabu"},
        {"thu", "Kamis"},
        {"fri", "Jumat"},
        {"sat", "Sabtu"},
        {"sun", "Minggu"}
    };

    private OpeningHoursFormatter() {}

    static Optional<String> format(String openingHoursJson) {
        if (openingHoursJson == null || openingHoursJson.isBlank()) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(openingHoursJson);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }

        StringJoiner joiner = new StringJoiner(", ");
        for (String[] day : DAYS) {
            String key = day[0];
            String label = day[1];
            JsonNode val = root.get(key);
            if (val == null || !val.isArray() || val.size() != 2) continue;
            JsonNode openNode = val.get(0);
            JsonNode closeNode = val.get(1);
            if (openNode == null || !openNode.isString() || closeNode == null || !closeNode.isString()) continue;
            String openStr = openNode.stringValue();
            String closeStr = closeNode.stringValue();
            LocalTime openTime;
            LocalTime closeTime;
            try {
                openTime = LocalTime.parse(openStr);
                closeTime = LocalTime.parse(closeStr);
            } catch (DateTimeParseException e) {
                continue;
            }
            if (openTime.equals(closeTime)) continue;
            String entry = label + " " + openStr + "-" + closeStr;
            if (closeTime.isBefore(openTime)) {
                entry += " (hingga keesokan harinya)";
            }
            joiner.add(entry);
        }

        String result = joiner.toString();
        if (result.isEmpty()) return Optional.empty();
        return Optional.of("Jam operasional: " + result);
    }
}
