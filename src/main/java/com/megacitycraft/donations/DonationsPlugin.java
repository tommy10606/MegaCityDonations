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
import org.bukkit.OfflinePlayer;
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
    private volatile boolean stopping;
    private record Target(UUID uuid, String name) { }
    private Path configPath() { return getDataFolder().toPath().resolve("config.yml"); }
    private Path playersPath() { return getDataFolder().toPath().resolve("players.yml"); }

    @Override public void onEnable() {
        stopping = false;
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
        if (getConfig().getBoolean("update-checker.enabled", true)) {
            updateTask = getServer().getScheduler().runTaskLaterAsynchronously(this, this::checkForUpdates, 20L);
        }
    }

    @Override public void onDisable() {
        stopping = true;
        if (updateTask != null) updateTask.cancel();
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
        if (args.length != 2) throw new IllegalArgumentException("Usage: /" + command + " PLAYER DONATIONID");
        Target player = resolve(args[0]);
        if (command.equalsIgnoreCase("adddonation")) {
            var feature = catalog.get(args[1]);
            store.add(player.uuid(), player.name(), feature);
            success(sender, "Recorded " + feature.name() + " (" + feature.id() + ") for " + player.name()
                    + ": " + catalog.money(feature.price()) + ". Total: " + catalog.money(store.total(player.uuid())));
            getLogger().info(sender.getName() + " added " + feature.id() + " for " + player.name() + " (" + player.uuid() + "): " + catalog.money(feature.price()));
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
        // Local server records only: no name lookup against Mojang, and no
        // invented offline UUID for a typo or a player who has never joined.
        for (OfflinePlayer player : getServer().getOfflinePlayers()) {
            if ((player.hasPlayedBefore() || player.isOnline()) && (player.getUniqueId().equals(requestedId)
                    || player.getName() != null && player.getName().equalsIgnoreCase(input)))
                matches.put(player.getUniqueId(), new Target(player.getUniqueId(), player.getName() == null ? input : player.getName()));
        }
        if (matches.isEmpty()) throw new IllegalArgumentException("Unknown player: " + input + ". They must have joined this server before.");
        if (matches.size() > 1) throw new IllegalArgumentException("More than one UUID matches that name. Use the player's UUID instead.");
        return matches.values().iterator().next();
    }

    private void manageCatalog(CommandSender sender, String[] args) throws IOException, InvalidConfigurationException {
        requireAdmin(sender);
        if (args.length == 0) { catalogHelp(sender); return; }
        String action = args[0].toLowerCase(Locale.ROOT);
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
            success(sender, "Permission nodes (display only): " + (feature.permissions().isEmpty() ? "None" : String.join(", ", feature.permissions())));
            return;
        }
        if (args.length < 3) { catalogHelp(sender); return; }
        if (!List.of("create", "rename", "price", "repeatable", "addpermission", "removepermission").contains(action)) {
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

    private String join(String[] args, int start) { return String.join(" ", Arrays.copyOfRange(args, start, args.length)); }
    private void success(CommandSender sender, String message) { sender.sendMessage(Component.text(message, NamedTextColor.GREEN)); }
    private void catalogHelp(CommandSender sender) {
        for (String usage : List.of("/donation list", "/donation info ID", "/donation create ID PRICE NAME...",
                "/donation rename ID NAME...", "/donation price ID AMOUNT", "/donation repeatable ID true|false",
                "/donation addpermission ID NODE", "/donation removepermission ID NODE"))
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
                if (args.length == 2) {
                    choices = new ArrayList<>(catalog.features.keySet());
                    if (name.equals("removedonation")) {
                        try { for (var purchase : store.purchases(resolve(args[0]).uuid())) choices.add(purchase.donationId()); }
                        catch (IllegalArgumentException ignored) { }
                    }
                }
            } else if (name.equals("donation")) {
                if (args.length == 1) choices = List.of("list", "info", "create", "rename", "price", "repeatable", "addpermission", "removepermission");
                if (args.length == 2 && !args[0].equalsIgnoreCase("create")) choices = new ArrayList<>(catalog.features.keySet());
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
        for (OfflinePlayer player : getServer().getOfflinePlayers()) if (player.getName() != null && player.hasPlayedBefore()) names.add(player.getName());
        return names.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
