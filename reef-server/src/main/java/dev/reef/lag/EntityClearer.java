package dev.reef.lag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Trident;

/**
 * Reef's built-in clearer for the junk that piles up and costs ticks: dropped items, arrows stuck
 * in blocks and experience orbs. Mobs are never touched; they belong to players' farms.
 *
 *  - "/reef clear <items|arrows|xp|all> [warning seconds]": now, after a warning in chat
 *  - on a schedule, if turned on in reef.yml (off by default)
 *  - chunk limits (on by default): when one chunk holds more than the limit, the oldest are removed,
 *    so a broken farm can't build a pile of thousands
 *
 * Named items (renamed in an anvil) and thrown tridents are always kept. On a shard only the chunks
 * it owns are touched.
 */
public final class EntityClearer {

    private static final Logger LOGGER = Logger.getLogger("Reef");

    public enum Kind { ITEMS, ARROWS, XP }

    // ---- config
    public static volatile boolean scheduled = false;
    public static volatile int intervalMinutes = 30;
    public static volatile int warnSeconds = 60;
    public static volatile List<Kind> scheduledKinds = List.of(Kind.ITEMS, Kind.ARROWS, Kind.XP);
    public static volatile boolean chunkLimits = true;
    public static volatile int maxItemsPerChunk = 300;
    public static volatile int maxArrowsPerChunk = 100;
    public static volatile int maxXpPerChunk = 100;

    // ---- state (main thread only)
    private static long tick = 0;
    private static long nextScheduledTick = -1;
    private static Pending pending = null;

    private record Pending(List<Kind> kinds, long clearAtTick) {}

    private EntityClearer() {
    }

    public static void load(final YamlConfiguration config) {
        final String path = "lag.entity-clearer";
        config.setComments(path, List.of(
            "Clears dropped items, arrows stuck in blocks and experience orbs. Mobs are never touched.",
            "Named items and thrown tridents are always kept. \"/reef clear <items|arrows|xp|all> [seconds]\" clears now."
        ));
        scheduled = DynamicDistance.bool(config, path + ".scheduled.enabled", false, "Clear on a schedule.");
        intervalMinutes = Math.max(1, DynamicDistance.integer(config, path + ".scheduled.every-minutes", 30, "How often."));
        warnSeconds = Math.max(0, DynamicDistance.integer(config, path + ".scheduled.warn-seconds", 60, "Warn players this long before."));
        scheduledKinds = parseKinds(DynamicDistance.strings(config, path + ".scheduled.what", List.of("items", "arrows", "xp"), "What to clear: items, arrows, xp."));
        chunkLimits = DynamicDistance.bool(config, path + ".chunk-limits.enabled", true,
            "Every 30 seconds, remove the oldest of these beyond the limit in any one chunk.");
        maxItemsPerChunk = Math.max(1, DynamicDistance.integer(config, path + ".chunk-limits.items", 300, "Dropped items per chunk."));
        maxArrowsPerChunk = Math.max(1, DynamicDistance.integer(config, path + ".chunk-limits.arrows", 100, "Arrows stuck in blocks per chunk."));
        maxXpPerChunk = Math.max(1, DynamicDistance.integer(config, path + ".chunk-limits.xp", 100, "Experience orbs per chunk."));
        nextScheduledTick = -1; // re-plan with the new interval
    }

    /** "items", "arrows", "xp" or "all" (case-insensitive). Unknown words are ignored. */
    public static List<Kind> parseKinds(final List<String> words) {
        final List<Kind> kinds = new ArrayList<>();
        for (final String word : words) {
            switch (word.toLowerCase(Locale.ROOT).trim()) {
                case "all" -> {
                    return List.of(Kind.ITEMS, Kind.ARROWS, Kind.XP);
                }
                case "items", "item" -> kinds.add(Kind.ITEMS);
                case "arrows", "arrow" -> kinds.add(Kind.ARROWS);
                case "xp", "orbs", "experience" -> kinds.add(Kind.XP);
                default -> {
                }
            }
        }
        return List.copyOf(new java.util.LinkedHashSet<>(kinds));
    }

    /** Schedules a clear after a warning. Returns false if one is already counting down. */
    public static boolean clearAfter(final List<Kind> kinds, final int seconds) {
        if (pending != null || kinds.isEmpty()) {
            return false;
        }
        pending = new Pending(kinds, tick + Math.max(0, seconds) * 20L);
        if (seconds > 0) {
            warn(kinds, seconds);
        }
        return true;
    }

