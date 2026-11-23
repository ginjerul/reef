package dev.reef;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * /reef          - show Reef's settings
 * /reef reload   - re-read reef.yml without restarting
 */
public final class ReefCommand extends Command {

    private static final String PERMISSION = "reef.command";

    public ReefCommand() {
        super("reef", "Reef settings, entity cleanup and lag report", "/reef [info|reload|clear <items|arrows|xp|all> [seconds]|entities]", List.of());
        this.setPermission(PERMISSION);
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!sender.isOp() && !sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        final String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> this.sendInfo(sender);
            case "reload" -> {
                try {
                    Reef.reload();
                    sender.sendMessage(Component.text("Reloaded reef.yml. All settings apply right away.", NamedTextColor.GREEN));
                } catch (final Exception exception) {
                    sender.sendMessage(Component.text("Reloading reef.yml failed: " + exception.getMessage() + " (see console)", NamedTextColor.RED));
                    java.util.logging.Logger.getLogger("Reef").log(java.util.logging.Level.SEVERE, "Failed to reload reef.yml", exception);
                }
            }
            case "clear" -> {
                final java.util.List<dev.reef.lag.EntityClearer.Kind> kinds = dev.reef.lag.EntityClearer.parseKinds(
                    args.length > 1 ? java.util.List.of(args[1]) : java.util.List.of("all"));
                int seconds = 0;
                if (args.length > 2) {
                    try {
                        seconds = Math.max(0, Math.min(600, Integer.parseInt(args[2])));
                    } catch (final NumberFormatException ignored) {
                        sender.sendMessage(Component.text("Seconds must be a number.", NamedTextColor.RED));
                        return true;
                    }
                }
                if (kinds.isEmpty()) {
                    sender.sendMessage(Component.text("Usage: /reef clear <items|arrows|xp|all> [warning seconds]", NamedTextColor.RED));
                } else if (seconds == 0) {
                    final int removed = dev.reef.lag.EntityClearer.clearNow(kinds);
                    sender.sendMessage(Component.text("Removed " + removed + " " + dev.reef.lag.EntityClearer.describe(kinds) + ".", NamedTextColor.GREEN));
                } else if (dev.reef.lag.EntityClearer.clearAfter(kinds, seconds)) {
                    sender.sendMessage(Component.text("Clearing " + dev.reef.lag.EntityClearer.describe(kinds) + " in " + seconds + " seconds; players were warned.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("A clear is already counting down.", NamedTextColor.YELLOW));
                }
            }
            case "entities" -> {
                try {
                    final java.util.Map<String, Object> report = dev.reef.lag.EntityReport.scan();
                    final java.io.File file = dev.reef.lag.EntityReport.write(report);
                    sender.sendMessage(Component.text("Counted " + report.get("entities") + " entities and " + report.get("block_entities")
                        + " block entities in " + report.get("loaded_chunks") + " loaded chunks. Worst chunks written to " + file.getName() + ".", NamedTextColor.GREEN));
                    @SuppressWarnings("unchecked")
                    final java.util.List<java.util.Map<String, Object>> chunks = (java.util.List<java.util.Map<String, Object>>) report.get("chunks");
                    for (final java.util.Map<String, Object> chunk : chunks.subList(0, Math.min(5, chunks.size()))) {
                        sender.sendMessage(Component.text(" - " + chunk.get("world") + " chunk " + chunk.get("x") + ", " + chunk.get("z") + " (block "
                            + ((int) chunk.get("x") * 16 + 8) + ", " + ((int) chunk.get("z") * 16 + 8) + "): " + chunk.get("entities") + " entities, "
                            + chunk.get("block_entities") + " block entities", NamedTextColor.GRAY));
                    }
                } catch (final java.io.IOException exception) {
                    sender.sendMessage(Component.text("Couldn't write the report: " + exception.getMessage(), NamedTextColor.RED));
                }
            }
            default -> sender.sendMessage(Component.text("Usage: " + this.getUsage(), NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            final String typed = args[0].toLowerCase(Locale.ROOT);
            return List.of("info", "reload", "clear", "entities").stream().filter(option -> option.startsWith(typed)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            final String typed = args[1].toLowerCase(Locale.ROOT);
            return List.of("items", "arrows", "xp", "all").stream().filter(option -> option.startsWith(typed)).toList();
        }
        return List.of();
    }

    private void sendInfo(final CommandSender sender) {
        sender.sendMessage(Component.text("Reef settings (reef.yml)", NamedTextColor.AQUA));
        line(sender, "Suffocation check interval", ReefConfig.checkStuckInWallInterval + " ticks", ReefConfig.checkStuckInWallInterval <= 1);
        line(sender, "Async mob spawning", onOff(ReefConfig.asyncMobSpawning)
            + (ReefConfig.asyncMobSpawning && !Reef.asyncMobSpawning() ? " (restart needed to start the thread)" : ""), !ReefConfig.asyncMobSpawning);
        line(sender, "DAB", ReefConfig.dabEnabled
            ? "on, from " + ReefConfig.dabStartDistance + " blocks, max every " + ReefConfig.dabMaximumActivationPriority + " ticks, "
                + ReefConfig.dabBlacklistedEntities.size() + " blacklisted"
            : "off", !ReefConfig.dabEnabled);
        line(sender, "Temptation check interval", ReefConfig.temptationCheckInterval + " ticks", ReefConfig.temptationCheckInterval <= 2);
        line(sender, "Follow-parent check interval", ReefConfig.followParentCheckInterval + " ticks", ReefConfig.followParentCheckInterval <= 2);
        line(sender, "Only players push entities", onOff(ReefConfig.onlyPlayersPushEntities), !ReefConfig.onlyPlayersPushEntities);
        line(sender, "Async player data saving", onOff(ReefConfig.asyncPlayerDataSaving), !ReefConfig.asyncPlayerDataSaving);
        line(sender, "Skip useless move packets", onOff(ReefConfig.skipUselessMovePackets), !ReefConfig.skipUselessMovePackets);
        line(sender, "Sharding", dev.reef.sharding.Sharding.describe(), !dev.reef.sharding.Sharding.enabled);
        line(sender, "Dynamic view distance", dev.reef.lag.DynamicDistance.describe(), !dev.reef.lag.DynamicDistance.enabled);
        line(sender, "Entity clearer", dev.reef.lag.EntityClearer.status(), false);
        line(sender, "Update checker", ReefConfig.updateCheckerEnabled
            ? "on (" + ReefConfig.updateCheckerRepository + ", " + ReefConfig.updateCheckerBranch + ")"
            : "off", false);
        sender.sendMessage(Component.text("Grey = vanilla behaviour. Edit reef.yml, then /reef reload.", NamedTextColor.DARK_GRAY));
    }

    private static void line(final CommandSender sender, final String name, final String value, final boolean vanilla) {
        sender.sendMessage(Component.text(" - " + name + ": ", NamedTextColor.GRAY)
            .append(Component.text(value, vanilla ? NamedTextColor.GRAY : NamedTextColor.WHITE)));
    }

    private static String onOff(final boolean value) {
        return value ? "on" : "off";
    }
}
