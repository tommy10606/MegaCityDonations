package com.megacitycraft.donations;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerIndexTest {
    @TempDir Path root;
    @Test void indexesModernAndLegacyFilesWithoutReadingOrConvertingPlayerData() throws Exception {
        UUID modern = UUID.randomUUID(), legacy = UUID.randomUUID(), neverJoined = UUID.randomUUID();
        Path world = root.resolve("world");
        Files.createDirectories(world.resolve("players/data"));
        Files.createDirectories(world.resolve("playerdata"));
        // Deliberately invalid NBT: the index must only inspect filenames.
        Path data = world.resolve("players/data/" + modern + ".dat");
        Files.writeString(data, "not NBT");
        Files.writeString(world.resolve("playerdata/" + legacy + ".dat"), "not NBT either");
        Files.writeString(world.resolve("playerdata/invalid.dat"), "ignore");
        Path cache = root.resolve("usercache.json");
        Files.writeString(cache, "[{\"uuid\":\"" + modern + "\",\"name\":\"OldPlayer\"},{\"uuid\":\""
                + neverJoined + "\",\"name\":\"NeverJoined\"}]");
        var index = PlayerIndex.load(cache, List.of(world.resolve("dimensions/minecraft/overworld")));
        assertEquals("OldPlayer", index.get(modern));
        assertEquals(legacy.toString(), index.get(legacy));
        assertFalse(index.containsKey(neverJoined));
        assertEquals(2, index.size());
        assertEquals("not NBT", Files.readString(data));
        assertThrows(UnsupportedOperationException.class, () -> index.put(neverJoined, "Other"));
    }
    @Test void missingCacheStillSupportsOfflineUuidTargets() throws Exception {
        UUID id = UUID.randomUUID();
        Files.createDirectories(root.resolve("players/data"));
        Files.writeString(root.resolve("players/data/" + id + ".dat"), "ignored");
        assertEquals(id.toString(), PlayerIndex.load(root.resolve("missing.json"), List.of(root)).get(id));
    }
}
