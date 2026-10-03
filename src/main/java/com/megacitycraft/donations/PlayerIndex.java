package com.megacitycraft.donations;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads identities and filenames only; never opens Minecraft player NBT. */
final class PlayerIndex {
    static Map<UUID, String> load(Path cache, List<Path> worlds) throws IOException {
        Map<UUID, String> players = new LinkedHashMap<>();
        for (Path location : worlds) {
            // Since 26.1 a Bukkit world's path is its dimension directory.
            Path world = location;
            for (Path ancestor = location; ancestor != null; ancestor = ancestor.getParent()) {
                if (ancestor.getFileName() != null && ancestor.getFileName().toString().equals("dimensions")) {
                    world = ancestor.getParent();
                    break;
                }
            }
            if (world == null) continue;
            for (Path directory : List.of(world.resolve("players/data"), world.resolve("playerdata"))) {
                if (!Files.isDirectory(directory)) continue;
                try (var files = Files.list(directory)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".dat")).forEach(path -> {
                        String file = path.getFileName().toString();
                        try {
                            UUID id = UUID.fromString(file.substring(0, file.length() - 4));
                            players.putIfAbsent(id, id.toString());
                        } catch (IllegalArgumentException ignored) { }
                    });
                }
            }
        }
        if (Files.isRegularFile(cache)) {
            try (var reader = Files.newBufferedReader(cache)) {
                for (var element : JsonParser.parseReader(reader).getAsJsonArray()) {
                    var entry = element.getAsJsonObject();
                    UUID id = UUID.fromString(entry.get("uuid").getAsString());
                    String name = entry.get("name").getAsString();
                    if (players.containsKey(id) && name.matches("[A-Za-z0-9_]{1,16}")) players.put(id, name);
                }
            } catch (RuntimeException error) {
                throw new IOException("Invalid usercache.json; saved plugin identities remain available.", error);
            }
        }
        return Map.copyOf(players);
    }
}
