package dev.reef;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reef's own settings, stored in {@code reef.yml} in the server's root folder.
 *
 * <p>Kept separate from Paper's config files so upstream config changes never
 * collide with ours. The file is created with defaults on first start, and any
 * new options added in later versions are appended automatically.</p>
 *
 * <p>Loaded once during server startup from {@code DedicatedServer#initServer}.
 * Every option that changes vanilla behaviour should say so in its comment.</p>
 */
public final class ReefConfig {

    private static final Logger LOGGER = Logger.getLogger("Reef");
    private static final File FILE = new File("reef.yml");
    private static final int CONFIG_VERSION = 1;

    // ---- optimizations.check-stuck-in-wall-interval ----

    /** Pufferfish "Optimize suffocation": ticks between suffocation checks. 1 = vanilla. */
    public static int checkStuckInWallInterval = 10;

    // ---- optimizations.async-mob-spawning ----

    /** Pufferfish "Optimize mob spawning": count mobs for spawning off the main thread. */
    public static boolean asyncMobSpawning = true;

    // ---- optimizations.dynamic-activation-of-brain ----

    /** Pufferfish "Dynamic Activation of Brain": tick far-away mob brains less often. */
    public static boolean dabEnabled = true;
    public static int dabStartDistance = 12;
    public static int dabStartDistanceSquared = 12 * 12;
    public static int dabMaximumActivationPriority = 20;
    public static int dabActivationDistanceMod = 8;
    public static boolean dabDontEnableIfInWater = true;
    public static List<String> dabBlacklistedEntities = new ArrayList<>(List.of(
        "villager",
        "axolotl",
        "hoglin",
        "zombified_piglin",
        "goat"
    ));

    // ---- optimizations.goal-check-interval ----

    /** Ticks between "is a player holding my food?" checks for idle animals. 2 = vanilla. */
    public static int temptationCheckInterval = 10;
    /** Ticks between "where is an adult to follow?" checks for baby animals. 2 = vanilla. */
    public static int followParentCheckInterval = 20;

    // ---- optimizations.only-players-push-entities ----

    /** Mobs only collide with players, not with each other. */
    public static boolean onlyPlayersPushEntities = false;

    // ---- optimizations.async-player-data-saving ----

    /** Compress and write player .dat files on a background thread. */
    public static boolean asyncPlayerDataSaving = true;

    // ---- optimizations.skip-useless-move-packets ----

    /** Don't re-send the position of entities that haven't moved. */
    public static boolean skipUselessMovePackets = true;

    // ---- update-checker ----

    /** Compare the running build with the latest commit on GitHub at startup and in /version. */
    public static boolean updateCheckerEnabled = true;
    public static String updateCheckerRepository = "gingercoot/reef";
    public static String updateCheckerBranch = "main";

    private static YamlConfiguration config;

    private ReefConfig() {
    }

    public static synchronized void init() {
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

        config.setComments("update-checker", List.of(
            "Checks GitHub for newer Reef builds at startup and in /version.",
            "Only works when the repository is public."
        ));
        updateCheckerEnabled = getBoolean("update-checker.enabled", updateCheckerEnabled,
            "Set to false to turn update checks off.");
        updateCheckerRepository = getString("update-checker.repository", updateCheckerRepository,
            "GitHub repository to check, as owner/name.");
        updateCheckerBranch = getString("update-checker.branch", updateCheckerBranch,
            "Branch that official builds come from.");

        checkStuckInWallInterval = Math.max(1, getInt(
            "optimizations.check-stuck-in-wall-interval", checkStuckInWallInterval,
            "How often (in ticks) entities check for suffocation inside blocks.",
            "1 = vanilla (every tick). Higher values save CPU with lots of entities.",
            "Changes vanilla behaviour slightly: suffocation damage can start up to this many ticks late."
        ));

        asyncMobSpawning = getBoolean(
            "optimizations.async-mob-spawning", asyncMobSpawning,
            "Counts mobs for natural spawning on a separate thread instead of the main thread.",
            "Can noticeably reduce lag on servers with many entities.",
            "Changes vanilla behaviour slightly: mob counts can be one tick behind, so a few more or",
            "fewer mobs may spawn in a given tick. Turn off if a plugin depends on exact spawn timing."
        );

        asyncPlayerDataSaving = getBoolean(
            "optimizations.async-player-data-saving", asyncPlayerDataSaving,
            "Writes player data files on a background thread instead of the main thread.",
            "Removes lag spikes on autosave and when players leave. Does not change gameplay.",
            "Plugins that read player .dat files directly may see the previous save for a moment."
        );

        skipUselessMovePackets = getBoolean(
            "optimizations.skip-useless-move-packets", skipUselessMovePackets,
            "Don't re-send the position of entities that haven't moved (normally sent every 3 seconds).",
            "Saves bandwidth with many idle animals and players. Does not change gameplay."
        );

        final String dab = "optimizations.dynamic-activation-of-brain";
        config.setComments(dab, List.of(
            "Dynamic Activation of Brain (DAB): mobs far away from players run their AI less often.",
            "Only affects mobs with 'brains' (villagers, piglins, axolotls, frogs, wardens, etc.).",
            "Changes vanilla behaviour: distant mobs react more slowly. Nearby mobs are unaffected."
        ));
        dabEnabled = getBoolean(dab + ".enabled", dabEnabled,
            "Set to false to turn DAB off completely.");
        dabStartDistance = Math.max(0, getInt(dab + ".start-distance", dabStartDistance,
            "Distance (in blocks) from the nearest player at which DAB starts slowing a mob down."));
        dabMaximumActivationPriority = Math.max(1, getInt(dab + ".max-tick-freq", dabMaximumActivationPriority,
            "The furthest mobs run their AI once every this many ticks. 20 = once per second."));
        dabActivationDistanceMod = Math.max(0, Math.min(30, getInt(dab + ".activation-dist-mod", dabActivationDistanceMod,
            "How quickly AI slows down with distance: frequency = (distance^2) / (2^value).",
            "Lower (e.g. 7) = distant mobs tick less often. Higher (e.g. 9) = more often.")));
        dabDontEnableIfInWater = getBoolean(dab + ".dont-enable-if-in-water", dabDontEnableIfInWater,
            "If true, land mobs standing in water are not slowed down, so they don't drown",
            "because their AI reacts too slowly to swim up.");
        dabBlacklistedEntities = getStringList(dab + ".blacklisted-entities", dabBlacklistedEntities,
            "Mobs that DAB never slows down. Use entity IDs like 'villager' or 'minecraft:villager'.",
            "Villagers are excluded by default so trading halls and iron/breeder farms work normally.");
        dabStartDistanceSquared = dabStartDistance * dabStartDistance;
        applyDabBlacklist();

        final String goals = "optimizations.goal-check-interval";
        config.setComments(goals, List.of(
            "How often (in ticks) idle animals run some expensive AI checks. 2 = vanilla.",
            "Higher values save CPU with many animals (cows, sheep, pigs, chickens, ...).",
            "Changes vanilla behaviour slightly: animals react up to this many ticks later."
        ));
        temptationCheckInterval = Math.max(2, getInt(goals + ".temptation", temptationCheckInterval,
            "Checking for a nearby player holding the animal's food (e.g. wheat for cows)."));
        followParentCheckInterval = Math.max(2, getInt(goals + ".follow-parent", followParentCheckInterval,
            "Baby animals looking for an adult to follow. Expensive in crowded breeding pens."));

        onlyPlayersPushEntities = getBoolean(
            "optimizations.only-players-push-entities", onlyPlayersPushEntities,
            "If true, mobs no longer push each other; players still push and get pushed by mobs.",
            "Big CPU saving for crowded animal pens.",
            "Changes vanilla behaviour: mobs can overlap, and cramming damage between mobs stops",
            "(cramming-based mob farms won't work)."
        );

        save();
    }

    /** Mobs re-check idle goals every 2 ticks, so convert a tick interval into a number of checks. */
    public static int goalCheckCalls(final int intervalTicks) {
        return Math.max(1, intervalTicks / 2);
    }

    private static void applyDabBlacklist() {
        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            entityType.dabEnabled = true;
        }
        for (String name : dabBlacklistedEntities) {
            String id = name.toLowerCase(Locale.ROOT).trim();
            if (!id.contains(":")) {
                id = "minecraft:" + id;
            }
            final String finalId = id;
            EntityType.byString(id).ifPresentOrElse(
                type -> type.dabEnabled = false,
                () -> LOGGER.warning("Unknown entity '" + name + "' in " + "optimizations.dynamic-activation-of-brain.blacklisted-entities (" + finalId + ")")
            );
        }
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

    private static String getString(String path, String def, String... comments) {
        if (!config.contains(path)) {
            config.set(path, def);
        }
        config.setComments(path, List.of(comments));
        return config.getString(path, def);
    }

    private static List<String> getStringList(String path, List<String> def, String... comments) {
        if (!config.contains(path)) {
            config.set(path, def);
        }
        config.setComments(path, List.of(comments));
        return new ArrayList<>(config.getStringList(path));
    }

    private static void save() {
        try {
            config.save(FILE);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Could not save reef.yml", e);
        }
    }
}
