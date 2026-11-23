package dev.reef;

import dev.reef.util.AsyncExecutor;

/**
 * Reef's startup entry point. Called once from {@code DedicatedServer#initServer},
 * right after Paper's own configuration has been loaded.
 */
public final class Reef {

    /** Background thread for counting mobs for natural spawning (Pufferfish "Optimize mob spawning"). */
    public static final AsyncExecutor MOB_SPAWN_EXECUTOR = new AsyncExecutor("Reef Async Mob Spawning");

    /** Single background thread that writes player .dat files, in order. */
    public static final java.util.concurrent.ExecutorService PLAYER_DATA_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "Reef Player Data Saving");
        thread.setDaemon(true); // flushed explicitly on shutdown, see flushPlayerData()
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static boolean tickerAdded = false;

    private Reef() {
    }

    public static void init() {
        ReefConfig.init();
        startServices();
        if (!tickerAdded) {
            tickerAdded = true;
            // runs every server tick on the main thread
            net.minecraft.server.MinecraftServer.getServer().addTickable(() -> {
                dev.reef.lag.DynamicDistance.tick();
                dev.reef.lag.EntityClearer.tick();
            });
        }
        // same way Spigot registers /restart and friends; the CraftServer exists at this point
        net.minecraft.server.MinecraftServer.getServer().server.getCommandMap().register("reef", "Reef", new ReefCommand());
    }

    /** Re-read reef.yml (/reef reload). Every setting is read live, so changes apply immediately. */
    public static void reload() {
        ReefConfig.init();
        startServices();
    }

    private static void startServices() {
        if (ReefConfig.asyncMobSpawning) {
            MOB_SPAWN_EXECUTOR.startIfNeeded(); // turning it off later just stops it from being used
        }
    }

    /** True when async player data saving is enabled and the server isn't shutting down. */
    public static boolean asyncPlayerDataSaving() {
        return ReefConfig.asyncPlayerDataSaving && !PLAYER_DATA_EXECUTOR.isShutdown();
    }

    /** Finish all pending player data writes. Called during server shutdown, after players are saved. */
    public static void flushPlayerData() {
        PLAYER_DATA_EXECUTOR.shutdown();
        try {
            if (!PLAYER_DATA_EXECUTOR.awaitTermination(60L, java.util.concurrent.TimeUnit.SECONDS)) {
                java.util.logging.Logger.getLogger("Reef").severe("Timed out waiting for player data to finish saving!");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True when async mob spawning is enabled in reef.yml and its thread is running. */
    public static boolean asyncMobSpawning() {
        return ReefConfig.asyncMobSpawning && MOB_SPAWN_EXECUTOR.isRunning();
    }
}
