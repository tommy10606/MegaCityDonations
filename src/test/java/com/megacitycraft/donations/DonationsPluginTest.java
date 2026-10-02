package com.megacitycraft.donations;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class DonationsPluginTest {
    private ServerMock server;
    private DonationsPlugin plugin;
    private PlayerMock admin;
    private PlayerMock buyer;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        var config = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                java.util.Objects.requireNonNull(DonationsPlugin.class.getResourceAsStream("/config.yml")), java.nio.charset.StandardCharsets.UTF_8));
        config.set("update-checker.enabled", false); // Tests never contact GitHub.
        plugin = MockBukkit.loadWithConfig(DonationsPlugin.class, config);
        admin = server.addPlayer("Admin");
        admin.setOp(true);
        buyer = server.addPlayer("Buyer");
        buyer.setOp(false);
        // Historical test fixtures are created by commands, not shipped defaults.
        run("donation create feed 5.00 Feed");
        run("donation addpermission feed essentials.feed");
        run("donation create pyro 10.00 Pyro");
        run("donation create diamondtool 9.00 Diamond Tool");
        drain(buyer); drain(admin);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }
    private DonationStore records() throws Exception {
        return new DonationStore(plugin.getDataFolder().toPath().resolve("players.yml"));
    }
    private void run(String command) { assertTrue(admin.performCommand(command)); }
    private List<Component> drain(PlayerMock player) {
        List<Component> messages = new ArrayList<>();
        Component message;
        while ((message = player.nextComponentMessage()) != null) messages.add(message);
        return messages;
    }
    private String plain(Component message) { return PlainTextComponentSerializer.plainText().serialize(message); }

    @Test void shippedCatalogHasOnlyRequestedIdsPermissionsAndRepeatability() {
        var yaml = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                java.util.Objects.requireNonNull(DonationsPlugin.class.getResourceAsStream("/config.yml")), java.nio.charset.StandardCharsets.UTF_8));
        var defaults = Catalog.parse(yaml);
        assertEquals(java.util.Set.of("nickname", "eff7"), defaults.features.keySet());
        assertEquals("$5.00", defaults.money(defaults.get("nickname").price()));
        assertFalse(defaults.get("nickname").repeatable());
        assertEquals(List.of("essentials.nick", "essentials.nick.color", "essentials.nick.format"), defaults.get("nickname").permissions());
        assertTrue(defaults.get("eff7").repeatable());
        assertEquals("$10.00", defaults.money(defaults.get("eff7").price()));
    }

    @Test void changingCurrencyUpdatesAllDisplaysWithoutConvertingStoredPayments() throws Exception {
        run("adddonation Buyer nickname");
        var path = plugin.getDataFolder().toPath().resolve("config.yml");
        var yaml = FilesSupport.read(path); yaml.set("currency", "EUR"); FilesSupport.write(path, yaml);
        run("mydonations reload"); drain(admin);
        run("donation list");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.equals("nickname - €5.00 - Not Repeatable")));
        buyer.performCommand("mydonations");
        assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("Total donated: €5.00")));
        assertEquals(new BigDecimal("5.00"), records().total(buyer.getUniqueId()));
        yaml.set("currency", "NOTVALID"); FilesSupport.write(path, yaml);
        run("mydonations reload");
        buyer.performCommand("mydonations");
        assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("Total donated: €5.00")));
    }

    @Test void activeVersionAndWebsiteAreCorrectAndVersionCommandRequiresOp() {
        run("mydonations version");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.equals("MegaCityDonations v1.0.0")));
        assertEquals("https://github.com/tommy10606/MegaCityDonations", plugin.getPluginMeta().getWebsite());
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "mydonations version"));
        buyer.performCommand("mydonations version");
        assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("Only operators")));
    }

    @Test void ordinaryPlayersCanViewOwnSummaryButCannotAdminister() throws Exception {
        assertNull(plugin.getCommand("mydonations").getPermission());
        assertTrue(buyer.performCommand("mydonations"));
        assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("$0.00")));
        for (String command : List.of("mydonations Admin", "mydonations reload", "adddonation Buyer feed",
                "removedonation Buyer feed", "donation create hacked 10 Hacked", "donation info feed")) {
            assertTrue(buyer.performCommand(command));
            assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("Only operators")), command);
        }
        assertEquals(0, records().purchases(buyer.getUniqueId()).size());
        assertFalse(Files.readString(plugin.getDataFolder().toPath().resolve("config.yml")).contains("hacked"));
    }

    @Test void explicitFalseAndOmittedRepeatableBothRejectDuplicates() throws Exception {
        run("adddonation Buyer feed"); run("adddonation Buyer feed");
        run("adddonation Buyer pyro"); run("adddonation Buyer pyro");
        assertEquals(2, records().purchases(buyer.getUniqueId()).size());
        assertEquals(new BigDecimal("15.00"), records().total(buyer.getUniqueId()));
    }

    @Test void donationListShowsIdsPricesAndBothRepeatabilityLabels() {
        run("donation repeatable diamondtool true");
        drain(admin);
        run("donation list");
        var lines = drain(admin).stream().map(this::plain).toList();
        assertTrue(lines.contains("feed - $5.00 - Not Repeatable"));
        assertTrue(lines.contains("diamondtool - $9.00 - Repeatable"));
        assertTrue(lines.contains("pyro - $10.00 - Not Repeatable"));
        buyer.performCommand("donation list");
        assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("Only operators")));
    }

    @Test void repeatableRecordsMultiplePaymentsAndRemovesOnlyLatest() throws Exception {
        run("donation repeatable diamondtool true");
        run("adddonation Buyer diamondtool"); run("adddonation Buyer diamondtool");
        assertEquals(new BigDecimal("18.00"), records().total(buyer.getUniqueId()));
        run("removedonation Buyer diamondtool");
        assertEquals(1, records().purchases(buyer.getUniqueId()).size());
        assertEquals(new BigDecimal("9.00"), records().total(buyer.getUniqueId()));
        run("removedonation Buyer diamondtool"); run("removedonation Buyer diamondtool");
        assertEquals(new BigDecimal("0.00"), records().total(buyer.getUniqueId()));
    }

    @Test void correctionAllowsUniqueFeatureToBeAssignedAgain() throws Exception {
        run("adddonation Buyer feed"); run("removedonation Buyer feed"); run("adddonation Buyer feed");
        assertEquals(1, records().purchases(buyer.getUniqueId()).size());
        assertEquals(new BigDecimal("5.00"), records().total(buyer.getUniqueId()));
    }

    @Test void offlineAssignmentsAndOperatorLookupWorkAfterRestart() throws Exception {
        buyer.disconnect();
        run("adddonation Buyer feed");
        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);
        run("mydonations Buyer");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.contains("Total donated: $5.00")));
        buyer.reconnect();
        buyer.performCommand("mydonations");
        assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("Total donated: $5.00")));
    }

    @Test void unknownPlayerIsNotInventedAndUuidAssignmentsWork() throws Exception {
        run("adddonation TypoPlayer feed");
        assertEquals(0, records().purchases(buyer.getUniqueId()).size());
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.contains("Unknown player")));
        run("adddonation " + buyer.getUniqueId() + " feed");
        assertEquals(1, records().purchases(buyer.getUniqueId()).size());
    }

    @Test void catalogCommandsPersistAndPricesNeverRewritePastPayments() throws Exception {
        run("donation create newtool 12.34 Shiny New Tool");
        var created = FilesSupport.read(plugin.getDataFolder().toPath().resolve("config.yml"));
        assertTrue(created.contains("donations.newtool.repeatable"));
        assertFalse(created.getBoolean("donations.newtool.repeatable"));
        run("donation info newtool");
        run("adddonation Buyer newtool"); run("adddonation Buyer newtool");
        assertEquals(1, records().purchases(buyer.getUniqueId()).size());
        run("donation price newtool 20.00");
        run("donation rename newtool Better Tool");
        run("donation repeatable newtool true");
        run("adddonation Buyer newtool");
        assertEquals(new BigDecimal("32.34"), records().total(buyer.getUniqueId()));
        var catalog = Catalog.load(plugin.getDataFolder().toPath().resolve("config.yml"));
        assertEquals("Better Tool", catalog.get("newtool").name());
        assertTrue(catalog.get("newtool").repeatable());
        run("mydonations reload");
        assertEquals(new BigDecimal("32.34"), records().total(buyer.getUniqueId()));
        run("removedonation Buyer newtool");
        assertEquals(new BigDecimal("12.34"), records().total(buyer.getUniqueId()));
    }

    private boolean hasHover(Component component) {
        return component.hoverEvent() != null || component.children().stream().anyMatch(this::hasHover);
    }
    @Test void permissionNodesAppearOnlyOnConfiguredFeatureNamesAndAreNotGranted() {
        run("adddonation Buyer feed"); run("adddonation Buyer pyro");
        buyer.performCommand("mydonations");
        List<Component> messages = drain(buyer);
        Component feed = messages.stream().filter(line -> plain(line).contains("Feed ·")).findFirst().orElseThrow();
        Component pyro = messages.stream().filter(line -> plain(line).contains("Pyro ·")).findFirst().orElseThrow();
        assertTrue(hasHover(feed));
        assertFalse(hasHover(pyro));
        assertFalse(buyer.hasPermission("essentials.feed"));
    }

    @Test void addAndRemovePermissionCommandsApplyImmediately() throws Exception {
        run("donation addpermission pyro example.pyro");
        run("donation addpermission pyro example.pyro");
        var path = plugin.getDataFolder().toPath().resolve("config.yml");
        assertEquals(List.of("example.pyro"), Catalog.load(path).get("pyro").permissions());
        run("adddonation Buyer pyro");
        buyer.performCommand("mydonations");
        assertTrue(drain(buyer).stream().anyMatch(this::hasHover));
        run("donation removepermission pyro example.pyro");
        buyer.performCommand("mydonations");
        assertFalse(drain(buyer).stream().anyMatch(this::hasHover));
    }

    @Test void paginationIncludesGrandTotalAndWorkingNextLink() throws Exception {
        run("donation repeatable diamondtool true");
        for (int index = 0; index < 10; index++) run("adddonation Buyer diamondtool");
        buyer.performCommand("mydonations");
        var first = drain(buyer);
        assertEquals(8, first.stream().map(this::plain).filter(line -> line.contains("Diamond Tool ·")).count());
        assertTrue(first.stream().map(this::plain).anyMatch(line -> line.contains("$90.00")));
        var footer = first.getLast();
        assertTrue(footer.children().stream().anyMatch(child -> child.clickEvent() != null));
        buyer.performCommand("mydonations 2");
        assertEquals(2, drain(buyer).stream().map(this::plain).filter(line -> line.contains("Diamond Tool ·")).count());
        buyer.performCommand("mydonations 3");
        assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("does not exist")));
    }

    @Test void invalidReloadPreservesLiveStateAndRecords() throws Exception {
        run("adddonation Buyer feed");
        var config = plugin.getDataFolder().toPath().resolve("config.yml");
        String good = Files.readString(config);
        Files.writeString(config, "donations: [broken");
        run("mydonations reload");
        run("adddonation Buyer pyro");
        assertEquals(new BigDecimal("15.00"), records().total(buyer.getUniqueId()));
        Files.writeString(config, good);
        run("mydonations reload");
        assertEquals(new BigDecimal("15.00"), records().total(buyer.getUniqueId()));
    }

    @Test void removedCatalogDefinitionDoesNotLoseHistoryOrPreventCorrection() throws Exception {
        run("adddonation Buyer feed");
        var config = plugin.getDataFolder().toPath().resolve("config.yml");
        var yaml = FilesSupport.read(config); yaml.set("donations.feed", null); FilesSupport.write(config, yaml);
        run("mydonations reload");
        buyer.performCommand("mydonations");
        assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("Feed · $5.00")));
        run("removedonation Buyer feed");
        assertEquals(new BigDecimal("0.00"), records().total(buyer.getUniqueId()));
    }

    @Test void consoleCanCreateAssignViewAndReload() throws Exception {
        var console = server.getConsoleSender();
        for (String command : List.of("donation create consoleitem 1.01 Console Item", "adddonation Buyer consoleitem",
                "mydonations Buyer", "mydonations reload")) assertTrue(server.dispatchCommand(console, command));
        assertEquals(new BigDecimal("1.01"), records().total(buyer.getUniqueId()));
    }

    @Test void invalidCatalogArgumentsDoNotSaveOrChangeRecords() throws Exception {
        var config = plugin.getDataFolder().toPath().resolve("config.yml");
        String before = Files.readString(config);
        for (String command : List.of("donation create bad -1 Bad", "donation price feed 1.234",
                "donation repeatable feed maybe", "donation create feed 10 Already Exists", "adddonation Buyer missing")) run(command);
        assertEquals(before, Files.readString(config));
        assertEquals(0, records().purchases(buyer.getUniqueId()).size());
    }
}
