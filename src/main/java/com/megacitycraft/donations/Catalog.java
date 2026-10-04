package com.megacitycraft.donations;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

final class Catalog {
    record Feature(String id, String name, BigDecimal price, boolean repeatable, List<String> permissions) { }
    final Map<String, Feature> features;
    final int pageSize;
    final Currency currency;
    final String activateCommand;
    final String deactivateCommand;
    private final YamlConfiguration yaml;

    private Catalog(YamlConfiguration yaml) {
        this.yaml = yaml;
        activateCommand = commandTemplate(yaml, "activate", "manuaddp {player} {permission}");
        deactivateCommand = commandTemplate(yaml, "deactivate", "manudelp {player} {permission}");
        try {
            currency = Currency.getInstance(yaml.getString("currency", "USD").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("currency must be a supported currency code such as USD, CAD, EUR, or GBP.", error);
        }
        if (yaml.contains("page-size") && !yaml.isInt("page-size"))
            throw new IllegalArgumentException("page-size must be a whole number between 1 and 20.");
        pageSize = yaml.getInt("page-size", 8);
        if (pageSize < 1 || pageSize > 20) throw new IllegalArgumentException("page-size must be between 1 and 20.");
        var section = yaml.getConfigurationSection("donations");
        if (section == null) throw new IllegalArgumentException("config.yml needs a donations section (donations: {} is allowed).");
        Map<String, Feature> definitions = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            if (!id.equals(normalizeId(id))) throw new IllegalArgumentException("Donation IDs must be lowercase: " + id);
            var feature = section.getConfigurationSection(id);
            if (feature == null) throw new IllegalArgumentException("Invalid definition for " + id);
            String name = feature.getString("name", id);
            if (name.isBlank()) throw new IllegalArgumentException("Feature names cannot be empty: " + id);
            if (hasLineBreak(name)) throw new IllegalArgumentException("Feature names must be one line. Fix donations." + id + ".name in config.yml.");
            if (!feature.contains("price")) throw new IllegalArgumentException("Missing price for " + id);
            BigDecimal price = amount(feature.get("price").toString());
            if (feature.contains("repeatable") && !feature.isBoolean("repeatable"))
                throw new IllegalArgumentException("repeatable must be true or false for " + id);
            Object nodes = feature.get("permissions");
            if (nodes != null && (!(nodes instanceof List<?> list) || list.stream().anyMatch(node -> !(node instanceof String))))
                throw new IllegalArgumentException("permissions must be a list of strings for " + id);
            List<String> permissions = feature.getStringList("permissions").stream().map(Catalog::permission).distinct().toList();
            definitions.put(id, new Feature(id, name, price, feature.getBoolean("repeatable", false), permissions));
        }
        features = Collections.unmodifiableMap(definitions);
    }

    static Catalog load(Path file) throws IOException, InvalidConfigurationException {
        return parse(FilesSupport.read(file));
    }
    static Catalog parse(YamlConfiguration yaml) { return new Catalog(yaml); }
    Feature get(String id) {
        Feature feature = features.get(normalizeId(id));
        if (feature == null) throw new IllegalArgumentException("Unknown donation ID: " + id);
        return feature;
    }
    YamlConfiguration copy() throws InvalidConfigurationException {
        YamlConfiguration copy = new YamlConfiguration();
        copy.loadFromString(yaml.saveToString());
        return copy;
    }
    void save(Path file) throws IOException { FilesSupport.write(file, yaml); }

    private static String commandTemplate(YamlConfiguration yaml, String action, String fallback) {
        String path = "permission-commands." + action;
        if (yaml.contains(path) && !yaml.isString(path)) throw new IllegalArgumentException(path + " must be a command string.");
        String template = yaml.getString(path, fallback).trim();
        if (template.isEmpty()) return ""; // Explicitly disable an action.
        if (hasLineBreak(template) || template.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(path + " must contain one command on one line.");
        if (template.startsWith("/")) template = template.substring(1);
        if (!template.contains("{permission}") || !(template.contains("{player}") || template.contains("{uuid}")))
            throw new IllegalArgumentException(path + " needs {permission} and {player} or {uuid} placeholders.");
        return template;
    }

    static boolean hasLineBreak(String input) {
        return input.codePoints().anyMatch(value -> value == '\n' || value == '\r' || value == 0x85
                || value == 0x2028 || value == 0x2029 || value == 0x0B || value == 0x0C);
    }
    static String normalizeId(String input) {
        String id = input.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Donation IDs use letters, numbers, underscores, or hyphens (max 64 characters).");
        return id;
    }
    static String permission(String input) {
        if (input.isBlank() || hasLineBreak(input) || input.codePoints().anyMatch(value -> Character.isWhitespace(value) || Character.isISOControl(value)))
            throw new IllegalArgumentException("A permission node cannot be empty or contain spaces.");
        return input;
    }
    static BigDecimal amount(String input) {
        if (!input.matches("[0-9]+(?:\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Use a nonnegative amount with at most two decimal places, e.g. 5.00.");
        return new BigDecimal(input).setScale(2, RoundingMode.UNNECESSARY);
    }
    String money(BigDecimal amount) {
        return currency.getSymbol(Locale.US) + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
