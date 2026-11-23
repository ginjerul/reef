package dev.reef.sharding;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Splits one world across several Reef servers behind a Velocity proxy.
 *
 * <p>Every server ("shard") has a full copy of the world but only simulates its own
 * regions. When a player crosses into a region owned by another shard, their data is
 * saved to a player data folder shared by all shards, and the proxy is asked (through
 * the BungeeCord plugin message channel, which Velocity supports) to move them. The
 * other shard then loads the same data, so they arrive at the same spot with the same
 * inventory, health, effects and vehicle.</p>
 *
 * <p>Outside its own regions a shard is read-only: players can't change blocks there
 * (or in a protection strip along each border), chunks there don't tick, and mobs
 * there are frozen.</p>
 */
public final class Sharding {

    private static final Logger LOGGER = Logger.getLogger("Reef");
    private static final Identifier BUNGEE_CHANNEL = Identifier.parse("bungeecord:main");
    private static final int ARRIVAL_GRACE_TICKS = 40;

    /**
     * An area of the sharded world owned by one server: a polygon (rectangles, circles and curves are
     * converted to one when loaded). Rectangles keep the old rule: min inclusive, max exclusive.
     */
    public static final class Region {
        private final String server;
        private final double[] xs;
        private final double[] zs;
        private final double minX, minZ, maxX, maxZ;
        private final boolean rectangle;

        Region(final String server, final double[] xs, final double[] zs, final boolean rectangle) {
            this.server = server;
            this.xs = xs;
            this.zs = zs;
            this.rectangle = rectangle;
            double x0 = Double.MAX_VALUE, z0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (int i = 0; i < xs.length; i++) {
                x0 = Math.min(x0, xs[i]);
                x1 = Math.max(x1, xs[i]);
                z0 = Math.min(z0, zs[i]);
                z1 = Math.max(z1, zs[i]);
            }
            this.minX = x0;
            this.minZ = z0;
            this.maxX = x1;
            this.maxZ = z1;
        }

        public String server() {
            return this.server;
        }

        boolean contains(final double x, final double z) {
            if (x < this.minX || x >= this.maxX || z < this.minZ || z >= this.maxZ) {
                return false;
            }
            if (this.rectangle) {
                return true;
            }
            // even-odd ray cast
            boolean inside = false;
            for (int i = 0, j = this.xs.length - 1; i < this.xs.length; j = i++) {
                if ((this.zs[i] > z) != (this.zs[j] > z)
                    && x < (this.xs[j] - this.xs[i]) * (z - this.zs[i]) / (this.zs[j] - this.zs[i]) + this.xs[i]) {
                    inside = !inside;
                }
            }
            return inside;
        }
    }

    private record SafePosition(double x, double y, double z, float yRot, float xRot) {}

    private record Handoff(String targetServer, int startTick) {}

    // ---- config ----
    public static volatile boolean enabled = false;
    public static volatile String thisServer = "";
    public static volatile String worldName = "world";
    public static volatile String sharedPlayerDataFolder = "";
    public static volatile int borderProtection = 16;
    public static volatile int handoffTimeoutTicks = 100;
    public static volatile List<Region> regions = List.of();

    // ---- state ----
    private static volatile ServerLevel shardedLevel;
    private static final Map<UUID, SafePosition> lastSafePositions = new ConcurrentHashMap<>();
    private static final Map<UUID, Handoff> handoffs = new ConcurrentHashMap<>();
    private static final Set<UUID> handedOff = ConcurrentHashMap.newKeySet();

    private Sharding() {
    }

    // ------------------------------------------------------------------ config

