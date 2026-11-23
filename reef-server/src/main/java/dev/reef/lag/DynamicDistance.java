package dev.reef.lag;

import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Lowers the simulation and view distance a step when the server can't keep up, and raises them
 * again once it's been fine for a while. Simulation distance (how far mobs, crops and redstone run)
 * goes first, because it costs the most and players notice it least; view distance follows.
 *
 * The upper limits are the server's own view and simulation distance from server.properties; the
 * lower limits come from reef.yml. Every world gets the same distances.
 */
public final class DynamicDistance {

    private static final Logger LOGGER = Logger.getLogger("Reef");

    // ---- config
    public static volatile boolean enabled = true;
    public static volatile int minView = 5;
    public static volatile int minSimulation = 3;
    public static volatile double lowerAboveMspt = 45.0;
    public static volatile double raiseBelowMspt = 35.0;
    public static volatile int checkSeconds = 10;
    public static volatile int raiseAfterSeconds = 60;

    // ---- state (main thread only)
    private static int view = -1;
    private static int simulation = -1;
    private static int goodSeconds = 0;
    private static int ticks = 0;

    private DynamicDistance() {
    }

    public static void load(final YamlConfiguration config) {
        final String path = "lag.dynamic-distance";
        config.setComments(path, List.of(
            "Lower the simulation distance, then the view distance, a step at a time when the server",
            "can't keep up, and raise them again once it's fine. The maximums are view-distance and",
            "simulation-distance in server.properties."
        ));
        enabled = bool(config, path + ".enabled", true, "Turn it on or off.");
        minView = Math.max(2, integer(config, path + ".min-view-distance", 5, "Never go below this view distance (chunks)."));
        minSimulation = Math.max(2, integer(config, path + ".min-simulation-distance", 3, "Never go below this simulation distance (chunks)."));
        lowerAboveMspt = number(config, path + ".lower-above-mspt", 45.0,
            "Lower a step when the average tick takes longer than this (milliseconds; 50 is the limit for 20 TPS).");
        raiseBelowMspt = number(config, path + ".raise-below-mspt", 35.0, "Raise a step once ticks stay below this...");
        raiseAfterSeconds = Math.max(10, integer(config, path + ".raise-after-seconds", 60, "...for this many seconds."));
        checkSeconds = Math.max(2, integer(config, path + ".check-seconds", 10, "How often to check."));

        if (!enabled && view != -1) {
            restore();
        }
    }

    /** Called every tick. */
    public static void tick() {
        if (!enabled || ++ticks < checkSeconds * 20) {
            return;
        }
        ticks = 0;
        final int maxView = Bukkit.getViewDistance();
        final int maxSimulation = Math.min(Bukkit.getSimulationDistance(), maxView);
        if (view == -1) {
            view = maxView;
            simulation = maxSimulation;
        }

        final double mspt = Bukkit.getAverageTickTime();
        final Step step = decide(mspt, view, simulation, goodSeconds, checkSeconds, maxView, maxSimulation, minView, minSimulation, lowerAboveMspt, raiseBelowMspt, raiseAfterSeconds);
        goodSeconds = step.goodSeconds();
        if (step.view() != view || step.simulation() != simulation) {
            LOGGER.info(String.format("Dynamic distance: %.1f ms per tick, view %d -> %d, simulation %d -> %d",
                mspt, view, step.view(), simulation, step.simulation()));
            view = step.view();
            simulation = step.simulation();
            apply();
        }
    }

    /** What the next check does; pure, so it can be tested. */
    public record Step(int view, int simulation, int goodSeconds) {}

    public static Step decide(final double mspt, final int view, final int simulation, final int goodSeconds, final int checkSeconds,
                              final int maxView, final int maxSimulation, final int minView, final int minSimulation,
                              final double lowerAbove, final double raiseBelow, final int raiseAfter) {
        // the server's own limits may have changed (or be below our minimums)
        int v = Math.min(view, maxView);
        int s = Math.min(Math.min(simulation, maxSimulation), v);
        if (mspt > lowerAbove) {
            if (s > Math.min(minSimulation, maxSimulation)) {
                s--;
            } else if (v > Math.min(minView, maxView) && v > s) {
                v--;
            }
            return new Step(v, s, 0);
        }
        if (mspt < raiseBelow) {
            final int good = goodSeconds + checkSeconds;
            if (good < raiseAfter) {
                return new Step(v, s, good);
            }
            if (v < maxView) {
                v++; // view first on the way up: players notice it, and it's cheaper
            } else if (s < maxSimulation) {
                s++;
            }
            return new Step(v, s, 0);
        }
        return new Step(v, s, 0); // in between: hold
    }

    private static void apply() {
        for (final World world : Bukkit.getWorlds()) {
            try {
                world.setSimulationDistance(Math.min(simulation, view));
                world.setViewDistance(view);
            } catch (final RuntimeException exception) {
                LOGGER.warning("Dynamic distance: couldn't set distances for " + world.getName() + ": " + exception.getMessage());
            }
        }
    }

    /** Back to the server's own distances (when it's turned off). */
    private static void restore() {
        view = Bukkit.getViewDistance();
        simulation = Math.min(Bukkit.getSimulationDistance(), view);
        apply();
        view = -1;
        simulation = -1;
        goodSeconds = 0;
    }

    public static String describe() {
        if (!enabled) {
            return "off";
        }
        if (view == -1) {
            return "on, starts after the first check";
        }
        return "on, view " + view + "/" + Bukkit.getViewDistance() + ", simulation " + simulation + "/" + Bukkit.getSimulationDistance()
            + String.format(" (%.1f ms per tick)", Bukkit.getAverageTickTime());
    }

    // ---- small config helpers (same style as Sharding)
    static boolean bool(final YamlConfiguration c, final String path, final boolean def, final String... comments) {
        if (!c.contains(path)) {
            c.set(path, def);
        }
        c.setComments(path, List.of(comments));
        return c.getBoolean(path, def);
    }

    static int integer(final YamlConfiguration c, final String path, final int def, final String... comments) {
        if (!c.contains(path)) {
            c.set(path, def);
        }
        c.setComments(path, List.of(comments));
        return c.getInt(path, def);
    }

    static double number(final YamlConfiguration c, final String path, final double def, final String... comments) {
        if (!c.contains(path)) {
            c.set(path, def);
        }
        c.setComments(path, List.of(comments));
        return c.getDouble(path, def);
    }

    static List<String> strings(final YamlConfiguration c, final String path, final List<String> def, final String... comments) {
        if (!c.contains(path)) {
            c.set(path, def);
        }
        c.setComments(path, List.of(comments));
        return c.getStringList(path);
    }
}
