package dev.reef;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reef's own settings, stored in {@code reef.yml} in the server's root folder.
 *
 * <p>Kept separate from Paper's config files so upstream config changes never
 * collide with ours. The file is created with defaults on first start, and any
 * new options added in later versions are appended automatically.</p>
 *
 * <p>Every option that changes vanilla behaviour should default to a safe value
 * and say so in its comment.</p>
 */
public final class ReefConfig {

    private static final Logger LOGGER = Logger.getLogger("Reef");
    private static final File FILE = new File("reef.yml");
    private static final int CONFIG_VERSION = 1;

    // ---- optimizations ----

    /**
     * How often (in ticks) living entities check whether they are stuck inside a block
     * and should take suffocation damage. Vanilla checks every tick (1).
     * Based on Pufferfish's "Optimize suffocation" patch.
     */
    public static int checkStuckInWallInterval = 10;

    private static YamlConfiguration config;

    static {
        load();
    }

    private ReefConfig() {
    }

    public static synchronized void load() {
        config = new YamlConfiguration();
        if (FILE.exists()) {
            try {
                config.load(FILE);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Could not read reef.yml, using defaults for missing values", e);
            }
        }

        config.options().setHeader(List.of(
            "This is the main configuration file for Reef.",
            "Options that change vanilla behaviour are marked as such.",
            "Restart the server after editing this file."
        ));
        config.set("config-version", CONFIG_VERSION);

        checkStuckInWallInterval = Math.max(1, getInt(
            "optimizations.check-stuck-in-wall-interval", checkStuckInWallInterval,
            "How often (in ticks) entities check for suffocation inside blocks.",
            "1 = vanilla (every tick). Higher values save CPU with lots of entities.",
            "Changes vanilla behaviour slightly: suffocation damage can start up to this many ticks late."
        ));

        save();
    }

    private static int getInt(String path, int def, String... comments) {
        if (!config.contains(path)) {
            config.set(path, def);
        }
        config.setComments(path, List.of(comments));
        return config.getInt(path, def);
    }

    private static boolean getBoolean(String path, boolean def, String... comments) {
        if (!config.contains(path)) {
            config.set(path, def);
        }
        config.setComments(path, List.of(comments));
        return config.getBoolean(path, def);
    }

    private static void save() {
        try {
            config.save(FILE);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Could not save reef.yml", e);
        }
    }
}