    public static void load(final YamlConfiguration config) {
        final String path = "sharding";
        config.setComments(path, List.of(
            "Split one world across several Reef servers behind a Velocity proxy.",
            "Every server needs the same world files and the same 'regions' list; only 'this-server' differs.",
            "Server names must match the names in Velocity's velocity.toml, and velocity.toml needs",
            "bungee-plugin-message-channel = true (the default).",
            "Region edges should be multiples of 16 so they line up with chunks."
        ));
        setDefault(config, path + ".enabled", false, "Turn sharding on.");
        setDefault(config, path + ".this-server", "server1", "This server's name in velocity.toml.");
        setDefault(config, path + ".world", "world", "The world that is split. Other worlds are not sharded.");
        setDefault(config, path + ".shared-player-data-folder", "",
            "Folder for player data that ALL shards use, e.g. /srv/reef-shared/playerdata.",
            "Must be the same folder for every shard (same machine, or a network share). Empty = this server's own folder.");
        setDefault(config, path + ".border-protection", 16,
            "Players can't change blocks within this many blocks of a border with another shard,",
            "so builds, water and lava never spill into another shard's region.");
        setDefault(config, path + ".handoff-timeout-seconds", 5,
            "If the proxy hasn't moved a player after this long, they are sent back from the border.");
        if (!config.contains(path + ".regions")) {
            config.set(path + ".regions", List.of(
                Map.of("server", "server1", "min-x", -100000, "min-z", -100000, "max-x", 0, "max-z", 100000),
                Map.of("server", "server2", "min-x", 0, "min-z", -100000, "max-x", 100000, "max-z", 100000)
            ));
        }
        config.setComments(path + ".regions", List.of(
            "Which server owns which part of the world. A server can own several areas. Shapes:",
            "  rectangle: {server, min-x, min-z, max-x, max-z} (min inclusive, max exclusive)",
            "  polygon:   {server, points: [[x, z], ...]}, add smooth: true for a curve through the points",
            "  circle:    {server, center-x, center-z, radius} (or radius-x / radius-z for an ellipse)",
            "Block coordinates. The editor in GinjerMap can draw these for you."
        ));

        enabled = config.getBoolean(path + ".enabled");
        thisServer = config.getString(path + ".this-server", "server1");
        worldName = config.getString(path + ".world", "world");
        sharedPlayerDataFolder = config.getString(path + ".shared-player-data-folder", "");
        borderProtection = Math.max(0, config.getInt(path + ".border-protection", 16));
        handoffTimeoutTicks = Math.max(20, config.getInt(path + ".handoff-timeout-seconds", 5) * 20);

        final List<Region> parsed = new ArrayList<>();
        for (final Map<?, ?> map : config.getMapList(path + ".regions")) {
            try {
                parsed.add(parseRegion(map));
            } catch (final RuntimeException exception) {
                LOGGER.warning("Ignoring invalid sharding region " + map + ": " + exception.getMessage());
            }
        }
        regions = List.copyOf(parsed);
        shardedLevel = null; // re-resolved on next use, in case the world name changed

        if (enabled) {
            final boolean ownsSomething = regions.stream().anyMatch(region -> region.server().equals(thisServer));
            LOGGER.info("Sharding enabled: this server is '" + thisServer + "', " + regions.size() + " region(s) defined"
                + (ownsSomething ? "" : " - WARNING: none of them belong to this server!"));
        }
    }

    /** Rectangle {min-x..max-z}, polygon {points, smooth?} or circle/ellipse {center-x, center-z, radius | radius-x, radius-z}. */
    static Region parseRegion(final Map<?, ?> map) {
        final String server = String.valueOf(map.get("server"));
        if (map.get("points") instanceof List<?> points) {
            if (points.size() < 3) {
                throw new IllegalArgumentException("a polygon needs at least 3 points");
            }
            double[][] pts = new double[points.size()][2];
            for (int i = 0; i < pts.length; i++) {
                final List<?> pair = (List<?>) points.get(i);
                pts[i][0] = toDouble(pair.get(0));
                pts[i][1] = toDouble(pair.get(1));
            }
            if (Boolean.TRUE.equals(map.get("smooth"))) {
                pts = smooth(pts);
            }
            return fromPoints(server, pts, false);
        }
        if (map.containsKey("center-x")) {
            final double cx = toDouble(map.get("center-x"));
            final double cz = toDouble(map.get("center-z"));
            final double rx = toDouble(map.containsKey("radius-x") ? map.get("radius-x") : map.get("radius"));
            final double rz = toDouble(map.containsKey("radius-z") ? map.get("radius-z") : map.get("radius"));
            if (!(rx > 0 && rz > 0)) {
                throw new IllegalArgumentException("radius must be above 0");
            }
            final int n = (int) Math.min(1024, Math.max(32, Math.ceil(2 * Math.PI * Math.max(rx, rz) / 8)));
            final double[][] pts = new double[n][2];
            for (int i = 0; i < n; i++) {
                pts[i][0] = cx + rx * Math.cos(2 * Math.PI * i / n);
                pts[i][1] = cz + rz * Math.sin(2 * Math.PI * i / n);
            }
            return fromPoints(server, pts, false);
        }
        final double minX = toDouble(map.get("min-x")), minZ = toDouble(map.get("min-z"));
        final double maxX = toDouble(map.get("max-x")), maxZ = toDouble(map.get("max-z"));
        if (minX >= maxX || minZ >= maxZ) {
            throw new IllegalArgumentException("min must be below max");
        }
        return new Region(server, new double[] {minX, maxX, maxX, minX}, new double[] {minZ, minZ, maxZ, maxZ}, true);
    }