    /** Called every tick. */
    public static void tick() {
        tick++;
        if (pending != null) {
            final long left = pending.clearAtTick() - tick;
            if (left <= 0) {
                final List<Kind> kinds = pending.kinds();
                pending = null;
                final int removed = clearNow(kinds);
                Bukkit.broadcast(Component.text("Cleared " + removed + " " + describe(kinds) + ".", NamedTextColor.GRAY));
                LOGGER.info("Entity clearer: removed " + removed + " " + describe(kinds));
            } else if (left % 20 == 0 && (left / 20 == 30 || left / 20 == 10 || left / 20 == 5)) {
                warn(pending.kinds(), (int) (left / 20));
            }
        }

        if (scheduled) {
            if (nextScheduledTick < 0) {
                nextScheduledTick = tick + intervalMinutes * 1200L;
            } else if (tick >= nextScheduledTick) {
                nextScheduledTick = tick + intervalMinutes * 1200L;
                clearAfter(scheduledKinds, warnSeconds);
            }
        }

        if (chunkLimits && tick % 600 == 0) {
            final int removed = enforceChunkLimits();
            if (removed > 0) {
                LOGGER.info("Entity clearer: removed " + removed + " entities over the per-chunk limits");
            }
        }
    }

    /** Removes everything of these kinds right away (main thread). Returns how many. */
    public static int clearNow(final List<Kind> kinds) {
        int removed = 0;
        for (final World world : Bukkit.getWorlds()) {
            for (final Entity entity : world.getEntities()) {
                final Kind kind = kindOf(entity);
                if (kind != null && kinds.contains(kind) && owned(entity)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** Removes the oldest junk beyond each chunk's limit. Returns how many. */
    static int enforceChunkLimits() {
        int removed = 0;
        for (final World world : Bukkit.getWorlds()) {
            final Map<Long, Map<Kind, List<Entity>>> byChunk = new HashMap<>();
            for (final Entity entity : world.getEntities()) {
                final Kind kind = kindOf(entity);
                if (kind != null && owned(entity)) {
                    final long key = (((long) (entity.getLocation().getBlockX() >> 4)) & 0xffffffffL) | ((((long) (entity.getLocation().getBlockZ() >> 4)) & 0xffffffffL) << 32);
                    byChunk.computeIfAbsent(key, k -> new HashMap<>()).computeIfAbsent(kind, k -> new ArrayList<>()).add(entity);
                }
            }
            for (final Map<Kind, List<Entity>> kinds : byChunk.values()) {
                for (final Map.Entry<Kind, List<Entity>> entry : kinds.entrySet()) {
                    final int limit = limitFor(entry.getKey());
                    final List<Entity> list = entry.getValue();
                    if (list.size() <= limit) {
                        continue;
                    }
                    list.sort((a, b) -> Integer.compare(b.getTicksLived(), a.getTicksLived())); // oldest first
                    for (int i = 0; i < list.size() - limit; i++) {
                        list.get(i).remove();
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    /** What kind of junk an entity is, or null if it's never cleared. */
    static Kind kindOf(final Entity entity) {
        if (entity instanceof final Item item) {
            final var stack = item.getItemStack();
            final boolean named = item.customName() != null || (stack.hasItemMeta() && stack.getItemMeta().hasDisplayName());
            return named ? null : Kind.ITEMS;
        }
        if (entity instanceof Trident) {
            return null; // players want their tridents back
        }
        if (entity instanceof final AbstractArrow arrow) {
            return arrow.isInBlock() ? Kind.ARROWS : null; // arrows still flying are left alone
        }
        if (entity instanceof ExperienceOrb) {
            return Kind.XP;
        }
        return null;
    }

    private static boolean owned(final Entity entity) {
        return dev.reef.sharding.Sharding.ownsBlock(entity.getWorld().getName(), entity.getLocation().getX(), entity.getLocation().getZ());
    }

    private static int limitFor(final Kind kind) {
        return switch (kind) {
            case ITEMS -> maxItemsPerChunk;
            case ARROWS -> maxArrowsPerChunk;
            case XP -> maxXpPerChunk;
        };
    }

    private static void warn(final List<Kind> kinds, final int seconds) {
        Bukkit.broadcast(Component.text("Clearing " + describe(kinds) + " in " + seconds + " second" + (seconds == 1 ? "" : "s") + ". Pick up anything you want to keep!", NamedTextColor.YELLOW));
    }

    public static String describe(final List<Kind> kinds) {
        final List<String> words = new ArrayList<>();
        for (final Kind kind : kinds) {
            words.add(switch (kind) {
                case ITEMS -> "ground items";
                case ARROWS -> "arrows";
                case XP -> "experience orbs";
            });
        }
        return String.join(", ", words);
    }

    public static String status() {
        return (pending != null ? "clearing in " + Math.max(0, (pending.clearAtTick() - tick) / 20) + "s; " : "")
            + (scheduled ? "every " + intervalMinutes + " min" : "on demand")
            + (chunkLimits ? ", chunk limits " + maxItemsPerChunk + " items / " + maxArrowsPerChunk + " arrows / " + maxXpPerChunk + " orbs" : "");
    }
}
