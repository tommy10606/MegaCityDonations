package com.megacitycraft.donations;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageTest {
    @Test void catalogValidatesDependencyChainsAndRejectsMalformedListsAndMissingIds() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("donations:\n  a:\n    price: '1'\n  b:\n    price: '2'\n    requires: [a]\n  c:\n    price: '3'\n    requires: [b]\n");
        assertTrue(Catalog.parse(yaml).get("a").requires().isEmpty());
        assertEquals(List.of("b"), Catalog.parse(yaml).get("c").requires());
        for (Object required : List.of("a", List.of(1), List.of("missing"), List.of("c"))) {
            yaml.set("donations.c.requires", required);
            assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml));
        }
        yaml.set("donations.c.requires", List.of("b"));
        yaml.set("donations.a.requires", List.of("c"));
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml));
    }

    @Test void permissionTemplatesDefaultCorrectlyAndRejectInvalidCommands() throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("donations: {}\n");
        var defaults = Catalog.parse(yaml);
        assertEquals("manuaddp {player} {permission}", defaults.activateCommand);
        assertEquals("manudelp {player} {permission}", defaults.deactivateCommand);
        for (Object value : List.of("manuaddp {player}", "manuaddp {permission}", "manuaddp {player} {permission}\nstop", List.of("cmd"))) {
            yaml.set("permission-commands.activate", value);
            assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml));
        }
        yaml.set("permission-commands.activate", "/customperms {uuid} {permission}");
        assertEquals("customperms {uuid} {permission}", Catalog.parse(yaml).activateCommand);
        yaml.set("permission-commands.activate", "");
        assertEquals("", Catalog.parse(yaml).activateCommand);
    }

    @Test void multilineCatalogNamesAreRejected() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("donations:\n  tool:\n    name: Tool\n    price: '9.00'\n");
        for (String name : List.of("Tool\nOther", "Tool\rOther", "Tool\u2028Other", "Tool\u0085Other")) {
            yaml.set("donations.tool.name", name);
            assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml));
        }
    }

    @Test void currencyDefaultsToUsdAndAcceptsSupportedCodes() throws Exception {
        var yaml = new YamlConfiguration(); yaml.loadFromString("donations: {}\n");
        assertEquals("$5.00", Catalog.parse(yaml).money(Catalog.amount("5")));
        yaml.set("currency", "GBP");
        assertEquals("£5.00", Catalog.parse(yaml).money(Catalog.amount("5")));
        yaml.set("currency", "eur");
        assertEquals("€5.00", Catalog.parse(yaml).money(Catalog.amount("5")));
        yaml.set("currency", "invalid");
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml));
    }
    @Test void decimalAccountingDoesNotAccumulateFloatingPointErrors(@TempDir Path directory) throws Exception {
        UUID id = UUID.randomUUID();
        var store = new DonationStore(directory.resolve("players.yml"));
        var feature = new Catalog.Feature("tool", "Tool", Catalog.amount("0.10"), true, List.of());
        for (int index = 0; index < 10; index++) store.add(id, "Player", feature);
        var reloaded = new DonationStore(directory.resolve("players.yml"));
        assertEquals(new BigDecimal("1.00"), reloaded.total(id));
        reloaded.remember(id, "NewName");
        assertEquals(10, reloaded.purchases(id).size());
        assertEquals("NewName", new DonationStore(directory.resolve("players.yml")).accounts().get(id).name());
    }

    @Test void failedSaveDoesNotCommitPurchaseToMemory(@TempDir Path directory) throws Exception {
        Path blocked = directory.resolve("blocked"); Files.writeString(blocked, "not a directory");
        var store = new DonationStore(blocked.resolve("players.yml"));
        UUID id = UUID.randomUUID();
        var feature = new Catalog.Feature("tool", "Tool", Catalog.amount("9.00"), false, List.of());
        assertThrows(IOException.class, () -> store.add(id, "Player", feature));
        assertTrue(store.purchases(id).isEmpty());
    }

    @Test void malformedSavedRecordsAreRejectedRatherThanDiscarded(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("players.yml");
        Files.writeString(file, "players:\n  not-a-uuid:\n    name: Player\n");
        assertThrows(IllegalArgumentException.class, () -> new DonationStore(file));
    }

    @Test void repeatableDefaultsFalseAndMalformedConfigIsRejected() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("donations:\n  tool:\n    name: Tool\n    price: '9.00'\n");
        assertFalse(Catalog.parse(yaml).get("tool").repeatable());
        yaml.set("donations.tool.repeatable", "yes");
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml));
        yaml.set("donations.tool.repeatable", false);
        yaml.set("donations.tool.permissions", "single-node");
        assertThrows(IllegalArgumentException.class, () -> Catalog.parse(yaml));
    }
}