    private static Region fromPoints(final String server, final double[][] pts, final boolean rectangle) {
        final double[] xs = new double[pts.length], zs = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            xs[i] = pts[i][0];
            zs[i] = pts[i][1];
        }
        return new Region(server, xs, zs, rectangle);
    }

    /** Closed Catmull-Rom spline through the points (same maths as GinjerMap). */
    private static double[][] smooth(final double[][] p) {
        final int n = p.length;
        final List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final double[] p0 = p[(i + n - 1) % n], p1 = p[i], p2 = p[(i + 1) % n], p3 = p[(i + 2) % n];
            final int steps = (int) Math.min(256, Math.max(4, Math.ceil(Math.hypot(p2[0] - p1[0], p2[1] - p1[1]) / 8)));
            for (int s = 0; s < steps; s++) {
                final double t = (double) s / steps, t2 = t * t, t3 = t2 * t;
                final double[] pt = new double[2];
                for (int k = 0; k < 2; k++) {
                    pt[k] = 0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2
                        + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3);
                }
                out.add(pt);
            }
        }
        return out.toArray(new double[0][]);
    }

    private static double toDouble(final Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value).trim());
    }

    private static void setDefault(final YamlConfiguration config, final String path, final Object value, final String... comments) {
        if (!config.contains(path)) {
            config.set(path, value);
        }
        config.setComments(path, List.of(comments));
    }

    // ------------------------------------------------------------------ lookups

    private static boolean isSharded(final Level level) {
        if (!enabled || !(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        final ServerLevel cached = shardedLevel;
        if (cached != null) {
            return cached == level;
        }
        if (serverLevel.getWorld().getName().equals(worldName)) {
            shardedLevel = serverLevel;
            return true;
        }
        return false;
    }

    private static Region regionAt(final double x, final double z) {
        for (final Region region : regions) {
            if (region.contains(x, z)) {
                return region;
            }
        }
        return null;
    }

    private static boolean ownedHere(final double x, final double z) {
        final Region region = regionAt(x, z);
        return region != null && region.server().equals(thisServer);
    }

    /** False for positions in the sharded world that another shard simulates. */
    public static boolean owns(final Level level, final double x, final double z) {
        return !isSharded(level) || ownedHere(x, z);
    }

    /**
     * For plugins (no server internals needed): whether this shard simulates the block at x, z in
     * the world with this name. True for other worlds and when sharding is off.
     */
    public static boolean ownsBlock(final String world, final double x, final double z) {
        return !enabled || !worldName.equals(world) || ownedHere(x, z);
    }

    /** For plugins: the name of the shard that owns x, z in this world, or null outside every region or when not sharded. */
    public static String ownerAt(final String world, final double x, final double z) {
        if (!enabled || !worldName.equals(world)) {
            return null;
        }
        final Region region = regionAt(x, z);
        return region == null ? null : region.server();
    }

    public static boolean ownsChunk(final Level level, final ChunkPos pos) {
        return !isSharded(level) || ownedHere((pos.x << 4) + 8.5, (pos.z << 4) + 8.5); // chunk centre
    }

    /** Whether this entity should tick. Players and anything carrying a passenger always do. */
    public static boolean shouldTickEntity(final Level level, final Entity entity) {
        if (!isSharded(level) || entity instanceof Player || entity.isVehicle()) {
            return true;
        }
        return ownedHere(entity.getX(), entity.getZ());
    }

    /** Hooked into ServerLevel#mayInteract: players may only change blocks in this shard's regions, away from borders. */
    public static boolean mayModify(final Level level, final Entity entity, final BlockPos pos) {
        if (!isSharded(level) || !(entity instanceof Player)) {
            return true;
        }
        final double x = pos.getX() + 0.5;
        final double z = pos.getZ() + 0.5;
        if (!ownedHere(x, z)) {
            return false;
        }
        final int strip = borderProtection;
        return strip == 0 || (ownedHere(x + strip, z) && ownedHere(x - strip, z) && ownedHere(x, z + strip) && ownedHere(x, z - strip));
    }

    // ------------------------------------------------------------------ player data

    /** The folder player data is read from and written to. */
    public static File playerDataFolder(final File defaultFolder) {
        dev.reef.ReefConfig.ensureLoaded();
        if (!enabled || sharedPlayerDataFolder == null || sharedPlayerDataFolder.isBlank()) {
            return defaultFolder;
        }
        final File folder = new File(sharedPlayerDataFolder);
        LOGGER.info("Sharding: using shared player data folder " + folder.getAbsolutePath());
        migratePlayerData(defaultFolder, folder);
        return folder;
    }

    /**
     * Copies player files from this world's own playerdata folder into the shared one when the
     * shared folder doesn't have them yet, so a server that was split into shards keeps everyone's
     * inventory and position without copying anything by hand. Never overwrites: a file already in
     * the shared folder is newer than any copy a shard still has.
     */
    static int migratePlayerData(final File localFolder, final File sharedFolder) {
        final File[] files = localFolder.listFiles((dir, name) -> name.endsWith(".dat"));
        if (files == null || files.length == 0 || localFolder.getAbsoluteFile().equals(sharedFolder.getAbsoluteFile())) {
            return 0;
        }
        sharedFolder.mkdirs();
        int copied = 0;
        for (final File file : files) {
            final java.nio.file.Path target = sharedFolder.toPath().resolve(file.getName());
            try {
                java.nio.file.Files.copy(file.toPath(), target); // fails if it exists, so nothing is overwritten
                copied++;
            } catch (final java.nio.file.FileAlreadyExistsException ignored) {
                // the shared copy wins
            } catch (final java.io.IOException exception) {
                LOGGER.warning("Sharding: couldn't copy " + file.getName() + " to the shared player data folder: " + exception.getMessage());
            }
        }
        if (copied > 0) {
            LOGGER.info("Sharding: copied " + copied + " player file(s) from " + localFolder + " into the shared player data folder");
        }
        return copied;
    }

    /** Players being moved to another shard aren't saved again here (their data is already saved, and the other shard owns it now). */
    public static boolean skipSave(final UUID uuid) {
        return handedOff.contains(uuid);
    }

    public static void onPlayerRemoved(final ServerPlayer player) {
        final UUID uuid = player.getUUID();
        lastSafePositions.remove(uuid);
        handoffs.remove(uuid);
        handedOff.remove(uuid);
    }

    // ------------------------------------------------------------------ border crossing

    /** Called every tick for every player. */
    public static void tickPlayer(final ServerPlayer player) {
        if (!isSharded(player.level()) || player.hasDisconnected() || player.connection == null) {
            return;
        }
        final UUID uuid = player.getUUID();

        final Handoff pending = handoffs.get(uuid);
        if (pending != null) {
            if (MinecraftServer.currentTick - pending.startTick() > handoffTimeoutTicks) {
                handoffs.remove(uuid);
                handedOff.remove(uuid);
                sendBack(player, Component.text("Couldn't move you to the server for that area (" + pending.targetServer()
                    + "). It may be offline - try again in a moment.", NamedTextColor.RED));
            }
            return; // waiting for the proxy
        }

        final Region region = regionAt(player.getX(), player.getZ());
        if (region != null && region.server().equals(thisServer)) {
            lastSafePositions.put(uuid, new SafePosition(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
            return;
        }

        final boolean justArrived = player.tickCount < ARRIVAL_GRACE_TICKS && lastSafePositions.containsKey(uuid);
        if (region == null || justArrived) {
            // outside every region (edge of the map), or bouncing straight back after arriving
            sendBack(player, region == null
                ? Component.text("You've reached the edge of the world.", NamedTextColor.RED)
                : Component.text("You just arrived - wait a moment before crossing back.", NamedTextColor.YELLOW));
            return;
        }

        startHandoff(player, region.server());
    }

    private static void startHandoff(final ServerPlayer player, final String targetServer) {
        final UUID uuid = player.getUUID();
        final MinecraftServer server = MinecraftServer.getServer();

        // Save now and wait for the write, so the other shard loads exactly this state.
        server.getPlayerList().playerIo.save(player);
        server.getPlayerList().playerIo.awaitPendingSave(uuid, player.getPlainTextName());
        handedOff.add(uuid);
        handoffs.put(uuid, new Handoff(targetServer, MinecraftServer.currentTick));

        final ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(targetServer);
        player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(BUNGEE_CHANNEL, out.toByteArray())));
    }

    private static void sendBack(final ServerPlayer player, final Component message) {
        final SafePosition safe = lastSafePositions.get(player.getUUID());
        if (safe != null) {
            player.getBukkitEntity().teleport(new org.bukkit.Location(player.level().getWorld(), safe.x(), safe.y(), safe.z(), safe.yRot(), safe.xRot()));
        }
        player.getBukkitEntity().sendActionBar(message);
    }

    // ------------------------------------------------------------------ /reef info

    public static String describe() {
        if (!enabled) {
            return "off";
        }
        final long owned = regions.stream().filter(region -> region.server().equals(thisServer)).count();
        return "on, this server is '" + thisServer + "' (" + owned + " of " + regions.size() + " regions), "
            + handoffs.size() + " handoff(s) in progress";
    }
}
