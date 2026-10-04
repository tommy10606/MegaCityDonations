package com.megacitycraft.donations;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

final class DonationStore {
    record Purchase(String donationId, String name, BigDecimal paid, String recordedAt) { }
    record Account(String name, List<Purchase> purchases) { }
    private final Path file;
    private Map<UUID, Account> accounts;

    DonationStore(Path file) throws IOException, InvalidConfigurationException {
        this.file = file;
        accounts = new LinkedHashMap<>();
        var yaml = FilesSupport.read(file);
        var players = yaml.getConfigurationSection("players");
        if (yaml.contains("players") && players == null) throw new IllegalArgumentException("players must be a mapping.");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            UUID id = UUID.fromString(key);
            var player = players.getConfigurationSection(key);
            if (player == null) throw new IllegalArgumentException("Invalid player record: " + key);
            Object raw = player.get("purchases");
            if (raw != null && !(raw instanceof List<?>)) throw new IllegalArgumentException("purchases must be a list: " + key);
            List<Purchase> purchases = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object value : list) {
                    if (!(value instanceof Map<?, ?> record)) throw new IllegalArgumentException("Invalid purchase for " + key);
                    String donationId = require(record, "id");
                    if (!donationId.equals(Catalog.normalizeId(donationId))) throw new IllegalArgumentException("Invalid purchase ID.");
                    String time = require(record, "recorded-at");
                    try { Instant.parse(time); }
                    catch (java.time.format.DateTimeParseException error) {
                        throw new IllegalArgumentException("Invalid recorded-at timestamp for " + key, error);
                    }
                    purchases.add(new Purchase(donationId, require(record, "name"), Catalog.amount(require(record, "paid")), time));
                }
            }
            accounts.put(id, new Account(player.getString("name", key), List.copyOf(purchases)));
        }
    }

    private static String require(Map<?, ?> record, String key) {
        Object value = record.get(key);
        if (value == null) throw new IllegalArgumentException("Missing purchase field: " + key);
        return value.toString();
    }
    Map<UUID, Account> accounts() { return Collections.unmodifiableMap(accounts); }
    List<Purchase> purchases(UUID player) {
        Account account = accounts.get(player);
        return account == null ? List.of() : account.purchases();
    }
    BigDecimal total(UUID player) {
        return purchases(player).stream().map(Purchase::paid).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }
    record Donor(UUID uuid, String name, BigDecimal total) { }
    BigDecimal grandTotal() {
        return accounts.keySet().stream().map(this::total).reduce(new BigDecimal("0.00"), BigDecimal::add);
    }
    List<Donor> leaderboard() {
        return accounts.entrySet().stream()
                .map(entry -> new Donor(entry.getKey(), entry.getValue().name(), total(entry.getKey())))
                .filter(donor -> donor.total().signum() > 0)
                .sorted(java.util.Comparator.comparing(Donor::total).reversed()
                        .thenComparing(Donor::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Donor::uuid))
                .toList();
    }
    void remember(UUID id, String name) throws IOException {
        Account existing = accounts.get(id);
        if (existing != null && existing.name().equals(name)) return;
        commit(id, new Account(name, purchases(id)));
    }
    void requirePrerequisites(UUID player, String name, Catalog.Feature feature) {
        var owned = purchases(player).stream().map(Purchase::donationId).collect(java.util.stream.Collectors.toSet());
        var missing = feature.requires().stream().filter(id -> !owned.contains(id)).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException(name + " must first purchase: " + String.join(", ", missing) + " (required for " + feature.id() + ").");
    }
    void add(UUID player, String name, Catalog.Feature feature) throws IOException {
        add(player, name, feature, false);
    }
    void add(UUID player, String name, Catalog.Feature feature, boolean override) throws IOException {
        if (!override) requirePrerequisites(player, name, feature);
        List<Purchase> records = new ArrayList<>(purchases(player));
        if (!feature.repeatable() && records.stream().anyMatch(record -> record.donationId().equals(feature.id())))
            throw new IllegalArgumentException(name + " already has " + feature.id() + "; repeat purchases are disabled for this ID.");
        records.add(new Purchase(feature.id(), feature.name(), feature.price(), Instant.now().toString()));
        commit(player, new Account(name, List.copyOf(records)));
    }
    Purchase removeLatest(UUID player, String name, String donationId) throws IOException {
        String id = Catalog.normalizeId(donationId);
        List<Purchase> records = new ArrayList<>(purchases(player));
        for (int index = records.size() - 1; index >= 0; index--) {
            Purchase record = records.get(index);
            if (record.donationId().equals(id)) {
                records.remove(index);
                commit(player, new Account(name, List.copyOf(records)));
                return record;
            }
        }
        throw new IllegalArgumentException(name + " has no recorded purchase for " + id + ".");
    }
    private void commit(UUID id, Account account) throws IOException {
        Map<UUID, Account> next = new LinkedHashMap<>(accounts);
        next.put(id, account);
        YamlConfiguration yaml = new YamlConfiguration();
        for (var entry : next.entrySet()) {
            String path = "players." + entry.getKey();
            yaml.set(path + ".name", entry.getValue().name());
            List<Map<String, Object>> records = new ArrayList<>();
            for (Purchase purchase : entry.getValue().purchases()) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("id", purchase.donationId());
                record.put("name", purchase.name());
                record.put("paid", purchase.paid().toPlainString());
                record.put("recorded-at", purchase.recordedAt());
                records.add(record);
            }
            yaml.set(path + ".purchases", records);
        }
        FilesSupport.write(file, yaml); // Memory changes only after a successful save.
        accounts = next;
    }
}
