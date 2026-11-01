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
        super("reef", "Reef settings and reload", "/reef [info|reload]", List.of());
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
            default -> sender.sendMessage(Component.text("Usage: " + this.getUsage(), NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            final String typed = args[0].toLowerCase(Locale.ROOT);
            return List.of("info", "reload").stream().filter(option -> option.startsWith(typed)).toList();
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
