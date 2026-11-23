package dev.reef.lag;

import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;

/**
 * "/reef entities": counts every entity and block entity in the loaded chunks and writes the worst
 * chunks to reef-entities.json in the server folder, for the Ginjer Panel's lag hotspots page.
 * Runs on the main thread; block entities are read without copying them.
 */
public final class EntityReport {

    public static final String FILE = "reef-entities.json";

    public static final int TOP = 30;

    private EntityReport() {
    }

    public record ChunkCount(String world, int x, int z, int entities, Map<String, Integer> types, int blockEntities, Map<String, Integer> blockTypes) {
        int load() {
            return this.entities + this.blockEntities;
        }
    }

    /** Counts everything; returns the top chunks and the totals by type. */
    public static Map<String, Object> scan() {
        final List<ChunkCount> chunks = new ArrayList<>();
        final Map<String, Integer> totals = new HashMap<>();
        int entities = 0;
        int blockEntities = 0;
        int loaded = 0;
        for (final World world : Bukkit.getWorlds()) {
            for (final Chunk chunk : world.getLoadedChunks()) {
                loaded++;
                final Map<String, Integer> types = new HashMap<>();
                final Entity[] chunkEntities = chunk.getEntities();
                for (final Entity entity : chunkEntities) {
                    types.merge(entity.getType().getKey().toString(), 1, Integer::sum);
                }
                final Map<String, Integer> blockTypes = new HashMap<>();
                final BlockState[] states = chunk.getTileEntities(false);
                for (final BlockState state : states) {
                    blockTypes.merge(state.getType().getKey().toString(), 1, Integer::sum);
                }
                types.forEach((type, count) -> totals.merge(type, count, Integer::sum));
                entities += chunkEntities.length;
                blockEntities += states.length;
                if (chunkEntities.length + states.length > 0) {
                    chunks.add(new ChunkCount(world.getName(), chunk.getX(), chunk.getZ(), chunkEntities.length, types, states.length, blockTypes));
                }
            }
        }
        chunks.sort((a, b) -> Integer.compare(b.load(), a.load()));

        final Map<String, Object> report = new LinkedHashMap<>();
        report.put("generated", System.currentTimeMillis());
        report.put("loaded_chunks", loaded);
        report.put("entities", entities);
        report.put("block_entities", blockEntities);
        report.put("totals", totals);
        final List<Map<String, Object>> top = new ArrayList<>();
        for (final ChunkCount c : chunks.subList(0, Math.min(TOP, chunks.size()))) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("world", c.world());
            row.put("x", c.x());
            row.put("z", c.z());
            row.put("entities", c.entities());
            row.put("types", c.types());
            row.put("block_entities", c.blockEntities());
            row.put("block_types", c.blockTypes());
            top.add(row);
        }
        report.put("chunks", top);
        return report;
    }

    /** Writes the report next to server.properties (atomically, so the panel never reads half a file). */
    public static File write(final Map<String, Object> report) throws IOException {
        final File file = new File(FILE).getAbsoluteFile();
        final File temp = new File(FILE + ".tmp").getAbsoluteFile();
        Files.writeString(temp.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }
}
