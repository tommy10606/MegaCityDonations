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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path testWorld;
    private ServerMock server;
    private DonationsPlugin plugin;
    private PlayerMock admin;
    private PlayerMock buyer;

    @BeforeEach void setUp() {
        server = MockBukkit.mock(new ServerMock() {
            @Override public org.bukkit.OfflinePlayer[] getOfflinePlayers() {
                throw new AssertionError("Commands and tab completion must never scan Bukkit offline players.");
            }
        });
        server.addWorld(new org.mockbukkit.mockbukkit.world.WorldMock() {
            @Override public java.nio.file.Path getWorldPath() { return testWorld; }
        });
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
    private boolean hasClick(Component message, String command) {
        return net.kyori.adventure.text.event.ClickEvent.runCommand(command).equals(message.clickEvent())
                || message.children().stream().anyMatch(child -> hasClick(child, command));
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

    @Test void tabCompletionUsesSavedOfflineNamesAndNeverLoadsPlayerData() {
        buyer.disconnect();
        for (String name : List.of("mydonations", "adddonation", "removedonation")) {
            var command = plugin.getCommand(name);
            assertTrue(plugin.onTabComplete(admin, command, name, new String[]{"Bu"}).contains("Buyer"));
        }
        run("adddonation Buyer nickname");
        assertTrue(plugin.onTabComplete(admin, plugin.getCommand("removedonation"), "removedonation",
                new String[]{"Buyer", "nick"}).contains("nickname"));
    }

    @Test void indexedHistoricalOfflinePlayerCanBeAssignedWithoutBukkitDataLoads() throws Exception {
        var id = java.util.UUID.randomUUID();
        var field = DonationsPlugin.class.getDeclaredField("indexedPlayers");
        field.setAccessible(true);
        field.set(plugin, java.util.Map.of(id, "Historical"));
        run("adddonation Historical nickname");
        assertEquals(new BigDecimal("5.00"), records().total(id));
        run("mydonations " + id);
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.contains("Total donated: $5.00")));
    }

    @Test void leaderboardRanksOfflineDonorsAndBreaksTiesByName() throws Exception {
        run("adddonation Admin nickname");
        run("adddonation Buyer feed");
        var old = server.addPlayer("OldDonor");
        run("adddonation OldDonor eff7");
        run("adddonation OldDonor eff7");
        old.disconnect(); drain(admin);
        run("donation top");
        var lines = drain(admin).stream().map(this::plain).toList();
        assertTrue(lines.contains("Grand total donated: $30.00"));
        assertTrue(lines.contains("1. OldDonor - $20.00"));
        assertTrue(lines.contains("2. Admin - $5.00"));
        assertTrue(lines.contains("3. Buyer - $5.00"));
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "donation top"));
        run("removedonation OldDonor eff7"); drain(admin);
        run("donation total");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.equals("Grand total donated: $20.00")));
        assertEquals(1, records().purchases(old.getUniqueId()).size());
    }

    @Test void leaderboardPaginationAndTotalReflectManuallyReloadedRecordsAndCurrency() throws Exception {
        run("adddonation Admin nickname"); run("adddonation Buyer feed");
        var recordsPath = plugin.getDataFolder().toPath().resolve("players.yml");
        var yaml = FilesSupport.read(recordsPath);
        var purchase = new java.util.LinkedHashMap<String, Object>();
        yaml.getMapList("players." + buyer.getUniqueId() + ".purchases").getFirst()
                .forEach((key, value) -> purchase.put(key.toString(), value));
        purchase.put("paid", "17.25");
        yaml.set("players." + buyer.getUniqueId() + ".purchases", List.of(purchase));
        FilesSupport.write(recordsPath, yaml);
        var configPath = plugin.getDataFolder().toPath().resolve("config.yml");
        var config = FilesSupport.read(configPath);
        config.set("page-size", 1); config.set("currency", "EUR"); FilesSupport.write(configPath, config);
        run("mydonations reload"); drain(admin);
        run("donation top");
        var messages = drain(admin);
        assertTrue(messages.stream().map(this::plain).anyMatch(line -> line.equals("1. Buyer - €17.25")));
        assertTrue(messages.stream().map(this::plain).anyMatch(line -> line.equals("Grand total donated: €22.25")));
        assertTrue(messages.stream().anyMatch(message -> hasClick(message, "/donation top 2")));
        run("donation leaderboard 2");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.equals("2. Admin - €5.00")));
        run("donation top 3");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.contains("does not exist")));
    }

    @Test void leaderboardAndTotalAreOpOnlyAndHandleEmptyRecords() {
        for (String command : List.of("donation top", "donation leaderboard", "donation total")) {
            assertTrue(buyer.performCommand(command));
            assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("Only operators")));
        }
        run("donation top");
        var lines = drain(admin).stream().map(this::plain).toList();
        assertTrue(lines.contains("No donations recorded yet."));
        assertTrue(lines.contains("Grand total donated: $0.00"));
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "donation total"));
        assertTrue(plugin.onTabComplete(admin, plugin.getCommand("donation"), "donation", new String[]{"to"}).containsAll(List.of("top", "total")));
        assertTrue(plugin.onTabComplete(buyer, plugin.getCommand("donation"), "donation", new String[]{""}).isEmpty());
    }

    @Test void pastedMultilineCommandsCannotChangeCatalog() throws Exception {
        var path = plugin.getDataFolder().toPath().resolve("config.yml");
        String before = Files.readString(path);
        for (String action : List.of("create", "rename")) {
            String[] args = action.equals("create") ? new String[]{"create", "bad", "10", "Vault\ndonation create evil 1 Evil"}
                    : new String[]{"rename", "nickname", "Vault\r\ndonation create evil 1 Evil"};
            plugin.onCommand(admin, plugin.getCommand("donation"), "donation", args);
            assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.contains("one line at a time")));
            assertEquals(before, Files.readString(path));
        }
    }

    private List<String> captureConsoleCommands(String name) {
        var calls = new ArrayList<String>();
        server.getCommandMap().register("test", new org.bukkit.command.Command(name) {
            @Override public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                assertInstanceOf(org.bukkit.command.ConsoleCommandSender.class, sender);
                calls.add(String.join(" ", args));
                return true;
            }
        });
        return calls;
    }

    @Test void explicitActivationAndDeactivationRunEachNodeAsConsoleWithoutChangingPayments() throws Exception {
        var added = captureConsoleCommands("manuaddp");
        var removed = captureConsoleCommands("manudelp");
        run("donation addpermission nickname -example.denied");
        run("adddonation Buyer nickname");
        assertTrue(added.isEmpty());
        buyer.disconnect();
        var file = plugin.getDataFolder().toPath().resolve("players.yml");
        String before = Files.readString(file);
        run("donation activate Buyer nickname");
        assertEquals(List.of("Buyer essentials.nick", "Buyer essentials.nick.color", "Buyer essentials.nick.format", "Buyer -example.denied"), added);
        run("donation deactivate Buyer nickname");
        assertEquals(added, removed);
        assertEquals(before, Files.readString(file));
        run("removedonation Buyer nickname");
        assertEquals(4, removed.size()); // Removal tracks payments only.
    }

    @Test void permissionActionsAreOpOnlyAndTemplatesCanBeChangedAndReloaded() throws Exception {
        var calls = captureConsoleCommands("customperms");
        for (String command : List.of("donation activate Buyer nickname", "donation deactivate Buyer nickname")) {
            buyer.performCommand(command);
            assertTrue(drain(buyer).stream().map(this::plain).anyMatch(line -> line.contains("Only operators")));
        }
        var path = plugin.getDataFolder().toPath().resolve("config.yml");
        var yaml = FilesSupport.read(path);
        yaml.set("permission-commands.activate", "customperms grant {uuid} {permission}");
        yaml.set("permission-commands.deactivate", "customperms revoke {player} {permission}");
        FilesSupport.write(path, yaml); run("mydonations reload");
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "donation activate Buyer feed"));
        run("donation deactivate Buyer feed");
        assertEquals(List.of("grant " + buyer.getUniqueId() + " essentials.feed", "revoke Buyer essentials.feed"), calls);
        assertEquals(0, records().purchases(buyer.getUniqueId()).size());
        assertTrue(plugin.onTabComplete(admin, plugin.getCommand("donation"), "donation", new String[]{"activate", "Bu"}).contains("Buyer"));
        assertTrue(plugin.onTabComplete(admin, plugin.getCommand("donation"), "donation", new String[]{"deactivate", "Buyer", "nick"}).contains("nickname"));
    }

    @Test void permissionActionsReportMissingNodesDisabledActionsAndUnavailableCommands() throws Exception {
        var calls = captureConsoleCommands("manuaddp");
        run("donation activate Buyer eff7");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.contains("No permission nodes")));
        assertTrue(calls.isEmpty());
        var path = plugin.getDataFolder().toPath().resolve("config.yml");
        var yaml = FilesSupport.read(path); yaml.set("permission-commands.activate", "");
        FilesSupport.write(path, yaml); run("mydonations reload"); drain(admin);
        run("donation activate Buyer nickname");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.contains("Set permission-commands.activate")));
        yaml.set("permission-commands.activate", "missingpermissionplugin {player} {permission}");
        FilesSupport.write(path, yaml); run("mydonations reload"); drain(admin);
        run("donation activate Buyer feed");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.contains("Submitted 0/1")));
        assertTrue(calls.isEmpty());
        assertEquals(0, records().purchases(buyer.getUniqueId()).size());
    }

    @Test void activeVersionAndWebsiteAreCorrectAndVersionCommandRequiresOp() {
        run("mydonations version");
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.equals("MegaCityDonations v1.0.2")));
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
        assertTrue(drain(admin).stream().map(this::plain).anyMatch(line -> line.contains("Unknown player") || line.contains("still loading")));
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
