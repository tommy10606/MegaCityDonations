package com.megacitycraft.donations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class DonationsPlugin extends JavaPlugin implements Listener {
    private Catalog catalog;
    private DonationStore store;
    private BukkitTask updateTask;
    private BukkitTask indexTask;
    private volatile java.util.Map<UUID, String> indexedPlayers = java.util.Map.of();
    private volatile boolean indexReady;
    private volatile boolean stopping;
    private record Target(UUID uuid, String name) { }
    private Path configPath() { return getDataFolder().toPath().resolve("config.yml"); }
    private Path playersPath() { return getDataFolder().toPath().resolve("players.yml"); }

    @Override public void onEnable() {
        stopping = false;
        indexReady = false;
        saveDefaultConfig();
        try {
            loadFiles();
            for (Player player : getServer().getOnlinePlayers()) store.remember(player.getUniqueId(), player.getName());
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException error) {
            getLogger().log(Level.SEVERE, "Unable to load donation files; disabling to protect existing records.", error);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        for (String name : List.of("mydonations", "adddonation", "removedonation", "donation")) {
            var command = Objects.requireNonNull(getCommand(name));
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        // Capture Bukkit world paths on the game thread; do all scanning off-thread.
        var worlds = getServer().getWorlds().stream().map(world -> world.getWorldPath()).toList();
        var cache = Path.of("usercache.json").toAbsolutePath();
        indexTask = getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                var loaded = PlayerIndex.load(cache, worlds);
                if (!stopping) indexedPlayers = loaded;
            } catch (IOException error) {
                if (!stopping) getLogger().warning("Offline player index unavailable: " + error.getMessage());
            } finally { indexReady = true; }
        });
        if (getConfig().getBoolean("update-checker.enabled", true)) {
            updateTask = getServer().getScheduler().runTaskLaterAsynchronously(this, this::checkForUpdates, 20L);
        }
    }

    @Override public void onDisable() {
        stopping = true;
        if (updateTask != null) updateTask.cancel();
        if (indexTask != null) indexTask.cancel();
    }

    private void checkForUpdates() {
        if (stopping) return;
        try {
            var result = new ReleaseChecker().check(getPluginMeta().getVersion());
            if (stopping) return;
            if (result.latestTag() == null) {
                getLogger().info("No published stable GitHub release found; update status is unknown.");
            } else if (result.updateAvailable()) {
                getLogger().info("Update Available!");
                getLogger().info("Running v" + getPluginMeta().getVersion() + "; latest "
                        + result.latestTag() + ". Download: " + ReleaseChecker.RELEASES_URL);
            } else {
                getLogger().info("Running the most up to date version.");
            }
        } catch (IOException error) {
            if (!stopping) getLogger().warning("Update check unavailable: " + error.getMessage()
                    + ". Donation tracking is unaffected; will check again on the next server boot.");
        }
    }

    private void loadFiles() throws IOException, InvalidConfigurationException {
        // Validate both files before replacing either live object.
        Catalog nextCatalog = Catalog.load(configPath());
        DonationStore nextStore = new DonationStore(playersPath());
        catalog = nextCatalog;
        store = nextStore;
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        try {
            store.remember(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        } catch (IOException error) {
            getLogger().log(Level.SEVERE, "Could not save player identity for offline donation assignments.", error);
        }
    }

    private boolean admin(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender
                || sender instanceof Player player && player.isOp();
    }
    private void requireAdmin(CommandSender sender) {
        if (!admin(sender)) throw new IllegalArgumentException("Only operators and the server console can use this command.");
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (Arrays.stream(args).anyMatch(Catalog::hasLineBreak))
                throw new IllegalArgumentException("Commands must be entered one line at a time. Pasted line breaks are not allowed.");
            switch (command.getName().toLowerCase(Locale.ROOT)) {
                case "mydonations" -> myDonations(sender, args);
                case "adddonation", "removedonation" -> recordPurchase(sender, command.getName(), args);
                case "donation" -> manageCatalog(sender, args);
                default -> throw new IllegalArgumentException("Unknown command.");
            }
        } catch (IllegalArgumentException error) {
            sender.sendMessage(Component.text(error.getMessage(), NamedTextColor.RED));
        } catch (InvalidConfigurationException error) {
            sender.sendMessage(Component.text("Invalid YAML. Fix the file and reload; the previous live configuration is still active.", NamedTextColor.RED));
            getLogger().log(Level.WARNING, "Invalid donation YAML.", error);
        } catch (IOException error) {
            sender.sendMessage(Component.text("Could not read or save donation files. Check the server console; the change was not applied.", NamedTextColor.RED));
            getLogger().log(Level.SEVERE, "Donation file operation failed.", error);
        }
        return true;
    }

    private void myDonations(CommandSender sender, String[] args) throws IOException, InvalidConfigurationException {
        if (args.length == 1 && args[0].equalsIgnoreCase("version")) {
            requireAdmin(sender);
            sender.sendMessage(Component.text("MegaCityDonations v" + getPluginMeta().getVersion(), NamedTextColor.GOLD));
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            requireAdmin(sender);
            loadFiles();
            success(sender, "MegaCityDonations reloaded. Player records were preserved.");
            return;
        }
        Target target;
        int page = 1;
        boolean self;
        if (args.length == 0 || args.length == 1 && args[0].matches("[0-9]+")) {
            if (!(sender instanceof Player player)) throw new IllegalArgumentException("Usage: /mydonations PLAYER [PAGE]");
            target = new Target(player.getUniqueId(), player.getName());
            if (args.length == 1) page = pageNumber(args[0]);
            self = true;
        } else if (args.length == 1 || args.length == 2) {
            requireAdmin(sender);
            target = resolve(args[0]);
            if (args.length == 2) page = pageNumber(args[1]);
            self = false;
        } else throw new IllegalArgumentException("Usage: /mydonations [PAGE] or /mydonations PLAYER [PAGE]");
        showDonations(sender, target, page, self);
    }

    private int pageNumber(String value) {
        try {
            int number = Integer.parseInt(value);
            if (number >= 1) return number;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Page must be a positive whole number.");
    }

    private void showDonations(CommandSender sender, Target target, int page, boolean self) {
        var purchases = store.purchases(target.uuid());
        int pages = Math.max(1, (purchases.size() + catalog.pageSize - 1) / catalog.pageSize);
        if (page > pages) throw new IllegalArgumentException("That page does not exist. Available pages: 1–" + pages);
        sender.sendMessage(Component.text("♦ " + (self ? "Your Donations" : target.name() + "'s Donations"), NamedTextColor.AQUA));
        sender.sendMessage(Component.text(purchases.size() + " purchases · Total donated: ", NamedTextColor.GRAY)
                .append(Component.text(catalog.money(store.total(target.uuid())), NamedTextColor.GOLD)));
        int start = (page - 1) * catalog.pageSize;
        for (var purchase : purchases.subList(start, Math.min(start + catalog.pageSize, purchases.size()))) {
            Catalog.Feature feature = catalog.features.get(purchase.donationId());
            Component name = Component.text(feature == null ? purchase.name() : feature.name(), NamedTextColor.WHITE);
            if (feature != null && !feature.permissions().isEmpty()) {
                Component hover = Component.text("Permission nodes", NamedTextColor.AQUA);
                for (String node : feature.permissions()) hover = hover.append(Component.newline()).append(Component.text(node, NamedTextColor.GRAY));
                name = name.hoverEvent(HoverEvent.showText(hover));
            }
            sender.sendMessage(Component.text(" • ", NamedTextColor.AQUA).append(name)
                    .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(catalog.money(purchase.paid()), NamedTextColor.GOLD)));
        }
        if (purchases.isEmpty()) sender.sendMessage(Component.text("No donations recorded yet.", NamedTextColor.GRAY));
        String base = "/mydonations" + (self ? "" : " " + target.name());
        Component footer = Component.text("Page " + page + "/" + pages, NamedTextColor.GRAY);
        if (page > 1) footer = Component.text("[‹ Previous] ", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(base + " " + (page - 1))).append(footer);
        if (page < pages) footer = footer.append(Component.text(" [Next ›]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(base + " " + (page + 1))));
        sender.sendMessage(footer);
    }

    private void recordPurchase(CommandSender sender, String command, String[] args) throws IOException {
        requireAdmin(sender);
        boolean adding = command.equalsIgnoreCase("adddonation");
        boolean override = adding && args.length == 3 && args[2].equalsIgnoreCase("--override");
        if (args.length != 2 && !override) throw new IllegalArgumentException("Usage: /" + command + " PLAYER DONATIONID" + (adding ? " [--override]" : ""));
        Target player = resolve(args[0]);
        if (command.equalsIgnoreCase("adddonation")) {
            var feature = catalog.get(args[1]);
            store.add(player.uuid(), player.name(), feature, override);
            success(sender, "Recorded " + feature.name() + " (" + feature.id() + ") for " + player.name()
                    + ": " + catalog.money(feature.price()) + ". Total: " + catalog.money(store.total(player.uuid())) + (override ? " (Dependency override used.)" : ""));
            getLogger().info(sender.getName() + " added " + feature.id() + " for " + player.name() + " (" + player.uuid() + "): " + catalog.money(feature.price()) + (override ? " [DEPENDENCY OVERRIDE]" : ""));
        } else {
            var removed = store.removeLatest(player.uuid(), player.name(), args[1]);
            success(sender, "Removed the latest " + removed.donationId() + " purchase for " + player.name()
                    + ": " + catalog.money(removed.paid()) + ". Total: " + catalog.money(store.total(player.uuid())));
            getLogger().info(sender.getName() + " removed " + removed.donationId() + " for " + player.name() + " (" + player.uuid() + "): " + catalog.money(removed.paid()));
        }
    }

    private Target resolve(String input) {
        Player online = getServer().getPlayerExact(input);
        if (online != null) return new Target(online.getUniqueId(), online.getName());
        var matches = new LinkedHashMap<UUID, Target>();
        UUID requestedId = null;
        try { requestedId = UUID.fromString(input); } catch (IllegalArgumentException ignored) { }
        for (var entry : store.accounts().entrySet()) {
            if (entry.getKey().equals(requestedId) || entry.getValue().name().equalsIgnoreCase(input))
                matches.put(entry.getKey(), new Target(entry.getKey(), entry.getValue().name()));
        }
        for (var entry : indexedPlayers.entrySet()) {
            if (entry.getKey().equals(requestedId) || entry.getValue().equalsIgnoreCase(input))
                matches.putIfAbsent(entry.getKey(), new Target(entry.getKey(), entry.getValue()));
        }
        if (matches.isEmpty()) {
            if (!indexReady) throw new IllegalArgumentException("Offline player index is still loading. Please try again shortly.");
            throw new IllegalArgumentException("Unknown player: " + input + ". Use their UUID if their name is no longer cached, or have them join once.");
        }
        if (matches.size() > 1) throw new IllegalArgumentException("More than one UUID matches that name. Use the player's UUID instead.");
        return matches.values().iterator().next();
    }

    private void manageCatalog(CommandSender sender, String[] args) throws IOException, InvalidConfigurationException {
        requireAdmin(sender);
        if (args.length == 0) { catalogHelp(sender); return; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("activate") || action.equals("deactivate")) {
            if (args.length != 3) throw new IllegalArgumentException("Usage: /donation " + action + " PLAYER DONATIONID");
            runPermissionCommands(sender, action, resolve(args[1]), catalog.get(args[2]));
            return;
        }
        if (action.equals("top") || action.equals("leaderboard")) {
            if (args.length > 2) throw new IllegalArgumentException("Usage: /donation top [PAGE]");
            showLeaderboard(sender, args.length == 2 ? pageNumber(args[1]) : 1);
            return;
        }
        if (action.equals("total")) {
            if (args.length != 1) throw new IllegalArgumentException("Usage: /donation total");
            showGrandTotal(sender);
            return;
        }
        if (action.equals("list") && args.length == 1) {
            sender.sendMessage(Component.text("Donation IDs (" + catalog.features.size() + ")", NamedTextColor.AQUA));
            for (var feature : catalog.features.values()) sender.sendMessage(
                    Component.text(feature.id(), NamedTextColor.WHITE)
                            .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(catalog.money(feature.price()), NamedTextColor.GOLD))
                            .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(feature.repeatable() ? "Repeatable" : "Not Repeatable", NamedTextColor.GRAY)));
            return;
        }
        if (action.equals("info") && args.length == 2) {
            var feature = catalog.get(args[1]);
            sender.sendMessage(Component.text("Donation ID: " + feature.id(), NamedTextColor.AQUA));
            success(sender, "Name: " + feature.name() + " · Price: " + catalog.money(feature.price()));
            success(sender, "Repeatable: " + feature.repeatable());
            success(sender, "Requires (all): " + (feature.requires().isEmpty() ? "None" : String.join(", ", feature.requires())));
            success(sender, "Permission nodes (hover and manual activation): " + (feature.permissions().isEmpty() ? "None" : String.join(", ", feature.permissions())));
            return;
        }
        if (args.length < 3) { catalogHelp(sender); return; }
        if (!List.of("create", "rename", "price", "repeatable", "addpermission", "removepermission", "adddependency", "removedependency").contains(action)) {
            catalogHelp(sender); return;
        }
        // Preserve manual edits already on disk when a catalog command saves.
        Catalog current = Catalog.load(configPath());
        var next = current.copy();
        String id = Catalog.normalizeId(args[1]);
        String path = "donations." + id;
        if (action.equals("create")) {
            if (args.length < 4) throw new IllegalArgumentException("Usage: /donation create ID PRICE NAME...");
            if (current.features.containsKey(id)) throw new IllegalArgumentException("That donation ID already exists.");
            next.set(path + ".price", Catalog.amount(args[2]).toPlainString());
            next.set(path + ".name", join(args, 3));
            next.set(path + ".repeatable", false);
            next.set(path + ".permissions", List.of());
            next.set(path + ".requires", List.of());
        } else {
            var feature = current.get(id);
            if (!action.equals("rename") && args.length != 3)
                throw new IllegalArgumentException("Usage: /donation " + action + " ID VALUE");
            switch (action) {
                case "rename" -> next.set(path + ".name", join(args, 2));
                case "price" -> next.set(path + ".price", Catalog.amount(args[2]).toPlainString());
                case "repeatable" -> {
                    if (!args[2].equalsIgnoreCase("true") && !args[2].equalsIgnoreCase("false"))
                        throw new IllegalArgumentException("Usage: /donation repeatable ID true|false");
                    next.set(path + ".repeatable", Boolean.parseBoolean(args[2]));
                }
                case "adddependency", "removedependency" -> {
                    String required = Catalog.normalizeId(args[2]);
                    List<String> requires = new ArrayList<>(feature.requires());
                    if (action.equals("adddependency")) {
                        current.get(required);
                        if (requires.contains(required)) throw new IllegalArgumentException("That dependency is already listed.");
                        requires.add(required);
                    } else if (!requires.remove(required)) throw new IllegalArgumentException("That dependency is not listed.");
                    next.set(path + ".requires", requires);
                }
                case "addpermission", "removepermission" -> {
                    String node = Catalog.permission(args[2]);
                    List<String> nodes = new ArrayList<>(feature.permissions());
                    if (action.equals("addpermission")) {
                        if (nodes.contains(node)) throw new IllegalArgumentException("That permission node is already listed.");
                        nodes.add(node);
                    } else if (!nodes.remove(node)) throw new IllegalArgumentException("That permission node is not listed.");
                    next.set(path + ".permissions", nodes);
                }
            }
        }
        Catalog validated = Catalog.parse(next);
        validated.save(configPath());
        catalog = validated;
        success(sender, "Saved " + id + ". Changes are active immediately; past payment amounts are unchanged.");
    }

    private void runPermissionCommands(CommandSender sender, String action, Target player, Catalog.Feature feature) {
        if (action.equals("activate") && store.purchases(player.uuid()).stream().noneMatch(purchase -> purchase.donationId().equals(feature.id())))
            throw new IllegalArgumentException(player.name() + " has no assigned donation for " + feature.id() + ". Assign it with /adddonation first.");
        String template = action.equals("activate") ? catalog.activateCommand : catalog.deactivateCommand;
        if (template.isEmpty()) throw new IllegalArgumentException("Set permission-commands." + action + " in config.yml, then run /mydonations reload.");
        if (feature.permissions().isEmpty()) throw new IllegalArgumentException("No permission nodes are configured for " + feature.id() + ".");
        if (template.contains("{player}") && !player.name().matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("No valid cached name is available for that player. Have them join once, or use a template with {uuid} instead of {player}.");
        int submitted = 0;
        for (String permission : feature.permissions()) {
            String command = template.replace("{player}", player.name()).replace("{uuid}", player.uuid().toString())
                    .replace("{permission}", permission);
            try {
                if (getServer().dispatchCommand(getServer().getConsoleSender(), command)) submitted++;
                else getLogger().warning("Permission command was not accepted: " + command);
            } catch (RuntimeException error) {
                getLogger().log(Level.WARNING, "Permission command failed: " + command, error);
            }
        }
        String message = "Submitted " + submitted + "/" + feature.permissions().size() + " " + action
                + " commands for " + player.name() + " (" + feature.id() + "). Check the permission plugin's output for results.";
        sender.sendMessage(Component.text(message, submitted == feature.permissions().size() ? NamedTextColor.GREEN : NamedTextColor.RED));
        getLogger().info(sender.getName() + " requested " + action + " for " + player.name() + " (" + player.uuid() + "), "
                + feature.id() + ": " + submitted + "/" + feature.permissions().size() + " commands accepted.");
    }

    private void showGrandTotal(CommandSender sender) {
        sender.sendMessage(Component.text("Grand total donated: ", NamedTextColor.AQUA)
                .append(Component.text(catalog.money(store.grandTotal()), NamedTextColor.GOLD)));
    }

    private void showLeaderboard(CommandSender sender, int page) {
        var donors = store.leaderboard();
        int pages = Math.max(1, (donors.size() + catalog.pageSize - 1) / catalog.pageSize);
        if (page > pages) throw new IllegalArgumentException("That page does not exist. Available pages: 1–" + pages);
        sender.sendMessage(Component.text("♦ Top Donors", NamedTextColor.AQUA));
        showGrandTotal(sender);
        int start = (page - 1) * catalog.pageSize;
        for (int index = start; index < Math.min(start + catalog.pageSize, donors.size()); index++) {
            var donor = donors.get(index);
            sender.sendMessage(Component.text((index + 1) + ". ", NamedTextColor.AQUA)
                    .append(Component.text(donor.name(), NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(catalog.money(donor.total()), NamedTextColor.GOLD)));
        }
        if (donors.isEmpty()) sender.sendMessage(Component.text("No donations recorded yet.", NamedTextColor.GRAY));
        Component footer = Component.text("Page " + page + "/" + pages, NamedTextColor.GRAY);
        if (page > 1) footer = Component.text("[‹ Previous] ", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/donation top " + (page - 1))).append(footer);
        if (page < pages) footer = footer.append(Component.text(" [Next ›]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/donation top " + (page + 1))));
        sender.sendMessage(footer);
    }

    private String join(String[] args, int start) { return String.join(" ", Arrays.copyOfRange(args, start, args.length)); }
    private void success(CommandSender sender, String message) { sender.sendMessage(Component.text(message, NamedTextColor.GREEN)); }
    private void catalogHelp(CommandSender sender) {
        for (String usage : List.of("/donation activate PLAYER DONATIONID", "/donation deactivate PLAYER DONATIONID", "/donation top [PAGE]", "/donation total", "/donation list", "/donation info ID", "/donation create ID PRICE NAME...",
                "/donation rename ID NAME...", "/donation price ID AMOUNT", "/donation repeatable ID true|false",
                "/donation addpermission ID NODE", "/donation removepermission ID NODE",
                "/donation adddependency ID REQUIRED_ID", "/donation removedependency ID REQUIRED_ID"))
            sender.sendMessage(Component.text(usage, NamedTextColor.YELLOW));
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> choices = List.of();
        if (name.equals("mydonations")) {
            if (args.length == 1) {
                var first = new ArrayList<>(List.of("1"));
                if (admin(sender)) { first.add("reload"); first.add("version"); first.addAll(playerNames()); }
                choices = first;
            }
        } else {
            if (!admin(sender)) return List.of();
            if (name.equals("adddonation") || name.equals("removedonation")) {
                if (args.length == 1) choices = playerNames();
                if (args.length == 3 && name.equals("adddonation")) choices = List.of("--override");
                if (args.length == 2) {
                    choices = new ArrayList<>(catalog.features.keySet());
                    if (name.equals("removedonation")) {
                        try { for (var purchase : store.purchases(resolve(args[0]).uuid())) choices.add(purchase.donationId()); }
                        catch (IllegalArgumentException ignored) { }
                    }
                }
            } else if (name.equals("donation")) {
                if (args.length == 2 && (args[0].equalsIgnoreCase("activate") || args[0].equalsIgnoreCase("deactivate")))
                    choices = playerNames();
                if (args.length == 3 && (args[0].equalsIgnoreCase("activate") || args[0].equalsIgnoreCase("deactivate")))
                    choices = new ArrayList<>(catalog.features.keySet());
                if (args.length == 1) choices = List.of("activate", "deactivate", "top", "leaderboard", "total", "list", "info", "create", "rename", "price", "repeatable", "addpermission", "removepermission", "adddependency", "removedependency");
                if (args.length == 2 && List.of("info", "rename", "price", "repeatable", "addpermission", "removepermission", "adddependency", "removedependency").contains(args[0].toLowerCase(Locale.ROOT)))
                    choices = new ArrayList<>(catalog.features.keySet());
                if (args.length == 2 && (args[0].equalsIgnoreCase("top") || args[0].equalsIgnoreCase("leaderboard")))
                    choices = java.util.stream.IntStream.rangeClosed(1, Math.max(1,
                            (store.leaderboard().size() + catalog.pageSize - 1) / catalog.pageSize))
                            .limit(100).mapToObj(Integer::toString).toList();
                if (args.length == 3 && args[0].equalsIgnoreCase("adddependency")) {
                    try {
                        var feature = catalog.get(args[1]);
                        choices = catalog.features.keySet().stream().filter(id -> !id.equals(feature.id()) && !feature.requires().contains(id)).toList();
                    } catch (IllegalArgumentException ignored) { }
                }
                if (args.length == 3 && args[0].equalsIgnoreCase("removedependency")) {
                    try { choices = catalog.get(args[1]).requires(); } catch (IllegalArgumentException ignored) { }
                }
                if (args.length == 3 && args[0].equalsIgnoreCase("repeatable")) choices = List.of("true", "false");
                if (args.length == 3 && args[0].equalsIgnoreCase("removepermission")) {
                    try { choices = catalog.get(args[1]).permissions(); } catch (IllegalArgumentException ignored) { }
                }
            }
        }
        if (args.length == 0) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().distinct().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(100).toList();
    }

    private List<String> playerNames() {
        var names = new ArrayList<String>();
        for (Player player : getServer().getOnlinePlayers()) names.add(player.getName());
        for (var account : store.accounts().values()) names.add(account.name());
        names.addAll(indexedPlayers.values().stream().filter(name -> !name.contains("-")).toList());
        return names.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
